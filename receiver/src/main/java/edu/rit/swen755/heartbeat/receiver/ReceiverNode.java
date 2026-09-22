package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import edu.rit.swen755.heartbeat.protocol.ServiceStatus;
import edu.rit.swen755.heartbeat.protocol.StatusReport;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The receiver process's runtime: it wraps a pure {@link HeartbeatReceiver} in the sockets and the
 * two threads that course slide 9 keeps separate — one that receives beats and one that checks
 * aliveness. On every check it sends the monitor a {@link StatusReport}, which doubles as the
 * receiver's own heartbeat.
 *
 * <p>A malformed datagram on the listen port is logged and skipped: the receiver is the watchdog,
 * not the process the injected fault is meant to crash. {@link #close()} stops both threads and
 * releases the socket.
 */
public final class ReceiverNode implements AutoCloseable {

    private static final Logger LOG = System.getLogger("receiver");
    private static final int RECEIVE_BUFFER_BYTES = 4096;
    private static final int RECEIVE_POLL_MS = 500;
    private static final long RECEIVE_ERROR_BACKOFF_MS = 100;

    private final HeartbeatReceiver receiver;
    private final DatagramSocket socket;
    private final InetSocketAddress reportTarget;
    private final long checkIntervalMs;
    private final String receiverId;

    private final Thread receiveThread;
    private final Thread checkThread;
    private final AtomicLong reportSeq = new AtomicLong();
    private final Map<String, ServiceState> lastLoggedState = new ConcurrentHashMap<>();
    private volatile boolean running;

    /**
     * Binds the listen socket immediately so {@link #listenPort()} is available before {@link
     * #start()}; pass {@code listenPort} 0 for an ephemeral port.
     */
    public ReceiverNode(HeartbeatReceiver receiver, int listenPort, InetSocketAddress reportTarget,
            long checkIntervalMs, String receiverId) throws SocketException {
        this.receiver = receiver;
        this.reportTarget = reportTarget;
        this.checkIntervalMs = checkIntervalMs;
        this.receiverId = receiverId;
        this.socket = new DatagramSocket(listenPort);
        this.socket.setSoTimeout(RECEIVE_POLL_MS);
        this.receiveThread = new Thread(this::receiveLoop, "receiver-receive");
        this.checkThread = new Thread(this::checkLoop, "receiver-check");
    }

    /** The bound listen port (useful when an ephemeral port was requested). */
    public int listenPort() {
        return socket.getLocalPort();
    }

    /** Starts the receive and checker threads. */
    public void start() {
        running = true;
        receiveThread.start();
        checkThread.start();
    }

    @Override
    public void close() {
        running = false;
        socket.close(); // unblocks a blocking receive()
        checkThread.interrupt(); // unblocks the checker's sleep
        join(receiveThread);
        join(checkThread);
    }

    // ---- receive thread: decode beats, update the last-seen table ---------------------------

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER_BYTES];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException poll) {
                continue; // periodic wake-up so the loop can observe shutdown
            } catch (SocketException closed) {
                if (running) {
                    LOG.log(Level.WARNING, "receive socket error", closed);
                }
                return; // socket closed by close()
            } catch (IOException e) {
                // Back off so a persistent receive error cannot spin the CPU in a tight loop.
                LOG.log(Level.WARNING, "receive failed; backing off", e);
                sleepQuietly(RECEIVE_ERROR_BACKOFF_MS);
                continue;
            }
            handle(packet);
        }
    }

    private void handle(DatagramPacket packet) {
        try {
            Message message = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            if (message instanceof Heartbeat beat) {
                receiver.pitAPat(beat);
            } else {
                LOG.log(Level.DEBUG, "ignoring non-heartbeat on receiver port: {0}", message);
            }
        } catch (IOException | RuntimeException malformed) {
            // Logged and skipped, never fatal: an unparseable or out-of-range beat must not take
            // the watchdog down with the service it is watching.
            LOG.log(Level.WARNING, "skipping malformed heartbeat datagram ({0} bytes): {1}",
                    packet.getLength(), malformed.toString());
        }
    }

    // ---- checker thread: evaluate state, log transitions, report to the monitor -------------

    private void checkLoop() {
        while (running) {
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running) {
                try {
                    tick();
                } catch (RuntimeException e) {
                    // The watchdog must not silently die on an unexpected error: log and keep
                    // checking, so detection and reporting continue on the next tick.
                    LOG.log(Level.WARNING, "check tick failed; continuing", e);
                }
            }
        }
    }

    private void tick() {
        List<ServiceStatus> statuses = receiver.check();
        logTransitions(statuses);
        StatusReport report = new StatusReport(
                receiverId, reportSeq.getAndIncrement(), System.currentTimeMillis(), statuses);
        try {
            byte[] bytes = Codec.encode(report);
            socket.send(new DatagramPacket(bytes, bytes.length, reportTarget));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "failed to send status report", e);
        }
    }

    private void logTransitions(List<ServiceStatus> statuses) {
        for (ServiceStatus status : statuses) {
            ServiceState previous = lastLoggedState.put(status.serviceId(), status.state());
            if (previous == status.state()) {
                continue;
            }
            switch (status.state()) {
                // SUSPECT is logged below FAILED: a build-up worth seeing, not yet an alarm.
                case HEALTHY -> LOG.log(Level.INFO, "{0} HEALTHY", status.serviceId());
                case SUSPECT -> LOG.log(Level.INFO,
                        "{0} SUSPECT (missed {1})", status.serviceId(), status.missedCount());
                case FAILED -> LOG.log(Level.WARNING,
                        "{0} FAILED (missed {1})", status.serviceId(), status.missedCount());
                default -> throw new IllegalStateException("unhandled state: " + status.state());
            }
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
