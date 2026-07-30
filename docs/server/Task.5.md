# Server Task 5 — Entity, Mob, and Event Capture Lane

<!-- AI-AGENT-INSTRUCTION: This is the Bukkit MIRROR of dev.nodera.mod.server.entity.EntityCaptureBridge
     — read that class before touching anything here; behaviour that differs from it is a bug unless
     the difference is recorded in REFERENCE.md §4 with a reason. Every handler runs on a region
     thread and must self-catch: a capture failure must degrade one entity, never a region and never
     the server. Two NeoForge hooks have NO Bukkit twin (entity tick cancellation, scheduled-tick
     suppression) — they are approximated and carry limitation rows; do not claim parity. Keep this
     header accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** L-67, L-69 · **Last audit:** 2026-07-28
**Depends on:** [server 4](Task.4.md), [engine 7](../engine/Task.7.md), [engine 8](../engine/Task.8.md), [minecraft 2](../minecraft/Task.2.md)
**Consumed by:** [server 6](Task.6.md), [server 8](Task.8.md)

---

## Goal

Entities, mob AI, and Minecraft events on an endpoint feed the validated lane, region by region, on
the thread that owns each region — so an endpoint's world is simulated and certified exactly the way a
modded host's world is, at whatever breadth its custody covers. This is
`EntityCaptureBridge` re-expressed against Bukkit, plus the multi-threaded dispatch that Folia makes
possible and Paper approximates.

## Status detail

Not started. Two NeoForge hooks the mod relies on have **no Bukkit equivalent** and are approximated
rather than matched — [L-67](LIMITATIONS.md) (scheduled-tick suppression) and
[L-69](LIMITATIONS.md) (validated-item tick suppression).

## Dependencies

- [server 4](Task.4.md) — the world view, the applier, and the chunk gate the lane writes through.
- [engine 7](../engine/Task.7.md) — the interference guard and chunk-lifecycle rules.
- [engine 8](../engine/Task.8.md) — the entity lane's headless half: ghosts, transfers, credits.
- [minecraft 2](../minecraft/Task.2.md) — the reference implementation this mirrors.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `EndpointCaptureBridge` — the Bukkit event mirror, the full mapping in [`REFERENCE.md`](REFERENCE.md) §4 | ⬜ |
| 2 | Per-Nodera-region tick task (`NoderaScheduler.onRegionRepeating`, period 1) replacing `ServerTickEvent.Post` | ⬜ |
| 3 | Per-entity ghost sampling via `EntityScheduler`, replacing `EntityTickEvent.Post` | ⬜ |
| 4 | Drop / pickup capture with the `VanillaCancelGate` contract preserved | ⬜ |
| 5 | Ghost capture, cross-region ghost transfer, and revocation for non-delegable entities | ⬜ |
| 6 | Validated-item projection pinning (the `EntityTickEvent.Pre` approximation) | ⬜ |
| 7 | Redstone/scheduled-tick reconciliation (the `LevelTicksMixin` approximation) | ⬜ |
| 8 | Ender-pearl drive evidence lines, matching the mod's `PEARL:` / `GHOST:` log contract | ⬜ |

## Design

### One bridge, one reference implementation

Every behaviour here already exists in
`dev.nodera.mod.server.entity.EntityCaptureBridge` and `LiveEntityLaneRuntime`. The plugin re-expresses
the *event sources*; the *decisions* are the same objects. Where a decision cannot be shared as-is, it
moves down into `engine` with a headless test and both platforms consume it — never two copies.

The full NeoForge→Bukkit mapping is normative in [`REFERENCE.md`](REFERENCE.md) §4. The rows that
matter:

| Mod hook | Endpoint mechanism | Fidelity |
|---|---|---|
| `EntityJoinLevelEvent` | `EntityAddToWorldEvent` (Paper) | exact |
| `EntityLeaveLevelEvent` | `EntityRemoveFromWorldEvent` (Paper), removal reason preserved | exact |
| `ItemTossEvent` | `PlayerDropItemEvent` | exact |
| `ItemEntityPickupEvent.Pre` | `EntityPickupItemEvent` / `PlayerAttemptPickupItemEvent` | exact |
| `EntityTeleportEvent.EnderPearl` | `PlayerTeleportEvent` with cause `ENDER_PEARL` | exact |
| `ServerTickEvent.Post` | per-region repeating task, period 1 | **better** — per region, not per server |
| `EntityTickEvent.Post` | `EntityScheduler` sampling on the owning region | equivalent |
| `EntityTickEvent.Pre` **cancel** | *no twin* — projection pinning | **approximation**, [L-69](LIMITATIONS.md) |
| `LevelTicksMixin` **cancel** | *no twin* — reconcile through the interference guard | **approximation**, [L-67](LIMITATIONS.md) |

### Threading: capture where the region lives, decide off-thread

Every handler runs on the thread that owns the entity's chunk — on Folia because the event fires
there, on Paper because there is only one. From there:

- **capture** (read entity state, build a `PersistedEntityState`) stays on the region thread;
- **hashing, encoding, proposal, vote, transport** hop to `NoderaScheduler.async` and the peer's own
  executors;
- **apply** comes back through the region's own `WorldMutationApplier`.

The rule that makes this safe on Folia is ALIGN-1: one Nodera region's work is one Folia region's
work, so nothing in the capture path ever needs another region's data. Any code that would need it is
a cross-region operation and goes through [server task 4](Task.4.md)'s path.

### Self-catching is mandatory, and the mod learned it the hard way

`EntityCaptureBridge.onTickPost` carries a comment recording a live crash: a lane-state failure on one
ghost took the whole integrated server down. Folia is stricter still — an uncaught exception on a tick
thread **halts the scheduler and stops the entire server** (`docs/minecraft/folia/06-schedulers.md`).

So: every handler self-catches, and the failure ladder is fixed —

> **drop the entity's capture → log once → keep the region → keep the server.**

Never the other way around. An ArchUnit rule over the module requires every listener method and every
scheduled task body to have a `catch (RuntimeException | LinkageError)` at its top level.

### The two approximations, stated rather than claimed

**Validated items ([L-69](LIMITATIONS.md)).** The mod cancels an item's vanilla tick so the canonical
item is the only one moving. Bukkit cannot cancel a tick. The endpoint instead **pins the projection**:
unlimited lifetime, zeroed velocity re-applied on the region tick, pickup delay held, pickup routed
through `EntityPickupItemEvent`. The observable exit is what actually matters to a player — the item
neither despawns nor drifts, and picking it up credits exactly once.

**Scheduled ticks ([L-67](LIMITATIONS.md)).** The mod cancels vanilla scheduled ticks in a delegated
region at the source, so the engine is the region's only scheduler. Bukkit has no equivalent. The
endpoint runs vanilla redstone in delegated regions and **reconciles through the interference guard** —
correct, but it converts every redstone edge into an external mutation, which is a throughput cost
under load. The exit test is a delegated region under sustained redstone load committing without a
resync storm.

Neither approximation is described as parity anywhere, in code or in a log line.

### Mob AI

Mobs are **vanilla-authoritative**, exactly as in the mod: captured as ghosts, emitted as external
mutations, throttled by `GhostUpdatePolicy`. Deterministic mob AI is
[`engine/Task.11.md`](../engine/Task.11.md) and is out of scope here.

What an endpoint changes is *breadth*: a modded player's node captures the mobs in its field of view;
an endpoint with `FULL` custody and N players captures every mob in every region its players activate
— which is the first time the ghost lane runs at world scale, and the first place its throughput will
be measured honestly.

An entity in a dimension that never opted into `mobCaptureDimensions` still **revokes its region**,
naming the reason — the behaviour [L-60](../minecraft/LIMITATIONS.md) blocks on a dedicated NeoForge
server is reachable here, because an endpoint's lane genuinely owns regions.

## Files

- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/entity/{EndpointCaptureBridge,EndpointEntityLaneRuntime,BukkitEntityAdapters,ProjectionPinner,RegionTickDriver}.java`
- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/entity/RedstoneReconciler.java`

## Testing

- `BukkitEntityAdaptersTest` — a Bukkit entity and the mod's `MinecraftEntityAdapters` produce the
  **same** `PersistedEntityState` bytes for the same logical entity. This is the anti-drift test and
  it is the most important one in the task.
- `EndpointCaptureBridgeTest` — the mapping table, driven by fake events: join/leave/drop/pickup/
  teleport reach the same runtime calls the mod's bridge makes.
- `CaptureFailureContainmentTest` — a throwing runtime drops one entity's capture and nothing else;
  an ArchUnit rule proves every handler self-catches.
- `ProjectionPinnerTest` — a pinned item does not despawn and does not drift over a simulated hour.
- Live (`e2e-endpoint.sh` P5): a tenant drops an item, a modded player picks it up, credited
  **exactly once** — the cross-client-class exactly-once assertion.
- Live (`e2e-folia.sh` F5): a delegated region under redstone load commits for five minutes with the
  resync count under threshold. **This is L-67's exit.**
- Live (`e2e-endpoint.sh` P6): a validated item survives ten minutes pinned and credits once.
  **This is L-69's exit.**

## Acceptance criteria

1. ⬜ Every row of [`REFERENCE.md`](REFERENCE.md) §4 is implemented or explicitly recorded as an
   approximation with a limitation row.
2. ⬜ Bukkit and NeoForge adapters produce byte-identical `PersistedEntityState` for the same entity.
3. ⬜ A capture failure degrades one entity; the region survives and the server survives.
4. ⬜ Ghost capture, cross-region transfer, and revocation all fire on an endpoint.
5. ⬜ A validated item neither despawns nor drifts, and pickup credits exactly once.
6. ⬜ Zero `ensureTickThread` / off-thread-access exceptions in a full Folia suite run.

## Limitations

- **L-67** — vanilla scheduled ticks cannot be cancelled at the source; delegated regions reconcile.
- **L-69** — validated items keep a vanilla-ticking projection, pinned rather than suppressed.
