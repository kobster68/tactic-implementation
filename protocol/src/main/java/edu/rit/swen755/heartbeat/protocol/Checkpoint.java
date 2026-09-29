package edu.rit.swen755.heartbeat.protocol;

/**
 * The primary's snapshot of its lane-departure state, sent to a warm-spare backup so the backup can
 * take over with current state on failover (passive redundancy).
 *
 * @param serviceId             non-blank identifier of the primary whose state this captures
 * @param seq                   monotonically increasing sequence number, {@code >= 0}
 * @param sentAt                sender wall-clock time in epoch milliseconds (display/latency only)
 * @param consecutiveDriftCount consecutive lane-departure readings observed so far, {@code >= 0}
 * @param driftDirection        current {@link DriftDirection} of the drift
 * @param warningActive         whether a lane-departure warning is currently active
 */
public record Checkpoint(String serviceId, long seq, long sentAt, int consecutiveDriftCount,
        DriftDirection driftDirection, boolean warningActive) implements Message {

    /** Rejects malformed snapshots so {@code Codec.decode} throws on them. */
    public Checkpoint {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must be non-blank");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0, was " + seq);
        }
        if (consecutiveDriftCount < 0) {
            throw new IllegalArgumentException(
                    "consecutiveDriftCount must be >= 0, was " + consecutiveDriftCount);
        }
        if (driftDirection == null) {
            throw new IllegalArgumentException("driftDirection must be non-null");
        }
    }
}
