package edu.rit.swen755.heartbeat.sensorsim;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Sensor-simulator process (Step 0 tracer). It sends exactly one valid
 * {@link SensorReading} to the critical service and stops. No timer, no fault injection.
 */
public final class Main {

    static {
        // One-line log format; set before the logger triggers java.util.logging init.
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("sensor-sim");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("sensor-sim"));

        InetSocketAddress target = cfg.sensorTarget();
        try (DatagramSocket socket = new DatagramSocket()) {
            SensorReading reading = new SensorReading("lane-cam-0", 0, System.currentTimeMillis(), 0.0);
            byte[] bytes = Codec.encode(reading);
            socket.send(new DatagramPacket(bytes, bytes.length, target));
            LOG.log(Level.INFO, "tracer sent {0} -> {1}", reading, target);
        }

        // TODO(sensor-sim owner): periodic sending every sensor.periodMs, plus fault injection:
        //   with probability sensor.faultProbability inject either an out-of-range laneOffsetMeters
        //   or a truncated datagram, chosen by sensor.corruptShare, and log which.
        //   Everything after this line is the sensor-sim owner's slice.
        System.exit(0);
    }
}
