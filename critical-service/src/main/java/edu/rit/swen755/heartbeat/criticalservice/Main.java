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
import java.util.Arrays;

/**
 * Critical-service process (Step 0 tracer). It receives one reading, decodes it in a
 * single uncaught call, then sends one {@link Heartbeat} to the receiver and stops.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("critical-service");
    private static final int RECEIVE_TIMEOUT_MS = 30_000;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("critical-service"));

        Message reading;
        try (DatagramSocket socket = new DatagramSocket(cfg.serviceListenPort())) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS); // a tracer run must not hang forever
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            // decode parses AND validates in one call; it is left uncaught, so a
            // malformed reading throws here and crashes the process (the fault the tactic detects).
            reading = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            LOG.log(Level.INFO, "tracer received {0} from {1}", reading, packet.getSocketAddress());

            Heartbeat beat = new Heartbeat("critical-service", 0, System.currentTimeMillis());
            byte[] beatBytes = Codec.encode(beat);
            InetSocketAddress heartbeatTarget = cfg.serviceHeartbeatTarget();
            socket.send(new DatagramPacket(beatBytes, beatBytes.length, heartbeatTarget));
            LOG.log(Level.INFO, "tracer sent {0} -> {1}", beat, heartbeatTarget);
        }

        // TODO(critical-service owner): a receive loop over readings and a heartbeat timer at
        //   heartbeat.periodMs. Keep Codec.decode uncaught so a malformed reading crashes the JVM
        //   and stops the beats. Everything after this line is your slice.
        if (!(reading instanceof SensorReading sensorReading)) {
            throw new IllegalArgumentException("Expected a SensorReading message");
        }
        LaneDetector laneDetector = new LaneDetector();
        LaneAssessment assessment = laneDetector.assess(sensorReading);
        LOG.log(Level.INFO, "lane offset={0} m, assessment={1}",
                sensorReading.laneOffsetMeters(), assessment);

        System.exit(0);
    }
}
