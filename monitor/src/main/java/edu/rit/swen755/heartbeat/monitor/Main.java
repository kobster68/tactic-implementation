package edu.rit.swen755.heartbeat.monitor;

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
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Monitor process: watches the receiver's {@link StatusReport} stream and logs transitions,
 * including a silent receiver reaching the FAILED state.
 */
public final class Main {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("monitor");
    private static final int RECEIVE_TIMEOUT_MS = 30_000;
    private static final int BUFFER_SIZE = 2048;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("monitor"));

        String receiverId = "unknown";
        long lastReceiverSeenMs = System.currentTimeMillis();
        ServiceState receiverState = ServiceState.HEALTHY;
        Map<String, ServiceState> lastServiceState = new HashMap<>();

        try (DatagramSocket socket = new DatagramSocket(cfg.monitorListenPort())) {
            socket.setSoTimeout((int) Math.min(cfg.monitorCheckIntervalMs(), RECEIVE_TIMEOUT_MS));

            while (true) {
                try {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    Message message = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
                    if (!(message instanceof StatusReport report)) {
                        LOG.log(Level.WARNING, "ignored non-StatusReport {0} from {1}",
                                message, packet.getSocketAddress());
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    receiverId = report.receiverId();
                    lastReceiverSeenMs = now;
                    LOG.log(Level.INFO, "received {0} from {1}", report, packet.getSocketAddress());

                    for (ServiceStatus status : report.services()) {
                        ServiceState previous = lastServiceState.get(status.serviceId());
                        if (previous != status.state()) {
                            if (previous == null) {
                                LOG.log(Level.INFO, "SERVICE {0} {1}",
                                        new Object[] {status.serviceId(), status.state()});
                            } else {
                                LOG.log(Level.INFO, "SERVICE {0} {1} -> {2}",
                                        new Object[] {status.serviceId(), previous, status.state()});
                            }
                            lastServiceState.put(status.serviceId(), status.state());
                        }
                    }

                    receiverState = evaluateReceiverState(cfg, receiverId, lastReceiverSeenMs, receiverState);
                } catch (SocketTimeoutException timeout) {
                    receiverState = evaluateReceiverState(cfg, receiverId, lastReceiverSeenMs, receiverState);
                } catch (Exception decodeFailure) {
                    LOG.log(Level.WARNING, "failed to decode incoming datagram: {0}", decodeFailure.getMessage());
                }
            }
        }
    }

    private static ServiceState evaluateReceiverState(
            NetConfig cfg,
            String receiverId,
            long lastReceiverSeenMs,
            ServiceState currentReceiverState) {

        long now = System.currentTimeMillis();
        long delayMs = Math.max(0L, now - lastReceiverSeenMs);
        long missed = Math.floorDiv(delayMs, cfg.monitorCheckIntervalMs());

        ServiceState nextState;
        if (missed <= 0L) {
            nextState = ServiceState.HEALTHY;
        } else if (missed >= cfg.monitorMissedCount()) {
            nextState = ServiceState.FAILED;
        } else {
            nextState = ServiceState.SUSPECT;
        }

        if (currentReceiverState != nextState) {
            if (nextState == ServiceState.FAILED) {
                LOG.log(Level.ERROR, "RECEIVER {0} FAILED", receiverId);
            } else {
                LOG.log(Level.INFO, "RECEIVER {0} {1} -> {2}",
                        new Object[] {receiverId, currentReceiverState, nextState});
            }
        }

        return nextState;
    }
}
