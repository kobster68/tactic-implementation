package edu.rit.swen755.heartbeat.receiver;

import edu.rit.swen755.heartbeat.protocol.Promote;

/**
 * One failover instruction produced by a checker tick: the {@link Promote} to send to the backup and
 * whether it is the first send at its epoch.
 *
 * <p>{@code firstAtEpoch} is true only on the tick a service enters FAILED (the edge that opened this
 * failover generation) and false on the loss-tolerant resends that follow while it stays FAILED, so
 * the runtime can log the initial failover at INFO and keep the resends at DEBUG.
 *
 * @param promote      the promotion to put on the wire, carrying the service id and failover epoch
 * @param firstAtEpoch whether this is the first send at {@link Promote#epoch()}
 */
record FailoverSignal(Promote promote, boolean firstAtEpoch) {
}
