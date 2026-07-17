# Task 15 — Deterministic Entity Simulation: Mob AI, Spawning, Projectiles, Ghost Retirement

**Phase:** 7 (parity program) · **Depends on:** Tasks 12, 14 · **Modules:** `core`,
`simulation`, `protocol`, `neoforge-mod`, `integration-tests`

## Goal

Retire the ghost lane species by species (ledger L-7, L-8, L-9, L-24): mobs, spawning,
projectiles, TNT, and rails/minecarts become engine-simulated, validated, part of the
region root. The strategy that makes this tractable — locked in Task 12's staging note —
is **Nodera-defined behaviour, not an NMS port**: integer/fixed-point AI implemented
from observable vanilla behaviour, matching the player experience, never matching
vanilla float trajectories bit-for-bit. Ghosts remain the working fallback for every
species not yet retired, so the world is never broken mid-program.

Retires: L-7 (mob AI per species), L-8 (spawning), L-9 (projectiles/TNT/rails),
L-24 (`mobCapture` staging).

---

## Folder structure (additions)

```
core additions:
├── state/EntityKind.java            # grows: MOB, PROJECTILE, TNT, MINECART (GHOST remains
│                                    #   for unretired species)
├── state/MobState.java              # species id, health, FixedVec3 pos/vel, ai memory blob
│                                    #   (canonical: goal id, target ref, path cursor, timers)
└── action/  (append)                # AttackEntityAction, UseItemOnEntityAction (player-driven)

simulation additions:
├── entity/
│   ├── MobRules.java                # dispatch per ValidatedSpecies
│   ├── ai/
│   │   ├── IntPathfinder.java       # integer A* on block grid; fixed tie-break order
│   │   │                            #   (f, h, insertion seq); bounded search budget —
│   │   │                            #   budget exhaustion is deterministic too
│   │   ├── GoalSelector.java        # seeded utility selection: wander / graze / flee /
│   │   │                            #   chase / attack — priorities + DeterministicRandom
│   │   └── Sensors.java             # line-of-sight (integer DDA), hearing radius, light
│   │                                #   (reads T14 LightField), all from committed state
│   ├── SpawnCycleRules.java         # per-batch spawn attempts: cap check, seeded position
│   │                                #   draws, light/surface predicates — vanilla-envelope rates
│   ├── ProjectileRules.java         # arrows/pearls/snowballs: fixed-point ballistics,
│   │                                #   integer raycast hit detection
│   ├── TntRules.java                # fuse in root; deterministic blast (seeded ray pattern,
│   │                                #   fixed block-destruction order) — cross-region blast
│   │                                #   via T13 group migration
│   └── RailRules.java               # cart kinematics on rail graph (fixed-point), powered
│                                    #   rails read redstone state (T13)
└── (RuleSet phase order grows)      # entity tick phase expands: sensors → goals → path →
                                     #   move → interact, fixed order per entity, entities
                                     #   iterated by NetworkEntityId

neoforge-mod additions:
├── dedicated/entity/
│   ├── SpeciesRetirement.java       # config ValidatedSpecies set; per-region: species in set
│   │                                #   ⇒ engine-owned; else ghost (T12 pipeline continues)
│   ├── GhostShareMetrics.java       # % entities per region still ghosted — the burn-down gauge
│   └── VisualReplicator.java        # engine entities → vanilla display entities for rendering
│                                    #   (applier-scope spawn/move of the visual counterparts)
└── COMPATIBILITY.md updates         # behaviour-envelope notes per retired species
```

## Class relationships

```
EntityRuleSet (T12) ── grows ──► MobRules / ProjectileRules / TntRules / RailRules
    dispatch by EntityKind + ValidatedSpecies; GHOST entities skip engine tick entirely
    (their updates keep arriving as external deltas — T12 pipeline untouched)

MobState.aiMemory — determinism rule:
    ALL AI state that influences behaviour lives in the hashed MobState (goal, target,
    path cursor, cooldowns). Nothing cached outside the root. A replica joining
    mid-chase computes the same next step from the snapshot alone.

IntPathfinder — the hard part, bounded on purpose:
    grid A* with integer costs, fixed neighbor order, deterministic tie-breaks,
    per-batch node budget (exhaustion ⇒ deterministic partial path + retry timer in
    aiMemory). No floats, no time-sliced background pathfinding.

SpeciesRetirement rollout per species S:
    ghosts(S) ──config flip──► engine(S) per region on next lease renewal
    rollback path: flip back to ghost on divergence alarm (spot-check FATAL from T7)
    GhostShareMetrics → /nodera entities: burn-down visible live

Spawning (SpawnCycleRules):
    per-batch attempt count + positions from DeterministicRandom(context, "spawn", tick);
    predicates read engine state only (LightField, block below, cap by region entity
    count). Despawn: distance/timer rules in root. Player proximity comes from the
    committed player-position feed (server-authoritative until T16, then validated).
```

## Implementation details

- **Species ladder** (each rung = its own PR + fixture set + ledger tick):
  1. Item-adjacent warmup: falling-block entity (upgrades T14 gravity to
     entity-accurate), primed TNT.
  2. Passive: chicken → sheep → cow (wander, graze, flee, breed — breeding items via
     player actions).
  3. Neutral/hostile: zombie → skeleton (chase, melee; skeleton adds `ProjectileRules`
     integration + strafe).
  4. Carts + powered rails (redstone interaction via T13 state).
  Creepers close the ladder (blast = TNT rules + chase AI). Everything else stays
  ghosted until ported — the ladder order is config, the mechanism is species-agnostic.
- **Combat boundary**: mob→mob and mob→block damage fully in-lane. Mob→player damage
  crosses into player state, which is server-authoritative until Task 16 — emitted as
  a `PlayerDamageIntent` in the delta, applied by the applier vanilla-side (one-way
  effect, same pattern as T12 inventory credits; ledger L-13 owns the full closure).
- **Vanilla-envelope testing, not bit-parity**: per species, statistical acceptance —
  spawn rates, wander radii, chase speeds within documented tolerance of vanilla
  measurements. Bit-parity with NMS is explicitly a non-target (that's what makes this
  task feasible); player-visible fidelity is the bar, `COMPATIBILITY.md` documents each
  envelope.
- **Rendering**: engine entities exist in region state; `VisualReplicator` maintains
  vanilla display counterparts (applier scope, so the T11 guard blesses them). After
  T16's local-replica view, clients render straight from replica state and the
  replicator becomes server-side legacy.

## Implementation details — server peer

- `SpeciesRetirement` + `GhostShareMetrics` on the coordinator; retirement is per
  species **and** per region (lease-renewal boundary), so a bad rollout is contained
  and reversible.
- Cross-region mob chase: entity transfer protocol (T12c) already moves entities;
  `aiMemory` travels inside `MobState` — chase continues seamlessly under the new
  region's committee (IT covers a zombie chasing a player across a border).
- Spot-check + equivocation machinery (T7) unchanged — entity state is just more bytes
  under the same root.

## Acceptance criteria

1. Per-species determinism fixtures: 3 replicas, scripted scenarios (wander/graze/
   breed/chase/shoot), 10k ticks ⇒ identical roots; `aiMemory`-in-root negative test
   (drop it from the hash ⇒ divergence on first goal change).
2. **L-8 exit**: spawn cycles across replicas identical; rates within the documented
   vanilla envelope over a 1-hour soak.
3. **L-9 exit**: arrow/pearl ballistics + hit detection fixtures; TNT blast determinism
   incl. cross-region blast via group migration (`@Invariant(11)`); cart loop on
   powered rails deterministic.
4. Ghost retirement mechanics: flip a species live ⇒ regions upgrade on lease renewal;
   forced divergence alarm ⇒ automatic rollback to ghost, no corruption;
   `GhostShareMetrics` reaches 0 for every laddered species on the reference world
   (**L-7/L-24 exit**).
5. Cross-region chase IT: zombie pursues player across a region border — transfer +
   continued chase, roots consistent.
6. Mob griefing parity: creeper hole via validated blast matches the engine root and
   the applied world exactly (no interference-pipeline involvement for validated
   species — counter asserted 0 for them).
7. `LIMITATIONS.md` L-7, L-8, L-9, L-24 flipped RETIRED; `COMPATIBILITY.md` envelope
   notes committed per species.
