package edu.rit.swen755.heartbeat.criticalservice;

import edu.rit.swen755.heartbeat.protocol.Checkpoint;
import edu.rit.swen755.heartbeat.protocol.Promote;
import java.util.Objects;

/** Owns replica role transitions and the state that must survive a promotion. */
public final class ReplicaController {

    private final String serviceId;
    private final LaneDepartureState laneState;
    private ReplicaRole role;
    private long lastCheckpointSeq = -1;
    // Epoch 0 is reserved as the pre-failover state; the receiver's first real promotion is 1.
    private long lastHandledEpoch = 0;

    public ReplicaController(String serviceId, ReplicaRole role, int warningThreshold) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must be non-blank");
        }
        this.serviceId = serviceId;
        this.role = Objects.requireNonNull(role, "role");
        this.laneState = new LaneDepartureState(warningThreshold);
    }

    public ReplicaRole role() {
        return role;
    }

    public LaneDepartureState laneState() {
        return laneState;
    }

    /** A passive backup accepts only newer checkpoints for its own service. */
    public boolean applyCheckpoint(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!serviceId.equals(checkpoint.serviceId()) || checkpoint.seq() <= lastCheckpointSeq) {
            return false;
        }
        laneState.apply(checkpoint);
        lastCheckpointSeq = checkpoint.seq();
        return true;
    }

    /** Promotes this replica once for each new epoch. */
    public boolean acceptPromotion(Promote promote) {
        Objects.requireNonNull(promote, "promote");
        if (!serviceId.equals(promote.serviceId()) || promote.epoch() <= lastHandledEpoch) {
            return false;
        }
        lastHandledEpoch = promote.epoch();
        role = ReplicaRole.PRIMARY;
        return true;
    }

    public boolean shouldProcessSensor() {
        return role == ReplicaRole.PRIMARY;
    }

    public boolean shouldSendHeartbeat() {
        return role == ReplicaRole.PRIMARY;
    }
}
