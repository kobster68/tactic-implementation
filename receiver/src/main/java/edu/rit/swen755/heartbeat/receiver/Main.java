package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.NetConfig;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.CountDownLatch;

/**
 * Receiver process: the watchdog over service heartbeats. It binds the listen port, updates a
 * per-service last-seen table as beats arrive, and every {@code receiver.checkIntervalMs} judges
 * each service HEALTHY / SUSPECT / FAILED and sends the monitor a {@code StatusReport} that doubles
 * as the receiver's own heartbeat. It runs until the process is stopped.
 *
 * <p>The watchdog logic lives in {@link HeartbeatReceiver} (pure, clock-injected) and the sockets
 * and threads in {@link ReceiverNode}; this class only wires configuration to them.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("receiver");

    /** Identifier this receiver reports under; override with {@code -Dreceiver.id=<name>}. */
    private static final String DEFAULT_RECEIVER_ID = "receiver-1";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("receiver"));

        String receiverId = System.getProperty("receiver.id", DEFAULT_RECEIVER_ID);
        HeartbeatReceiver receiver = new HeartbeatReceiver(
                cfg.heartbeatPeriodMs(), cfg.receiverMissedCount(), Clock.SYSTEM);
        ReceiverNode node = new ReceiverNode(receiver, cfg.receiverListenPort(),
                cfg.receiverReportTarget(), cfg.receiverCheckIntervalMs(), receiverId);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            node.close();
            stopped.countDown();
        }, "receiver-shutdown"));

        node.start();
        // Startup line above (cfg.describe) already logs the timings; this only adds the id and the
        // actually-bound port. The port is passed as a String so MessageFormat does not group it.
        LOG.log(Level.INFO, "{0} watching udp/{1}", receiverId, Integer.toString(node.listenPort()));

        stopped.await(); // run until Ctrl-C / SIGTERM triggers the shutdown hook
    }
}
