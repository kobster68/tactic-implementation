package edu.rit.swen755.heartbeat.protocol;

/**
 * Health of a monitored service as judged by the receiver's watchdog.
 *
 * <p>The three states make the build-up to a failure visible in the demo, while the receiver still
 * exposes a boolean {@code checkAlive()} (true unless {@link #FAILED}) so the class diagram keeps
 * the SL-HB s.8 operation. The same vocabulary is reused for the monitor's view of the receiver.
 */
public enum ServiceState {

    /** Beats are arriving on time; no misses outstanding. */
    HEALTHY,

    /** At least one beat has been missed, but fewer than the configured missed-count threshold. */
    SUSPECT,

    /** The missed-count threshold was reached, or the expiry window elapsed with no beat. */
    FAILED
}
