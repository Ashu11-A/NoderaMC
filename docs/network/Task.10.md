# Network Task 10 — Tick-Lag / TPS Metric + Low-TPS Region Handoff

<!-- AI-AGENT-INSTRUCTION: Metrics in this task take INJECTED time — never a wall clock — and live
     OUTSIDE the engine. A remote peer's tick report is ADVISORY; only a locally verified certificate
     is a reference. Only the deterministic successor may initiate a handoff, or one slow window
     splits a committee into competing epochs. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (with a live call site since 2026-07-25)
**Category:** network · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [engine 5](../engine/Task.5.md), [network 7](Task.7.md)
**Consumed by:** [minecraft 3](../minecraft/Task.3.md), [worker 4](../peer/Task.4.md)

---

## Goal

Realise the hosting rule that a **tick-lag metric** governs region-boundary synchronisation: a peer
running low TPS is detected, its reliability drops, and **another peer takes over its regions** via
the existing committee failover — preventing boundary desync when neighbouring regions are run by
different players.

## Status detail

Complete, and — since 2026-07-25 — with a **live call site**, which it previously lacked.

The headless lane: `SessionKeepAlive` emits compatible v2 per-region progress (tag kept, body
versioned, v1 accepted as empty); `TickSync` admits only locally certified region and network
references and treats remote reports as advisory; integer-EMA `TickSkewMeter` and `TpsMeter` live
outside the engine and take injected time; `LagHandoffPolicy` requires skew strictly above four ticks
for consecutive windows, with assignment resets and a cooldown; guarded `CommitteeFailover` rejects
stale decisions, applies one reliability penalty, and bumps exactly one epoch.

The live half, added when a laggy player was found wedging its regions for everyone:
`WorkerValidationService.forwardLagTickBps` measures the age of the **oldest forwarded action the
primary has not answered** — literally what the player at the boundary is waiting on — and
`tickLagHandoff` (one window per 100 ticks, three unhealthy windows to fire) drives the policy into
`CommitteeFailover.promoteOnLag`.

## Dependencies

- [engine 5](../engine/Task.5.md) — the failover this triggers.
- [network 7](Task.7.md) — the reliability the penalty adjusts.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `SessionKeepAlive` v2 — per-region assignment progress, v1-compatible | ✅ |
| 2 | `TickSync` — certified references only; remote reports advisory | ✅ |
| 3 | `TickSkewMeter` / `TpsMeter` — integer EMA, injected time, outside the engine | ✅ |
| 4 | `LagHandoffPolicy` — sustained skew, assignment resets, cooldown | ✅ |
| 5 | Guarded `CommitteeFailover` — stale rejection, one penalty, exactly one epoch | ✅ |
| 6 | Live call site: `forwardLagTickBps` → `tickLagHandoff` → `promoteOnLag` | ✅ |

## Design

**Measure what the waiting player is waiting on.** A generic "this peer's TPS is low" signal is easy
to compute and hard to act on. The age of the oldest forwarded action a primary has not answered is
*exactly* the latency a player at a region boundary experiences, which makes the threshold meaningful
rather than arbitrary.

**Only certified references count.** If a lagging peer's own report could serve as the reference
tick, a lying or confused peer could make everyone else look late. `TickSync` admits only locally
verified certificates as references; remote reports are advisory input.

**Sustained, not instantaneous.** One slow window is a garbage-collection pause. Three consecutive
unhealthy windows is a peer that cannot keep up. The cooldown then stops the promoted committee from
flapping back.

**Only the deterministic successor may initiate.** If any member could promote itself on noticing lag,
two members noticing simultaneously would produce competing epochs and split the committee. The
successor is canonically ordered, so exactly one peer is entitled to act.

**Exactly one epoch bump.** A failover that bumps twice invalidates the promotion it just performed.

**No wall clocks.** Every meter takes injected time, so the tests are deterministic and the metric
cannot drift with the host's clock.

## Files

- `peer/src/main/java/dev/nodera/diagnostics/{TickSkewMeter,TpsMeter}.java`
- `peer/src/main/java/dev/nodera/peer/TickSync.java`
- `library/java/engine/src/main/java/dev/nodera/coordinator/LagHandoffPolicy.java`
- `library/java/engine/src/main/java/dev/nodera/committee/CommitteeFailover.java`

## Testing

- `LagHandoffIT` — isolated promotion, continued commit at epoch+1, the neighbouring region untouched,
  certified replay.
- Threshold tests: the exact threshold stays healthy; sustained skew demotes only the lagging region;
  stale decisions cannot bump epochs; cooldown suppresses flapping.
- `LiveLagHandoffIT` (4) — the live lane: forwarded-action age drives the policy; only the
  deterministic successor initiates; re-seating does not rewind a live replica.

## Acceptance criteria

1. ✅ Skew and TPS are measured outside the engine with injected time.
2. ✅ Only locally certified references are used; remote reports are advisory.
3. ✅ Sustained skew demotes only the lagging region, under cooldown.
4. ✅ Failover bumps exactly one epoch and neighbours are untouched.
5. ✅ The lane has a live call site driven by real forwarded-action latency.

## Limitations

None open. **L-42** is RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
