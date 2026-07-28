# Engine Task 9 — Validated Redstone + Contraption Ownership Migration

<!-- AI-AGENT-INSTRUCTION: The scheduled-tick queue is CONSENSUS STATE and lives in the region root.
     Never move it to a side structure "for performance" — a delay that does not survive a delta
     boundary is a divergence waiting for a failover. Bump RULES_VERSION and the palette literal
     whenever component behaviour changes, so an old peer refuses rather than diverges. Keep this
     header's status accurate. -->

**Status:** 🚧 IN PROGRESS (palette v4 complete; `RULES_VERSION` is now 6 / `palette.v6` after obsidian + farmland/wheat; contraption migration remains)
**Category:** engine · **Owns:** — (L-26 RETIRED) · **Last audit:** 2026-07-28
**Depends on:** [engine 6](Task.6.md), [engine 7](Task.7.md), [network 3](../network/Task.3.md)
**Consumed by:** [engine 10](Task.10.md), [engine 12](Task.12.md), [minecraft 2](../minecraft/Task.2.md)

---

## Goal

Bring redstone into the validated lane: deterministic signal propagation, scheduled ticks, and piston
block events inside the engine, with the **scheduled-tick queue as part of the region state root**.
Cross-region contraptions are solved the MultiPaper way — **move ownership, do not coordinate the
cross**: every region of a contraption group gets the same primary.

## Status detail

**Palette v2 completed at `RULES_VERSION` 4 / `palette.v4`** (since raised to **6** by the obsidian
and farmland increments — see [`PROGRESS.md`](PROGRESS.md)). The three staged component
groups are green: wire/torch/repeater/comparator/piston, observer + quasi-connectivity + daylight
sensor, and comparator/hopper/note block. The final two components landed 2026-07-25:

- **Pressure plates** — the component that couples the entity lane to the redstone lane. Every other
  source answers to a block or a scheduled tick; a plate answers to *where something is standing*, so
  it only became expressible once entities were validated root state. A plate is pressed while a
  validated entity's block position is the plate's own, emits 15 omni, and releases after 20 ticks
  through the **hashed** scheduled-tick queue. Re-entering re-arms it, so it is a usable repeat
  trigger rather than a stutter. **GHOSTs never press one** — their positions are
  server-authoritative, and letting a non-validated input drive validated state is exactly the hole
  the lane exists to close. A pressed plate is unplaceable, so it cannot be minted.
- **Sticky pistons** — every piston helper became family-aware (`isSticky`, `retractedBaseOf`,
  `extendedBaseOf`, `headBaseOf`), sharing the whole extend path and differing only on retraction,
  where the head pulls the block it was touching back into the vacated cell. An unmovable neighbour,
  a redstone component, or a region border all **fail closed** and the piston still retracts — the
  same discipline as the push side.

**Remaining:** cross-region contraption ownership migration (`BorderSignal` → whole-group migration
with a certificate chain), version-checked `HaloUpdate` halo reads, and live evidence.

## Dependencies

- [engine 6](Task.6.md) / [engine 7](Task.7.md) — the lane and the guard.
- [network 3](../network/Task.3.md) — certificate chains for the migration.
- [engine 8](Task.8.md) — validated entities, required by pressure plates.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Palette v2 (now v4): wire, torch, repeater, comparator, piston, observer, QC, daylight sensor, hopper, note block, pressure plate, sticky piston | ✅ |
| 2 | `ScheduledTickQueue` in the region root (`@Invariant(10)`) | ✅ |
| 3 | Fixed `NeighborUpdateOrder` | ✅ |
| 4 | Piston two-phase `BlockEventEntry` | ✅ |
| 5 | `BorderSignal` — cross-border propagation signalling | ✅ |
| 6 | Whole-group contraption ownership migration + certificate chain | 🚧 |
| 7 | `HaloUpdate` version-checked halo reads | 🚧 |
| 8 | Live evidence (repeater clock, group migration on a real server) | ⏳ → [minecraft 2](../minecraft/Task.2.md) |

## Design

**The scheduled-tick queue must be in the root or nothing works.** A repeater's delay, a plate's
release, a piston's second phase — all are pending future behaviour. If that queue is a side
structure, a delta boundary or a primary failover loses it and the replicas diverge in a way that
only shows up seconds later. Putting it in the hashed root makes "the pending work" part of what the
committee agrees on, which is why `@Invariant(10)`'s negative test (drop the queue from the hash ⇒
divergence must be detected) is the load-bearing assertion.

**Neighbour update order is fixed, not natural.** Vanilla's update order is an implementation detail;
reproducing it exactly is unnecessary, but *agreeing* on it is mandatory. `NeighborUpdateOrder` pins a
canonical order so two replicas resolve the same network to the same state.

**Move ownership, do not coordinate the cross.** A contraption that spans a border could be handled
with a distributed transaction per tick; that is expensive and fragile. MultiPaper's answer — assign
the whole group to one owner — is adopted instead: `BorderSignal` detects the span and the group
migrates as a unit under a certificate chain. A migration is rare; a cross-region tick is not.

**Fail closed at every boundary.** An unmovable neighbour, a redstone component in the push path, or
a region border stops the motion instead of guessing. A guess here mints or destroys blocks.

**Version the palette, not just the code.** `palette.v4` plus `RULES_VERSION` 4 mean a peer on an
older palette **refuses** to validate rather than silently diverging on a component it cannot model.

## Files

- `java/engine/src/main/java/dev/nodera/simulation/rules/{RedstoneRules,PistonRules,PressurePlateRules,ObserverRules,ComparatorRules,HopperRules}.java`
- `java/engine/src/main/java/dev/nodera/simulation/{ScheduledTickQueue,NeighborUpdateOrder}.java`
- `java/engine/src/main/java/dev/nodera/simulation/border/BorderSignal.java`
- Mod-side contraption migration: `java/neoforge-mod/.../contraption/` — [minecraft 2](../minecraft/Task.2.md)

## Testing

- `PressurePlateStickyPistonTest` (7) — everything through the full engine path; each assertion is
  also a root assertion, and the order-independence property is pinned (the same entity set handed in
  reversed list order produces the identical root).
- Repeater-clock determinism over 10k ticks, including the **drop-queue-from-hash negative test**.
- Piston push/pull failure modes: unmovable neighbour, redstone component, region border.
- Comparator container-fill reactivity on every container mutation.
- Planned: group-migration certificate-chain verification; failover mid-piston resumes from the
  committed root.

## Acceptance criteria

1. ✅ Palette v2 complete; `RULES_VERSION` and the palette literal bumped together.
2. ✅ The scheduled-tick queue is in the root and its absence is detectably divergent.
3. ✅ Pressure plates are driven only by validated entities; ghosts cannot press one.
4. ✅ Sticky-piston retraction fails closed at every boundary and still retracts.
5. 🚧 A contraption group spanning a border migrates as a unit, with a verified certificate chain.
6. 🚧 Failover mid-piston resumes from the committed root.
7. ⏳ Live evidence on a real server.

## Limitations

**L-26** (full redstone parity) is **RETIRED** — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
Contraption migration and live evidence are ordinary remaining scope in this task, not a register
row.
