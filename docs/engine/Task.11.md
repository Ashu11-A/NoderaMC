# Engine Task 11 — Deterministic Entity Simulation: Mob AI, Spawning, Projectiles, Ghost Retirement

<!-- AI-AGENT-INSTRUCTION: The strategy here is Nodera-DEFINED behaviour, not an NMS port. Do not
     copy vanilla AI code or try to match it instruction-for-instruction; match the OBSERVABLE
     player experience within a documented statistical envelope, in integer/fixed-point arithmetic.
     A species is retired only when its ghost share reaches zero in soak. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS (first species' behaviour originates in the engine; L-8 + L-24 retired; per-species retirement of L-7 remains)
**Category:** engine · **Owns:** L-7 (L-8, L-24 RETIRED) · **Last audit:** 2026-07-28
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

**Mob AI (L-7) is RETIRING.** `MobAiRules` is the first per-species retirement step — **ghost zombies
now move from the validated root, not from the server's mob.** Every 10 ticks each ghost draws **one**
decision in canonical id order: idle (3/8) or a one-block horizontal step in one of four directions.
The decision count is fixed regardless of branch, so the random stream stays aligned across replicas.
A step lands only on a walkable cell (solid floor, two air headroom, ±1 climb/drop, block-centred
result), so the root always holds a legal stance, and region borders fail closed. Ghosts past their
despawn horizon are removed every tick, so the population breathes like vanilla's despawn cycle,
deterministically. A 2400-tick dark-shelter soak ends with **every** ghost on a walkable cell with
`ageTicks > 0` and replica-identical roots. Remaining: targeting, pathfinding, combat, per-species
retirement. The `mobCapture` default flip shipped as **L-24 RETIRED** (the species-default capture
landed; `e2e-mobs.sh` G2a/G2b green).

**Combat vitals are in.** `EntityKind.MOB` carries `[u16 health][u16 maxHealth]` in the root;
`MobCombatRules` routes every damage source (arrow strike flat 5, TNT blast with linear integer
falloff — the dead take no knockback), and health ≤ 0 removes the entity, so death is committed
replica-identical state. GHOST vitals stay server-authoritative: shoved, never wounded.

## Dependencies

- [engine 8](Task.8.md) — the entity root and the ghost lane this task retires.
- [engine 10](Task.10.md) — `LightField`, which spawning and AI both read.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `SpawnRules` — deterministic spawn cycles in the vanilla rate envelope | 🚧 |
| 2 | `MobAiRules` — decision draws, walkable-cell movement, despawn horizon | 🚧 |
| 3 | `MobCombatRules` + `EntityKind.MOB` vitals in the root | ✅ |
| 4 | `IntPathfinder` — integer A* | ⬜ |
| 5 | Projectile, TNT, and rail/minecart rules | 🚧 |
| 6 | Per-species retirement ladder + ghost-share metric | ⬜ |
| 7 | Vanilla spawn-suppression mixin + live evidence | ⏳ → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Nodera-defined, not vanilla-ported.** Porting NMS AI would import float math, wall-clock-ish
tie-breaks, and hash-ordered iteration — three determinism breaks in one move — and would tie the
engine to a Minecraft version. Implementing from *observable* behaviour keeps the arithmetic integer,
keeps the code ours, and turns "is it right?" into a statistical question about the player experience
with a documented envelope, which is a question a soak test can answer.

**A fixed decision count per opportunity.** The single most important constraint in the whole task: a
mob that draws once when idle and three times when moving desynchronises the random stream. Every
opportunity draws the same number of times and discards what it does not use.

**Canonical id order.** Mobs are processed in canonical entity-id order, not iteration order, so two
replicas that hold the same set process it identically.

**The root always holds a legal stance.** A step is committed only onto a walkable cell. The
alternative — commit the move and correct it next tick — puts an illegal state into a hashed root,
where every replica must reproduce the illegality identically to stay in agreement.

**Retirement is measured, not declared.** A species is retired when its **ghost share reaches zero**
in soak: the engine is producing all of that species' behaviour and none is being mirrored from the
server. A forced-divergence alarm rolls a species back to ghost rather than letting it diverge.

## Files

- `library/java/engine/src/main/java/dev/nodera/simulation/rules/{SpawnRules,MobAiRules,MobCombatRules,ProjectileRules,TntRules,RailRules}.java`
- `library/java/engine/src/main/java/dev/nodera/simulation/ai/`

## Testing

- `SpawnRulesTest` — 2000-tick dark-shelter soak: population ≤ cap, every spawn inside the shelter,
  replica-identical roots; lit platform spawns zero; empty world spawns zero.
- `MobAiRulesTest` (3) — 2400-tick soak with every ghost on a walkable cell and replica-identical
  roots; direct-state wander eventually steps; the despawn horizon fires exactly on time.
- `MobCombatTest` (6) + vitals assertions in `SpawnRulesTest`.
- Planned: per-species determinism plus vanilla-envelope statistical soaks; a forced divergence
  alarm that auto-rolls back to ghost.

## Acceptance criteria

1. ✅ Deterministic spawn cycles match the vanilla rate envelope (**L-8** exit — `SpawnRulesTest`).
2. 🚧 Per-species retirement: ghost share = 0 for every shipped species in soak (**L-7** exit).
3. ✅ `mobCapture` default flips per species as validation ships (**L-24** exit — `e2e-mobs.sh` G2a/G2b green).
4. ⏳ Forced divergence auto-rolls back to ghost.
5. ⏳ Live evidence on a real server.

## Limitations

- **L-7** — mob AI (RETIRING).
- **L-8** — spawn cycles — **RETIRED 2026-07-25** (see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).
- **L-24** — `mobCapture` default-off — **RETIRED 2026-07-26** (see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

See [`LIMITATIONS.md`](LIMITATIONS.md).
