# Sensor-sim slice — UML and narrative

Design deliverables for the **sensor-sim** process of the heartbeat fault-detection tactic
(SWEN-755, Group 1). Sources are the two PlantUML files beside this note:

- [`sensor-sim-class.puml`](sensor-sim-class.puml) — class diagram
- [`sensor-sim-sequence.puml`](sensor-sim-sequence.puml) — sequence diagram

Render either with PlantUML, e.g. `plantuml sensor-sim-class.puml` (produces a PNG/SVG next to it).

## What the sensor-sim is

The sensor-sim stands in for the lane camera and is the **fault source** for the demo. Every
`sensor.periodMs` it sends the critical service a `SensorReading` (a lane offset in metres). With
probability `sensor.faultProbability` it sends a broken reading instead, which the critical service
cannot decode. That uncaught exception crashes the critical service, its heartbeats stop, and the
receiver and monitor have a real failure to detect.

## Class diagram

The slice is a single `Main` class, kept small on purpose. The decision logic is split into static
methods so it can be tested without sockets or timing:

- **`main()`** is the runtime. It loads `NetConfig`, checks the startup arguments, and runs the send
  loop: pick a kind, build the payload, send one UDP datagram, then sleep for the period.
- **`pick()`** decides what to send from the two configured probabilities. First it chooses valid
  vs. fault (`faultProbability`), then which fault (`corruptShare`).
- **`payload()`** builds the bytes for each `Kind`:
  - `VALID`: a real `SensorReading` with an offset in [-1.5, 1.5] m, so the lane detector sees both
    centred and drifting readings. It's encoded with `Codec`.
  - `TRUNCATED`: the first half of a valid datagram, so JSON parsing fails.
  - `OUT_OF_RANGE`: an offset beyond `SensorReading.MAX_LANE_OFFSET_METERS` (3.5 m). This JSON is
    written by hand because the `SensorReading` constructor would refuse the value. The limit is
    read from the shared constant rather than repeated as a magic number.
- **`parseCount()`** reads the optional `--count n`. It stops the sim after n sends for tracer and
  test runs. Without it, the sim runs forever.

Startup is strict, matching the other slices: a `sensor.periodMs` of 0 or less, or a `--count` that
is missing, non-numeric, or not positive, fails immediately with a clear message.

The `protocol` classes (`SensorReading`, `Codec`, `NetConfig`) are the **shared contract**. The
sensor-sim uses them and never edits them.

## Sequence diagram

1. **Startup:** load the configuration, validate the period, and parse `--count`.
2. **Send loop** (every `sensor.periodMs`):
   - **Valid reading:** the critical service decodes it, assesses the lane (CENTERED /
     DRIFTING_LEFT / DRIFTING_RIGHT) and keeps running. Its heartbeats to the receiver run on a
     separate timer and don't depend on readings.
   - **Fault:** the sim logs the exact bytes at WARNING and sends them. The critical service's
     `Codec.decode` throws (`JsonEOFException` for truncated, `ValueInstantiationException` for out
     of range) and the process dies. Its heartbeats stop, and the receiver moves it
     HEALTHY → SUSPECT → FAILED, as shown in `receiver-sequence.puml`.

UDP is fire-and-forget, so the sim doesn't notice the crash and keeps sending. The tracer runs the
sim with `-Dsensor.faultProbability=0 --count 3`, so tracer runs are deterministic and end on their
own.

## How the code is verified

`MainTest` covers the slice without sockets:

- Valid payloads always decode to a `SensorReading`.
- Both fault payloads always make `Codec.decode` throw, so the injected fault really crashes the
  critical service.
- `pick()` honours the probabilities at their extremes.
- `parseCount()` accepts a positive count, defaults to 0 (run forever), and rejects a missing,
  non-numeric, or zero value.

A manual check confirmed that each fault kind crashes the running critical service with exit code 1.
