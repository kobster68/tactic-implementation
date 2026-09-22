package edu.rit.swen755.heartbeat.criticalservice;

import edu.rit.swen755.heartbeat.protocol.SensorReading;

/** Minimal lane-position assessment using a prototype drift threshold. */
public final class LaneDetector {

    private static final double DRIFT_THRESHOLD_METERS = 0.5;

    /**
     * Assesses a validated reading: negative offsets indicate left, positive indicate right.
     * Offsets exactly at either threshold are considered centered.
     */
    public LaneAssessment assess(SensorReading reading) {
        double offset = reading.laneOffsetMeters();
        if (offset < -DRIFT_THRESHOLD_METERS) {
            return LaneAssessment.DRIFTING_LEFT;
        }
        if (offset > DRIFT_THRESHOLD_METERS) {
            return LaneAssessment.DRIFTING_RIGHT;
        }
        return LaneAssessment.CENTERED;
    }
}
