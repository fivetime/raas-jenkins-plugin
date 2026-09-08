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

    /** How long a connected agent may sit idle without ever having run a task before it is released. */
    static final long IDLE_UNUSED_MS = TimeUnit.MINUTES.toMillis(3);

    @Override
    public long check(RaasComputer c) {
        RaasAgent node = c.getNode();
        if (node == null) {
            return 1;
        }
        long now = System.currentTimeMillis();
        String why = decide(c.isUsed(), c.isOnline(), c.isIdle(), c.getConnectTime(), node.getCreatedAt(),
                TimeUnit.MINUTES.toMillis(node.getConnectTimeoutMinutes()), now);
        if (why != null) {
            terminate(c, why);
        }
        return 1;
    }

    /**
     * The whole retention policy as a pure decision, so it can be tested without a clock or a Jenkins.
     *
     * <ul>
     * <li>ran a task and is idle again → release: the machine is spent.</li>
     * <li>never connected within the connect timeout → release: the tenant's Jenkins is unreachable or too old.</li>
     * <li>connected, idle, never used for {@link #IDLE_UNUSED_MS} → release: Jenkins' provisioner routinely asks
     *     for one machine more than the queue ends up needing (it re-plans while the first one boots); without
     *     this rule that machine would sit there, billed, until someone noticed.</li>
     * </ul>
     *
     * @return the reason to terminate, or null to keep the node
     */
    static String decide(boolean used, boolean online, boolean idle, long connectTime, long createdAt,
            long connectTimeoutMs, long now) {
        if (used) {
            return idle ? "build finished" : null;
        }
        if (!online) {
            return now - createdAt > connectTimeoutMs ? "never connected within the connect timeout" : null;
        }
        if (idle && connectTime > 0 && now - connectTime > IDLE_UNUSED_MS) {
            return "connected but never used";
        }
        return null;
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
    public void taskStarted(Executor executor, Queue.Task task) {
        // The build reference is only reliably reachable while the task runs. For a Pipeline the executable
        // is a placeholder whose parent executable is the WorkflowRun; for a freestyle job it is the Run itself.
        Computer c = executor.getOwner();
        if (c instanceof RaasComputer) {
            RaasAgent node = ((RaasComputer) c).getNode();
            Run<?, ?> run = runOf(executor.getCurrentExecutable());
            if (node != null && run != null) {
                node.recordBuild(run.getExternalizableId(), null);
            }
        }
    }

    /** Walks the executable's parents until a {@link Run} shows up (Pipeline placeholders → WorkflowRun). */
    static Run<?, ?> runOf(Queue.Executable exe) {
        for (int i = 0; exe != null && i < 8; i++) {
            if (exe instanceof Run) {
                return (Run<?, ?>) exe;
            }
            exe = exe.getParentExecutable();
        }
        return null;
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
            Run<?, ?> run = runOf(executor.getCurrentExecutable());
            String ref = run != null ? run.getExternalizableId() : node.getBuildRef();
            String result = null;
            if (run != null && run.getResult() != null) {
                result = run.getResult().toString();
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
