package edu.rit.swen755.heartbeat.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void receiverNeverSeenStaysUnfailed() {
        NetConfig cfg = NetConfig.load(new String[0]);
        long now = System.currentTimeMillis();

        ServiceState state = Main.evaluateReceiverState(cfg, null, now - cfg.monitorExpireMs(), ServiceState.HEALTHY);

        assertEquals(ServiceState.HEALTHY, state);
    }

    @Test
    void monitorRejectsNonPositiveCheckInterval() {
        String original = System.getProperty("monitor.checkIntervalMs");
        try {
            System.setProperty("monitor.checkIntervalMs", "0");
            IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> Main.main(new String[0]));
            assertEquals("monitor.checkIntervalMs must be positive", ex.getMessage());
        } finally {
            restoreProperty("monitor.checkIntervalMs", original);
        }
    }

    @Test
    void receiverIsHealthyWhenFreshlySeen() {
        NetConfig cfg = NetConfig.load(new String[0]);
        long now = System.currentTimeMillis();

        ServiceState state = Main.evaluateReceiverState(cfg, "receiver-0", now, ServiceState.HEALTHY);

        assertEquals(ServiceState.HEALTHY, state);
    }

    @Test
    void receiverOneReportIntervalLateStaysHealthy() {
        NetConfig cfg = NetConfig.load(new String[0]);
        long now = System.currentTimeMillis();
        // One report interval of cadence jitter is normal, not a miss worth flagging SUSPECT.
        long lastSeen = now - cfg.receiverCheckIntervalMs();

        ServiceState state = Main.evaluateReceiverState(cfg, "receiver-0", lastSeen, ServiceState.HEALTHY);

        assertEquals(ServiceState.HEALTHY, state);
    }

    @Test
    void receiverBecomesSuspectBeforeTheFailureThreshold() {
        NetConfig cfg = NetConfig.load(new String[0]);
        long now = System.currentTimeMillis();
        long lastSeen = now - (cfg.monitorExpireMs() - cfg.receiverCheckIntervalMs());

        ServiceState state = Main.evaluateReceiverState(cfg, "receiver-0", lastSeen, ServiceState.HEALTHY);

        assertEquals(ServiceState.SUSPECT, state);
    }

    @Test
    void receiverBecomesFailedAtTheConfiguredExpireWindow() {
        NetConfig cfg = NetConfig.load(new String[0]);
        long now = System.currentTimeMillis();
        long lastSeen = now - cfg.monitorExpireMs();

        ServiceState state = Main.evaluateReceiverState(cfg, "receiver-0", lastSeen, ServiceState.SUSPECT);

        assertEquals(ServiceState.FAILED, state);
    }

    @Test
    void thresholdUsesReceiverReportCadenceNotMonitorPollingCadence() {
        String originalCheck = System.getProperty("receiver.checkIntervalMs");
        String originalMissed = System.getProperty("monitor.missedCount");
        try {
            System.setProperty("receiver.checkIntervalMs", "200");
            System.setProperty("monitor.missedCount", "3");

            NetConfig cfg = NetConfig.load(new String[0]);
            long now = System.currentTimeMillis();
            long lastSeen = now - (cfg.monitorExpireMs() - cfg.receiverCheckIntervalMs());

            ServiceState state = Main.evaluateReceiverState(cfg, "receiver-0", lastSeen, ServiceState.HEALTHY);

            assertEquals(ServiceState.SUSPECT, state);
        } finally {
            restoreProperty("receiver.checkIntervalMs", originalCheck);
            restoreProperty("monitor.missedCount", originalMissed);
        }
    }

    @Test
    void failedWindowIsHonouredEvenWhenTheJitterGraceWouldCoverIt() {
        String original = System.getProperty("monitor.missedCount");
        try {
            System.setProperty("monitor.missedCount", "1"); // expire = 1 x receiver.checkIntervalMs
            NetConfig cfg = NetConfig.load(new String[0]);
            long now = System.currentTimeMillis();
            // Silent past the expire window but within the jitter grace; must be FAILED, not HEALTHY.
            long lastSeen = now - (cfg.monitorExpireMs() + cfg.receiverCheckIntervalMs() / 4);

            ServiceState state =
                    Main.evaluateReceiverState(cfg, "receiver-0", lastSeen, ServiceState.HEALTHY);

            assertEquals(ServiceState.FAILED, state);
        } finally {
            restoreProperty("monitor.missedCount", original);
        }
    }

    private static void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
