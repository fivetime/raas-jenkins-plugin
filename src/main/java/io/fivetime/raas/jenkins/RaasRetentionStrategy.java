package io.fivetime.raas.jenkins;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * One build, then gone.
 *
 * <p>Two triggers destroy a {@link RaasAgent}: the task that ran on it completed (so the machine's
 * state is spent — Docker leftovers, workspaces, credentials in memory), or it never connected within
 * the cloud's connect timeout (the tenant's Jenkins is unreachable from the RaaS cloud, or the version
 * predates WebSocket agents). Nothing here kills a node by age while a build is running: long builds are
 * the tenant's business, and RaaS's own reconciler asks the machine, not the clock.
 */
public class RaasRetentionStrategy extends RetentionStrategy<RaasComputer> implements ExecutorListener {
    private static final Logger LOGGER = Logger.getLogger(RaasRetentionStrategy.class.getName());

    @DataBoundConstructor
    public RaasRetentionStrategy() {}

    @Override
    public long check(RaasComputer c) {
        RaasAgent node = c.getNode();
        if (node == null) {
            return 1;
        }
        if (c.isUsed() && c.isIdle()) {
            terminate(c, "build finished");
            return 1;
        }
        if (!c.isUsed() && c.isOffline() && c.getChannel() == null) {
            long age = System.currentTimeMillis() - node.getCreatedAt();
            if (age > TimeUnit.MINUTES.toMillis(node.getConnectTimeoutMinutes())) {
                terminate(c, "never connected within " + node.getConnectTimeoutMinutes() + " minutes");
            }
        }
        return 1;
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        Computer c = executor.getOwner();
        if (c instanceof RaasComputer) {
            RaasComputer rc = (RaasComputer) c;
            rc.markUsed();
            // Nothing else may be scheduled here; the machine is spent once this task ends.
            rc.setAcceptingTasks(false);
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor, null);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor, problems);
    }

    private void done(Executor executor, Throwable problems) {
        Computer c = executor.getOwner();
        if (!(c instanceof RaasComputer)) {
            return;
        }
        RaasComputer rc = (RaasComputer) c;
        RaasAgent node = rc.getNode();
        if (node != null) {
            Queue.Executable exe = executor.getCurrentExecutable();
            String ref = exe instanceof Run ? ((Run<?, ?>) exe).getExternalizableId() : null;
            String result = null;
            if (exe instanceof Run && ((Run<?, ?>) exe).getResult() != null) {
                result = ((Run<?, ?>) exe).getResult().toString();
            } else if (problems != null) {
                result = "FAILURE";
            }
            node.recordBuild(ref, result);
        }
        terminate(rc, "task completed");
    }

    /** Terminates once, off the executor thread: RaaS is an HTTP call away and must not block Jenkins. */
    static void terminate(RaasComputer c, String why) {
        if (!c.beginTerminate()) {
            return;
        }
        RaasAgent node = c.getNode();
        if (node == null) {
            return;
        }
        LOGGER.log(Level.INFO, "RaaS agent {0}: terminating ({1})", new Object[] {node.getNodeName(), why});
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                node.terminate();
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.WARNING, "RaaS agent " + node.getNodeName() + ": terminate failed; RaaS's reconciler will reclaim it", e);
            }
        });
    }

    @Override
    public boolean isAcceptingTasks(RaasComputer c) {
        return !c.isUsed();
    }

    @Override
    public void start(RaasComputer c) {
        c.connect(false);
    }

    /** Serialisation compatibility marker for the node's config.xml. */
    @Extension
    public static final class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "RaaS: one build, then destroy";
        }
    }

    @SuppressWarnings("unused")
    private static final TaskListener NULL = TaskListener.NULL;
}
