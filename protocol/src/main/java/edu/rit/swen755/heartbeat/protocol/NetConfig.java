package edu.rit.swen755.heartbeat.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Effective network and timing configuration shared by every process.
 *
 * <p>Sources are layered, later overriding earlier:
 * <ol>
 *   <li>the packaged classpath defaults {@code /heartbeat.properties} (the demo profile),</li>
 *   <li>an optional file named by {@code --config <path>} in the process arguments,</li>
 *   <li>system properties {@code -Dkey=value} for any known key.</li>
 * </ol>
 *
 * <p>Endpoints use one file with localhost defaults for same-device testing and {@code -D} host
 * overrides for separate-device runs (H). {@link #describe(String)} yields the startup log line.
 */
public final class NetConfig {

    /** Classpath location of the packaged defaults. */
    public static final String DEFAULTS_RESOURCE = "/heartbeat.properties";

    private final Properties props;

    private NetConfig(Properties props) {
        this.props = props;
    }

    /**
     * Loads the layered configuration.
     *
     * @param args the process arguments; {@code --config <path>} (if present) names an override file
     */
    public static NetConfig load(String[] args) {
        Properties merged = new Properties();

        try (InputStream in = NetConfig.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "packaged defaults not found on classpath: " + DEFAULTS_RESOURCE);
            }
            merged.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read packaged defaults", e);
        }

        String configPath = parseConfigArg(args);
        if (configPath != null) {
            try (InputStream in = Files.newInputStream(Path.of(configPath))) {
                merged.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read --config file: " + configPath, e);
            }
        }

        // -Dkey=value overrides for any known key (latest source wins).
        for (String key : merged.stringPropertyNames()) {
            String override = System.getProperty(key);
            if (override != null) {
                merged.setProperty(key, override);
            }
        }

        return new NetConfig(merged);
    }

    private static String parseConfigArg(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    // ---- generic typed accessors -------------------------------------------------------------

    private String require(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("missing config key: " + key);
        }
        return value.trim();
    }

    /** Raw string value for {@code key}. */
    public String getString(String key) {
        return require(key);
    }

    /** Integer value for {@code key}. */
    public int getInt(String key) {
        return Integer.parseInt(require(key));
    }

    /** Long value for {@code key}. */
    public long getLong(String key) {
        return Long.parseLong(require(key));
    }

    /** Double value for {@code key}. */
    public double getDouble(String key) {
        return Double.parseDouble(require(key));
    }

    // ---- endpoints ------------------------------------------------------------

    public String sensorTargetHost() {
        return getString("sensor.target.host");
    }

    public int sensorTargetPort() {
        return getInt("sensor.target.port");
    }

    public int serviceListenPort() {
        return getInt("service.listen.port");
    }

    public String serviceHeartbeatTargetHost() {
        return getString("service.heartbeat.target.host");
    }

    public int serviceHeartbeatTargetPort() {
        return getInt("service.heartbeat.target.port");
    }

    public int receiverListenPort() {
        return getInt("receiver.listen.port");
    }

    public String receiverReportTargetHost() {
        return getString("receiver.report.target.host");
    }

    public int receiverReportTargetPort() {
        return getInt("receiver.report.target.port");
    }

    public int monitorListenPort() {
        return getInt("monitor.listen.port");
    }

    // ---- timings and probabilities ------------------------------------

    public long sensorPeriodMs() {
        return getLong("sensor.periodMs");
    }

    public double sensorFaultProbability() {
        return getDouble("sensor.faultProbability");
    }

    public double sensorCorruptShare() {
        return getDouble("sensor.corruptShare");
    }

    public long heartbeatPeriodMs() {
        return getLong("heartbeat.periodMs");
    }

    public long receiverCheckIntervalMs() {
        return getLong("receiver.checkIntervalMs");
    }

    /** Derived: the receiver judges a service FAILED after missedCount × heartbeat period. */
    public long receiverExpireMs() {
        return receiverMissedCount() * heartbeatPeriodMs();
    }

    public int receiverMissedCount() {
        return getInt("receiver.missedCount");
    }

    public long monitorCheckIntervalMs() {
        return getLong("monitor.checkIntervalMs");
    }

    /** Derived: the monitor judges the receiver FAILED after missedCount × report cadence. */
    public long monitorExpireMs() {
        return monitorMissedCount() * receiverCheckIntervalMs();
    }

    public int monitorMissedCount() {
        return getInt("monitor.missedCount");
    }

    // ---- socket address helpers, one per outbound target -------------------------------------

    /** Where the sensor simulator sends its readings (critical service inbound). */
    public InetSocketAddress sensorTarget() {
        return new InetSocketAddress(sensorTargetHost(), sensorTargetPort());
    }

    /** Where the critical service sends its heartbeats (receiver inbound). */
    public InetSocketAddress serviceHeartbeatTarget() {
        return new InetSocketAddress(serviceHeartbeatTargetHost(), serviceHeartbeatTargetPort());
    }

    /** Where the receiver sends its status reports (monitor inbound). */
    public InetSocketAddress receiverReportTarget() {
        return new InetSocketAddress(receiverReportTargetHost(), receiverReportTargetPort());
    }

    // ---- startup log line --------------------------------------------------------------------

    /**
     * One-line summary of the effective endpoints and timings relevant to {@code processName},
     * for the startup log so an endpoint mismatch is visible in the first line each process prints.
     *
     * @param processName one of {@code sensor-sim}, {@code critical-service}, {@code receiver},
     *                    {@code monitor}
     */
    public String describe(String processName) {
        return switch (processName) {
            case "sensor-sim" -> "sensor-sim | target->" + sensorTargetHost() + ":" + sensorTargetPort()
                    + " | sensor.periodMs=" + sensorPeriodMs()
                    + " faultProbability=" + sensorFaultProbability()
                    + " corruptShare=" + sensorCorruptShare();
            case "critical-service" -> "critical-service | listen=udp/" + serviceListenPort()
                    + " | heartbeat->" + serviceHeartbeatTargetHost() + ":" + serviceHeartbeatTargetPort()
                    + " | heartbeat.periodMs=" + heartbeatPeriodMs();
            case "receiver" -> "receiver | listen=udp/" + receiverListenPort()
                    + " | report->" + receiverReportTargetHost() + ":" + receiverReportTargetPort()
                    + " | checkIntervalMs=" + receiverCheckIntervalMs()
                    + " expireMs(derived)=" + receiverExpireMs()
                    + " missedCount=" + receiverMissedCount();
            case "monitor" -> "monitor | listen=udp/" + monitorListenPort()
                    + " | checkIntervalMs=" + monitorCheckIntervalMs()
                    + " expireMs(derived)=" + monitorExpireMs()
                    + " missedCount=" + monitorMissedCount();
            default -> throw new IllegalArgumentException("unknown process name: " + processName);
        };
    }
}
