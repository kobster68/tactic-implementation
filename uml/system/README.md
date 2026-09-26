# System slice — UML and narrative

Design deliverables for the complete **heartbeat fault-detection tactic**
(SWEN-755, Group 1). Sources are the two PlantUML files beside this note:

- [`system-class.puml`](system-class.puml) — system class diagram
- [`system-sequence.puml`](system-sequence.puml) — system sequence diagram

Rendered PNG versions are also included. Render either source with PlantUML, for example:

```text
plantuml system-class.puml
plantuml system-sequence.puml
```

## What the system does

The prototype detects failure of a critical autonomous-vehicle function using periodic heartbeats.
The selected critical function is **lane-position and lane-departure assessment**. The system is
implemented as four independent Java processes:

1. **`sensor-sim`** — simulates a lane camera and sends `SensorReading` datagrams.
2. **`critical-service`** — receives readings, assesses lane position, and sends heartbeats.
3. **`receiver`** — acts as the heartbeat watchdog and reports service health.
4. **`monitor`** — logs service transitions and detects receiver silence.

The processes communicate over UDP using JSON messages defined by the shared `protocol` module.
Each process runs in its own JVM. The receiver and monitor can be placed on separate machines from
the critical service so that failure of the service's processor does not also stop detection.

## System topology

```text
sensor-sim --SensorReading UDP:5001--> critical-service
critical-service --Heartbeat UDP:5002--> receiver
receiver --StatusReport UDP:5003--> monitor
```

The configured target and listen ports must match. The default configuration uses localhost for a
single-machine demonstration. Hostnames and ports can be overridden through JVM properties or a
configuration file; see the root README for the complete configuration table.

## Class diagram

The system class diagram groups the implementation by process and shared contract:

- **`SensorSimulator` / `sensor-sim`** — `Main` is the process entry point. `pick` randomly chooses
  a valid reading or one of two fault kinds, and `payload` creates the corresponding datagram.
- **`CriticalService` / `critical-service`** — the `Main` entry point receives readings, uses
  `LaneDetector`, and emits heartbeats. The lane detector returns `CENTERED`, `DRIFTING_LEFT`, or
  `DRIFTING_RIGHT`.
- **`ReceiverNode` / `receiver`** — owns the UDP runtime, including a receive loop and a checker
  loop. It delegates health decisions to `HeartbeatReceiver`.
- **`HeartbeatReceiver`** — maintains each known service's last-observed heartbeat and converts
  elapsed silence into `HEALTHY`, `SUSPECT`, or `FAILED`.
- **`Monitor` / `monitor`** — receives `StatusReport` messages, logs service-state transitions,
  and evaluates whether the receiver itself has stopped reporting.
- **Shared protocol records** — `SensorReading`, `Heartbeat`, `StatusReport`, and `ServiceStatus`
  define the wire messages. `ServiceState` defines the health vocabulary.

The UML names `SensorSimulator`, `CriticalService`, and `Monitor` represent the corresponding
process entry points; the concrete Java entry-point classes are named `Main` in each module.

## Normal operation

During normal operation:

1. `sensor-sim` periodically sends a valid `SensorReading` to `critical-service`.
2. `critical-service` decodes the reading, classifies its lane offset, and logs the result.
3. Independently, `critical-service` sends a `Heartbeat` at `heartbeat.periodMs`.
4. `receiver` records the heartbeat arrival time.
5. Every `receiver.checkIntervalMs`, the receiver evaluates known services and sends a
   `StatusReport` to `monitor`.
6. `monitor` logs new service states and state transitions.

The receiver's status-report stream also acts as the receiver's heartbeat. This allows the monitor
to detect failure of the watchdog itself.

## Failure behavior

The sensor simulator uses `sensor.faultProbability` and a fresh `Random` instance to select faults
nondeterministically. The two fault forms are:

- a truncated JSON datagram;
- an out-of-range lane offset.

The critical service deliberately leaves decoding and record-validation failures uncaught. The
resulting exception terminates the critical-service JVM without using `process.exit` or a similar
forced shutdown. Because the service's heartbeat scheduling and sensor processing share the same
process, heartbeats stop when the service crashes.

The receiver then observes increasing silence:

```text
missed == 0                         -> HEALTHY
0 < missed < receiver.missedCount   -> SUSPECT
missed >= receiver.missedCount      -> FAILED
```

The receiver includes the resulting `ServiceStatus` in later reports, and the monitor logs the
critical service's transition to `FAILED`.

If the receiver process stops, its `StatusReport` messages stop. The monitor independently evaluates
that silence using `monitor.missedCount × receiver.checkIntervalMs` and can transition the receiver
from `HEALTHY` through `SUSPECT` to `FAILED`.

## Sequence diagram

The system sequence diagram contains four phases:

1. **Normal sensor flow** — readings travel from `sensor-sim` to `critical-service`, which decodes
   and assesses them.
2. **Normal heartbeat flow** — heartbeats travel from the critical service to the receiver, while
   status reports travel from the receiver to the monitor.
3. **Critical-service failure** — a randomly corrupted reading causes an uncaught exception;
   heartbeats stop; the receiver progresses from `HEALTHY` to `SUSPECT` to `FAILED` and reports the
   failure.
4. **Receiver failure** — status reports stop and the monitor detects receiver silence using its own
   timeout rule.

## Configuration and execution

Build all modules from the repository root:

```bash
mvn package
```

The build produces self-contained runnable JARs:

```text
sensor-sim/target/sensor-sim.jar
critical-service/target/critical-service.jar
receiver/target/receiver.jar
monitor/target/monitor.jar
```

Start the processes in listener-to-sender order for a local demonstration:

```bash
java -jar monitor/target/monitor.jar
java -jar receiver/target/receiver.jar
java -jar critical-service/target/critical-service.jar
java -jar sensor-sim/target/sensor-sim.jar
```

For a multi-machine deployment, configure the receiver and service target hosts to point to the
next process in the topology and permit the required UDP traffic through the network firewalls.
The repository also includes `scripts/run-tracer.sh` for a bounded localhost demonstration.

## How the system is verified

- Protocol tests verify JSON round trips, malformed datagrams, configuration, and record validation.
- Lane-detector tests verify the ±0.5-meter classification boundaries.
- Sensor-simulator tests verify randomized selection and construction of valid, truncated, and
  out-of-range payloads.
- Receiver tests verify the watchdog state rule, recovery, per-service tracking, malformed-input
  handling, and UDP integration behavior.
- Monitor tests verify receiver-silence state evaluation.

Run the complete suite from the repository root with:

```bash
mvn test
```
