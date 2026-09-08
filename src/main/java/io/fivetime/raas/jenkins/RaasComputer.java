package io.fivetime.raas.jenkins;

import hudson.slaves.AbstractCloudComputer;
import java.util.concurrent.atomic.AtomicBoolean;

/** The computer side of a {@link RaasAgent}. Remembers whether a build has already run here. */
public class RaasComputer extends AbstractCloudComputer<RaasAgent> {
    private final AtomicBoolean used = new AtomicBoolean(false);
    private final AtomicBoolean terminating = new AtomicBoolean(false);

    public RaasComputer(RaasAgent agent) {
        super(agent);
    }

    /** A task has been accepted here: this node is spent once that task finishes. */
    void markUsed() {
        used.set(true);
    }

    public boolean isUsed() {
        return used.get();
    }

    /** Returns true the first time, so termination runs exactly once. */
    boolean beginTerminate() {
        return terminating.compareAndSet(false, true);
    }
}
