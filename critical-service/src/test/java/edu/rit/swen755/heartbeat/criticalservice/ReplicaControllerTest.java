package edu.rit.swen755.heartbeat.criticalservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.heartbeat.protocol.Checkpoint;
import edu.rit.swen755.heartbeat.protocol.DriftDirection;
import edu.rit.swen755.heartbeat.protocol.Promote;
import org.junit.jupiter.api.Test;

class ReplicaControllerTest {

    @Test
    void backupIsPassiveUntilPromoted() {
        ReplicaController replica = new ReplicaController("critical-service", ReplicaRole.BACKUP, 3);

        assertFalse(replica.shouldProcessSensor());
        assertFalse(replica.shouldSendHeartbeat());

        assertTrue(replica.acceptPromotion(new Promote("critical-service", 10, 1)));
        assertTrue(replica.shouldProcessSensor());
        assertTrue(replica.shouldSendHeartbeat());
    }

    @Test
    void duplicateAndStalePromotionsAreIgnored() {
        ReplicaController replica = new ReplicaController("critical-service", ReplicaRole.BACKUP, 3);

        assertTrue(replica.acceptPromotion(new Promote("critical-service", 10, 4)));
        assertFalse(replica.acceptPromotion(new Promote("critical-service", 11, 4)));
        assertFalse(replica.acceptPromotion(new Promote("critical-service", 12, 3)));
        assertTrue(replica.acceptPromotion(new Promote("critical-service", 13, 5)));
    }

    @Test
    void backupAppliesOnlyNewerMatchingCheckpoints() {
        ReplicaController replica = new ReplicaController("critical-service", ReplicaRole.BACKUP, 3);
        Checkpoint first = new Checkpoint("critical-service", 2, 10, 2,
                DriftDirection.RIGHT, false);
        Checkpoint duplicate = new Checkpoint("critical-service", 2, 11, 0,
                DriftDirection.NONE, false);
        Checkpoint wrongService = new Checkpoint("other-service", 3, 12, 9,
                DriftDirection.LEFT, true);

        assertTrue(replica.applyCheckpoint(first));
        assertFalse(replica.applyCheckpoint(duplicate));
        assertFalse(replica.applyCheckpoint(wrongService));
        assertTrue(replica.laneState().consecutiveDriftCount() == 2);
    }
}
