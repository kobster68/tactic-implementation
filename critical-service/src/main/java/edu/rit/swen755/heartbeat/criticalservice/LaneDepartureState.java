package edu.rit.swen755.heartbeat.criticalservice;

import edu.rit.swen755.heartbeat.protocol.Checkpoint;
import edu.rit.swen755.heartbeat.protocol.DriftDirection;

/** Mutable, checkpointable state for the prototype lane-departure detector. */
public final class LaneDepartureState {

    private final int warningThreshold;
    private int consecutiveDriftCount;
    private DriftDirection driftDirection = DriftDirection.NONE;
    private boolean warningActive;

    public LaneDepartureState(int warningThreshold) {
        if (warningThreshold < 1) {
            throw new IllegalArgumentException("warningThreshold must be at least 1");
        }
        this.warningThreshold = warningThreshold;
    }

    /** Applies one lane assessment and returns whether the warning became active. */
    public boolean apply(LaneAssessment assessment) {
        if (assessment == null) {
            throw new NullPointerException("assessment");
        }
        if (assessment == LaneAssessment.CENTERED) {
            consecutiveDriftCount = 0;
            driftDirection = DriftDirection.NONE;
            warningActive = false;
        } else {
            consecutiveDriftCount++;
            driftDirection = assessment == LaneAssessment.DRIFTING_LEFT
                    ? DriftDirection.LEFT : DriftDirection.RIGHT;
            warningActive = consecutiveDriftCount >= warningThreshold;
        }
        return warningActive;
    }

    /** Replaces local state with a validated checkpoint from the primary. */
    public void apply(Checkpoint checkpoint) {
        if (checkpoint == null) {
            throw new NullPointerException("checkpoint");
        }
        consecutiveDriftCount = checkpoint.consecutiveDriftCount();
        driftDirection = checkpoint.driftDirection();
        warningActive = checkpoint.warningActive();
    }

    public Checkpoint checkpoint(String serviceId, long seq, long sentAt) {
        return new Checkpoint(serviceId, seq, sentAt, consecutiveDriftCount,
                driftDirection, warningActive);
    }

    public int consecutiveDriftCount() {
        return consecutiveDriftCount;
    }

    public DriftDirection driftDirection() {
        return driftDirection;
    }

    public boolean warningActive() {
        return warningActive;
    }
}
