package edu.rit.swen755.heartbeat.receiver;

/**
 * The receiver's source of "now", in epoch milliseconds.
 *
 * <p>Extracting the clock is what makes the aliveness rule ({@code missed = floor((now - lastSeen) /
 * period)}) testable without real waiting: a test injects a hand-advanced clock and steps time
 * forward beat by beat, while production uses {@link #SYSTEM}.
 */
@FunctionalInterface
public interface Clock {

    /** Current time in epoch milliseconds. */
    long millis();

    /** The wall-clock the receiver uses in production. */
    Clock SYSTEM = System::currentTimeMillis;
}
