# Engine Task 4 — Coordinator: Leases, Epochs, Propose → Verify → Commit

<!-- AI-AGENT-INSTRUCTION: The two-pass compare-and-set applier in this task is the ONLY place that
     mutates a world view. Do not add a second write path, and do not weaken the CAS to "apply what
     matches and skip the rest" — partial application of a delta is the corruption mode this design
     exists to make impossible. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (headless; live half → [minecraft 2](../minecraft/Task.2.md))
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [engine 3](Task.3.md)
**Consumed by:** [engine 5](Task.5.md), [engine 6](Task.6.md), [engine 7](Task.7.md), [minecraft 2](../minecraft/Task.2.md)

---

## Goal

Turn shadow lanes into the real pipeline for delegated regions: the assigned **primary executes
first** and submits a `RegionProposal`; the verifier re-executes, compares roots, and **commits the
proposer's delta to the real world** on match. Full lease/epoch machinery, reassignment on failure,
and compare-and-set world application.

## Status detail

Complete headlessly. `CoordinatorIT` proves commit-on-match, forced-mismatch reject with the world
left uncorrupted, stale-epoch drop, and primary-death reassignment under a bumped epoch.

The live half — NeoForge event capture and cancel, and the real `ServerLevel` applier — is delivered
by [`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [engine 3](Task.3.md) — the shadow gate must be green before real delegation is safe.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `NodeRegistry` + `ReliabilityLedger` (EMA, persisted) | ✅ |
| 2 | `RendezvousPlacementPolicy` — deterministic region → peer placement | ✅ |
| 3 | `RegionAllocator` + `LeaseManager` (epoch bump, stale-epoch rejection) | ✅ |
| 4 | `HeartbeatMonitor` | ✅ |
| 5 | `RegionPipeline` — the per-region state machine | ✅ |
| 6 | `ProposalManager` + `ServerVerifier` | ✅ |
| 7 | `WorldMutationApplier` — two-pass CAS over the `MutableWorldView` seam | ✅ |
| 8 | `DelegabilityPolicy` (first cut; completed in [task 7](Task.7.md)) | ✅ |
| 9 | Live `ServerLevel` applier + event capture/cancel | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Epochs make stale decisions harmless.** Every assignment carries an epoch. A proposal, vote, or
commit that names an older epoch is dropped rather than reconciled. This is what makes failover safe
without distributed locking: the old primary's in-flight work becomes inert the moment the epoch
bumps, so there is no window in which two primaries both commit.

**Two-pass CAS, never partial application.** The applier first *verifies* every position in the
delta against its expected prior state, then applies. A mismatch anywhere aborts the whole delta with
zero writes. The alternative — apply-as-you-go — produces half-applied deltas, which are worse than
a rejected batch because the world is now in a state no replica ever computed.

**A `MutableWorldView` seam, not a Minecraft type.** The coordinator is Minecraft-free, so it writes
through an abstraction the mod implements against `ServerLevel`. This is what lets `CoordinatorIT`
prove commit/reject/abort semantics with no game running, and it is why the live half is adapters
rather than a rewrite.

**Reliability is an EMA, deliberately.** A single bad proposal should not evict a peer, and a long
history of good ones should not make a peer unassailable. The EMA plus later multi-factor scoring
([network 7](../network/Task.7.md)) gives placement a stable, slowly-moving signal.

## Files

- `java/engine/src/main/java/dev/nodera/coordinator/{NodeRegistry,ReliabilityLedger,RegionAllocator,LeaseManager,HeartbeatMonitor,RegionPipeline,ProposalManager,ServerVerifier,WorldMutationApplier,DelegabilityPolicy}.java`
- `java/engine/src/test/java/dev/nodera/coordinator/CoordinatorIT.java`

## Testing

- `CoordinatorIT`: commit-on-match; forced mismatch rejected and the world unchanged; stale-epoch
  drop; primary death ⇒ reassignment under a bumped epoch.
- Applier atomicity: a bad CAS mid-delta results in **zero** applied writes.
- Placement property test: same inputs ⇒ same placement regardless of iteration order.

## Acceptance criteria

1. ✅ A matching proposal commits; a mismatching one is rejected with the world uncorrupted.
2. ✅ A stale-epoch message never affects state.
3. ✅ Primary death reassigns the region under a bumped epoch and the pipeline continues.
4. ✅ The applier is atomic.
5. ⏳ Live: capture/cancel on real events and the `ServerLevel` applier —
   [minecraft 2](../minecraft/Task.2.md).

## Limitations

None owned. The live-wiring gap rides **L-45** in
[`minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md).
