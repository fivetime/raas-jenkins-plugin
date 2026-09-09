package io.fivetime.raas.jenkins;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.slaves.Cloud;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Once a minute, two things that keep RaaS's view of this controller honest even when a call was lost:
 *
 * <ul>
 * <li><b>Retry lost terminations.</b> When {@link RaasAgent#_terminate} could not reach RaaS the node is
 *     still removed from Jenkins (the build is over; keeping a dead node helps nobody), and the release is
 *     queued here and retried until RaaS answers 204 or 404. The queue is persisted, so a controller restart
 *     does not lose it.</li>
 * <li><b>Heartbeat.</b> Tell each RaaS cloud which agents this controller still holds. RaaS reclaims a
 *     connected agent that a <em>live</em> controller no longer knows (node deleted while RaaS was down,
 *     plugin uninstalled, ...), and does nothing while heartbeats are absent altogether (controller down,
 *     Pipeline possibly resuming). The machine cannot tell on its own: over WebSocket remoting keeps
 *     reconnecting to a deleted node forever.</li>
 * </ul>
 */
@Extension
public class RaasPeriodicWork extends PeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(RaasPeriodicWork.class.getName());

    /** One release RaaS has not acknowledged yet. */
    public static final class Pending {
        public String cloudName;
        public long agentId;
        public String buildRef;
        public String conclusion;
        public long since;

        public Pending() {}

        Pending(String cloudName, long agentId, String buildRef, String conclusion) {
            this.cloudName = cloudName;
            this.agentId = agentId;
            this.buildRef = buildRef;
            this.conclusion = conclusion;
            this.since = System.currentTimeMillis();
        }
    }

    /** Persisted state: the pending releases. */
    public static final class State {
        public List<Pending> pending = new ArrayList<>();
    }

    private final Object lock = new Object();
    private State state;

    public static RaasPeriodicWork get() {
        return ExtensionList.lookupSingleton(RaasPeriodicWork.class);
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public long getInitialDelay() {
        return TimeUnit.SECONDS.toMillis(30);
    }

    /** Remembers a release RaaS did not acknowledge; retried every minute. */
    public void pendingTerminate(String cloudName, long agentId, String buildRef, String conclusion) {
        synchronized (lock) {
            State s = load();
            for (Pending p : s.pending) {
                if (p.agentId == agentId && cloudName.equals(p.cloudName)) {
                    return;
                }
            }
            s.pending.add(new Pending(cloudName, agentId, buildRef, conclusion));
            save(s);
        }
        LOGGER.log(Level.WARNING, "RaaS agent {0}: release not acknowledged; will retry every minute", agentId);
    }

    /** Snapshot of what is still pending (for tests and the UI). */
    public List<Pending> pending() {
        synchronized (lock) {
            return new ArrayList<>(load().pending);
        }
    }

    @Override
    protected void doRun() {
        retryPending();
        heartbeat();
    }

    void retryPending() {
        List<Pending> todo;
        synchronized (lock) {
            todo = new ArrayList<>(load().pending);
        }
        for (Pending p : todo) {
            RaasCloud cloud = RaasCloud.byName(p.cloudName);
            boolean done = false;
            if (cloud == null) {
                LOGGER.log(Level.WARNING, "RaaS cloud ''{0}'' is gone; dropping pending release of agent {1} (RaaS's reconciler will reclaim it)",
                        new Object[] {p.cloudName, p.agentId});
                done = true;
            } else {
                try {
                    cloud.client().terminate(p.agentId, p.buildRef, p.conclusion);
                    LOGGER.log(Level.INFO, "RaaS agent {0}: release retried successfully", p.agentId);
                    done = true;
                } catch (RaasException e) {
                    if (e.getStatus() == 404) {
                        done = true; // already gone on their side
                    } else {
                        LOGGER.log(Level.FINE, "RaaS agent " + p.agentId + ": release retry refused: " + e);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "RaaS agent " + p.agentId + ": release retry failed: " + e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (done) {
                synchronized (lock) {
                    State s = load();
                    s.pending.removeIf(q -> q.agentId == p.agentId && p.cloudName.equals(q.cloudName));
                    save(s);
                }
            }
        }
    }

    void heartbeat() {
        Map<String, List<Long>> held = new HashMap<>();
        for (Cloud c : Jenkins.get().clouds) {
            if (c instanceof RaasCloud) {
                held.put(c.name, new ArrayList<>());
            }
        }
        if (held.isEmpty()) {
            return;
        }
        for (Node n : Jenkins.get().getNodes()) {
            if (n instanceof RaasAgent) {
                RaasAgent a = (RaasAgent) n;
                List<Long> ids = held.get(a.getCloudName());
                if (ids != null && a.getAgentId() > 0) {
                    ids.add(a.getAgentId());
                }
            }
        }
        for (Map.Entry<String, List<Long>> e : held.entrySet()) {
            RaasCloud cloud = RaasCloud.byName(e.getKey());
            if (cloud == null) {
                continue;
            }
            try {
                cloud.client().heartbeat(e.getValue());
            } catch (IOException ex) {
                LOGGER.log(Level.FINE, "RaaS cloud '" + e.getKey() + "': heartbeat failed: " + ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- persistence ----

    private XmlFile file() {
        return new XmlFile(Jenkins.XSTREAM2, new File(Jenkins.get().getRootDir(), getClass().getName() + ".xml"));
    }

    private State load() {
        if (state != null) {
            return state;
        }
        State s = new State();
        XmlFile f = file();
        if (f.exists()) {
            try {
                Object o = f.read();
                if (o instanceof State) {
                    s = (State) o;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "could not read " + f + "; starting with an empty pending list", e);
            }
        }
        if (s.pending == null) {
            s.pending = new ArrayList<>();
        }
        state = s;
        return s;
    }

    private void save(State s) {
        state = s;
        try {
            file().write(s);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "could not persist pending RaaS releases", e);
        }
    }
}
