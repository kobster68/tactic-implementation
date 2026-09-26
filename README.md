# tactic-implementation

SWEN-755 Tactic Implementation, Group 1: fault detection with the **heartbeat availability
tactic**, implemented as four Java processes communicating over UDP.

This repository implements a **minimum heartbeat fault-detection prototype**: the shared message
contract, JSON codec, configuration loader, Maven build, and all four process slices are implemented.
A bounded **tracer** runs the processes on one machine and checks that the receiver detects service
silence. The design choices below are the binding contract for every slice.

## Purpose

Detect the failure of a critical service by having it emit periodic heartbeats. A receiver watches
those beats and reports service health to a monitor. A separate sensor simulator feeds the critical
service lane-position readings; a malformed reading crashes the service (an unhandled parse/validate
exception), which stops its heartbeats -- the fault the tactic exists to detect.

The selected autonomous-vehicle functionality is **lane-position / lane-departure assessment**.
`LaneDetector` approximates this function: offsets below -0.5 m are `DRIFTING_LEFT`, offsets above
+0.5 m are `DRIFTING_RIGHT`, and offsets within those boundaries (inclusive) are `CENTERED`.
The shared `SensorReading` accepts finite offsets within [-3.5, 3.5] m; these validation bounds
are separate from the prototype's drift thresholds. The service logs each assessment.

**Availability scenario:** during normal operation, after heartbeats have been established, a
malformed camera reading causes the lane-assessment process to crash. The receiver on another
machine observes missing heartbeats and reports `FAILED`; the monitor logs the transition.
With the default settings, the receiver's detection target is approximately 3-3.5 seconds after
the last observed heartbeat (three periods plus up to one check interval), with additional network
and scheduling delay before the monitor logs it. Recovery is reported when heartbeats resume after a manual service restart.

The simulated defect is failure to handle corrupted sensor input: the simulator randomly selects
a truncated JSON payload or an out-of-range value, and the service's uncaught parse/validation
exception terminates its process.

## Four-process topology

```
  sensor-sim            critical-service          receiver                  monitor
  (lane camera)         (Process 1)               (Process 2, watchdog)     (Process 3)
      |                     |                         |                         |
      |   SensorReading     |      Heartbeat          |      StatusReport       |
      |   UDP -> :5001      |      UDP -> :5002       |      UDP -> :5003       |
      +-------------------->+------------------------>+------------------------>+
    send periodically   receive + assess          receive + decode          receive + decode
                        periodic heartbeat        (owns last-seen table,    (logs / notifies on
                                                   sends periodic report)    transitions)
```

Hop rule: every `*.target.port` equals its partner's `*.listen.port` (asserted by a unit test).
The receiver is the watchdog; its periodic `StatusReport` doubles as its
own heartbeat so the monitor can detect a dead receiver.

Each component runs in a separate JVM process. For the network demonstration, place the critical
service, receiver, and monitor on separate machines so losing the service's processor does not
also stop detection. The localhost tracer alone does not demonstrate processor-failure isolation.

## Design summary

| Topic | Choice |
| --- | --- |
| Receiver role | Aggregator: the receiver owns the last-seen table and `checkAlive()`, is the watchdog, and sends a periodic `StatusReport` that doubles as its own heartbeat. |
| Fault source | A separate `sensor-sim` process sends `SensorReading` datagrams to the critical service under a configured fault probability (four processes). |
| Build layout | Maven multi-module: parent pom, shared `protocol`, one runnable-jar module per process. |
| Wire encoding | JSON via Jackson; one record per type with a `type` discriminator; `Codec.decode` parses and applies record validation. The critical service leaves decoding uncaught; the receiver and monitor log and skip decoding errors. |
| Hop-2 heartbeat | A dedicated `Heartbeat` on an independent deadline in the service loop (not piggybacked on sensor messages). The same thread processes readings, so a processing crash also stops heartbeats. |
| Configuration | One shared `heartbeat.properties`, loaded by `NetConfig`, overridable with `--config <path>` or `-Dkey=value`. |
| Default timings | Human-watchable demo profile (1 s period, 500 ms check, 3 missed); an aggressive 200 ms profile ships commented out. |
| Fault injection | Two fault kinds -- out-of-range lane offset or truncated datagram -- chosen by `sensor.faultProbability` and `sensor.corruptShare`. |
| Service state | Three states `HEALTHY, SUSPECT, FAILED`, with a boolean `checkAlive()` beside a `state()` accessor. |
| Process lifetime | Continuous by default; the critical service accepts `--run-for-ms`, and sensor-sim accepts `--count`, for bounded demo runs. |
| Silent receiver | The monitor uses the same `HEALTHY/SUSPECT/FAILED` vocabulary for the receiver, logged as `RECEIVER <id> FAILED` at the highest level. |
| FAILED trigger | Receiver: `missed = floor((now − lastSeen) / period)`; HEALTHY at zero, SUSPECT below `missedCount`, FAILED at or above. No `expireMs` key; expiry is derived as `missedCount × period`. |
| Receiver-silence rule | Monitor: FAILED at `monitor.missedCount × receiver.checkIntervalMs`; before that, HEALTHY through 1.5 report intervals and SUSPECT beyond that grace. The FAILED threshold takes precedence. |

> Heartbeats indicate process liveness, not sensor freshness or correctness of the lane assessment.
> The implementation is a course prototype; see the validation limitations below.

## Build and run

Requires JDK 21 and Maven 3.9+ (tests verified with Java 21.0.12.1, Maven 3.9.16).
Run Maven commands from the repository root. Java and Maven must be on `PATH`.

```bash
# build all modules, run tests, and produce self-contained runnable jars
mvn -q package

# run the bounded tracer (builds, launches all four, prints the logs)
bash scripts/run-tracer.sh

# alternatively, start each process in its own terminal, in this order
java -jar monitor/target/monitor.jar
java -jar receiver/target/receiver.jar
java -jar critical-service/target/critical-service.jar
java -jar sensor-sim/target/sensor-sim.jar

# override any config value at launch: -D flags are JVM options and must come BEFORE
# -jar; --config names an override file and is a program argument, so it comes AFTER the jar
java -Dmonitor.listen.port=6003 -jar monitor/target/monitor.jar
java -jar sensor-sim/target/sensor-sim.jar --config ./my-hosts.properties
```

The manual commands run continuously until stopped with Ctrl+C or until the critical service
crashes on malformed input. Start the simulator after observing the service's first heartbeat
if you want to see the established-service failure path. Default fault probability is 0.1 per
reading, so the time to a crash varies. Look for a simulator `TRUNCATED` or `OUT_OF_RANGE` log,
an exception and nonzero exit in the service, then `critical-service FAILED` in the receiver and
a `SERVICE critical-service ... -> FAILED` transition in the monitor. Keep the receiver and
monitor running to observe detection; stop the remaining processes when finished.

For a recovery demonstration, stop the simulator, restart it with `-Dsensor.faultProbability=0`
before `-jar`, and restart the critical service. Its next heartbeat restores `HEALTHY`. To observe
receiver failure, stop the receiver after the monitor has received reports; the monitor should log
`RECEIVER receiver-1 FAILED` after the configured silence window and a subsequent check.

The Bash tracer is intended for macOS/Linux and Windows Git Bash. It disables sensor faults,
sends three sensor readings, runs the service for five seconds, then allows six seconds for
detection before stopping the receiver and monitor. Logs are in `target/tracer-logs/`. Its success
check requires clean producer exits, daemons still running before cleanup, and a receiver log
reporting service failure; it does not assert delivery of that failure to the monitor. The normal
timed shutdown demonstrates silence detection, not the assignment's random crash.

```bash
# optional bounded process runs
java -jar critical-service/target/critical-service.jar --run-for-ms 5000
java -Dsensor.faultProbability=0 -jar sensor-sim/target/sensor-sim.jar --count 3

# Bash-only tracer overrides: service runtime, startup spacing, and detection grace
TRACER_RUN_FOR_MS=10000 TRACER_START_DELAY=1 TRACER_GRACE_SECS=6 bash scripts/run-tracer.sh
```

Allow enough runtime for the simulator to start, and increase the grace window if you increase
the heartbeat period or missed-count threshold. The script cleans up daemons on normal completion;
if interrupted, check for remaining processes before rerunning.

### Libraries and build tools

| Component | Use |
| --- | --- |
| Java / JDK 21 | Records, UDP sockets (`java.net`), threads, clocks, and `System.Logger` |
| Jackson Databind 2.22.2 | JSON message encoding and decoding |
| JUnit Jupiter 5.14.4 | Unit and receiver UDP integration tests (test scope only) |
| Maven 3.9+ | Multi-module build and dependency resolution |
| Maven Compiler 3.14.0 / Surefire 3.6.0 / Shade 3.6.2 | Java compilation, test execution, and executable jars with dependencies |
| Bash | Tracer launcher; not required to run the individual jars |
| PlantUML | Optional rendering of UML source files; receiver PNGs are included |

Each process executable is generated at `<module>/target/<module>.jar` by `mvn package`.
Deploy those jars with Java 21; Maven is only needed on the build machine.

### Running on separate machines

Copy each executable jar to its assigned machine. In this example, replace `service-host`,
`receiver-host`, and `monitor-host` with reachable IP addresses or hostnames. Run these commands
from the directories containing the copied jars, starting the listeners first:

```bash
# monitor machine: allow inbound UDP 5003
java -jar monitor.jar

# receiver machine: allow inbound UDP 5002
java -Dreceiver.report.target.host=monitor-host -jar receiver.jar

# critical-service machine: allow inbound UDP 5001
java -Dservice.heartbeat.target.host=receiver-host -jar critical-service.jar

# sensor simulator machine (may share the critical-service machine)
java -Dsensor.target.host=service-host -jar sensor-sim.jar
```

Permit the corresponding UDP traffic through host/network firewalls. Keep periods and thresholds
consistent across processes, using the same override properties file where appropriate.

### Tests and validation

```bash
# run all tests across all modules
mvn test
```

The current suite has 52 passing tests covering protocol/configuration, lane-assessment boundaries,
runtime arguments, simulated fault payloads, receiver state transitions and UDP handling, and
monitor state evaluation. Maven writes results to each module's `target/surefire-reports/`.

Known prototype limitations: receiver and monitor timeout calculations use wall-clock time;
clock adjustments can affect decisions. The monitor's timeout checks can be delayed by continuous
invalid traffic, and report fields are not fully validated before refreshing liveness. UDP delivery
is not guaranteed. These cases are not covered by the passing suite.

### Java SE timing considerations

This prototype uses standard Java SE, which is practical for the assignment but is not generally
hard-real-time technology: garbage collection, JIT compilation, OS scheduling, and UDP timing can
introduce latency and reduce execution-time predictability. 

A production real-time version could use a real-time Java profile such as RTSJ,
a deterministic process/thread scheduler, and bounded-memory practices.

## Configuration keys

All keys live in `protocol/src/main/resources/heartbeat.properties`. Defaults are the demo
profile.
File overrides are applied over the packaged defaults, then JVM `-D` overrides take precedence.
Use positive periods/counts and probabilities in [0, 1]; the simulator does not currently validate
probability ranges. The receiver also supports `-Dreceiver.id=<name>` (default `receiver-1`);
this is read directly as a JVM property rather than from `heartbeat.properties`.

| Key | Default | Meaning |
| --- | --- | --- |
| `sensor.target.host` | `localhost` | host the sensor-sim sends readings to |
| `sensor.target.port` | `5001` | port the sensor-sim sends readings to |
| `service.listen.port` | `5001` | port the critical service binds for readings |
| `service.heartbeat.target.host` | `localhost` | host the critical service sends heartbeats to |
| `service.heartbeat.target.port` | `5002` | port the critical service sends heartbeats to |
| `receiver.listen.port` | `5002` | port the receiver binds for heartbeats |
| `receiver.report.target.host` | `localhost` | host the receiver sends status reports to |
| `receiver.report.target.port` | `5003` | port the receiver sends status reports to |
| `monitor.listen.port` | `5003` | port the monitor binds for status reports |
| `heartbeat.periodMs` | `1000` | critical-service beat interval |
| `receiver.checkIntervalMs` | `500` | how often the receiver runs `checkAlive()` and emits a `StatusReport` |
| `receiver.missedCount` | `3` | missed beats before the receiver judges a service FAILED; `missed = floor((now - lastSeen) / heartbeat.periodMs)`; derived expiry 3000 ms |
| `monitor.checkIntervalMs` | `500` | how often the monitor evaluates the report stream |
| `monitor.missedCount` | `3` | missed reports before the monitor judges the receiver FAILED; derived expiry 3 x `receiver.checkIntervalMs` = 1500 ms |
| `sensor.periodMs` | `1000` | sensor-sim send interval |
| `sensor.faultProbability` | `0.1` | probability a given reading is a fault |
| `sensor.corruptShare` | `0.5` | of injected faults, the share that are truncated datagrams vs. out-of-range values |

## Module responsibilities

Each process module owns its implementation and tests. The shared `protocol` module defines the
message records, JSON codec, configuration loader, and default properties used across all processes.

| Module | Responsibility |
| --- | --- |
| `sensor-sim` | Sends sensor readings at `sensor.periodMs` and randomly injects malformed or out-of-range payloads. |
| `critical-service` | Receives and assesses lane-position readings and sends heartbeats at `heartbeat.periodMs`. |
| `receiver` | Maintains the last-seen table, evaluates service health through `checkAlive()` and state snapshots, and sends periodic `StatusReport` messages. |
| `monitor` | Logs service health transitions and detects receiver silence from missing status reports. |

## Deliverables (course slide s.14)

- [x] **Code** -- the four processes and the shared `protocol` module (this repo).
- [x] **ReadMe** -- this file.
- [x] **Executable packaging** -- `mvn package` produces the four self-contained runnable jars.
- [x] **UML class diagram + sequence diagrams, with narrative** -- UML diagrams and narrative are in respective folders under `uml/`.

## Module layout

```
tactic-implementation/
  pom.xml                     parent (packaging pom): versions, plugins, module list
  protocol/                   shared contract: Message + records, Codec, NetConfig, heartbeat.properties, tests
  sensor-sim/                 Process: sends SensorReading
  critical-service/           Process 1: receives readings, sends Heartbeat
  receiver/                   Process 2 (watchdog): receives Heartbeat, sends StatusReport
  monitor/                    Process 3: receives StatusReport
  scripts/run-tracer.sh       bounded four-process tracer with receiver failure check
  uml/                        class/sequence diagrams and rendered PNGs
```
