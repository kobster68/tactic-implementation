package edu.rit.swen755.heartbeat.sensorsim;

import edu.rit.swen755.heartbeat.protocol.Codec;
import edu.rit.swen755.heartbeat.protocol.NetConfig;
import edu.rit.swen755.heartbeat.protocol.SensorReading;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sensor-simulator process: sends a {@link SensorReading} to the critical service every
 * {@code sensor.periodMs}, and with probability {@code sensor.faultProbability} sends a fault
 * instead -- a truncated datagram (share {@code sensor.corruptShare}) or an out-of-range lane
 * offset. Either fault makes the critical service's uncaught {@code Codec.decode} throw.
 * In active-redundancy mode each payload is fanned out to the primary and backup replica. Optional
 * {@code --count <n>} stops after n sends (tracer/test runs), and {@code --lane-offset <metres>}
 * makes valid readings deterministic for the recovery demonstration.
 */
public final class Main {

    static {
        // One-line log format; set before the logger triggers java.util.logging init.
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "%1$tF %1$tT [%4$s] %3$s: %5$s%6$s%n");
    }

    private static final Logger LOG = System.getLogger("sensor-sim");
    private static final String SENSOR_ID = "lane-cam-0";

    /** What a single send carries. */
    enum Kind { VALID, OUT_OF_RANGE, TRUNCATED }

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        NetConfig cfg = NetConfig.load(args);
        long count = parseCount(args);
        Double laneOffset = parseLaneOffset(args);
        LOG.log(Level.INFO, "startup {0}", cfg.describe("sensor-sim"));

        long periodMs = cfg.sensorPeriodMs();
        if (periodMs <= 0) {
            throw new IllegalArgumentException("sensor.periodMs must be positive");
        }

        List<InetSocketAddress> targets = targets(
                cfg.redundancyMode(), cfg.sensorTarget(), cfg.backupTarget());
        Random rng = new Random();
        try (DatagramSocket socket = new DatagramSocket()) {
            for (long seq = 0; count == 0 || seq < count; seq++) {
                Kind kind = pick(rng, cfg.sensorFaultProbability(), cfg.sensorCorruptShare());
                byte[] bytes = laneOffset != null && kind == Kind.VALID
                        ? validPayload(seq, laneOffset)
                        : payload(kind, seq, rng);
                for (InetSocketAddress target : targets) {
                    socket.send(new DatagramPacket(bytes, bytes.length, target));
                }
                LOG.log(kind == Kind.VALID ? Level.INFO : Level.WARNING,
                        "sent seq={0} {1} -> {2}: {3}", seq, kind, targets,
                        new String(bytes, StandardCharsets.UTF_8));
                Thread.sleep(periodMs);
            }
        }
    }

    /**
     * Selects the reading destinations. Passive mode sends only to the primary; after promotion,
     * the deployment/demo starts a sensor phase whose primary target is the backup. Active mode
     * sends identical datagrams to both replicas so they can update state in parallel.
     */
    static List<InetSocketAddress> targets(String mode, InetSocketAddress primary,
            InetSocketAddress backup) {
        if (mode == null) {
            throw new IllegalArgumentException("redundancy.mode must be passive or active");
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "passive" -> List.of(primary);
            case "active" -> primary.equals(backup) ? List.of(primary) : List.of(primary, backup);
            default -> throw new IllegalArgumentException(
                    "redundancy.mode must be passive or active, was " + mode);
        };
    }

    /** Chooses valid vs. fault, then which fault, from the two configured probabilities. */
    static Kind pick(Random rng, double faultProbability, double corruptShare) {
        if (rng.nextDouble() >= faultProbability) {
            return Kind.VALID;
        }
        return rng.nextDouble() < corruptShare ? Kind.TRUNCATED : Kind.OUT_OF_RANGE;
    }

    /** Builds the datagram bytes for one send of the given kind. */
    static byte[] payload(Kind kind, long seq, Random rng) throws IOException {
        long now = System.currentTimeMillis();
        return switch (kind) {
            // Within +/-1.5 m so the lane detector sees centred and drifting readings.
            case VALID -> Codec.encode(new SensorReading(SENSOR_ID, seq, now, rng.nextDouble(-1.5, 1.5)));
            case TRUNCATED -> {
                byte[] full = Codec.encode(new SensorReading(SENSOR_ID, seq, now, 0.0));
                yield Arrays.copyOf(full, full.length / 2);
            }
            // SensorReading's constructor rejects this value, so the JSON is written by hand.
            case OUT_OF_RANGE -> {
                double offset = (rng.nextBoolean() ? 1 : -1)
                        * (SensorReading.MAX_LANE_OFFSET_METERS + rng.nextDouble(0.5, 5.0));
                yield String.format(Locale.ROOT,
                        "{\"type\":\"SensorReading\",\"sensorId\":\"%s\",\"seq\":%d,\"sentAt\":%d,"
                                + "\"laneOffsetMeters\":%s}", SENSOR_ID, seq, now, offset)
                        .getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /** Builds a valid reading at an exact offset for a repeatable recovery demonstration. */
    static byte[] validPayload(long seq, double laneOffsetMeters) throws IOException {
        return Codec.encode(new SensorReading(SENSOR_ID, seq, System.currentTimeMillis(),
                laneOffsetMeters));
    }

    /** Returns 0 (run forever) unless {@code --count <positive n>} is given. */
    static long parseCount(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--count".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--count needs a positive whole number");
                }
                long n;
                try {
                    n = Long.parseLong(args[i + 1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--count must be a whole number, was " + args[i + 1], e);
                }
                if (n <= 0) {
                    throw new IllegalArgumentException("--count must be positive, was " + n);
                }
                return n;
            }
        }
        return 0;
    }

    /** Returns null for randomized valid readings, or the exact offset supplied by the caller. */
    static Double parseLaneOffset(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--lane-offset".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--lane-offset needs a numeric value");
                }
                double offset;
                try {
                    offset = Double.parseDouble(args[i + 1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "--lane-offset must be numeric, was " + args[i + 1], e);
                }
                if (!Double.isFinite(offset)
                        || offset < SensorReading.MIN_LANE_OFFSET_METERS
                        || offset > SensorReading.MAX_LANE_OFFSET_METERS) {
                    throw new IllegalArgumentException("--lane-offset must be within ["
                            + SensorReading.MIN_LANE_OFFSET_METERS + ", "
                            + SensorReading.MAX_LANE_OFFSET_METERS + "], was " + offset);
                }
                return offset;
            }
        }
        return null;
    }
}
