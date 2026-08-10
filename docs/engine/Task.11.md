# Engine Task 11 — Deterministic Entity Simulation: Mob AI, Spawning, Projectiles, Ghost Retirement

<!-- AI-AGENT-INSTRUCTION: The strategy here is Nodera-DEFINED behaviour, not an NMS port. Do not
     copy vanilla AI code or try to match it instruction-for-instruction; match the OBSERVABLE
     player experience within a documented statistical envelope, in integer/fixed-point arithmetic.
     A species is retired only when its ghost share reaches zero in soak. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS (engine-owned mobs now remember an intention and route to it; L-8 + L-24 retired; per-species retirement of L-7 remains)
**Category:** engine · **Owns:** L-7 (L-8, L-24 RETIRED) · **Last audit:** 2026-08-10
**Depends on:** [engine 8](Task.8.md), [engine 10](Task.10.md)
**Consumed by:** [engine 12](Task.12.md)

---

## Goal

Retire the ghost lane **species by species**: mobs, spawning, projectiles, TNT, and rails/minecarts
become engine-simulated, validated, and part of the region root. The strategy is **Nodera-defined
behaviour, not an NMS port** — integer/fixed-point AI implemented from observable vanilla behaviour,
matching the player experience, never matching vanilla's internals.

## Status detail

**Spawning (L-8) is RETIRED.** The cycle landed and its exit clause — *engine light + seeded RNG
matching the vanilla rate envelope* — is asserted in `SpawnRulesTest` (see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)). `SpawnRules` runs on the engine tick hook: every 20
ticks one attempt draws a random owned column from the per-tick `DeterministicRandom`, collects the
column's *actual* standing cells (solid floor plus two air headroom — roof and cave floor are both
candidates), draws among them, and spawns only when combined `LightField` light < 8 and the region's
ghost population is under a cap of 8. Interval × cap is the documented vanilla rate envelope. Spawns
enter the **root** as `GHOST` zombies with deterministic ids and a 6000-tick despawn horizon. A
2000-tick dark-shelter soak stays under the cap, spawns **every** mob inside the shelter (never on
the lit roof), and produces replica-identical roots; a lit platform spawns zero. Remaining (live
mirroring half, not engine scope): passive species, per-species rates, mod-side mirroring of engine
spawns, the vanilla spawn-suppression mixin, and live evidence.

**Mob AI (L-7) is RETIRING.** `MobAiRules` is the first per-species retirement step — **mobs move
from the validated root, not from the server's mob.** Every `AI_INTERVAL_TICKS` (10) region ticks each
mob gets one decision opportunity, in canonical entity-id order. A step lands only on a walkable cell
(solid floor, two air headroom, ±1 climb/drop, block-centred result), so the root always holds a legal
stance, and region borders fail closed. Mobs past their despawn horizon are removed every tick, so the
population breathes like vanilla's despawn cycle, deterministically. A 2400-tick dark-shelter soak ends
with **every** mob on a walkable cell with `ageTicks > 0` and replica-identical roots. The `mobCapture`
default flip shipped as **L-24 RETIRED** (the species-default capture landed; `e2e-mobs.sh` G2a/G2b
green).

**Mobs remember, and they route (2026-08-10).** The wander MVP could not decide anything that
outlived one decision — next interval it had forgotten why it moved — so a "wandering" mob jittered
around its spawn instead of going anywhere, and no amount of tuning could change that: the intention
had nowhere to live. It lives in the root now.

- `MobState` is the canonical `EntityKind.MOB` payload: vitals **plus** an `AiMemory` triple
  `(goal, untilTick, destination)`. An engine-owned mob adopts a wander goal — a destination up to
  `WANDER_RADIUS` (6) blocks away, snapped to the nearest legal stance within `WANDER_CLIMB` (3) —
  holds it for at most `WANDER_BUDGET_TICKS` (200), and advances one block along it per decision.
- `IntPathfinder` chooses that block: bounded integer A* over walkable stances, node budget
  `DEFAULT_NODE_BUDGET` (192), heuristic `max(|Δx| + |Δz|, |Δy|)`, no `double` anywhere. Only the
  **first** step is returned and the route is re-derived every decision, so a corridor walled up
  between two decisions simply stops yielding a step and the mob gives the goal up rather than
  holding a stale plan in the hashed root.
- The tie-break is the contract, not an implementation detail. Shortest routes on a Minecraft grid
  are almost never unique, so the open set is ordered by `(f, g descending, position)` with position
  in `NBlockPos`' canonical `(y, z, x)` order — a **total** order, which is what makes
  `PriorityQueue.poll()` fully determined despite a heap's own tie behaviour being unspecified. An
  A* with a hash-ordered open set would return a different, equally short route on two replicas,
  walk their mobs to different blocks, and part their roots with nothing logged anywhere.
- A GHOST keeps the memoryless one-block wander. Its payload belongs to the mirroring lane, not to
  the engine, so the engine does not invent an intention for an entity whose authority is elsewhere
  — **giving a species memory is exactly what retiring it from GHOST to MOB means.**

The draw count is fixed *per entity kind* (MOB draws `DRAWS_PER_DECISION` = 2 and discards what its
branch did not need; GHOST draws 1) and kind is hashed root state, so two replicas holding the same
root necessarily draw the same number of times in the same order. What is forbidden — and what the
lane has never done — is a count that varies with the branch a mob *chose*.

Remaining: targeting, combat goals, per-species retirement, live evidence. See
[§The remaining lane](#the-remaining-lane).

**Combat vitals are in.** `EntityKind.MOB` carries its vitals in the leading fields of the
`MobState` payload; `MobCombatRules` routes every damage source (arrow strike flat 5, TNT blast with
linear integer falloff — the dead take no knockback), and health ≤ 0 removes the entity, so death is
committed replica-identical state. Damage uses `MobState#withHealth` rather than rebuilding the
payload, because being hit is not a reason to forget where you were going. GHOST vitals stay
server-authoritative: shoved, never wounded.

## Dependencies

- [engine 8](Task.8.md) — the entity root and the ghost lane this task retires.
- [engine 10](Task.10.md) — `LightField`, which spawning and AI both read.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `SpawnRules` — deterministic spawn cycles in the vanilla rate envelope | 🚧 |
| 2 | `MobAiRules` — decision draws, walkable-cell movement, despawn horizon | 🚧 |
| 3 | `MobCombatRules` + `EntityKind.MOB` vitals in the root | ✅ |
| 4 | `IntPathfinder` — integer A* | 🚧 (search + tie-break + budget landed; targeting/flee goals remain) |
| 5 | Projectile, TNT, and rail/minecart rules | 🚧 |
| 6 | Per-species retirement ladder + ghost-share metric | ⬜ (defined below, unbuilt) |
| 7 | Vanilla spawn-suppression mixin + live evidence | ⏳ → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Nodera-defined, not vanilla-ported.** Porting NMS AI would import float math, wall-clock-ish
tie-breaks, and hash-ordered iteration — three determinism breaks in one move — and would tie the
engine to a Minecraft version. Implementing from *observable* behaviour keeps the arithmetic integer,
keeps the code ours, and turns "is it right?" into a statistical question about the player experience
with a documented envelope, which is a question a soak test can answer.

**A fixed decision count per opportunity.** The single most important constraint in the whole task: a
mob that draws once when idle and three times when moving desynchronises the random stream. Every
opportunity draws the same number of times and discards what it does not use. The count may depend on
values that are already in the hashed root (entity kind does), never on the branch the mob chose.

**The intention is hashed state.** An AI that reads only the current root can decide nothing that
outlives one decision. The moment a decision is kept anywhere *other* than the committed state, two
replicas holding the same root can legally disagree about what a mob does next, and the disagreement
is invisible until their roots part — the exact class of failure `@Invariant(10)` exists to make
impossible. So `AiMemory` is a field of the hashed entity payload: replicas either agree about a
mob's intention or they have already failed the ordinary root comparison. `CombatStateRootTest`
asserts this from both sides, including the negative — dropping the memory back out of the payload
must move the root.

**A destination, never a stored path.** A path in the root would grow the hashed state by its own
length and would have to be re-validated against every block change under it. Holding only the
destination keeps the root fixed-width and makes the route a pure function of the current world.

**Goal codes, not enum ordinals.** `GOAL_NONE`/`GOAL_WANDER` are explicit Nodera codes. An ordinal
would make the declaration order of a Java enum part of the consensus contract, so inserting a goal
in the middle of the list would silently restate every committed mob.

**The pathfinder's tie-break is part of the contract.** See §Status detail; the short version is that
"which of the equally short routes" is the answer, not a detail of it, and an unordered open set
answers it differently on different replicas with nothing logged.

**Canonical id order.** Mobs are processed in canonical entity-id order, not iteration order, so two
replicas that hold the same set process it identically.

**The root always holds a legal stance.** A step is committed only onto a walkable cell. The
alternative — commit the move and correct it next tick — puts an illegal state into a hashed root,
where every replica must reproduce the illegality identically to stay in agreement.

**Retirement is measured, not declared.** A species is retired when its **ghost share reaches zero**
in soak: the engine is producing all of that species' behaviour and none is being mirrored from the
server. A forced-divergence alarm rolls a species back to ghost rather than letting it diverge.

## Migration

`RULES_VERSION` moved **7 → 8** with `MobState`, because every `EntityKind.MOB` in every region now
hashes differently from a version-7 build. This is a **root-shape change**, which is a consensus
break for worlds that were already shared, and the engine is built to refuse rather than diverge:

- **A mixed-version committee cannot form.** `FlatWorldRegionEngine`'s constructor
  (`library/java/engine/src/main/java/dev/nodera/simulation/engine/FlatWorldRegionEngine.java:80`,
  `:118`) throws when handed a `rulesVersion` or `registryFingerprint` that is not this build's, and
  `MobAiRules.semanticFingerprint()` is now mixed into `FlatWorldRules.registryFingerprint()` — so
  two builds that spawn the same blocks but move their mobs differently no longer agree to validate
  for each other. A peer on 7 and a peer on 8 fail to seat a shared region instead of committing
  two different roots.
- **An existing world's entity lane does not activate.** A save whose certified genesis pins
  `rulesVersion = 7` reaches that constructor through
  `LiveEntityLaneSession.open` (`endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/LiveEntityLaneSession.java:88-91`).
  The throw is contained — the bootstrap runs on its own daemon thread inside
  `NoderaHost`'s `catch (RuntimeException | LinkageError)`
  (`endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaHost.java:1399-1401`) — so the
  world still loads and plays as vanilla, and the log carries
  `Nodera: entity lane bootstrap failed: java.lang.IllegalArgumentException: rulesVersion 7 does not
  match FlatWorldRules.RULES_VERSION 8`. **Silent** divergence is what this must never be, and it is
  not; a refused lane is the designed outcome.
- **The recovery is a re-mint.** Deleting the world's `nodera/entity-lane` directory and its
  certified genesis makes the next share mint genesis under version 8. That changes `genesisRoot`,
  and therefore `worldId` (`EntityLaneBootstrap.genesis`,
  `peer/src/main/java/dev/nodera/peer/validation/EntityLaneBootstrap.java:68-81`), so the world
  re-announces under a new tracker identity; `NoderaHost`'s legacy-worldId reconciliation is what
  keeps that from orphaning the save.
- **No wire tag moved.** `MobState` is the *opaque payload* of an already-framed
  `PersistedEntityState`, exactly like `ItemEntityRules.payload`. No `MessageCodec` kind, no
  `TypeTags` entry and no `fixtures/wire/` golden changes, so deployed trackers, rendezvous and
  relays are unaffected — the compatibility boundary here is entirely between engines.

There is no in-place upgrade path and there deliberately is not one: rewriting every committed mob's
payload would have to be done identically by every replica *before* any of them executed a tick, and
a migration that runs at different moments on different peers is the divergence it was meant to
avoid.

## The remaining lane

What this increment did **not** do, in the order the next increment should do it — later steps are
meaningless without earlier ones.

| Step | Deliverable | Why it comes here |
|---|---|---|
| 1 | **`Sensors`** — pure reads off the region view plus halo: nearest player, nearest mob of a species, line of sight, light at a stance | Targeting needs a *query*, and today `IntPathfinder.isWalkable` is the only one. Every later goal is a sensor plus a destination. |
| 2 | **`GoalSelector`** — a fixed-draw table of `(priority, condition, goal)` replacing the inline `adopt` | Once there are three goals rather than one, the fixed-draw discipline stops being expressible inline. It must stay a *fixed* draw count per opportunity. |
| 3 | **Targeting + combat goals** — pursue, melee at `MobCombatRules.MELEE_REACH`, flee | The player-visible half of "AI is validated". Melee needs the player lane's actor identity, so it lands with [engine 12](Task.12.md). |
| 4 | **`UseItemOnEntityAction`** — an append-only wire tag | The only part of this task that touches a frozen contract. Follow the eleven-place append checklist (`AGENTS.md` §Frozen contracts, `docs/README.md` §4.4) including the `MessageTypes` authorisation row, the Rust mirror in `library/rust/nodera-codec` (`tests/tag_mirror.rs`, `tests/fixtures.rs`) and a regenerated `fixtures/wire/` golden — never hand-edited. |
| 5 | **`GhostShareMetrics`** in `dev.nodera.diagnostics` | It *measures* steps 1–3, so it cannot come before them. It belongs in `diagnostics`, **not** `simulation`: it counts, and counters near hashed state are how determinism dies. |
| 6 | **`SpeciesRetirement`** — a declarative table `entity-type id → VALIDATED \| GHOST`, consumed by `EntityRuleSet` and by `EntityCaptureBridge.mobCaptureSpecies` | The ladder itself. Retire one species at a time, each with its own soak. |
| 7 | **The soak** — a `mobs-ai` live scenario | L-7's exit clause. Blocked today: live scenarios cannot run on the build box (issue #266 — the harness binds the product's own default port, which the installed app holds). |

### What "ghost share" means

L-7's exit clause is *"ghost share = 0 for every shipped species in soak"*, and the term has never
been defined anywhere, which is why no one can tell whether the row is close. It is defined here:

> **Ghost share**, for a species *s* over a soak window *W*, is the fraction of that species'
> **state transitions in the committed root** during *W* that were **not produced by an engine
> rule** — i.e. that arrived as a mirrored capture from the vanilla server rather than out of
> `MobAiRules`/`MobCombatRules`/`SpawnRules` re-execution.
>
> Numerator: transitions of entities with `typeId == s` attributable to a capture path
> (`EntityCaptureBridge`) in the committed deltas of *W*. Denominator: all committed transitions of
> entities with `typeId == s` in *W*. Both are counted from **committed deltas**, never from live
> server state, so the number is the same on every replica and can be asserted by a scenario rather
> than eyeballed.

Three consequences worth stating, because each one has bitten a similar metric before:

- **Zero is only meaningful with a non-zero denominator.** A species with no committed transitions
  in the window scores 0/0. The metric must report the denominator alongside the share, and a soak
  that produced no transitions of *s* is a skipped assertion, not a green one.
- **It is per species, not per region or per world.** A world with one retired species and five
  ghost species must show 0 for the retired one, which an aggregate figure cannot express.
- **Retirement is measured, not declared.** Flipping `SpeciesRetirement` to `VALIDATED` is what
  makes the share *able* to reach zero; it is not evidence that it did.

## Files

- `library/java/engine/src/main/java/dev/nodera/simulation/entity/{SpawnRules,MobAiRules,MobCombatRules,MobState,IntPathfinder,ProjectileRules,TntRules,RailRules}.java`
- `library/java/engine/src/main/java/dev/nodera/simulation/rules/{EntityRuleSet,FlatWorldRules}.java`
  — `RULES_VERSION` and the registry fingerprint the AI lane now contributes to.

## Testing

- `SpawnRulesTest` — 2000-tick dark-shelter soak: population ≤ cap, every spawn inside the shelter,
  replica-identical roots; lit platform spawns zero; empty world spawns zero.
- `PathfindingTest` (7) — the route goes **round** a two-high wall by the shortest detour and never
  onto a wall cell; equal-length routes are broken by canonical `(y, z, x)` order (pinned as an
  exact expected step); the same room built in the opposite write order gives the same route for all
  121 destinations; a boxed-in mob gets no route and no exception; a destination that is not a
  stance is refused before the search starts; the node budget is a hard ceiling and a non-positive
  budget throws; standing on the destination takes no step.
- `MobAiRulesTest` (7) — 2400-tick soak with every mob on a walkable cell and replica-identical
  roots; **six consecutive decisions walk six blocks towards one destination** (which a memoryless
  mob cannot do); being hit does not erase where a mob was going; a goal whose route is walled up is
  given up without moving; the payload round-trips and refuses anything else; direct-state wander
  eventually steps; the despawn horizon fires exactly on time.
- `CombatStateRootTest` (2 new) — the negative determinism pair: identical vitals with different AI
  memory give **different** roots, and dropping the memory back out of the payload moves the root.
- `MobCombatTest` (5) + vitals assertions in `SpawnRulesTest`. The sixth case asserted
  `MobCombatRules.vitalsPayload`/`decodeVitals`/`Vitals`, which this increment **deleted**:
  `MobState` became the one definition of the MOB payload, so a second encoder-decoder pair beside
  it is how the two come to disagree, and once `damage` routed through `MobState` the pair had no
  production caller at all (`:peer:structureReport` `unreachable_methods` 112 → 113 caught it).
- Planned: per-species determinism plus vanilla-envelope statistical soaks; a forced divergence
  alarm that auto-rolls back to ghost; the `mobs-ai` live soak.

## Acceptance criteria

1. ✅ Deterministic spawn cycles match the vanilla rate envelope (**L-8** exit — `SpawnRulesTest`).
2. 🚧 Per-species retirement: ghost share = 0 for every shipped species in soak (**L-7** exit). The
   term is now defined — see [§What "ghost share" means](#what-ghost-share-means) — and the
   remaining steps are enumerated in [§The remaining lane](#the-remaining-lane). Nothing measures it
   yet, and the soak that would read it is blocked on issue #266.
3. ✅ `mobCapture` default flips per species as validation ships (**L-24** exit — `e2e-mobs.sh` G2a/G2b green).
4. ⏳ Forced divergence auto-rolls back to ghost.
5. ⏳ Live evidence on a real server.

## Limitations

- **L-7** — mob AI (RETIRING).
- **L-8** — spawn cycles — **RETIRED 2026-07-25** (see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).
- **L-24** — `mobCapture` default-off — **RETIRED 2026-07-26** (see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

See [`LIMITATIONS.md`](LIMITATIONS.md).
