package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.Promote;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import edu.rit.swen755.heartbeat.protocol.ServiceStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The watchdog's aliveness logic, with no sockets or threads: the per-service last-seen table and
 * the rule that turns elapsed silence into a {@link ServiceState}.
 */
public final class HeartbeatReceiver {

    /**
     * How many promotes a single failover generation may send. The first is the FAILED edge; the
     * rest are loss-tolerant resends. Bounded so a permanently-dead service does not emit a promote
     * on every tick forever (the backup acts on an epoch once, so a handful of sends is plenty).
     */
    private static final int MAX_PROMOTE_SENDS_PER_EPOCH = 5;

    private final long periodMs;
    private final int missedCount;
    private final Clock clock;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    // Failover trigger state, touched only by the checker: the last state each service was judged in
    // (for the FAILED edge), its current failover generation (0 until the first failure), and how
    // many promotes it has sent in that generation (to bound the loss-tolerant resends).
    private final Map<String, ServiceState> failoverState = new ConcurrentHashMap<>();
    private final Map<String, Long> failoverEpoch = new ConcurrentHashMap<>();
    private final Map<String, Integer> promoteSends = new ConcurrentHashMap<>();

    public HeartbeatReceiver(long periodMs, int missedCount, Clock clock) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("periodMs must be positive: " + periodMs);
        }
        if (missedCount < 1) {
            throw new IllegalArgumentException("missedCount must be at least 1: " + missedCount);
        }
        this.periodMs = periodMs;
        this.missedCount = missedCount;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Beat handler: records that {@code beat}'s service was observed alive just now. */
    public void pitAPat(Heartbeat beat) {
        updateTime(beat.serviceId());
    }

    /** Stamps the service's last-seen time with the receiver's current clock. */
    void updateTime(String serviceId) {
        lastSeen.put(serviceId, clock.millis());
    }

    /**
     * Consecutive beats missed by a known service: {@code floor((now - lastSeen) / period)}. Zero
     * at the instant of a beat, one after a full period of silence, and so on.
     *
     * @throws IllegalArgumentException if {@code serviceId} has never sent a beat; use {@link
     *     #check()} for a snapshot over all tracked services
     */
    public int missed(String serviceId) {
        return missedSince(clock.millis(), seenAt(serviceId));
    }

    /**
     * Current judged state of a known service: HEALTHY with no misses, SUSPECT while misses are
     * below the configured threshold, FAILED once they reach it.
     *
     * @throws IllegalArgumentException if {@code serviceId} has never sent a beat (see {@link
     *     #missed(String)})
     */
    public ServiceState state(String serviceId) {
        return stateFor(missed(serviceId));
    }

    /**
     * True unless the service is {@link ServiceState#FAILED}, mirroring course slide 8.
     *
     * @throws IllegalArgumentException if {@code serviceId} has never sent a beat (see {@link
     *     #missed(String)})
     */
    public boolean checkAlive(String serviceId) {
        return state(serviceId) != ServiceState.FAILED;
    }

    /**
     * A snapshot judgement of every known service, evaluated against a single "now" so the states
     * in one report are mutually consistent. This is the checker tick that feeds a StatusReport.
     */
    public List<ServiceStatus> check() {
        long now = clock.millis();
        List<ServiceStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastSeen.entrySet()) {
            long seen = entry.getValue();
            int missed = missedSince(now, seen);
            statuses.add(new ServiceStatus(entry.getKey(), stateFor(missed), seen, missed));
        }
        return statuses;
    }

    /**
     * The derived expiry window shown on course slide 8 as {@code expireTime}: there is no separate
     * expiry key, so a service is FAILED once {@code missedCount} periods of silence elapse.
     */
    public long expireMs() {
        return (long) missedCount * periodMs;
    }

    /**
     * The failover trigger for one checker tick, derived from the {@code statuses} that same tick
     * judged. A service crossing into FAILED (its previous judged state was not FAILED) opens a new
     * failover generation: its epoch increments once and a {@link Promote} is issued for the backup,
     * flagged as the first send at that epoch. While the service stays FAILED the same epoch is
     * re-issued on later ticks so a dropped datagram is retried and the backup — which acts on an
     * epoch once and ignores repeats — still takes over; the resends are capped at {@value
     * #MAX_PROMOTE_SENDS_PER_EPOCH} per generation so a permanently-dead service does not send
     * forever. A service that is not FAILED issues nothing and keeps its epoch, so a later failure
     * opens a strictly higher generation with a fresh send budget.
     *
     * <p>Socket- and thread-free, so it is unit-testable with an injected clock — but NOT a pure
     * query: it advances the per-service failover state (edge, epoch, send budget) on each call and
     * is meant to be called exactly once per checker tick. {@code sentAt} is stamped from the
     * injected clock, which is the wall clock in production.
     *
     * @param statuses the states this tick judged (typically the result of {@link #check()})
     * @return one signal per FAILED service still within its send budget, in the order the statuses
     *     were given; empty if none
     */
    public List<FailoverSignal> failoverSignals(List<ServiceStatus> statuses) {
        long now = clock.millis();
        List<FailoverSignal> signals = new ArrayList<>();
        for (ServiceStatus status : statuses) {
            String serviceId = status.serviceId();
            ServiceState previous = failoverState.put(serviceId, status.state());
            boolean enteringFailed =
                    status.state() == ServiceState.FAILED && previous != ServiceState.FAILED;
            if (enteringFailed) {
                // A new generation: bump the epoch and reset the send budget.
                failoverEpoch.merge(serviceId, 1L, Long::sum);
                promoteSends.put(serviceId, 0);
            }
            if (status.state() == ServiceState.FAILED) {
                int sent = promoteSends.getOrDefault(serviceId, 0);
                if (sent < MAX_PROMOTE_SENDS_PER_EPOCH) {
                    long epoch = failoverEpoch.getOrDefault(serviceId, 0L);
                    // The FAILED edge is the only tick that opens a generation, so it is exactly the
                    // first (INFO) send; the ticks after it are the quiet (DEBUG) resends.
                    signals.add(new FailoverSignal(new Promote(serviceId, now, epoch), enteringFailed));
                    promoteSends.put(serviceId, sent + 1);
                }
            }
        }
        return signals;
    }

    // ---- the one aliveness formula, shared by missed(), state() and check() ------------------

    private long seenAt(String serviceId) {
        Long seen = lastSeen.get(serviceId);
        if (seen == null) {
            throw new IllegalArgumentException("unknown service: " + serviceId);
        }
        return seen;
    }

    private int missedSince(long now, long seen) {
        long elapsed = now - seen;
        return elapsed <= 0 ? 0 : (int) (elapsed / periodMs);
    }

    private ServiceState stateFor(int missed) {
        if (missed == 0) {
            return ServiceState.HEALTHY;
        }
        return missed < missedCount ? ServiceState.SUSPECT : ServiceState.FAILED;
    }
}
