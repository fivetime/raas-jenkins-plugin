package io.fivetime.raas.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * A Jenkins {@link Cloud} backed by RaaS.
 *
 * <p>The tenant configures two things: the RaaS URL and an application credential. Everything else
 * (labels, flavors, images, networks) is RaaS's knowledge and is read from {@code GET /v1/jenkins/labels}.
 * When Jenkins has queued work for one of those labels, {@link #provision} asks RaaS for one agent per
 * excess executor: the node is added to Jenkins first (that is what mints the JNLP secret), then RaaS is
 * told the node name, the controller URL and the secret; the machine connects back over WebSocket.
 */
public class RaasCloud extends Cloud {
    private static final Logger LOGGER = Logger.getLogger(RaasCloud.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    /** How long the label list is trusted before asking RaaS again. Operators add specs on the RaaS side. */
    static final Duration LABELS_TTL = Duration.ofSeconds(60);

    private final String url;
    private final String credentialsId;
    /** Minutes an agent may take from request to connect before we give up on it (0 = 15). */
    private int connectTimeoutMinutes = 15;

    private transient volatile List<String> cachedLabels;
    private transient volatile Instant cachedLabelsAt;

    @DataBoundConstructor
    public RaasCloud(String name, String url, String credentialsId) {
        super(name);
        this.url = url == null ? "" : url.trim();
        this.credentialsId = credentialsId;
    }

    public String getUrl() {
        return url;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public int getConnectTimeoutMinutes() {
        return connectTimeoutMinutes <= 0 ? 15 : connectTimeoutMinutes;
    }

    @DataBoundSetter
    public void setConnectTimeoutMinutes(int minutes) {
        this.connectTimeoutMinutes = minutes;
    }

    /** Builds a client from the configured credential. Fails loudly when the credential is gone. */
    public RaasClient client() throws IOException {
        StandardUsernamePasswordCredentials c = lookupCredentials(credentialsId);
        if (c == null) {
            throw new IOException("RaaS cloud '" + name + "': credential '" + credentialsId + "' not found");
        }
        return new RaasClient(url, c.getUsername(), c.getPassword().getPlainText());
    }

    @CheckForNull
    static StandardUsernamePasswordCredentials lookupCredentials(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(StandardUsernamePasswordCredentials.class,
                        Jenkins.get(), ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(id));
    }

    /** Labels RaaS is willing to deliver, cached for {@link #LABELS_TTL}. Empty on error (logged). */
    public List<String> labels() {
        List<String> cached = cachedLabels;
        Instant at = cachedLabelsAt;
        if (cached != null && at != null && Instant.now().isBefore(at.plus(LABELS_TTL))) {
            return cached;
        }
        List<String> fresh = new ArrayList<>();
        try {
            for (RaasClient.Label l : client().labels()) {
                fresh.add(l.label);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RaaS cloud '" + name + "': could not list labels: " + e.getMessage());
            if (cached != null) {
                return cached; // stale beats empty while RaaS is briefly unreachable
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cachedLabels = fresh;
        cachedLabelsAt = Instant.now();
        return fresh;
    }

    /** The RaaS label this Jenkins label resolves to, or null. Only atomic RaaS labels are offered. */
    @CheckForNull
    String labelFor(@CheckForNull Label label) {
        if (label == null) {
            return null;
        }
        for (String l : labels()) {
            if (label.matches(Label.parse(l))) {
                return l;
            }
        }
        return null;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return labelFor(state.getLabel()) != null;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        String label = labelFor(state.getLabel());
        List<NodeProvisioner.PlannedNode> planned = new ArrayList<>();
        if (label == null) {
            return planned;
        }
        for (int i = 0; i < excessWorkload; i++) {
            String nodeName = nodeName(label);
            Future<Node> f = Computer.threadPoolForRemoting.submit(() -> provisionOne(label, nodeName));
            planned.add(new NodeProvisioner.PlannedNode(nodeName, f, 1));
        }
        LOGGER.log(Level.INFO, "RaaS cloud ''{0}'': asked for {1} agent(s) for label {2}",
                new Object[] {name, planned.size(), label});
        return planned;
    }

    static String nodeName(String label) {
        byte[] b = new byte[4];
        RANDOM.nextBytes(b);
        StringBuilder hex = new StringBuilder();
        for (byte x : b) {
            hex.append(String.format("%02x", x));
        }
        return "raas-" + label + "-" + hex;
    }

    /**
     * The order matters: the node has to be in Jenkins before it has a JNLP secret, and RaaS has to have the
     * secret before the machine boots. On any failure the node is removed again so Jenkins is not left with a
     * phantom agent that never connects.
     */
    Node provisionOne(String label, String nodeName) throws Exception {
        Jenkins j = Jenkins.get();
        RaasAgent agent = new RaasAgent(nodeName, label, name, getConnectTimeoutMinutes());
        j.addNode(agent);
        try {
            SlaveComputer c = (SlaveComputer) agent.toComputer();
            if (c == null) {
                throw new IOException("node " + nodeName + " has no computer after addNode");
            }
            String controller = JenkinsLocationConfiguration.get().getUrl();
            if (controller == null || controller.isEmpty()) {
                throw new IOException("Jenkins URL is not configured (Manage Jenkins → System → Jenkins URL); the RaaS agent needs it to connect back");
            }
            RaasClient.Agent a = client().provision(label, nodeName, controller, c.getJnlpMac());
            agent.setAgentId(a.id);
            try {
                agent.save();
            } catch (IOException e) {
                // RaaS already holds a machine for us; do not leave it there because our own bookkeeping failed.
                RaasPeriodicWork.get().pendingTerminate(name, a.id, null, null);
                throw e;
            }
            LOGGER.log(Level.INFO, "RaaS agent {0} requested as node {1}", new Object[] {a.id, nodeName});
            return agent;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "RaaS cloud ''{0}'': provisioning {1} failed: {2}",
                    new Object[] {name, nodeName, e.toString()});
            try {
                j.removeNode(agent);
            } catch (IOException re) {
                LOGGER.log(Level.WARNING, "could not remove node " + nodeName + " after a failed provision", re);
            }
            throw e;
        }
    }

    @CheckForNull
    static RaasCloud byName(String name) {
        Cloud c = Jenkins.get().getCloud(name);
        return c instanceof RaasCloud ? (RaasCloud) c : null;
    }

    /** {@code raas} is the key in Configuration as Code: {@code jenkins: clouds: - raas: {...}}. */
    @Extension
    @Symbol("raas")
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "RaaS";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel().includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, Jenkins.get(), StandardUsernamePasswordCredentials.class,
                            Collections.emptyList(), CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        public FormValidation doCheckUrl(@QueryParameter String value) {
            String v = value == null ? "" : value.trim();
            if (v.isEmpty()) {
                return FormValidation.error("The RaaS URL is required, e.g. https://raas.example.com");
            }
            if (!v.startsWith("https://") && !v.startsWith("http://")) {
                return FormValidation.error("The RaaS URL must start with https://");
            }
            return FormValidation.ok();
        }

        /** Lists the labels RaaS would deliver: the first proof that URL and credential are right. */
        @POST
        public FormValidation doTestConnection(@QueryParameter String url, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardUsernamePasswordCredentials c = lookupCredentials(credentialsId);
            if (c == null) {
                return FormValidation.error("Select an application credential (username = credential id, password = secret)");
            }
            try {
                List<RaasClient.Label> labels = new RaasClient(url, c.getUsername(), c.getPassword().getPlainText()).labels();
                if (labels.isEmpty()) {
                    return FormValidation.warning("Connected, but RaaS offers no labels to this project yet");
                }
                StringBuilder sb = new StringBuilder("Connected. Labels you can use in `agent { label '...' }`:");
                for (RaasClient.Label l : labels) {
                    sb.append("\n  ").append(l);
                }
                return FormValidation.ok(sb.toString());
            } catch (RaasException e) {
                return FormValidation.error("RaaS refused: " + e.getCode() + " — " + e.getMessage());
            } catch (IOException e) {
                return FormValidation.error("Could not reach RaaS: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FormValidation.error("Interrupted");
            }
        }
    }
}
