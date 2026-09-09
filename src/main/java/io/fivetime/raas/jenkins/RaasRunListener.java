package io.fivetime.raas.jenkins;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * Reports a build's conclusion to RaaS once the build is over.
 *
 * <p>A Pipeline releases its RaaS node when the {@code agent} block ends, which is before the run has a
 * result, so the release call cannot carry the conclusion. {@link RaasPeriodicWork} remembers which agent
 * ran which build; when the run completes we send the result for it.
 */
@Extension
public class RaasRunListener extends RunListener<Run<?, ?>> {
    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        String result = run.getResult() == null ? null : run.getResult().toString();
        RaasPeriodicWork.get().conclude(run.getExternalizableId(), result);
    }
}
