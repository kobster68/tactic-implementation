package edu.rit.swen755.heartbeat.sensorsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.net.InetSocketAddress;
import java.util.List;
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

    @Test
    void activeModeFansOutToBothReplicasAndPassiveUsesPrimary() {
        InetSocketAddress primary = new InetSocketAddress("primary.example", 5001);
        InetSocketAddress backup = new InetSocketAddress("backup.example", 5011);

        assertEquals(List.of(primary), Main.targets("passive", primary, backup));
        assertEquals(List.of(primary, backup), Main.targets("active", primary, backup));
        assertEquals(List.of(primary), Main.targets("active", primary, primary),
                "a single endpoint must not receive duplicate datagrams");
        assertThrows(IllegalArgumentException.class,
                () -> Main.targets("unknown", primary, backup));
    }

    @Test
    void fixedLaneOffsetProducesRepeatableValidReadings() throws Exception {
        double offset = 1.25;
        SensorReading reading = assertInstanceOf(
                SensorReading.class, Codec.decode(Main.validPayload(7, offset)));

        assertEquals(7, reading.seq());
        assertEquals(offset, reading.laneOffsetMeters());
        assertEquals(offset, Main.parseLaneOffset(new String[] {"--lane-offset", "1.25"}));
        assertNull(Main.parseLaneOffset(new String[0]));
    }

    @Test
    void parseLaneOffsetRejectsMissingInvalidAndOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseLaneOffset(new String[] {"--lane-offset"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseLaneOffset(new String[] {"--lane-offset", "right"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseLaneOffset(new String[] {"--lane-offset", "4.0"}));
    }
}
