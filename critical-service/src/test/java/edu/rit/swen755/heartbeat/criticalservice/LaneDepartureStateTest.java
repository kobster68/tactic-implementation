package edu.rit.swen755.heartbeat.criticalservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.heartbeat.protocol.Checkpoint;
import edu.rit.swen755.heartbeat.protocol.DriftDirection;
import org.junit.jupiter.api.Test;

class LaneDepartureStateTest {

    @Test
    void warningActivatesAfterConsecutiveDriftThreshold() {
        LaneDepartureState state = new LaneDepartureState(3);

        assertFalse(state.apply(LaneAssessment.DRIFTING_LEFT));
        assertFalse(state.apply(LaneAssessment.DRIFTING_LEFT));
        assertTrue(state.apply(LaneAssessment.DRIFTING_LEFT));
        assertEquals(3, state.consecutiveDriftCount());
        assertEquals(DriftDirection.LEFT, state.driftDirection());
    }

    @Test
    void centeredReadingResetsWarningState() {
        LaneDepartureState state = new LaneDepartureState(2);
        state.apply(LaneAssessment.DRIFTING_RIGHT);
        state.apply(LaneAssessment.DRIFTING_RIGHT);

        state.apply(LaneAssessment.CENTERED);

        assertEquals(0, state.consecutiveDriftCount());
        assertEquals(DriftDirection.NONE, state.driftDirection());
        assertFalse(state.warningActive());
    }

    @Test
    void changingDriftDirectionStartsANewStreak() {
        LaneDepartureState state = new LaneDepartureState(3);
        state.apply(LaneAssessment.DRIFTING_LEFT);
        state.apply(LaneAssessment.DRIFTING_LEFT);

        assertFalse(state.apply(LaneAssessment.DRIFTING_RIGHT));
        assertEquals(1, state.consecutiveDriftCount());
        assertEquals(DriftDirection.RIGHT, state.driftDirection());
    }

    @Test
    void checkpointRestoresAllRecoverableState() {
        LaneDepartureState primary = new LaneDepartureState(3);
        primary.apply(LaneAssessment.DRIFTING_RIGHT);
        primary.apply(LaneAssessment.DRIFTING_RIGHT);

        LaneDepartureState backup = new LaneDepartureState(3);
        backup.apply(primary.checkpoint("critical-service", 7, 1000));

        assertEquals(2, backup.consecutiveDriftCount());
        assertEquals(DriftDirection.RIGHT, backup.driftDirection());
        assertFalse(backup.warningActive());
        assertTrue(backup.apply(LaneAssessment.DRIFTING_RIGHT));
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new LaneDepartureState(0));
    }

    @Test
    void checkpointContainsCurrentState() {
        LaneDepartureState state = new LaneDepartureState(1);
        state.apply(LaneAssessment.DRIFTING_LEFT);

        Checkpoint checkpoint = state.checkpoint("critical-service", 4, 99);

        assertEquals(1, checkpoint.consecutiveDriftCount());
        assertEquals(DriftDirection.LEFT, checkpoint.driftDirection());
        assertTrue(checkpoint.warningActive());
    }
}
