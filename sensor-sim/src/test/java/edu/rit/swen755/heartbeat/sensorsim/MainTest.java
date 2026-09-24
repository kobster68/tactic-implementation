package edu.rit.swen755.heartbeat.sensorsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MainTest {

    private final Random rng = new Random(42);

    @Test
    void validPayloadDecodes() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertInstanceOf(SensorReading.class, Codec.decode(Main.payload(Main.Kind.VALID, i, rng)));
        }
    }

    @Test
    void faultPayloadsMakeDecodeThrow() throws Exception {
        for (int i = 0; i < 100; i++) {
            byte[] outOfRange = Main.payload(Main.Kind.OUT_OF_RANGE, i, rng);
            byte[] truncated = Main.payload(Main.Kind.TRUNCATED, i, rng);
            assertThrows(Exception.class, () -> Codec.decode(outOfRange));
            assertThrows(Exception.class, () -> Codec.decode(truncated));
        }
    }

    @Test
    void pickHonoursProbabilities() {
        assertEquals(Main.Kind.VALID, Main.pick(rng, 0.0, 0.5));
        assertEquals(Main.Kind.TRUNCATED, Main.pick(rng, 1.0, 1.0));
        assertEquals(Main.Kind.OUT_OF_RANGE, Main.pick(rng, 1.0, 0.0));
    }

    @Test
    void parseCountAcceptsPositiveAndDefaultsToForever() {
        assertEquals(3, Main.parseCount(new String[] {"--count", "3"}));
        assertEquals(0, Main.parseCount(new String[] {}));
    }

    @Test
    void parseCountRejectsMissingNonNumericAndNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseCount(new String[] {"--count"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parseCount(new String[] {"--count", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parseCount(new String[] {"--count", "0"}));
    }
}
