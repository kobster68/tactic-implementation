package edu.rit.swen755.heartbeat.criticalservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.rit.swen755.heartbeat.protocol.SensorReading;
import org.junit.jupiter.api.Test;

/** Pins the lane assessment behavior, including the two inclusive centered boundaries. */
class LaneDetectorTest {

    private final LaneDetector detector = new LaneDetector();

    @Test
    void offsetBelowLeftThresholdIsDriftingLeft() {
        SensorReading reading = new SensorReading("lane-cam-0", 0, 1_700_000_000_000L, -0.51);
        assertEquals(LaneAssessment.DRIFTING_LEFT, detector.assess(reading));
    }

    @Test
    void offsetAboveRightThresholdIsDriftingRight() {
        SensorReading reading = new SensorReading("lane-cam-0", 0, 1_700_000_000_000L, 0.51);
        assertEquals(LaneAssessment.DRIFTING_RIGHT, detector.assess(reading));
    }

    @Test
    void zeroOffsetIsCentered() {
        SensorReading reading = new SensorReading("lane-cam-0", 0, 1_700_000_000_000L, 0.0);
        assertEquals(LaneAssessment.CENTERED, detector.assess(reading));
    }

    @Test
    void exactLeftThresholdIsCentered() {
        SensorReading reading = new SensorReading("lane-cam-0", 0, 1_700_000_000_000L, -0.5);
        assertEquals(LaneAssessment.CENTERED, detector.assess(reading));
    }

    @Test
    void exactRightThresholdIsCentered() {
        SensorReading reading = new SensorReading("lane-cam-0", 0, 1_700_000_000_000L, 0.5);
        assertEquals(LaneAssessment.CENTERED, detector.assess(reading));
    }
}
