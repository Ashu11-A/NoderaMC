# Engine Task 6 — Server-Fallback Lane + Cross-Region Router

<!-- AI-AGENT-INSTRUCTION: The fallback lane exists so that no action is ever silently dropped, NOT
     as a convenient escape hatch. Every action routed to the server lane is a committee-commit-ratio
     debit and must be visible in SoakMetrics. Do not widen the fallback's classification set to
     "make things work" — widen the committee's coverage instead. Keep this header's status
     accurate. -->

**Status:** ✅ COMPLETED (headless; live soak → [minecraft 2](../minecraft/Task.2.md))
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [engine 5](Task.5.md), [engine 7](Task.7.md)
**Consumed by:** [minecraft 2](../minecraft/Task.2.md), [worker 4](../worker/Task.4.md)

---

## Goal

Invert the default: committees execute, and the server's own engine runs **only** for what a
committee cannot own. A `CrossRegionRouter` classifies every action into the committee lane or the
server lane; a `FallbackExecutor` commits the server lane through the coordinator's applier; and
`SoakMetrics` measures the committee-commit ratio, whose **> 90%** threshold is this phase's exit
criterion.

## Status detail

Complete headlessly. `FallbackRoutingIT` proves a spread-out session clears the > 90%
committee-commit exit criterion. The lane is also **live in the worker**:
`WorkerValidationService.routeAndMaybeFallback` classifies through the router and commits
unassigned-region actions through the executor, with the soak ratio riding the worker's `STATE`
telemetry.

Real vanilla cross-region execution and a live synthetic-client soak are
[`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [engine 5](Task.5.md) — the committee lane this one is the complement of.
- [engine 7](Task.7.md) — **hard prerequisite on non-flat worlds.** Without the interference guard,
  foreign mutations in delegated regions turn every soak into a CAS-abort/resync storm and the lane
  metrics lie.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `CrossRegionRouter` — classify into committee lane vs server lane | ✅ |
| 2 | `FallbackExecutor` — commit the server lane through the coordinator applier | ✅ |
| 3 | `SoakMetrics` — committee-commit ratio and lane counters | ✅ |
| 4 | Barrier-based atomic cross-region apply (`PAUSED_FOR_XR`) | ✅ |
| 5 | Live vanilla cross-region execution + synthetic-client soak | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Four reasons an action goes to the server lane**, and no fifth without a spec change: the region is
**unassigned**, the action is **cross-region**, the region is **disputed**, or its committee has
**collapsed**. Each is a named, counted classification, so the fallback rate is a measurable health
signal instead of an invisible default.

**Cross-region actions pause both sides rather than coordinating a distributed transaction.** A
`PAUSED_FOR_XR` barrier stops the participating regions, applies atomically, and resumes. This is the
same instinct as the redstone contraption design in [task 9](Task.9.md): *move or freeze ownership,
do not invent a cross-region commit protocol*.

**The ratio is the exit criterion because it is the honest one.** "Committees work" is not
falsifiable; "> 90% of actions committed through committees during a spread-out session" is. The
metric is deliberately awkward to game — the fallback path increments it in the opposite direction.

## Files

- `java/engine/src/main/java/dev/nodera/fallback/{CrossRegionRouter,FallbackExecutor,SoakMetrics}.java`
- `java/engine/src/test/java/dev/nodera/fallback/FallbackRoutingIT.java`
- Live driver: `java/peer/src/main/java/dev/nodera/peer/validation/WorkerValidationService.java`

## Testing

- `FallbackRoutingIT`: a spread-out session clears > 90% committee-commit.
- Classification unit tests for each of the four server-lane reasons.
- Cross-region atomicity: a barrier failure leaves both regions untouched.

## Acceptance criteria

1. ✅ Every action is classified into exactly one lane, with the reason recorded.
2. ✅ The server lane commits through the same applier as the committee lane — one write path.
3. ✅ A spread-out session clears > 90% committee-commit.
4. ✅ Cross-region application is atomic across both regions.
5. ⏳ Live: the same soak on a real server with guard counters stable —
   [minecraft 2](../minecraft/Task.2.md).

## Limitations

None owned. The live soak rides **L-45** in
[`minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md).
