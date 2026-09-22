package edu.rit.swen755.heartbeat.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import edu.rit.swen755.heartbeat.protocol.ServiceStatus;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The aliveness state rule, exercised through {@link HeartbeatReceiver}'s public interface with a
 * hand-advanced clock so no real time passes. Defaults under test: period 1000 ms, missedCount 3.
 */
class HeartbeatReceiverTest {

    private static final String SVC = "critical-service";
    private static final long PERIOD_MS = 1_000;
    private static final int MISSED_COUNT = 3;

    /** A clock the test steps forward explicitly; nothing here reads real time. */
    private static final class FakeClock implements Clock {
        private long now;

        FakeClock(long start) {
            this.now = start;
        }

        void advance(long ms) {
            now += ms;
        }

        @Override
        public long millis() {
            return now;
        }
    }

    private HeartbeatReceiver newReceiver(FakeClock clock) {
        return new HeartbeatReceiver(PERIOD_MS, MISSED_COUNT, clock);
    }

    @Test
    void aServiceThatJustBeatIsHealthy() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);

        receiver.pitAPat(new Heartbeat(SVC, 0, clock.millis()));

        assertEquals(ServiceState.HEALTHY, receiver.state(SVC));
        assertTrue(receiver.checkAlive(SVC));
    }

    @Test
    void oneMissedPeriodIsSuspect() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);
        receiver.pitAPat(new Heartbeat(SVC, 0, clock.millis()));

        clock.advance(PERIOD_MS); // one full period of silence

        assertEquals(ServiceState.SUSPECT, receiver.state(SVC));
        assertTrue(receiver.checkAlive(SVC), "SUSPECT is still alive");
    }

    @Test
    void justBelowThresholdIsStillSuspect() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);
        receiver.pitAPat(new Heartbeat(SVC, 0, clock.millis()));

        clock.advance((MISSED_COUNT - 1) * PERIOD_MS); // 2 missed with the defaults

        assertEquals(2, receiver.missed(SVC));
        assertEquals(ServiceState.SUSPECT, receiver.state(SVC));
    }

    @Test
    void missedCountReachedIsFailed() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);
        receiver.pitAPat(new Heartbeat(SVC, 0, clock.millis()));

        clock.advance(MISSED_COUNT * PERIOD_MS); // exactly the threshold

        assertEquals(ServiceState.FAILED, receiver.state(SVC));
        assertFalse(receiver.checkAlive(SVC), "FAILED is not alive");
    }

    @Test
    void aBeatAfterFailureRecoversToHealthy() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);
        receiver.pitAPat(new Heartbeat(SVC, 0, clock.millis()));
        clock.advance(MISSED_COUNT * PERIOD_MS);
        assertEquals(ServiceState.FAILED, receiver.state(SVC));

        receiver.pitAPat(new Heartbeat(SVC, 1, clock.millis())); // the next beat arrives

        assertEquals(ServiceState.HEALTHY, receiver.state(SVC));
        assertTrue(receiver.checkAlive(SVC));
    }

    @Test
    void checkReportsEachKnownServiceIndependently() {
        FakeClock clock = new FakeClock(1_000);
        HeartbeatReceiver receiver = newReceiver(clock);

        receiver.pitAPat(new Heartbeat("svc-a", 0, clock.millis())); // a last seen at 1000
        clock.advance(PERIOD_MS);
        receiver.pitAPat(new Heartbeat("svc-b", 0, clock.millis())); // b last seen at 2000
        clock.advance(2 * PERIOD_MS); // now = 4000

        // a: elapsed 3000 -> missed 3 -> FAILED; b: elapsed 2000 -> missed 2 -> SUSPECT
        Map<String, ServiceStatus> byId = receiver.check().stream()
                .collect(Collectors.toMap(ServiceStatus::serviceId, Function.identity()));

        assertEquals(2, byId.size());
        assertEquals(ServiceState.FAILED, byId.get("svc-a").state());
        assertEquals(3, byId.get("svc-a").missedCount());
        assertEquals(1_000L, byId.get("svc-a").lastSeen());
        assertEquals(ServiceState.SUSPECT, byId.get("svc-b").state());
        assertEquals(2, byId.get("svc-b").missedCount());
        assertEquals(2_000L, byId.get("svc-b").lastSeen());
    }

    @Test
    void expiryIsDerivedFromMissedCountAndPeriod() {
        HeartbeatReceiver receiver = newReceiver(new FakeClock(0));

        assertEquals(MISSED_COUNT * PERIOD_MS, receiver.expireMs());
    }

    @Test
    void rejectsNonPositivePeriodAndMissedCount() {
        FakeClock clock = new FakeClock(0);
        // A zero/negative period would divide-by-zero in the aliveness rule; guard at construction.
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatReceiver(0, MISSED_COUNT, clock));
        // A zero missedCount would erase the SUSPECT band and derive a zero expiry.
        assertThrows(IllegalArgumentException.class,
                () -> new HeartbeatReceiver(PERIOD_MS, 0, clock));
    }

    @Test
    void rejectsNullClock() {
        // A null clock would fail later at the first clock.millis(), far from here; fail fast.
        assertThrows(NullPointerException.class,
                () -> new HeartbeatReceiver(PERIOD_MS, MISSED_COUNT, null));
    }

    @Test
    void queryingAServiceThatNeverBeatThrows() {
        HeartbeatReceiver receiver = newReceiver(new FakeClock(1_000));
        // The single-service queries require a known service; check() is the snapshot of all.
        assertThrows(IllegalArgumentException.class, () -> receiver.state("never-seen"));
        assertThrows(IllegalArgumentException.class, () -> receiver.checkAlive("never-seen"));
        assertThrows(IllegalArgumentException.class, () -> receiver.missed("never-seen"));
    }
}
