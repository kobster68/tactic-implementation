package edu.rit.swen755.heartbeat.receiver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.Heartbeat;
import edu.rit.swen755.heartbeat.protocol.Message;
import edu.rit.swen755.heartbeat.protocol.ServiceState;
import edu.rit.swen755.heartbeat.protocol.ServiceStatus;
import edu.rit.swen755.heartbeat.protocol.StatusReport;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end over real UDP sockets on ephemeral ports: a stand-in "critical service" beats at a
 * discovered listen port, a stand-in "monitor" socket captures the {@link StatusReport} stream, and
 * the receiver runs its real receive and checker threads. Timing is compressed so the tests finish
 * quickly; {@link #reportsFailureAtDemoDefaultTiming()} additionally exercises the shipped profile.
 */
class ReceiverNodeIntegrationTest {

    private static final String SVC = "critical-service";

    @Test
    void reportsHealthyThenSuspectThenFailedAsBeatsStop() throws Exception {
        long periodMs = 100;
        long checkMs = 50;
        int missedCount = 3;

        try (DatagramSocket monitor = new DatagramSocket(new InetSocketAddress("localhost", 0))) {
            monitor.setSoTimeout(500);
            InetSocketAddress reportTarget =
                    new InetSocketAddress("localhost", monitor.getLocalPort());
            HeartbeatReceiver receiver = new HeartbeatReceiver(periodMs, missedCount, Clock.SYSTEM);

            try (ReceiverNode node =
                    new ReceiverNode(receiver, 0, reportTarget, checkMs, "receiver-test")) {
                node.start();
                InetSocketAddress listen = new InetSocketAddress("localhost", node.listenPort());

                try (DatagramSocket service = new DatagramSocket()) {
                    // Beat well within a period so the service stays solidly HEALTHY for a while.
                    for (int seq = 0; seq < 8; seq++) {
                        sendHeartbeat(service, listen, SVC, seq);
                        Thread.sleep(periodMs / 2);
                    }
                }
                // Beats have stopped; watch the report stream march to FAILED.
                long deadline = System.currentTimeMillis() + (missedCount + 4) * periodMs + 1_000;
                List<ServiceState> states = observeStates(monitor, SVC, deadline);

                assertOrderedHealthySuspectFailed(states);
            }
        }
    }

    @Test
    void malformedDatagramIsSkippedAndReceiverKeepsRunning() throws Exception {
        long periodMs = 100;
        long checkMs = 50;
        int missedCount = 3;

        try (DatagramSocket monitor = new DatagramSocket(new InetSocketAddress("localhost", 0))) {
            monitor.setSoTimeout(500);
            InetSocketAddress reportTarget =
                    new InetSocketAddress("localhost", monitor.getLocalPort());
            HeartbeatReceiver receiver = new HeartbeatReceiver(periodMs, missedCount, Clock.SYSTEM);

            try (ReceiverNode node =
                    new ReceiverNode(receiver, 0, reportTarget, checkMs, "receiver-test")) {
                node.start();
                InetSocketAddress listen = new InetSocketAddress("localhost", node.listenPort());

                try (DatagramSocket service = new DatagramSocket()) {
                    // Garbage on the listen port must not take the receive thread down.
                    byte[] junk = "{not a valid heartbeat".getBytes(StandardCharsets.UTF_8);
                    service.send(new DatagramPacket(junk, junk.length, listen));
                    // Real beats follow: if the thread survived, the service is reported HEALTHY.
                    for (int seq = 0; seq < 6; seq++) {
                        sendHeartbeat(service, listen, SVC, seq);
                        Thread.sleep(periodMs / 2);
                    }
                }
                long deadline = System.currentTimeMillis() + 1_000;
                assertTrue(waitForState(monitor, SVC, ServiceState.HEALTHY, deadline),
                        "receiver should still report HEALTHY after a malformed datagram");
            }
        }
    }

    @Test
    void reportsFailureAtDemoDefaultTiming() throws Exception {
        long periodMs = 1_000; // the shipped demo profile: 1 s period, 500 ms check, 3 missed
        long checkMs = 500;
        int missedCount = 3;

        try (DatagramSocket monitor = new DatagramSocket(new InetSocketAddress("localhost", 0))) {
            monitor.setSoTimeout(1_000);
            InetSocketAddress reportTarget =
                    new InetSocketAddress("localhost", monitor.getLocalPort());
            HeartbeatReceiver receiver = new HeartbeatReceiver(periodMs, missedCount, Clock.SYSTEM);

            try (ReceiverNode node =
                    new ReceiverNode(receiver, 0, reportTarget, checkMs, "receiver-test")) {
                node.start();
                InetSocketAddress listen = new InetSocketAddress("localhost", node.listenPort());

                try (DatagramSocket service = new DatagramSocket()) {
                    for (int seq = 0; seq < 3; seq++) { // establish HEALTHY within a period
                        sendHeartbeat(service, listen, SVC, seq);
                        Thread.sleep(periodMs / 2);
                    }
                }
                // FAILED must land within missedCount x period plus one check interval (+ margin).
                long deadline =
                        System.currentTimeMillis() + missedCount * periodMs + checkMs + 2_000;
                List<ServiceState> states = observeStates(monitor, SVC, deadline);

                assertOrderedHealthySuspectFailed(states);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void sendHeartbeat(DatagramSocket from, InetSocketAddress to, String id, int seq)
            throws IOException {
        byte[] bytes = Codec.encode(new Heartbeat(id, seq, System.currentTimeMillis()));
        from.send(new DatagramPacket(bytes, bytes.length, to));
    }

    /**
     * Reads status reports until the target service is seen FAILED or the deadline passes, returning
     * that service's states in order with consecutive duplicates collapsed.
     */
    private static List<ServiceState> observeStates(
            DatagramSocket monitor, String serviceId, long deadlineMs) throws IOException {
        List<ServiceState> sequence = new ArrayList<>();
        byte[] buffer = new byte[4096];
        while (System.currentTimeMillis() < deadlineMs) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                monitor.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            }
            Message message = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            if (!(message instanceof StatusReport report)) {
                continue;
            }
            for (ServiceStatus status : report.services()) {
                if (!status.serviceId().equals(serviceId)) {
                    continue;
                }
                if (sequence.isEmpty() || sequence.get(sequence.size() - 1) != status.state()) {
                    sequence.add(status.state());
                }
                if (status.state() == ServiceState.FAILED) {
                    return sequence;
                }
            }
        }
        return sequence;
    }

    /** True once the target service is reported in {@code target} state before the deadline. */
    private static boolean waitForState(
            DatagramSocket monitor, String serviceId, ServiceState target, long deadlineMs)
            throws IOException {
        byte[] buffer = new byte[4096];
        while (System.currentTimeMillis() < deadlineMs) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                monitor.receive(packet);
            } catch (SocketTimeoutException timeout) {
                continue;
            }
            Message message = Codec.decode(Arrays.copyOf(packet.getData(), packet.getLength()));
            if (!(message instanceof StatusReport report)) {
                continue;
            }
            for (ServiceStatus status : report.services()) {
                if (status.serviceId().equals(serviceId) && status.state() == target) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertOrderedHealthySuspectFailed(List<ServiceState> states) {
        int healthy = states.indexOf(ServiceState.HEALTHY);
        int suspect = states.indexOf(ServiceState.SUSPECT);
        int failed = states.indexOf(ServiceState.FAILED);
        assertTrue(healthy >= 0, () -> "expected a HEALTHY report, saw " + states);
        assertTrue(suspect > healthy, () -> "expected SUSPECT after HEALTHY, saw " + states);
        assertTrue(failed > suspect, () -> "expected FAILED after SUSPECT, saw " + states);
    }
}
