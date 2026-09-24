# Receiver slice — UML and narrative

Design deliverables for the **receiver** process of the heartbeat fault-detection tactic
(SWEN-755, Group 1). Sources are the two PlantUML files beside this note:

- [`receiver-class.puml`](receiver-class.puml) — class diagram
- [`receiver-sequence.puml`](receiver-sequence.puml) — sequence diagram

Render either with PlantUML, e.g. `plantuml receiver-class.puml` (produces a PNG/SVG next to it).

## What the receiver is

The receiver is the **watchdog**. The critical service emits a periodic `Heartbeat`; the receiver
keeps a per-service last-seen table, judges each service HEALTHY / SUSPECT / FAILED, and every
check interval sends the monitor a `StatusReport`. That report stream doubles as the receiver's own
heartbeat, so the monitor can detect a dead receiver by the same silence-and-timeout logic. This
matches the course's reference design on slides 8–10, where the `HeartbeatReceiver` owns the timing
fields and `checkAlive()` while the fault monitor only hears about state changes.

## Class diagram

The slice is split into a **pure logic** class and a **runtime** class so the aliveness rule is
testable without real time or sockets:

- **`HeartbeatReceiver`** — the watchdog logic, no sockets and no threads. It holds the last-seen
  table and applies the rule below. Its method names mirror course slides 8 and 10: `pitAPat()` is
  the beat handler, `updateTime()` stamps the last-seen time, `checkAlive()` is the boolean liveness
  query (true unless FAILED), and `expireMs()` is slide 8's `expireTime` shown as a **derived**
  value. Because it takes its "now" from an injected `Clock`, a unit test can step time forward
  beat by beat with no waiting.
- **`ReceiverNode`** — the runtime. It binds the UDP socket and runs the two threads the tactic
  keeps separate (slide 9): one receives beats and updates the table, the other checks aliveness and
  sends the report. It is `AutoCloseable`, so an integration test can start it on an ephemeral port
  and shut it down cleanly.
- **`Clock`** — a one-method seam (`millis()`) with a `SYSTEM` default; this is what makes the state
  rule deterministic under test.
- **`Main`** — wiring only: it loads `NetConfig`, constructs the two classes, starts the node, and
  blocks until the process is stopped.

The `protocol` classes (`Heartbeat`, `StatusReport`, `ServiceStatus`, `ServiceState`, `Codec`,
`NetConfig`) are the **shared contract**; the receiver consumes them and never edits them.

### The aliveness rule

On each check tick, for every known service:

```
missed = floor((now - lastSeen) / heartbeat.periodMs)
HEALTHY  when missed == 0
SUSPECT  when 0 < missed < receiver.missedCount
FAILED   when missed >= receiver.missedCount
```

`lastSeen` is the time the beat was **observed** by the receiver, not the sender's `sentAt`. There
is no separate expiry setting: the effective expiry is `missedCount x periodMs` (3 s with the demo
defaults of a 1 s period and a missed count of 3), exposed as the derived `expireMs()`. This keeps
the two tunables the textbook names — the period and the missed count (SAiP4, §17.2, p. 252) — as
the only knobs, with everything else derived.

## Sequence diagram

Two concurrent loops, as on slide 10:

1. **Beat loop** (driven by the sender, every `heartbeat.periodMs`): each `Heartbeat` arriving on the
   listen port is decoded and handed to `pitAPat()`, which calls `updateTime()` to refresh
   `lastSeen`. A datagram that cannot be parsed or that carries an out-of-range value is logged and
   skipped — the receiver is the watchdog, not the process the injected fault is meant to crash.
2. **Check loop** (the checker thread, every `receiver.checkIntervalMs`): `check()` evaluates every
   known service against the rule above and returns a `ServiceStatus` list; the node logs any state
   transition (SUSPECT at a lower level than FAILED) and sends one `StatusReport`.

The diagram then shows the failure path: once the critical service crashes and its beats stop, each
successive check sees `missed` climb — SUSPECT while it is below the threshold, FAILED once it
reaches it — so the monitor observes HEALTHY → SUSPECT → FAILED within `missedCount x period` plus
one check interval.

## How the code is verified

- `HeartbeatReceiverTest` exercises the state rule through the public interface with an injected
  clock: HEALTHY at zero misses, SUSPECT below the threshold, FAILED at it, recovery to HEALTHY on
  the next beat, per-service independence, and the derived expiry.
- `ReceiverNodeIntegrationTest` runs the real threads over real UDP sockets on ephemeral ports: it
  asserts the HEALTHY → SUSPECT → FAILED report sequence (at both a compressed timing profile and
  the shipped demo profile) and that a malformed datagram does not take the receiver down.
