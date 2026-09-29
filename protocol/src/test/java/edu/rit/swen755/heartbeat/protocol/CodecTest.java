package edu.rit.swen755.heartbeat.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Codec round-trips and the two decode-must-throw paths the fault story relies on. */
class CodecTest {

    @Test
    void sensorReadingRoundTrips() throws IOException {
        Message original = new SensorReading("lane-cam-0", 7, 1_700_000_000_000L, 1.25);
        Message decoded = Codec.decode(Codec.encode(original));
        assertEquals(original, decoded);
        assertInstanceOf(SensorReading.class, decoded);
    }

    @Test
    void heartbeatRoundTrips() throws IOException {
        Message original = new Heartbeat("critical-service", 3, 1_700_000_000_000L);
        Message decoded = Codec.decode(Codec.encode(original));
        assertEquals(original, decoded);
        assertInstanceOf(Heartbeat.class, decoded);
    }

    @Test
    void statusReportRoundTrips() throws IOException {
        Message original = new StatusReport("receiver-0", 5, 1_700_000_000_000L,
                List.of(new ServiceStatus("critical-service", ServiceState.HEALTHY, 1_700_000_000_000L, 0)));
        Message decoded = Codec.decode(Codec.encode(original));
        assertEquals(original, decoded);
        assertInstanceOf(StatusReport.class, decoded);
    }

    @Test
    void checkpointRoundTrips() throws IOException {
        Message original = new Checkpoint("critical-service", 9, 1_700_000_000_000L, 2,
                DriftDirection.LEFT, true);
        Message decoded = Codec.decode(Codec.encode(original));
        assertEquals(original, decoded);
        assertInstanceOf(Checkpoint.class, decoded);
    }

    @Test
    void promoteRoundTrips() throws IOException {
        Message original = new Promote("critical-service", 1_700_000_000_000L, 4);
        Message decoded = Codec.decode(Codec.encode(original));
        assertEquals(original, decoded);
        assertInstanceOf(Promote.class, decoded);
    }

    @Test
    void checkpointRejectsBlankServiceId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Checkpoint(" ", 0, 1_700_000_000_000L, 0, DriftDirection.NONE, false));
    }

    @Test
    void checkpointRejectsNegativeDriftCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new Checkpoint("critical-service", 0, 1_700_000_000_000L, -1,
                        DriftDirection.NONE, false));
    }

    @Test
    void promoteRejectsNegativeEpoch() {
        assertThrows(IllegalArgumentException.class,
                () -> new Promote("critical-service", 1_700_000_000_000L, -1));
    }

    @Test
    void decodeRejectsTruncatedDatagram() throws IOException {
        byte[] full = Codec.encode(new Heartbeat("critical-service", 1, 1_700_000_000_000L));
        byte[] truncated = Arrays.copyOf(full, full.length / 2);
        assertThrows(IOException.class, () -> Codec.decode(truncated));
    }

    @Test
    void decodeRejectsOutOfRangeLaneOffset() {
        // Well-formed JSON whose laneOffsetMeters is outside the team range [-3.5, 3.5]:
        // the record's compact constructor throws, and decode does not catch it.
        String json = "{\"type\":\"SensorReading\",\"sensorId\":\"lane-cam-0\",\"seq\":0,"
                + "\"sentAt\":1700000000000,\"laneOffsetMeters\":9.9}";
        Exception ex = assertThrows(Exception.class, () -> Codec.decode(json.getBytes(UTF_8)));
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertInstanceOf(IllegalArgumentException.class, root);
    }
}
