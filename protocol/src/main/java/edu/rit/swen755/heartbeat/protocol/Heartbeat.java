package edu.rit.swen755.heartbeat.protocol;

/**
 * A periodic liveness beat from the critical service to the receiver (hop 2).
 *
 * @param serviceId identifier of the beating service
 * @param seq       per-beat sequence number, used to count misses on the lossy UDP hop (S4 p.252)
 * @param sentAt    sender wall-clock time in epoch milliseconds (display/latency only)
 */
public record Heartbeat(String serviceId, long seq, long sentAt) implements Message {
}
