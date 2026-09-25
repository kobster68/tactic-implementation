# Monitor slice — UML and narrative

Design deliverables for the **monitor** process of the heartbeat fault-detection tactic
(SWEN-755, Group 1). Sources are the two PlantUML files beside this note:

- [`monitor-class.puml`](monitor-class.puml) — class diagram
- [`monitor-sequence.puml`](monitor-sequence.puml) — sequence diagram

Rendered diagrams are included beside their sources:

- [`monitor-class.png`](monitor-class.png)
- [`monitor-sequence.png`](monitor-sequence.png)

To regenerate them with PlantUML, run `plantuml monitor-class.puml monitor-sequence.puml` from this
directory (PNG output is the default).

## What the monitor is

The monitor is the final hop in the heartbeat tactic. It consumes the receiver's periodic
`StatusReport` datagrams, logs changes in each monitored service's state, and independently watches
for silence from the receiver. Because each report also acts as the receiver's heartbeat, no separate
receiver-liveness message is needed.

The implementation is intentionally a single `Main` class. Its receive loop owns the UDP socket,
tracks the latest reported state by service ID, and compares the report-arrival time with configured
thresholds to judge whether the receiver is HEALTHY, SUSPECT, or FAILED. This design makes service
transitions visible while ensuring that a lost receiver report eventually produces a failure log.

The protocol types (`StatusReport`, `ServiceStatus`, `ServiceState`, `Codec`, and `NetConfig`) are the
shared contract. The monitor consumes them and does not alter their behavior.

## Class diagram

The class diagram describes the monitor entry point and the protocol records it consumes:

- **`Main`** loads and validates configuration, binds the monitor UDP port, and processes incoming
  packets. The per-service state map, receiver ID, last-report timestamp, and receiver state are
  local to `main()`.
- **`evaluateReceiverState()`** classifies receiver silence. An unseen receiver remains in its
  current state. Once a receiver has reported, the method uses elapsed wall-clock time and the
  shared configuration thresholds to derive HEALTHY, SUSPECT, or FAILED and logs state changes.
- **`StatusReport`** contains the receiver identity and a list of `ServiceStatus` snapshots; each
  status reports one service ID, its state, last-seen time, and missed count.
- **`Codec`** decodes a UDP datagram into the shared `Message` contract. A decoded message that is
  not a `StatusReport` is ignored, and malformed datagrams are logged and skipped.

For receiver liveness, FAILED is checked first at `monitorExpireMs()` (derived from
`monitor.missedCount × receiver.checkIntervalMs`). Before that threshold, the monitor tolerates one
report interval plus half an interval for scheduling jitter; it reports HEALTHY within that grace
period and SUSPECT beyond it. This avoids spurious state flapping around poll boundaries without
allowing the configured failure window to be masked.

## Sequence diagram

1. **Startup:** load the layered configuration, reject non-positive monitor/receiver intervals and a
   missed count below one, log effective settings, bind the monitor port, and configure timed
   receives. The timeout is capped at 30 seconds.
2. **Valid report:** decode the datagram, refresh the receiver's last-seen timestamp, then inspect
   each service status. The monitor logs and saves a service's first state or a changed state, and
   leaves unchanged states quiet. It then reevaluates receiver liveness.
3. **Silence:** a receive timeout reevaluates the receiver's state. Once silence reaches the derived
   expiry, the monitor logs `RECEIVER <id> FAILED` at ERROR; SUSPECT/HEALTHY transitions are logged
   at INFO. Reports that resume can move the receiver back to HEALTHY.
4. **Invalid input and shutdown:** non-`StatusReport` messages, malformed datagrams, and invalid
   reports are logged and skipped. Closing the socket from the shutdown hook unblocks `receive()`,
   allowing the loop to exit cleanly.

## How the code is verified

[`MainTest.java`](../../monitor/src/test/java/edu/rit/swen755/heartbeat/monitor/MainTest.java)
covers the receiver's unseen, fresh, jitter-grace, SUSPECT, and expiry cases, including the edge
case where the configured expiry is reached inside the jitter grace. It also checks that a
non-positive monitor check interval is rejected at startup.
