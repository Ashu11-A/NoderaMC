# COMPATIBILITY.md — the mod-compatibility contract (Task 11)

This file is **normative**: it fixes what other mods (and modpacks) can rely on when running
alongside NoderaMC, and what they must not do. Referenced from `docs/Plan.md`; owned by Task 11.
The enforcement mechanism is the interference guard (`MutationGuard` — the single
`setBlockState` choke point on delegated chunks) plus the delegability policy.

## 1. Event ordering

Nodera captures/cancels player actions at `EventPriority.LOW` with `receiveCanceled = false`.
**Protection mods at any earlier priority always win**: if a claims/protection mod cancels a
block event before Nodera sees it, Nodera never captures it and the validated lane never learns
it existed.

## 2. Fake players

Fake players (machine-operated block breakers/placers) never become Nodera actors and never join
committees. Their effects on delegated regions are foreign writes: converted into certified
external deltas (`ServerAuthorityCertificate` reason `EXTERNAL_MUTATION`) in CONVERT mode. A
region with recurring fake-player activity is demoted (`FAKE_PLAYER_ACTIVE`) and runs pure
vanilla — the machines keep working; the region simply is not validated.

## 3. Unknown blocks

Any block outside the Nodera palette makes its region **and its neighbor ring**
(`DELEGABLE_NEIGHBOR_RING`) non-delegable (`UNSUPPORTED_PALETTE` / `NEIGHBOR_UNSUPPORTED`).
Other mods' machines simply keep vanilla semantics — Nodera never simulates a block it does not
understand, and never delegates a region whose boundary a foreign mechanic could bleed across.

## 4. Redstone

The validated lane owns redstone in delegated regions (Task 13/14, palette v3): wire, torch,
repeater, button, piston, observer — including quasi-connectivity (a piston also reads power
through the cell above it, and only re-evaluates when it receives an update, so BUD behavior
emerges from the scheduling model rather than being simulated). Vanilla scheduled ticks for
delegated chunks are cancelled at the source (`LevelTicksMixin`); a contraption whose signals
cross into a vanilla-lane region demotes its whole group (`CONTRAPTION_CROSSES_VANILLA`) and
runs pure vanilla — correct, slower — until redelegation.

## 5. Chunk tickets

Nodera holds delegated chunks loaded with its **own** ticket type (`NODERA_DELEGATED`), added on
lease issue and released on revoke. Foreign tickets (ender pearls, portals, other mods' chunk
loaders) are **never cancelled** — Nodera only adds/removes its own type. Loader-held chunks with
no nearby session player are vanilla lane by policy: the guard does not touch them.

## 6. What other mods must NOT do

1. **Mutate delegated chunks from async threads.** Async world writes are undefined behaviour
   even in vanilla; the guard converts main-thread writes only. Async writes into delegated
   chunks are logged as errors and are outside every compatibility guarantee.
2. **Depend on same-tick visibility of block changes in delegated regions.** Committed effects
   appear 1–2 ticks after their cause (batch + quorum latency — normative, see
   `docs/LIMITATIONS.md` L-16). A mod that reads a delegated block the same tick it caused a
   change may see the pre-commit state.

## 7. What other mods CAN rely on

- Main-thread writes into delegated regions are never silently dropped in CONVERT mode (the
  default): they land, and Nodera folds them into its version chain as certified external
  deltas. STRICT mode (debug/CI: `interference.mode=STRICT`) blocks them loudly.
- Vanilla-lane regions (anything non-delegated) are untouched: no guard, no capture, no
  behavioural change.
- Region demotion is graceful: an in-flight validated batch resolves before the region returns
  to vanilla execution, and re-delegation waits out `DELEGABILITY_COOLDOWN_TICKS`.

## 8. Entity species — validated parity envelopes (Task 15)

Spawn cycles, mob AI, TNT, projectiles, and minecarts become **engine-owned validated state**
for delegated regions (ledger L-7/L-8/L-9). The contract is **player-visible parity, never
NMS bit-parity**: each species is integer/fixed-point behaviour implemented from observable
vanilla, matching what a player sees, never matching a vanilla float trajectory bit-for-bit
(that is what makes the lane tractable and replica-deterministic). The envelope per species:

- **Spawning (L-8):** interval × cap (`SPAWN_INTERVAL_TICKS=20`, `MOB_CAP=8`) reproduces the
  vanilla hostile rate envelope; gating reads committed `LightField` (`< 8`). Engine spawns are
  `GHOST` entities the live lane mirrors like captured mobs.
- **Mob AI (L-7):** seeded wander at a vanilla-ish idle cadence (`AI_INTERVAL_TICKS=10`); steps
  land only on walkable cells. Targeting/combat remain vanilla-side until their increments ship.
- **TNT (L-9):** vanilla fuse (`80`) and radius (`4`); the blast shape is a seeded per-cell
  destruction sphere (`P(destroy)=1−distSq/R²`) — a Nodera crater, not vanilla's ray pattern.
  The blast also knocks back nearby kinematic entities (items/projectiles/minecarts), impulse
  decaying with squared distance; mobs are not yet kinematic. Chain ignition and border fail-closed
  (cross-region blast rides the T13 migration lane).
- **Projectiles (L-9):** vanilla-shaped arc (drag `0.99`, gravity `0.05`) in Q32.32; an opaque
  block stops the shot, and so does a mob within a half-block radius (arrow embedded in a mob).
  Hit detection marches in ≤1-block sub-steps so a thin wall stops even a fast shot (a true
  voxel-DDA face-snap + water slow-down is a later refinement). Applying damage to a struck mob
  is the L-13 lane.
- **Minecarts (L-9):** vanilla top speed (`0.4`); a cart follows the rail graph by connectivity
  (no rail-shape states), powered rails boost, plain rails bleed speed. Slopes/ascent gravity
  and redstone-gated powered rails are not yet modelled.

Unretired species keep the ghost fallback (Task 12) — the world is never broken mid-program. A
region reverts to ghosting a species on divergence alarm (the species-retirement rollback, Task
15 acceptance #4). Entity-kind ordinals are wire-stable: `ITEM`/`GHOST` bytes are unchanged as
new engine-owned kinds (`TNT`/`PROJECTILE`/`MINECART`) are appended.
