# Critical-service slice — UML and narrative

Design deliverables for the **critical-service** process of the heartbeat fault-detection tactic
(SWEN-755, Group 1). Sources are the two PlantUML files beside this note:

- [`critical-service-class.puml`](critical-service-class.puml) — class diagram
- [`critical-service-sequence.puml`](critical-service-sequence.puml) — sequence diagram

Rendered PNG versions are also included. Render either source with PlantUML, for example:

```text
plantuml critical-service-class.puml
plantuml critical-service-sequence.puml
```

## What the critical service is

The critical service is the autonomous vehicle's **lane-position and lane-departure assessment**
function. It receives `SensorReading` datagrams from the sensor simulator, classifies the vehicle's
lateral offset from the lane center, logs the assessment, and periodically sends `Heartbeat`
datagrams to the receiver.

The service is intentionally a minimum prototype. `LaneDetector` provides the critical
functionality; the surrounding `Main` class provides the process, UDP socket, heartbeat scheduling,
and configuration wiring.

The service and its heartbeat sender run in one JVM process. Consequently, an uncaught processing
failure terminates the process and stops both the lane-assessment work and heartbeat emission. The
receiver is a separate process and detects this loss of heartbeats.

## Class diagram

The implementation uses the following classes:

- **`Main`** — process entry point. It loads `NetConfig`, validates the optional
  `--run-for-ms` argument, binds the service UDP socket, receives sensor datagrams, invokes
  `LaneDetector`, and sends periodic heartbeats.
- **`LaneDetector`** — pure lane-assessment logic. It classifies a validated lane offset as
  `DRIFTING_LEFT`, `CENTERED`, or `DRIFTING_RIGHT`.
- **`LaneAssessment`** — enumeration containing the three lane-position results.
- **`Codec`** — shared JSON encoder/decoder from the `protocol` module. Decoding also applies the
  `SensorReading` record's validation rules.
- **`SensorReading`** — shared message containing the sensor ID, sequence number, timestamp, and
  lane offset.
- **`Heartbeat`** — shared message identifying the service and its heartbeat sequence number.
- **`NetConfig`** — shared configuration loader for UDP endpoints and timing values.

The repository does not contain a `CriticalServiceNode` class. The runtime behavior represented in
the diagram is implemented directly by `Main`.

### Lane-assessment rule

`LaneDetector` applies the following rule to a validated sensor reading:

```text
offset < -0.5 m                 -> DRIFTING_LEFT
-0.5 m <= offset <= 0.5 m       -> CENTERED
offset > 0.5 m                  -> DRIFTING_RIGHT
```

The protocol applies a separate validity range of `[-3.5, 3.5]` meters. A reading outside that
range is invalid and is rejected before lane assessment occurs.

## Failure design

The sensor simulator randomly chooses whether each reading is valid or faulty using
`sensor.faultProbability`. Faults are randomly selected between two kinds:

1. **Truncated datagram** — the simulator sends only part of an otherwise valid JSON message.
2. **Out-of-range reading** — the simulator sends a JSON lane offset outside the valid protocol
   range.

When the critical service receives either payload, `Codec.decode` throws a parsing or validation
exception. The service deliberately does not catch that exception. It propagates out of `Main`,
terminates the JVM with a nonzero status, and stops sending heartbeats. No `process.exit` or similar
forced termination is used.

This failure is nondeterministic because the simulator uses a fresh `Random` instance and applies
the configured probability independently to each reading. The failure is also realistic: corrupted
or invalid sensor input is not handled by the minimum critical-service prototype, so the monitoring
tactic must detect the resulting process failure.

## Heartbeat behavior

The heartbeat deadline is independent of the arrival of sensor readings. The service sends its first
heartbeat immediately and then attempts to send another heartbeat every `heartbeat.periodMs`.

The heartbeat and sensor-receive operations use the same service loop and process. This is an
intentional design choice for the prototype: if a fatal sensor-processing exception kills the
service process, no separate heartbeat thread remains to falsely report that the service is alive.

The receiver records the time at which each heartbeat was observed. If heartbeats stop, it evaluates
the service state using the configured period and missed-heartbeat count:

```text
missed == 0                         -> HEALTHY
0 < missed < receiver.missedCount   -> SUSPECT
missed >= receiver.missedCount      -> FAILED
```

The receiver then sends a `StatusReport` to the monitor. The monitor logs service-state transitions,
including the critical service reaching `FAILED`.

## Sequence diagram

The sequence diagram shows the normal and failure paths:

1. `sensor-sim` creates and sends a `SensorReading` to the critical service.
2. `Main` receives and decodes the datagram.
3. `LaneDetector` classifies a valid reading and the service logs the result.
4. On its independent deadline, `Main` sends a `Heartbeat` to the receiver.
5. The sensor simulator eventually sends a randomly selected faulty payload.
6. Decoding or validation throws an uncaught exception in the critical service.
7. The critical-service process terminates and sends no further heartbeats.
8. The receiver transitions the service through `SUSPECT` to `FAILED` and reports the state to the
   monitor.

## Configuration and execution

The service uses the shared configuration file at
`protocol/src/main/resources/heartbeat.properties`. Important settings include:

- `service.listen.port` — UDP port on which the service receives sensor readings.
- `service.heartbeat.target.host` — receiver host.
- `service.heartbeat.target.port` — receiver UDP port.
- `heartbeat.periodMs` — heartbeat interval.

After building from the repository root, run the service with:

```bash
java -jar critical-service/target/critical-service.jar
```

For a bounded demonstration run, use:

```bash
java -jar critical-service/target/critical-service.jar --run-for-ms 5000
```

The service normally runs continuously. Its simulated random failure is generated by running the
separate sensor simulator process, for example:

```bash
java -jar sensor-sim/target/sensor-sim.jar
```

For deployment across machines, set the service's heartbeat target host and configure the receiver's
listen endpoint appropriately. Separate JVM processes are required; separate physical machines are
recommended when demonstrating processor-failure isolation.

## How the code is verified

- `LaneDetectorTest` verifies centered and drifting classifications at and around both ±0.5-meter
  boundaries.
- `MainTest` verifies runtime argument parsing and both simulated fault payload forms.
- `CodecTest` and `SensorReading` validation tests verify malformed and out-of-range messages.
- Receiver unit and UDP integration tests verify that stopped heartbeats eventually produce the
  `HEALTHY → SUSPECT → FAILED` sequence.

Run the complete test suite from the repository root with:

```bash
mvn test
```
