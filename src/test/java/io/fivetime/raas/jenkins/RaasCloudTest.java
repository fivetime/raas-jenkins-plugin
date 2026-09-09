package io.fivetime.raas.jenkins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.fasterxml.jackson.databind.JsonNode;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.SlaveComputer;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** The provisioning contract between the plugin and RaaS, against a real (test) Jenkins. */
public class RaasCloudTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    private FakeRaas raas;
    private RaasCloud cloud;

    @Before
    public void setUp() throws Exception {
        raas = new FakeRaas();
        UsernamePasswordCredentialsImpl cred = new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "raas-cred", "RaaS application credential", "ac-1", "s3cret");
        SystemCredentialsProvider.getInstance().getCredentials().add(cred);
        SystemCredentialsProvider.getInstance().save();
        cloud = new RaasCloud("raas", raas.url, "raas-cred");
        j.jenkins.clouds.add(cloud);
    }

    @After
    public void tearDown() {
        raas.close();
    }

    /** Only labels RaaS publishes are provisionable: the tenant configures nothing label-related. */
    @Test
    public void canProvisionOnlyRaasLabels() {
        assertTrue(cloud.canProvision(new Cloud.CloudState(Label.get("raas-ubuntu-24.04"), 0)));
        assertFalse(cloud.canProvision(new Cloud.CloudState(Label.get("windows-2022"), 0)));
        assertFalse("no label at all is not ours", cloud.canProvision(new Cloud.CloudState(null, 0)));
    }

    /**
     * The node exists in Jenkins before RaaS hears about it (that is what mints the secret), and RaaS is
     * told exactly the node name, this controller's URL and that node's JNLP secret.
     */
    @Test
    public void provisionAddsNodeThenTellsRaasTheSecret() throws Exception {
        Collection<NodeProvisioner.PlannedNode> planned =
                cloud.provision(new Cloud.CloudState(Label.get("raas-ubuntu-24.04"), 0), 1);
        assertEquals(1, planned.size());
        NodeProvisioner.PlannedNode p = planned.iterator().next();
        assertEquals(1, p.numExecutors);
        Node n = p.future.get(60, TimeUnit.SECONDS);
        assertTrue(n instanceof RaasAgent);
        RaasAgent agent = (RaasAgent) n;
        assertEquals(17, agent.getAgentId());
        assertNotNull("node must be registered in Jenkins", j.jenkins.getNode(agent.getNodeName()));
        assertEquals(1, agent.getNumExecutors());
        assertEquals(Node.Mode.EXCLUSIVE, agent.getMode());
        assertEquals("raas-ubuntu-24.04", agent.getLabelString());
        assertTrue(agent.getLauncher() instanceof JNLPLauncher);
        assertTrue("agents dial out over WebSocket; no inbound port on either side",
                ((JNLPLauncher) agent.getLauncher()).isWebSocket());
        assertTrue(agent.getRetentionStrategy() instanceof RaasRetentionStrategy);

        assertEquals(1, raas.provisionBodies.size());
        JsonNode body = raas.provisionBodies.get(0);
        assertEquals("raas-ubuntu-24.04", body.path("label").asText());
        assertEquals(agent.getNodeName(), body.path("node_name").asText());
        assertEquals(j.getURL().toString(), body.path("jenkins_url").asText());
        SlaveComputer c = (SlaveComputer) agent.toComputer();
        assertNotNull(c);
        assertEquals("RaaS must get the secret Jenkins expects for this very node",
                c.getJnlpMac(), body.path("secret").asText());

        // Termination reports the build and removes the node; RaaS's DELETE is the destroy signal.
        agent.recordBuild("job/hello/1", "SUCCESS");
        agent.terminate();
        assertEquals(java.util.List.of("17"), raas.deletes);
        assertEquals("job/hello/1", raas.deleteBodies.get(0).path("build_ref").asText());
        assertNull("node is gone after terminate", j.jenkins.getNode(agent.getNodeName()));
    }

    /** A refusal leaves no phantom node behind and surfaces RaaS's code, not a timeout. */
    @Test
    public void refusalRemovesTheNodeAndNamesTheReason() throws Exception {
        raas.refuseStatus = 409;
        raas.refuseCode = "no_network";
        int before = j.jenkins.getNodes().size();
        NodeProvisioner.PlannedNode p =
                cloud.provision(new Cloud.CloudState(Label.get("raas-ubuntu-24.04"), 0), 1).iterator().next();
        ExecutionException e = assertThrows(ExecutionException.class, () -> p.future.get(60, TimeUnit.SECONDS));
        assertTrue("cause: " + e.getCause(), e.getCause() instanceof RaasException);
        assertEquals("no_network", ((RaasException) e.getCause()).getCode());
        assertEquals("the node added before the request must be removed again", before, j.jenkins.getNodes().size());
    }

    /** The descriptor's Test connection lists the labels: the tenant's first proof the pairing works. */
    @Test
    public void testConnectionListsLabels() {
        RaasCloud.DescriptorImpl d = (RaasCloud.DescriptorImpl) cloud.getDescriptor();
        String msg = d.doTestConnection(raas.url, "raas-cred").renderHtml();
        assertTrue(msg, msg.contains("raas-ubuntu-24.04"));
        String bad = d.doTestConnection(raas.url, "missing-cred").renderHtml();
        assertTrue(bad, bad.contains("application credential"));
    }

    /**
     * RaaS is down when the build ends: the node is still removed (the build is over), the release is queued,
     * and the next periodic run delivers it with the build reference intact.
     */
    @Test
    public void lostReleaseIsRetriedUntilRaasAcknowledges() throws Exception {
        NodeProvisioner.PlannedNode p =
                cloud.provision(new Cloud.CloudState(Label.get("raas-ubuntu-24.04"), 0), 1).iterator().next();
        RaasAgent agent = (RaasAgent) p.future.get(60, TimeUnit.SECONDS);
        agent.recordBuild("job/hello/7", "SUCCESS");
        raas.failDeletes = true;
        agent.terminate();
        assertNull("node is removed even though RaaS refused", j.jenkins.getNode(agent.getNodeName()));
        assertTrue("nothing acknowledged yet", raas.deletes.isEmpty());
        assertEquals(1, RaasPeriodicWork.get().pending().size());

        RaasPeriodicWork.get().retryPending();
        assertTrue("still down: keep it queued", raas.deletes.isEmpty());
        assertEquals(1, RaasPeriodicWork.get().pending().size());

        raas.failDeletes = false;
        RaasPeriodicWork.get().retryPending();
        assertEquals(java.util.List.of("17"), raas.deletes);
        assertEquals("job/hello/7", raas.deleteBodies.get(0).path("build_ref").asText());
        assertTrue("acknowledged: nothing pending", RaasPeriodicWork.get().pending().isEmpty());
    }

    /** The heartbeat lists exactly the RaaS agents this controller holds; with none it still beats (empty). */
    @Test
    public void heartbeatListsHeldAgents() throws Exception {
        RaasPeriodicWork.get().heartbeat();
        assertEquals(java.util.List.of(), raas.heartbeats.get(raas.heartbeats.size() - 1));
        NodeProvisioner.PlannedNode p =
                cloud.provision(new Cloud.CloudState(Label.get("raas-ubuntu-24.04"), 0), 1).iterator().next();
        p.future.get(60, TimeUnit.SECONDS);
        RaasPeriodicWork.get().heartbeat();
        assertEquals(java.util.List.of(17L), raas.heartbeats.get(raas.heartbeats.size() - 1));
    }
}
