package edu.rit.swen755.heartbeat.monitor;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

/**
 * Monitor process (Step 0 tracer). It receives and logs one {@link StatusReport}
 * from the receiver and stops. No transition logic and no timer on the report stream yet.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("monitor");
    private static final int RECEIVE_TIMEOUT_MS = 30_000;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("monitor"));

        try (DatagramSocket socket = new DatagramSocket(cfg.monitorListenPort())) {
            socket.setSoTimeout(RECEIVE_TIMEOUT_MS);
            byte[] buffer = new byte[2048];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            Message report = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            LOG.log(Level.INFO, "tracer received {0} from {1}", report, packet.getSocketAddress());
        }

        // TODO(monitor owner): log and notify on state transitions carried in each StatusReport, and
        //   evaluate the report stream every monitor.checkIntervalMs with the miss-count rule so a silent
        //   receiver is logged as "RECEIVER <id> FAILED".
        //   Everything after this line is your slice.
        System.exit(0);
    }
}
