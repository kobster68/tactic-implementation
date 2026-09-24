package edu.rit.swen755.heartbeat.criticalservice;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.io.IOException;
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
 * Optional {@code --run-for-ms <positive milliseconds>} bounds demo/test runs; it is not fault injection.
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
        long runForNanos = parseRunForNanos(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("critical-service"));

        long heartbeatPeriodMs = cfg.heartbeatPeriodMs();
        if (heartbeatPeriodMs <= 0) {
            throw new IllegalArgumentException("heartbeat.periodMs must be positive");
        }
        // Monotonic deadlines avoid changes to the system clock affecting heartbeat scheduling.
        long heartbeatPeriodNanos = Math.multiplyExact(heartbeatPeriodMs, 1_000_000L);
        InetSocketAddress heartbeatTarget = cfg.serviceHeartbeatTarget();
        LaneDetector laneDetector = new LaneDetector();

        // This socket closes on both normal completion and an uncaught input failure.
        try (DatagramSocket socket = new DatagramSocket(cfg.serviceListenPort())) {
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long heartbeatSeq = 0;
            // Send the first heartbeat immediately, without waiting for a sensor reading.
            long nextHeartbeatNanos = System.nanoTime();
            long stopAtNanos = nextHeartbeatNanos + runForNanos;

            // One thread owns processing and heartbeats: no heartbeat worker can outlive a crash.
            while (true) {
                long now = System.nanoTime();
                // A supplied runtime is a normal demo shutdown, not the simulated random fault.
                if (runForNanos > 0 && now - stopAtNanos >= 0) {
                    LOG.log(Level.INFO, "demo runtime elapsed; stopping normally");
                    break;
                }
                if (now - nextHeartbeatNanos >= 0) {
                    // Sequence numbers restart with the process; sentAt is a wall-clock log timestamp.
                    Heartbeat beat = new Heartbeat("critical-service", heartbeatSeq++,
                            System.currentTimeMillis());
                    byte[] beatBytes = Codec.encode(beat);
                    try {
                        socket.send(new DatagramPacket(beatBytes, beatBytes.length, heartbeatTarget));
                        LOG.log(Level.INFO, "sent {0} -> {1}", beat, heartbeatTarget);
                    } catch (IOException e) {
                        // Drop this beat and try the next scheduled one; sensor decoding stays uncaught.
                        LOG.log(Level.WARNING, "heartbeat send failed for seq=" + beat.seq()
                                + " -> " + heartbeatTarget, e);
                    }
                    // Skip overdue beats rather than sending a burst after a delay.
                    nextHeartbeatNanos = now + heartbeatPeriodNanos;
                }

                now = System.nanoTime();
                // Wait only until the next heartbeat or, when enabled, the demo deadline.
                long remainingNanos = nextHeartbeatNanos - now;
                if (runForNanos > 0) {
                    remainingNanos = Math.min(remainingNanos, stopAtNanos - now);
                }
                if (remainingNanos <= 0) {
                    continue;
                }
                // Round up: a zero socket timeout would block indefinitely.
                long timeoutMs = remainingNanos / 1_000_000L
                        + (remainingNanos % 1_000_000L == 0 ? 0 : 1);
                socket.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
                // receive() changes the packet length; restore capacity for the next datagram.
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
                // Perform the vehicle-function stub; health monitoring belongs to the receiver.
                LaneAssessment assessment = laneDetector.assess(sensorReading);
                LOG.log(Level.INFO, "lane offset={0} m, assessment={1}",
                        sensorReading.laneOffsetMeters(), assessment);
            }
        }
    }

    /** Returns zero for continuous operation, otherwise a validated demo runtime in nanoseconds. */
    static long parseRunForNanos(String[] args) {
        long runForNanos = 0;
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                i++; // NetConfig owns this option and its path argument.
            } else if ("--run-for-ms".equals(args[i])) {
                if (runForNanos != 0 || i + 1 >= args.length) {
                    throw new IllegalArgumentException("Use --run-for-ms once with a positive millisecond value");
                }
                long runForMs = Long.parseLong(args[++i]);
                if (runForMs <= 0 || runForMs > Long.MAX_VALUE / 1_000_000L) {
                    throw new IllegalArgumentException("--run-for-ms must be positive and fit in nanoseconds");
                }
                runForNanos = runForMs * 1_000_000L;
            }
        }
        return runForNanos;
    }
}
