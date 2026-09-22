# tactic-implementation

SWEN-755 Tactic Implementation, Group 1: fault detection with the **heartbeat availability
tactic**, implemented as four Java processes communicating over UDP.

This repository is at **Step 0**: the shared message contract, the JSON codec, the configuration
loader, the Maven build, and skeleton `Main` classes are complete, and a one-datagram **tracer**
proves the wiring end to end on one machine. No tactic logic (timers, miss counting, the state
machine, fault injection) is built yet -- each of those is a teammate's slice. The design choices
below are the binding contract for every slice.

## Purpose

Detect the failure of a critical service by having it emit periodic heartbeats. A receiver watches
those beats and reports service health to a monitor. A separate sensor simulator feeds the critical
service lane-position readings; a malformed reading crashes the service (an unhandled parse/validate
exception), which stops its heartbeats -- the fault the tactic exists to detect.

## Four-process topology

```
  sensor-sim            critical-service          receiver                  monitor
  (lane camera)         (Process 1)               (Process 2, watchdog)     (Process 3)
      |                     |                         |                         |
      |   SensorReading     |      Heartbeat          |      StatusReport       |
      |   UDP -> :5001      |      UDP -> :5002       |      UDP -> :5003       |
      +-------------------->+------------------------>+------------------------>+
    send one            receive + decode          receive + decode          receive + decode
                        send one beat             (owns last-seen table,    (logs / notifies on
                                                   sends periodic report)    transitions)
```

Hop rule: every `*.target.port` equals its partner's `*.listen.port` (asserted by a unit test).
The receiver is the watchdog; its periodic `StatusReport` doubles as its
own heartbeat so the monitor can detect a dead receiver.

## Design summary

| Topic | Choice |
| --- | --- |
| Receiver role | Aggregator: the receiver owns the last-seen table and `checkAlive()`, is the watchdog, and sends a periodic `StatusReport` that doubles as its own heartbeat. |
| Fault source | A separate `sensor-sim` process sends `SensorReading` datagrams to the critical service under a configured fault probability (four processes). |
| Build layout | Maven multi-module: parent pom, shared `protocol`, one runnable-jar module per process. |
| Wire encoding | JSON via Jackson; one record per type with a `type` discriminator; `Codec.decode` parses **and** validates in one uncaught call. |
| Hop-2 heartbeat | A dedicated `Heartbeat` on its own timer (not piggybacked). |
| Configuration | One shared `heartbeat.properties`, loaded by `NetConfig`, overridable with `--config <path>` or `-Dkey=value`. |
| Default timings | Human-watchable demo profile (1 s period, 500 ms check, 3 missed); an aggressive 200 ms profile ships commented out. |
| Fault injection | Two fault kinds -- out-of-range lane offset or truncated datagram -- chosen by `sensor.faultProbability` and `sensor.corruptShare`. |
| Service state | Three states `HEALTHY, SUSPECT, FAILED`, with a boolean `checkAlive()` beside a `state()` accessor. |
| Skeleton depth | Tracer bullet: each `Main` does config + socket + one datagram, then stops at a `TODO(<owner>)` line. |
| Silent receiver | The monitor uses the same `HEALTHY/SUSPECT/FAILED` vocabulary for the receiver, logged as `RECEIVER <id> FAILED` at the highest level. |
| FAILED trigger | `missed = floor((now − lastSeen) / period)`; SUSPECT below `missedCount`, FAILED at or above. No `expireMs` key; expiry is derived as `missedCount × period`. |

> The `TODO` comments in each `Main` name what its owner builds; no timer, counting or state logic
> exists anywhere in Step 0.

## Build and run

Requires JDK 21 and Maven 3.9+ (verified: Java 21.0.7, Maven 3.9.10).

```bash
# build all modules and run the protocol unit tests
mvn -q package

# run the full Step 0 tracer end to end (builds, launches all four, prints the logs)
scripts/run-tracer.sh

# run a single process (each is a self-contained runnable jar)
java -jar sensor-sim/target/sensor-sim.jar
java -jar critical-service/target/critical-service.jar
java -jar receiver/target/receiver.jar
java -jar monitor/target/monitor.jar

# override any config value at launch
java -jar monitor/target/monitor.jar -Dmonitor.listen.port=6003
java -jar sensor-sim/target/sensor-sim.jar --config ./my-hosts.properties
```

Each `Main` sets a 30 s socket receive timeout so a tracer run cannot hang forever, and exits 0
after its single send/receive.

## Configuration keys

All keys live in `protocol/src/main/resources/heartbeat.properties`. Defaults are the demo
profile.

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
| `receiver.missedCount` | `3` | missed beats before the receiver judges a service FAILED; `missed = floor((now − lastSeen) / heartbeat.periodMs)`; derived expiry 3000 ms |
| `monitor.checkIntervalMs` | `500` | how often the monitor evaluates the report stream |
| `monitor.missedCount` | `3` | missed reports before the monitor judges the receiver FAILED; derived expiry 3 × `receiver.checkIntervalMs` = 1500 ms |
| `sensor.periodMs` | `1000` | sensor-sim send interval |
| `sensor.faultProbability` | `0.1` | probability a given reading is a fault |
| `sensor.corruptShare` | `0.5` | of injected faults, the share that are truncated datagrams vs. out-of-range values |

## Slice ownership

Everything **after** the `TODO(<owner>)` line in each process `Main` belongs to that module's owner;
that line is the slice boundary. The `protocol` module (records, `Codec`, `NetConfig`, the properties
file) is shared -- **changes to `protocol` go through a PR**, never a direct edit on a slice branch.

| Module | Owner slice (after the TODO line) |
| --- | --- |
| `sensor-sim` | periodic sending at `sensor.periodMs` and fault injection |
| `critical-service` | receive loop over readings and the heartbeat timer at `heartbeat.periodMs` |
| `receiver` | last-seen table, `checkAlive()`, periodic `StatusReport`, the `HEALTHY/SUSPECT/FAILED` transitions |
| `monitor` | transition logging/notification and the timer that reports a silent receiver |

## Deliverables (course slide s.14)

- [x] **Code** -- the four processes and the shared `protocol` module (this repo).
- [x] **ReadMe** -- this file.
- [ ] **UML class diagram + sequence diagrams, with narrative** -- to be added under
  [`uml/`](uml/) (placeholder directory in place).

## Module layout

```
tactic-implementation/
  pom.xml                     parent (packaging pom): versions, plugins, module list
  protocol/                   shared contract: Message + records, Codec, NetConfig, heartbeat.properties, tests
  sensor-sim/                 Process: sends SensorReading
  critical-service/           Process 1: receives readings, sends Heartbeat
  receiver/                   Process 2 (watchdog): receives Heartbeat, sends StatusReport
  monitor/                    Process 3: receives StatusReport
  scripts/run-tracer.sh       Step 0 end-to-end tracer
  uml/                        UML deliverables (placeholder)
```
