package io.fivetime.raas.jenkins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The retention policy as a table: what gets released, and — just as important — what does not. */
public class RaasRetentionStrategyTest {
    private static final long M = TimeUnit.MINUTES.toMillis(1);
    private static final long TIMEOUT = 15 * M;

    @Test
    public void spentNodeIsReleasedOnlyOnceIdle() {
        assertNull("a running build is never interrupted, however long it takes",
                RaasRetentionStrategy.decide(true, true, false, 0, 0, TIMEOUT, 72 * 60 * M));
        assertEquals("build finished", RaasRetentionStrategy.decide(true, true, true, 0, 0, TIMEOUT, 10 * M));
    }

    @Test
    public void neverConnectedIsReleasedAfterTheTimeout() {
        long created = 0;
        assertNull(RaasRetentionStrategy.decide(false, false, true, 0, created, TIMEOUT, 14 * M));
        assertEquals("never connected within the connect timeout",
                RaasRetentionStrategy.decide(false, false, true, 0, created, TIMEOUT, 16 * M));
    }

    @Test
    public void overProvisionedIdleNodeIsReleasedAfterAGrace() {
        long connected = 100 * M;
        assertNull("just connected: a queued build may still land here",
                RaasRetentionStrategy.decide(false, true, true, connected, 0, TIMEOUT, connected + 2 * M));
        assertEquals("connected but never used",
                RaasRetentionStrategy.decide(false, true, true, connected, 0, TIMEOUT, connected + 4 * M));
        assertNull("busy nodes are never touched",
                RaasRetentionStrategy.decide(false, true, false, connected, 0, TIMEOUT, connected + 40 * M));
    }
}
