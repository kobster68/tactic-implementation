package edu.rit.swen755.heartbeat.protocol;

/** Direction of lane drift carried in a lane-departure {@link Checkpoint}. */
public enum DriftDirection {

    /** No drift; the vehicle is tracking lane centre. */
    NONE,

    /** Drift towards the left lane boundary. */
    LEFT,

    /** Drift towards the right lane boundary. */
    RIGHT
}
