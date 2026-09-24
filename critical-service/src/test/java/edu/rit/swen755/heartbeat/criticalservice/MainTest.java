package edu.rit.swen755.heartbeat.criticalservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests demo runtime argument parsing without starting the service or opening sockets. */
class MainTest {

    @Test
    void noRuntimeFlagMeansContinuousOperation() {
        assertEquals(0L, Main.parseRunForNanos(new String[0]));
    }

    @Test
    void convertsMillisecondsToNanoseconds() {
        assertEquals(5_000_000_000L,
                Main.parseRunForNanos(new String[] {"--run-for-ms", "5000"}));
    }

    @Test
    void rejectsDuplicateRuntimeFlag() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseRunForNanos(
                new String[] {"--run-for-ms", "1000", "--run-for-ms", "2000"}));
    }

    @Test
    void rejectsMissingRuntimeValue() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseRunForNanos(new String[] {"--run-for-ms"}));
    }

    @Test
    void rejectsZeroRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseRunForNanos(new String[] {"--run-for-ms", "0"}));
    }

    @Test
    void rejectsNegativeRuntime() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseRunForNanos(new String[] {"--run-for-ms", "-1"}));
    }

    @Test
    void acceptsLargestRuntimeThatFitsInNanoseconds() {
        assertEquals(9_223_372_036_854_000_000L,
                Main.parseRunForNanos(new String[] {"--run-for-ms", "9223372036854"}));
    }

    @Test
    void rejectsRuntimeThatWouldOverflowNanoseconds() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Main.parseRunForNanos(new String[] {"--run-for-ms", "9223372036855"}));
        assertEquals("--run-for-ms must be positive and fit in nanoseconds", error.getMessage());
    }

    @Test
    void nonnumericRuntimeHasClearMessageAndPreservesCause() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Main.parseRunForNanos(new String[] {"--run-for-ms", "abc"}));
        assertEquals("--run-for-ms must be a whole number of milliseconds", error.getMessage());
        assertInstanceOf(NumberFormatException.class, error.getCause());
    }

    @Test
    void skipsConfigPathBeforeRuntimeFlag() {
        assertEquals(250_000_000L, Main.parseRunForNanos(
                new String[] {"--config", "demo.properties", "--run-for-ms", "250"}));
    }

    @Test
    void acceptsConfigAfterRuntimeFlag() {
        assertEquals(250_000_000L, Main.parseRunForNanos(
                new String[] {"--run-for-ms", "250", "--config", "demo.properties"}));
    }

    @Test
    void configPathThatLooksLikeRuntimeFlagIsNotParsedAsAnOption() {
        assertEquals(0L,
                Main.parseRunForNanos(new String[] {"--config", "--run-for-ms"}));
    }
}
