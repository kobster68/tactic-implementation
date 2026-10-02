# Fault recovery with redundancy

This folder documents the recovery design that extends the heartbeat fault-detection prototype. The
detection half answers "is the critical service alive?"; this half answers "what do we do when it
isn't?" The answer is a redundant spare: a second critical-service replica that takes over when the
primary dies, so the vehicle function keeps running instead of stopping at the first crash.

The three diagrams here are the recovery deliverables:

- [`recovery-class.puml`](recovery-class.puml) — the static structure: the replica role and state
  classes in `critical-service`, and the two protocol records (`Checkpoint`, `Promote`) that carry
  recovery across processes.
- [`recovery-sequence.puml`](recovery-sequence.puml) — the dynamic structure across the concurrent
  processes: normal passive checkpointing, the primary crash, receiver-driven promotion, and the
  backup resuming service.
- [`recovery-state.puml`](recovery-state.puml) — the two state machines that run in parallel: a
  replica's role and the receiver's per-service failover generation.

The detection slices (`uml/receiver`, `uml/critical-service`, `uml/monitor`, `uml/system`) still
describe the baseline; this folder is the recovery layer on top of them.

## The chosen design, in one paragraph

One primary and one backup critical-service run on two machines. Both carry the same lane-departure
state (a consecutive-drift counter, its direction, and whether a warning is active). While the
primary is healthy it streams that state to the backup as periodic `Checkpoint` messages, so the
backup is a warm spare: kept current, but not acting. The existing receiver is the only failure
detector. When the primary's heartbeats stop and the receiver judges it `FAILED`, the receiver sends
the backup a `Promote`, and the backup becomes primary using the last checkpoint it received. Because
the backup takes over under the same `serviceId`, detection sees the service go `FAILED -> HEALTHY`
on its own, and the monitor logs the recovery.

## Design trade-offs and rationale

### Passive (warm spare) over active (hot spare) or cold spare

The redundant-spare tactic comes in three flavours (SAiP4 ch. 4, §4.4). A **hot spare / active
redundancy** has the backup process every input in lockstep with the primary, so failover is almost
instant but you pay for two full copies doing the same work and you have to fan every input to both.
A **warm spare / passive redundancy** keeps the backup current through periodic state snapshots from
the primary; failover costs one checkpoint interval of staleness but the backup is otherwise idle. A
**cold spare** starts from nothing on failure: cheapest to run, slowest and least certain to recover.

We built the **warm spare** as the default. For a lane-departure counter the recoverable state is
tiny, a checkpoint is cheap, and the recovery gap is bounded by the checkpoint cadence, which is far
shorter than the detection window the heartbeat already imposes. So passive redundancy buys almost
all of the hot spare's availability at a fraction of the cost and complexity. The hot-spare path is
not thrown away: the sensor simulator can fan identical readings to both replicas (`redundancy.mode=
active`), which is the input-replication half of active redundancy. The backup's parallel processing
of those readings is a documented follow-up, so the mode switch is in place without committing to the
full cost now.

### Reusing the receiver as the single failure detector

Failover needs a trigger, and the trigger needs to agree on who is dead. We already have a component
whose whole job is deciding that: the heartbeat receiver. So the receiver is the only promoter. It
watches the primary's beats, and on the `FAILED` edge it sends the backup a `Promote`.

The alternative, letting the backup time out the primary itself, would put two independent detectors
in the system and invite split-brain: the backup could promote itself while the primary is merely
slow, and now two primaries write divergent state. A single detector removes that class of bug
outright. It is sound here because the fault we recover from is a hard crash (an uncaught exception
that kills the process), not a network partition, so "the receiver can't see it" and "it is dead"
are the same thing.

### One-plus-one, not voting or TMR

Triple modular redundancy and voting detect *wrong* answers by comparing replicas and need at least
three of them. Our fault model is different: the service doesn't lie, it stops. Detecting a stopped
process needs liveness, not a quorum, and the heartbeat already provides it. So one primary plus one
backup is enough, and a third replica would add cost without covering any failure the heartbeat
misses. If the hazard were a service computing a bad lane assessment rather than crashing, voting
would earn its place; for a crash, redundancy of one is the right size.

### Epoch-stamped promotion for loss-tolerant, idempotent failover

Promotion rides the same lossy UDP channel as everything else, so a single `Promote` can be dropped.
The receiver therefore resends while the primary stays `FAILED`, but a naive resend risks promoting
twice. Each `Promote` carries an **epoch**: the receiver bumps it once per `FAILED` edge and reuses
it for the resends of that same failure, and the backup acts on an epoch only once, ignoring repeats
and anything older. Epoch 0 is reserved as the pre-failover state, so the first real promotion is
epoch 1. The result tolerates lost datagrams (resends get through) without the hazard of a double
takeover (repeats are ignored). The resends are capped so a permanently dead primary does not emit a
promote on every tick forever.

### Recovery visible through the existing detection path

The backup resumes under the same `serviceId` as the primary rather than announcing itself as a new
service. That choice means the detection system needs no new vocabulary for recovery: the receiver
simply sees beats resume and reports `HEALTHY` again, and the monitor logs the service's
`FAILED -> HEALTHY` cycle. The monitor labels the promotion step "inferred," because the `Promote`
travels receiver-to-backup and never reaches the monitor; the monitor reports what it can actually
observe, which is the recovery, and is honest that the promotion itself is an inference.

### Two machines for processor isolation

The point of the spare is to survive losing the primary's processor, so the backup and the receiver
run on a different machine from the primary. If they shared a host, a processor failure would take
the detector and the spare down with the service, and there would be nothing left to recover. Running
the processes on one host (the local demo) exercises the logic but does not demonstrate that
isolation; the two-machine launch scripts do.

## Recovery messages

| Message | Direction | Purpose |
| --- | --- | --- |
| `Checkpoint` | primary -> backup | Snapshot of the lane-departure state so the backup stays current (passive redundancy). Carries a sequence number; the backup ignores stale or duplicate snapshots. |
| `Promote` | receiver -> backup | Instruction to take over, stamped with a failover epoch so the backup promotes once per failure and ignores resends. |

Both are ordinary `protocol` records on the shared UDP contract, decoded by `Codec` like every other
message. The backup listens on one socket and tells them apart by type.
