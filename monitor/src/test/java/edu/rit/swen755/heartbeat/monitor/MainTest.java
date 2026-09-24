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

    private static void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
