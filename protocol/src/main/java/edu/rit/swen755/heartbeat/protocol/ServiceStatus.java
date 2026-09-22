package edu.rit.swen755.heartbeat.protocol;

/**
 * Per-service line item inside a {@link StatusReport}: the receiver's current judgement of one
 * monitored service.
 *
 * @param serviceId   identifier of the monitored service
 * @param state       current {@link ServiceState}
 * @param lastSeen    epoch milliseconds of the last beat observed from the service
 * @param missedCount consecutive beats missed so far (feeds the SUSPECT/FAILED thresholds)
 */
public record ServiceStatus(String serviceId, ServiceState state, long lastSeen, int missedCount) {
}
