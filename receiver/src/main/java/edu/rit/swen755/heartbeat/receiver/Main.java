package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import edu.rit.swen755.heartbeat.protocol.ServiceStatus;
import edu.rit.swen755.heartbeat.protocol.StatusReport;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * Receiver process (Step 0 tracer). It receives one heartbeat, then sends one
 * {@link StatusReport} carrying a single HEALTHY {@link ServiceStatus} to the monitor and stops.
 * No last-seen table, no watchdog timer, no state machine yet.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("receiver");
    private static final int RECEIVE_TIMEOUT_MS = 30_000;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("receiver"));

        try (DatagramSocket socket = new DatagramSocket(cfg.receiverListenPort())) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            Message heartbeat = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            LOG.log(Level.INFO, "tracer received {0} from {1}", heartbeat, packet.getSocketAddress());

            long now = System.currentTimeMillis();
            StatusReport report = new StatusReport("receiver-0", 0, now,
                    List.of(new ServiceStatus("critical-service", ServiceState.HEALTHY, now, 0)));
            byte[] reportBytes = Codec.encode(report);
            InetSocketAddress reportTarget = cfg.receiverReportTarget();
            socket.send(new DatagramPacket(reportBytes, reportBytes.length, reportTarget));
            LOG.log(Level.INFO, "tracer sent {0} -> {1}", report, reportTarget);
        }

        // TODO(receiver owner): a per-service last-seen table, checkAlive() every
        //   receiver.checkIntervalMs, a periodic StatusReport every check interval, and the
        //   HEALTHY/SUSPECT/FAILED transitions using missed = floor((now - lastSeen) /
        //   heartbeat.periodMs) against receiver.missedCount. Everything after this line is your slice.
        System.exit(0);
    }
}
