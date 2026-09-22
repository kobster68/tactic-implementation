package edu.rit.swen755.heartbeat.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** NetConfig default load, -D override, and the endpoint-symmetry invariant. */
class NetConfigTest {

    @Test
    void loadsPackagedDefaults() {
        NetConfig cfg = NetConfig.load(new String[0]);
        assertEquals(1000L, cfg.heartbeatPeriodMs());
        assertEquals(500L, cfg.receiverCheckIntervalMs());
        assertEquals(3, cfg.receiverMissedCount());
        assertEquals(3000L, cfg.receiverExpireMs()); // derived: 3 × 1000
        assertEquals(1500L, cfg.monitorExpireMs());  // derived: 3 × 500
        assertEquals(1000L, cfg.sensorPeriodMs());
        assertEquals(0.1, cfg.sensorFaultProbability(), 1e-9);
        assertEquals(0.5, cfg.sensorCorruptShare(), 1e-9);
        assertEquals("localhost", cfg.sensorTargetHost());
        assertEquals(5001, cfg.sensorTargetPort());
    }

    @Test
    void systemPropertyOverridesDefault() {
        String key = "heartbeat.periodMs";
        String previous = System.getProperty(key);
        System.setProperty(key, "250");
        try {
            NetConfig cfg = NetConfig.load(new String[0]);
            assertEquals(250L, cfg.heartbeatPeriodMs());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void everyTargetPortMatchesItsPartnerListenPort() {
        NetConfig cfg = NetConfig.load(new String[0]);
        assertEquals(cfg.serviceListenPort(), cfg.sensorTargetPort(),
                "sensor.target.port must equal service.listen.port");
        assertEquals(cfg.receiverListenPort(), cfg.serviceHeartbeatTargetPort(),
                "service.heartbeat.target.port must equal receiver.listen.port");
        assertEquals(cfg.monitorListenPort(), cfg.receiverReportTargetPort(),
                "receiver.report.target.port must equal monitor.listen.port");
    }
}
