package edu.rit.swen755.heartbeat.protocol;

/**
 * A lane-position measurement sent by the sensor simulator to the critical service (hop 1).
 *
 * @param sensorId         non-blank identifier of the emitting sensor
 * @param seq              monotonically increasing sequence number, {@code >= 0}
 * @param sentAt           sender wall-clock time in epoch milliseconds (display/latency only;
 *                         ordering is by {@code seq}, per S4 p.57 Timestamp)
 * @param laneOffsetMeters signed lateral offset of the vehicle from lane centre, in metres.
 *                         <strong>Team value:</strong> the valid range is {@code [-3.5, 3.5]} m,
 *                         chosen as roughly one standard lane half-width either side of centre; a
 *                         reading outside it (or non-finite) is treated as a misbehaving sensor and
 *                         rejected here, which is one of the two fault kinds.
 */
public record SensorReading(String sensorId, long seq, long sentAt, double laneOffsetMeters)
        implements Message {

    /** Lower bound (metres) of the accepted lane offset; see {@link #laneOffsetMeters()}. */
    public static final double MIN_LANE_OFFSET_METERS = -3.5;

    /** Upper bound (metres) of the accepted lane offset; see {@link #laneOffsetMeters()}. */
    public static final double MAX_LANE_OFFSET_METERS = 3.5;

    /** Rejects malformed readings so {@code Codec.decode} throws on them. */
    public SensorReading {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId must be non-blank");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0, was " + seq);
        }
        if (!Double.isFinite(laneOffsetMeters)
                || laneOffsetMeters < MIN_LANE_OFFSET_METERS
                || laneOffsetMeters > MAX_LANE_OFFSET_METERS) {
            throw new IllegalArgumentException(
                    "laneOffsetMeters must be finite and within [" + MIN_LANE_OFFSET_METERS + ", "
                            + MAX_LANE_OFFSET_METERS + "] m, was " + laneOffsetMeters);
        }
    }
}
