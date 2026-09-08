package io.fivetime.raas.jenkins;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tells RaaS the moment an agent comes online. That timestamp is the start of the billable period:
 * everything before it (taking the machine, attaching the network, downloading agent.jar) is RaaS's
 * provisioning time and is not charged to the tenant.
 */
@Extension
public class RaasComputerListener extends ComputerListener {
    private static final Logger LOGGER = Logger.getLogger(RaasComputerListener.class.getName());

    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        if (!(c instanceof RaasComputer)) {
            return;
        }
        RaasAgent node = ((RaasComputer) c).getNode();
        if (node == null || node.getAgentId() == 0) {
            return;
        }
        RaasCloud cloud = RaasCloud.byName(node.getCloudName());
        if (cloud == null) {
            return;
        }
        try {
            cloud.client().connected(node.getAgentId());
            listener.getLogger().println("RaaS agent " + node.getAgentId() + " connected");
        } catch (IOException e) {
            // Not fatal for the build: RaaS's reconciler will still settle the agent; billing may start late.
            LOGGER.log(Level.WARNING, "RaaS agent " + node.getAgentId() + ": could not report connected: " + e);
        }
    }
}
