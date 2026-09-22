package edu.rit.swen755.heartbeat.protocol;

import java.util.List;

/**
 * The receiver's periodic report to the monitor (hop 3). It doubles as the receiver's
 * own heartbeat, so the monitor can detect a dead receiver from silence on this stream.
 *
 * @param receiverId identifier of the reporting receiver
 * @param seq        per-report sequence number
 * @param sentAt     sender wall-clock time in epoch milliseconds (display/latency only)
 * @param services   one {@link ServiceStatus} per monitored service
 */
public record StatusReport(String receiverId, long seq, long sentAt, List<ServiceStatus> services)
        implements Message {
}
