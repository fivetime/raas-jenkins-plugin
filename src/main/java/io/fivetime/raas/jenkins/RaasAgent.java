package io.fivetime.raas.jenkins;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.JNLPLauncher;
import hudson.model.Slave;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One RaaS-provided agent: a single-executor, exclusive node that connects to the controller over
 * WebSocket and is destroyed after one build. {@link #_terminate} tells RaaS to destroy the machine.
 *
 * <p>Deliberately <b>not</b> an {@code EphemeralNode}: those vanish on a controller restart without
 * {@code _terminate} ever running, so RaaS would never hear about the node and a resumed Pipeline would
 * wait forever for an agent that no longer exists. Persisting the node lets the WebSocket agent reconnect
 * after the restart, the build resume, and the retention strategy release the machine as usual.
 */
public class RaasAgent extends AbstractCloudSlave {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(RaasAgent.class.getName());

    private final String cloudName;
    private final String raasLabel;
    private final long createdAt;
    private final int connectTimeoutMinutes;
    /** RaaS's id for this agent; 0 until RaaS has accepted the request. */
    private long agentId;
    /** What ran here, reported to RaaS on termination so the tenant can see it in their history. */
    private String buildRef;
    private String conclusion;

    public RaasAgent(String name, String raasLabel, String cloudName, int connectTimeoutMinutes)
            throws Descriptor.FormException, IOException {
        super(name, "/home/runner/jenkins", webSocketLauncher());
        this.cloudName = cloudName;
        this.raasLabel = raasLabel;
        this.createdAt = System.currentTimeMillis();
        this.connectTimeoutMinutes = connectTimeoutMinutes;
        setNodeDescription("RaaS agent (" + raasLabel + ") from cloud " + cloudName);
        setNumExecutors(1);
        setMode(Mode.EXCLUSIVE);
        setLabelString(raasLabel);
        setRetentionStrategy(new RaasRetentionStrategy());
        setNodeProperties(Collections.emptyList());
    }

    private static JNLPLauncher webSocketLauncher() {
        JNLPLauncher l = new JNLPLauncher();
        // The machine has no inbound path; it dials the controller on 443. Needs Jenkins >= 2.217.
        l.setWebSocket(true);
        return l;
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getRaasLabel() {
        return raasLabel;
    }

    public long getAgentId() {
        return agentId;
    }

    void setAgentId(long id) {
        this.agentId = id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getConnectTimeoutMinutes() {
        return connectTimeoutMinutes <= 0 ? 15 : connectTimeoutMinutes;
    }

    void recordBuild(String ref, String result) {
        if (ref != null) {
            this.buildRef = ref;
        }
        if (result != null) {
            this.conclusion = result;
        }
    }

    public String getBuildRef() {
        return buildRef;
    }

    @Override
    public AbstractCloudComputer<RaasAgent> createComputer() {
        return new RaasComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        if (agentId == 0) {
            // RaaS never accepted this node; there is nothing on their side to destroy.
            return;
        }
        RaasCloud cloud = RaasCloud.byName(cloudName);
        if (cloud == null) {
            listener.error("RaaS cloud '" + cloudName + "' no longer exists; agent " + agentId
                    + " will be reclaimed by RaaS's own reconciler");
            return;
        }
        if (buildRef != null && conclusion == null) {
            // A Pipeline releases the node before the run has a result: report it when the run completes.
            RaasPeriodicWork.get().awaitConclusion(cloudName, agentId, buildRef);
        }
        try {
            cloud.client().terminate(agentId, buildRef, conclusion);
            listener.getLogger().println("RaaS agent " + agentId + " released");
        } catch (RaasException e) {
            if (e.getStatus() == 404) {
                return; // already gone on their side: idempotent
            }
            // A refusal we cannot fix from here (5xx, 429): keep asking. The node itself is removed regardless —
            // the build is over — so the release must not depend on this one call getting through.
            LOGGER.log(Level.WARNING, "RaaS agent " + agentId + ": terminate not acknowledged (" + e + "); queued for retry");
            RaasPeriodicWork.get().pendingTerminate(cloudName, agentId, buildRef, conclusion);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RaaS agent " + agentId + ": RaaS unreachable (" + e + "); release queued for retry");
            RaasPeriodicWork.get().pendingTerminate(cloudName, agentId, buildRef, conclusion);
        }
    }

    @Extension
    public static final class DescriptorImpl extends Slave.SlaveDescriptor {
        @Override
        public String getDisplayName() {
            return "RaaS agent";
        }

        @Override
        public boolean isInstantiable() {
            return false; // only the cloud creates these
        }
    }
}
