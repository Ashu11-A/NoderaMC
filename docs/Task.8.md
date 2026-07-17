# Task 8 — Server-Fallback-Only Execution + Cross-Region Router (Phase 4)

**Phase:** 4 · **Depends on:** Tasks 7, 11 · **Modules:** `neoforge-mod`, `simulation`,
`integration-tests`

> Task 11 is a hard prerequisite for running this phase on non-flat worlds: without the
> interference guard, foreign mutations (random ticks, mobs, other mods) in delegated
> regions turn every soak into a CAS-abort/resync storm and the lane metrics lie.

## Goal

Invert the default: committees execute; the server's engine runs **only** for
(1) unassigned/non-delegable regions, (2) disputed proposals, (3) cross-region actions,
(4) collapsed committees, (5) spot-checks. Prove it with soak metrics: > 90% of validated
batches in assigned regions commit without server re-execution. First honest measurement
of what the architecture buys.

---

## Folder structure (additions)

```
neoforge-mod/src/main/java/dev/nodera/mod/dedicated/
├── fallback/
│   ├── ServerRegionExecutor.java     # engine-driven execution for fallback categories;
│   │                                 #   self-committing (no quorum needed — server authority lane)
│   └── FailedRegionRecovery.java     # committee collapse: revoke, execute server-side,
│                                     #   rebuild committee when eligible nodes return
├── routing/
│   ├── CrossRegionRouter.java        # BorderClassifier verdict ⇒ fallback lane; also actions
│   │                                 #   spanning delegated+non-delegated boundary
│   └── LaneMetrics.java              # per-lane counters: committee / fallback / disputed / spot
└── command/  (extend NoderaCommand)  # /nodera lanes — the honesty dashboard

simulation additions:
└── border/CrossRegionPlan.java       # decomposition of a cross-region action into per-region
                                      #   sub-deltas (server computes; regions receive as
                                      #   authoritative "external mutation" batches)

integration-tests additions:
└── src/test/java/dev/nodera/it/
    ├── CrossRegionActionIT.java      # e.g. block placed exactly on a region border line
    ├── CommitteeCollapseIT.java      # kill 2 of 3 members ⇒ fallback ⇒ recovery
    └── SoakIT.java                   # long-running mixed workload, lane-share assertion
```

## Class relationships

```
ActionRouter (Task 6) grows a decision tree:
    action ─► BorderClassifier
        ├─ single-region & delegated & committee healthy ─► committee lane (Task 7 path)
        ├─ single-region & not delegated                 ─► vanilla lane (untouched MC behaviour)
        ├─ cross-region (any)                            ─► CrossRegionRouter ─► ServerRegionExecutor
        └─ region DISPUTED/FROZEN                        ─► ServerRegionExecutor

Router sees CAPTURED ACTIONS ONLY. Uncaptured cross-lane effects (vanilla-lane piston
pushing into a delegated region, fluid flow, mob griefing, fake players) never reach it —
they are handled below the router by the Task 11 interference guard (converted to
ExternalDelta) and prevented structurally by the Task 11 neighbor-ring delegability rule.
LaneMetrics gains externalDeltaApplied + interferenceConverted counters so soak results
prove the guard, not just the router, is carrying that load.

ServerRegionExecutor
    ├─ uses the ONE engine (FlatWorldRegionEngine) for delegated-format regions
    ├─ for vanilla-lane regions: does nothing (Minecraft ticks normally)
    └─ commits via WorldMutationApplier with a ServerAuthorityCertificate
       (type + reason enum defined in Task 11; this task adds no new certificate types,
        it exercises reasons UNASSIGNED, NON_DELEGABLE, CROSS_REGION, DISPUTED,
        COMMITTEE_COLLAPSED, SPOT_CHECK)

FailedRegionRecovery
    committee healthy? ── no ──► revoke leases (epoch++), lane = fallback,
                                 watch NodeRegistry ── eligible ≥ 3 ──► CommitteeAssembler rebuild
```

Cross-region handling (MVP semantics, per Plan §3.11):

```
Cross-region action A touching regions R1 (delegated) + R2 (delegated):
 1. CrossRegionRouter freezes batch assembly for R1,R2 (barrier tick).
 2. ServerRegionExecutor executes A against a stitched view (both snapshots + halos)
    → CrossRegionPlan{subDelta(R1), subDelta(R2)}.
 3. WorldMutationApplier applies BOTH sub-deltas in one main-thread slice —
    atomic by construction (single thread, two-pass validate-all first across both).
 4. Both regions' versions bump with ServerAuthorityCertificate; committees resume
    from the new base version (they receive the sub-delta as an "external mutation"
    message → apply to replica → continue).
Partial commit impossible: one thread, validate-first. Invariant 11 holds trivially.

Recurring cross-region mechanics (redstone clocks, piston lines) would pay this barrier
every event — they stay out of the validated lane entirely until Task 13 introduces
contraption ownership migration (whole group → one primary), the MultiPaper-takeover
analog at region granularity.
```

## Implementation details — server peer

- **Batch barrier**: `RegionPipeline` gets `PAUSED_FOR_XR` state; `CrossRegionRouter`
  requests pause on affected regions, waits for in-flight proposals to resolve (commit
  or timeout) before executing the cross-region action — ordering: everything
  sequenced by the server's serverSeq stays externally consistent.
- **External mutation feed to replicas**: `ExternalDelta(region, baseVersion,
  encodedDelta, certificateBytes)` — introduced by Task 11's interference pipeline;
  clients apply without voting (certificate-verified). This task reuses the same
  message + replica-advancement path for cross-region sub-deltas.
- **`ServerAuthorityCertificate`**: reasons enum `UNASSIGNED, NON_DELEGABLE,
  CROSS_REGION, DISPUTED, COMMITTEE_COLLAPSED, SPOT_CHECK`. Signed by server identity.
  Explicitly documented as a **transitional** instrument — dies in Task 9's peer model
  except for the gateway's vanilla-lane duties.
- **Adaptive spot-check validation**: the Task 7 controller (N=4 → N=8 → N=64 by
  committee reliability) is exercised end-to-end here — SoakIT drives committees from
  fresh to proven and records lane shares + CPU at each level; controller thresholds
  live-reloadable (ledger L-22 exit evidence).
- **Metrics that decide the phase exit**: `LaneMetrics` per 5-min window —
  committee-committed batches, fallback batches by reason, disputed count, mean commit
  latency per lane, server engine CPU time vs Phase 2 baseline. `/nodera lanes` prints;
  soak asserts.

## Implementation details — NeoForge mod (client)

- Handle `ExternalDelta` (apply-without-vote path + version bump).
- Handle `PAUSED_FOR_XR`/resume implicitly via batch versioning (no new client state:
  batches just don't arrive during the barrier; replica stays consistent by version
  chain).
- HUD: lane indicator per region.

## Acceptance criteria

1. `CrossRegionActionIT`: border-line placement affects two regions atomically; both
   replicas converge; no partial state under injected applier abort.
2. `CommitteeCollapseIT`: kill 2/3 members ⇒ region flips to fallback within one lease
   period, actions keep committing (server lane) ⇒ members return ⇒ committee rebuilt
   under new epoch ⇒ committee lane resumes.
3. `SoakIT` (30+ min CI-nightly, longer manual): with the adaptive controller, proven
   committees (reliability ≥ 0.99) reach spot-check cost ≤ 2% and committee-lane share
   ≥ 95%; fresh committees start at N=4 and decay as designed (assert the trajectory,
   not just the endpoint). Non-spot-check server executions ≤ 2% throughout. Record
   real numbers in `Plan.md` Phase 4 notes (ledger L-22 → RETIRED on green).
4. CPU honesty: server engine-thread CPU under soak measurably below Phase 2
   configuration of the same workload (numbers recorded, no target — this is
   measurement, not marketing).
5. Vanilla lane untouched: regions never delegated behave byte-identically to vanilla
   (regression: GameTest or scripted comparison on a non-delegated area).
6. Interference accounting on a **normal-world** soak section: `interferenceConverted`
   and `externalDeltaApplied` non-zero and stable; CAS-abort resyncs per hour below
   `resync.stormThreshold` (config, default 6/region/hour) — proves the Task 11 guard
   holds under Phase 4 load, not just in its own unit tests.
