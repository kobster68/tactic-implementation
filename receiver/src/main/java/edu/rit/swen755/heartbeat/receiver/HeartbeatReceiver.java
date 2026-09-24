package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.Heartbeat;
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

    private final long periodMs;
    private final int missedCount;
    private final Clock clock;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

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
