# Engine Task 10 — Environment Lane: Random Ticks, Fluids, Fire, Gravity, Lighting, Observer/QC

<!-- AI-AGENT-INSTRUCTION: Every rule in this task consumes the per-tick DeterministicRandom. A rule
     that draws a VARIABLE number of times depending on world state desynchronises the random stream
     across replicas — the classic failure here. Draw a fixed count per opportunity and discard, or
     skip WITHOUT consuming. Assert this in the test, not the review. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS (four of six sub-lanes retired; random ticks and fluids RETIRING)
**Category:** engine · **Owns:** L-1, L-2 · **Last audit:** 2026-07-28
**Depends on:** [engine 8](Task.8.md), [engine 9](Task.9.md), [engine 7](Task.7.md) live half
**Consumed by:** [engine 11](Task.11.md), [engine 12](Task.12.md)

---

## Goal

Retire the environment exclusions: the engine takes ownership of everything the interference guard
currently suppresses in delegated regions. After this task, delegated regions have **living
environments** — grass spreads, crops grow, fire burns, water flows, sand falls, light propagates —
all deterministic, all in the state root.

## Status detail

| Sub-lane | State |
|---|---|
| Gravity (instant-settle) + bounded fire | ✅ RETIRED (L-3) |
| Deterministic lighting as a pure function of committed state (`LightField`) | ✅ RETIRED (L-4) |
| Observer + quasi-connectivity | ✅ RETIRED (L-5) |
| Daylight sensor (`committedWorldTime` context) | ✅ RETIRED (L-6) |
| Random ticks | 🚧 RETIRING (L-1) — engine-owned selection landed; live suppression mixin + farm soak remain |
| Fluids | 🚧 RETIRING (L-2) — finite automaton, interactions, and dense-halo border seeding landed; certified halo delivery + live evidence remain |

**Random ticks (L-1).** `RandomTickRules` runs on the engine tick hook with vanilla-shaped selection:
three draws per eligible 16³ section per tick from the per-tick `DeterministicRandom`, canonical
column order, and state-derived section eligibility — an ineligible section skips **without consuming
randomness**, so replicas skip identically. Grass MVP semantics are in: smothered grass dies to dirt;
one 3×3×3 spread attempt per selection onto dirt-with-air-above. A 200-tick soak over a grass/dirt
checkerboard produces identical roots on 3 replicas *with the lane actively spreading*. Remaining:
fire and crops, the live random-tick suppression mixin, and a farm soak with the suppression counter
at zero.

**Fluids (L-2).** `FluidRules` is a per-cell automaton riding the [task 9](Task.9.md) hashed
scheduled-tick queue, so pending fluid updates are consensus state and survive delta boundaries. The
level is encoded **in the block id** (palette 52–63: water source plus flows 1–7, lava source plus
flows 1–3; sources placeable, flows minted). Desired state is a pure neighbourhood function —
falling first, so a hanging column never pyramids; horizontal contribution requires the neighbour to
*sit on solid*; support loss decays flows to air, so breaking the source drains the network. Vanilla
cadence (water 5, lava 30) and reach (water 7, lava 3); water outcompetes lava deterministically;
fluids do not block pistons (the push destroys them); spread across a border emits
`BorderSignal.Kind.FLUID` — **no halo writes**. A backed halo scans the complete ownership-facing
16×16 face for either representation, skips diagonal columns, and indexes scheduled positions once.
At the standard 8×8×24 shape, dense work is bounded at 4×8×24×256 = 196,608 candidates instead of
36×24×1,024 = 884,736 four-face probes. Cadence comes from `desiredAt`'s winning fluid, not whichever
source was visited first. `CrossRegionFluidTest` proves blocked uniform targets do not hide open
cells, dense scans remain side-only, mixed water/lava uses water cadence, and the off-corner dense
case schedules the exact same tick and root on two replicas. `RULES_VERSION` stays at the issue's
6→7 increment; the palette remains `palette.v6`. Remaining: certified halo delivery and live
evidence tracked by L-2. L-51 is RETIRED.

## Dependencies

- [engine 9](Task.9.md) — the hashed scheduled-tick queue fluids ride.
- [engine 8](Task.8.md) — entities, for gravity-block and item interactions.
- [engine 7](Task.7.md) live half — the suppression mixins this task's exits delete.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `GravityRules` — instant settle v1 | ✅ |
| 2 | `FireRules` — bounded spread and burnout | ✅ |
| 3 | `LightField` in the root + incremental deterministic lighting | ✅ |
| 4 | `ObserverRules` + quasi-connectivity (palette v3) | ✅ |
| 5 | `DaylightSensorRules` + `committedWorldTime` as a context input | ✅ |
| 6 | `RandomTickRules` | 🚧 |
| 7 | `FluidRules` | 🚧 |
| 8 | Live suppression-counter-zero soaks | ⏳ |

## Design

**Lighting is the load-bearing sub-lane.** Deterministic lighting is famously hard because vanilla's
propagation is incremental and order-dependent. Nodera's answer is to make `LightField` a **pure
function of committed state** held in the root: two replicas compute the same field because they hash
the same world, not because they replayed the same event order. Everything that depends on light —
spawning, daylight sensors, grass — then inherits determinism for free.

**Committed world time is a context input, not region state.** `committedWorldTime` rides
`RegionExecutionContext` and is root-determining, but it is *agreed* rather than *owned*. That is why
[task 12](Task.12.md)'s command subset **refuses** `/time set`: mutating a context input mid-batch
would leave one replica's context disagreeing with the others' for the rest of that batch.

**Encode the fluid level in the block id.** The alternative — a side table of levels — is another
structure that can disagree with the palette across a delta boundary. Putting the level in the id
makes the state root carry it automatically and makes "flows are minted, sources are placeable" a
palette rule rather than a validation special case.

**Falling before spreading is what stops the pyramid.** A hanging water column that spread
horizontally before falling would build a pyramid that vanilla never produces. The ordering is
therefore part of the rule, not an optimisation.

**Skipping must not consume randomness.** The subtle failure mode in this whole task: if an
ineligible section consumed a draw on one replica and not another, every subsequent draw would be
misaligned and the roots would diverge with no obvious cause. Eligibility is state-derived and the
skip is free.

## Files

- `library/java/engine/src/main/java/dev/nodera/simulation/rules/RandomTickRules.java` — random-tick selection, grass spread, **fire** (fuel-bounded spread/burnout lives in `applyFireTick` here, not a separate `FireRules`), and crop growth.
- `library/java/engine/src/main/java/dev/nodera/simulation/rules/FluidRules.java` — the finite fluid automaton + lava/water interactions.
- `library/java/engine/src/main/java/dev/nodera/simulation/rules/GravityRules.java` — instant-settle gravity.
- Observer + quasi-connectivity and the daylight sensor live in `RedstoneRules.java` (not separate files): `RedstoneRules.isObserver/observer*/daylightOutput` and the comparator fill signal.
- `library/java/engine/src/main/java/dev/nodera/simulation/lighting/LightField.java` — deterministic sky/block light as a pure function of committed state.

## Testing

- `RandomTickRulesTest` — direct semantics pins plus the acceptance core: a 200-tick soak over a
  grass/dirt checkerboard with identical roots on 3 replicas while the lane actively spreads; a
  nothing-tickable world leaves its delta untouched.
- `FluidRulesTest` (6, full engine path) — level-per-hop and finite reach with replica-identical
  roots; source-break drain; fall-before-spread and pooling; lava reach 3; mint protection; border
  signal.
- `CrossRegionFluidTest` (9) — complete uniform-face behavior, bounded ownership-facing dense scans,
  diagonal rejection, winning-fluid cadence, receiver authority, halo-root input, and exact
  two-replica dense-section off-corner seeding.
- `LightFieldTest`, `GravityFireRulesTest`, `ObserverQcTest`, `DaylightSensorTest`.
- Pending: farm soak with the live suppression counter at zero; cross-region fluid spread.

## Acceptance criteria

1. ✅ Gravity, fire, lighting, observer/QC, and the daylight sensor are deterministic and in the root.
2. 🚧 Engine-owned random ticks with identical roots on 3 replicas, and the live suppression counter
   deleted (**L-1** exit).
3. 🚧 Water and lava spread deterministically across replicas, including cross-region via the
   migration lane (**L-2** exit).
4. ⏳ The interference probe rate is ≈ 0 on a normal world with the full palette.

## Limitations

- **L-1** — random ticks (RETIRING).
- **L-2** — fluids (RETIRING).
- L-3, L-4, L-5, L-6, L-51 — RETIRED, see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
