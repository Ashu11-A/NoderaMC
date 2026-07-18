# Task 25 — Tick-Lag / TPS Metric + Low-TPS Region Handoff (Phase 6–7)

**Phase:** 6–7 · **Depends on:** Task 7 (committee/failover), Task 22 (reliability) ·
**Modules:** `diagnostics` (metric), `peer-runtime` (sync), `coordinator` (handoff trigger).

## Goal

Realise the user's rule 9: a **tick-lag metric** (how many ticks a peer is behind the region's
reference tick) governs region-boundary synchronisation. A peer running low TPS is detected, its
reliability is lowered, and **another peer takes over the region** via the existing committee
failover (Task 7) — preventing boundary desync/corruption when neighbouring regions are run by
different players. The system aims for maximum tick synchronisation.

## Context

- Tick ranges are modelled end-to-end (`RegionExecutionContext.tickFrom/tickTo`,
  `ActionBatch`, `SnapshotVersion`, `Checkpoint.tick`), and `ServerHello.currentTick` gives a
  one-shot handshake alignment — but there is **no continuous cross-peer tick-skew / TPS metric**
  anywhere (LIMITATIONS L-42; Plan §9 risk table).
- **Critical constraint:** the deterministic engine forbids wall-clocks (`System.currentTimeMillis`
  / `nanoTime`) inside `simulation` (Plan §3.7, `ForbiddenApiTest`). The tick-lag metric therefore
  lives **outside** the engine — in `peer-runtime`/`diagnostics` — and is never part of hashed
  consensus state. It is a liveness/operational signal, not consensus truth (same boundary the
  `VoteCollector` timeout already respects).
- Handoff reuses Task 7's `CommitteeFailover` (primary loss → validator promoted under bumped
  epoch); this task adds the **low-TPS trigger** that fires before corruption, not after a
  disconnect.

## Folder structure (additions)

```
diagnostics/src/main/java/dev/nodera/diagnostics/metric/
├── TickSkewMeter.java            # per-peer ticks-behind vs the region reference tick (EMA)
└── TpsMeter.java                 # rolling throughput (commits/sec) per peer

peer-runtime/src/main/java/dev/nodera/peer/
└── TickSync.java                 # gossip each peer's last-applied tick; feed TickSkewMeter

coordinator/src/main/java/dev/nodera/coordinator/
└── LagHandoffPolicy.java         # threshold: skew > X ticks for Y windows ⇒ reliability penalty
                                   #   ⇒ CommitteeFailover (Task 7) reassigns the region
```

## Implementation details

- **Reference tick.** The region's reference tick = the highest committed `tickTo` among the
  committee (the primary's progress). Each validator gossips its last-applied tick in the existing
  heartbeat cadence (`SessionKeepAlive` gains a `lastAppliedTick` field — append-only). `TickSkewMeter`
  computes `reference − peer` as an EMA; `TpsMeter` computes commits/sec over a rolling window.
- **Metric outside the hashed path.** `TickSkewMeter`/`TpsMeter` read wall-clock (they must — TPS is
  a wall-clock quantity) but live in `diagnostics`/`peer-runtime`, never in `simulation`; they are
  excluded from `RegionExecutionContext`, `StateRoot`, and every certificate (the `simulation`
  forbidden-API ArchUnit rule is scoped to `dev.nodera.simulation..` and does not cover these
  packages — same precedent as `shadow-validation` timing).
- **Handoff (rule 9).** `LagHandoffPolicy`: if a primary's tick-skew exceeds `handoff.skewThreshold`
  (default 4 ticks ≈ 200 ms) for `handoff.windows` consecutive windows, lower its reliability (Task
  22), and if it falls below the assignment floor, trigger `CommitteeFailover` — a validator is
  promoted under a bumped epoch (Task 7), and boundary state is reconciled by replaying the certified
  log forward (Task 9 `EventReplayer`). The boundary itself (neighbouring regions under different
  primaries) stays consistent because each region commits independently and cross-region effects go
  through the Task 8 fallback/router.
- **"Maximum synchronisation" aim.** The policy skews toward early handoff (small threshold) so
  boundaries rarely wait on a laggard; the diagnostics HUD (Task 18) surfaces per-peer skew live.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-42** — no tick-skew/TPS metric, no low-TPS handoff today. Exit: `TickSkewMeter`/`TpsMeter`
  computed outside the engine; `LagHandoffPolicy` triggers committee failover on sustained skew;
  `LagHandoffIT` proves a laggard primary is replaced and boundary state stays consistent.
- **A-1** (consensus latency vs 50 ms tick budget) is partially addressed: a laggard no longer
  stalls its region's boundary.

## Acceptance criteria

1. `TickSkewMeterTest`: deterministic given a stream of (peer, appliedTick) vs reference; EMA
   matches the formula; no clock read inside `simulation` (ArchUnit still green).
2. `LagHandoffIT` (headless): a primary artificially delayed (skew > threshold for N windows) →
   reliability drops below floor → `CommitteeFailover` promotes a validator under epoch+1; the
   region's committed state and its neighbour's boundary reconcile cleanly via replay.
3. A non-laggard primary is **not** flipped (hysteresis — `DELEGABILITY_COOLDOWN_TICKS`).
4. `./gradlew check` green; L-42 → RETIRING; README/Tested updated.
