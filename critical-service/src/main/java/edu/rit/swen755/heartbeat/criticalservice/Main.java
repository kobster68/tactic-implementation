package edu.rit.swen755.heartbeat.criticalservice;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/**
 * Critical-service process: assesses sensor readings and sends periodic heartbeats.
 * Malformed input propagates out of the single service thread, stopping the process and its beats.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("critical-service");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("critical-service"));

        long heartbeatPeriodMs = cfg.heartbeatPeriodMs();
        if (heartbeatPeriodMs <= 0) {
            throw new IllegalArgumentException("heartbeat.periodMs must be positive");
        }
        long heartbeatPeriodNanos = Math.multiplyExact(heartbeatPeriodMs, 1_000_000L);
        InetSocketAddress heartbeatTarget = cfg.serviceHeartbeatTarget();
        LaneDetector laneDetector = new LaneDetector();

        try (DatagramSocket socket = new DatagramSocket(cfg.serviceListenPort())) {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long heartbeatSeq = 0;
            long nextHeartbeatNanos = System.nanoTime();

            while (true) {
                long now = System.nanoTime();
                if (now - nextHeartbeatNanos >= 0) {
                    Heartbeat beat = new Heartbeat("critical-service", heartbeatSeq++,
                            System.currentTimeMillis());
                    byte[] beatBytes = Codec.encode(beat);
                    socket.send(new DatagramPacket(beatBytes, beatBytes.length, heartbeatTarget));
                    LOG.log(Level.INFO, "sent {0} -> {1}", beat, heartbeatTarget);
                    // Skip overdue beats rather than sending a burst after a delay.
                    nextHeartbeatNanos = now + heartbeatPeriodNanos;
                }

                long remainingNanos = nextHeartbeatNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    continue;
                }
                // Round up: a zero socket timeout would block indefinitely.
                long timeoutMs = remainingNanos / 1_000_000L
                        + (remainingNanos % 1_000_000L == 0 ? 0 : 1);
                socket.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
                packet.setLength(buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue; // Recheck the heartbeat deadline.
                }

                // Parse and validation failures remain uncaught and stop the service and its beats.
                Message reading = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
                if (!(reading instanceof SensorReading sensorReading)) {
                    throw new IllegalArgumentException("Expected a SensorReading message");
                }
                LaneAssessment assessment = laneDetector.assess(sensorReading);
                LOG.log(Level.INFO, "lane offset={0} m, assessment={1}",
                        sensorReading.laneOffsetMeters(), assessment);
            }
        }
    }
}
