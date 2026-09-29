package edu.rit.swen755.heartbeat.protocol;

/**
 * The receiver instructs the backup for {@code serviceId} to become primary.
 *
 * @param serviceId non-blank identifier of the service being failed over
 * @param sentAt    sender wall-clock time in epoch milliseconds (display/latency only)
 * @param epoch     monotonically increasing failover generation, {@code >= 0}, so a backup can
 *                  ignore a stale or duplicate promote
 */
public record Promote(String serviceId, long sentAt, long epoch) implements Message {

    /** Rejects malformed instructions so {@code Codec.decode} throws on them. */
    public Promote {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must be non-blank");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, was " + epoch);
        }
    }
}
