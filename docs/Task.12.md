# Task 12 — Entity & Mob Lane: Entity State in Roots, Ghost Mobs, Cross-Region Transfer

**Phase:** 5+ extension · **Depends on:** Tasks 9, 11 · **Modules:** `core`,
`simulation`, `protocol`, `neoforge-mod`, `integration-tests`

## Goal

Make entity-bearing regions delegable. Three sub-lanes, in order:

- **12a — validated item entities**: deterministic item physics inside the engine;
  entity table becomes part of the region state root (Invariant 10 extension).
- **12b — ghost mobs (`mobCapture` mode)**: mobs stay vanilla-server-authoritative but
  their state and world effects flow through the Task 11 interference pipeline as
  certified external events — visible, replica-consistent, never validated.
- **12c — cross-region entity transfer**: certified handoff protocol for entities
  crossing region boundaries (items, thrown ender pearls), built on the Task 8 barrier
  machinery.

**Staged, not permanent** (ledger L-7): deterministic mob AI (pathfinding, targeting,
brain ticks) is out of **this** task. Vanilla mob simulation is float-heavy, RNG-heavy,
and version-churned — validating it means reimplementing it, and that is exactly what
Task 15 does: Nodera-defined behaviour, fixed-point integer pathfinding, seeded brains,
retired species by species with ghosts as the working fallback at every step. This task
bounds the determinism surface deliberately; Task 15 expands it deliberately. Ghost
retirement criteria live in Task 15 and `LIMITATIONS.md`.

---

## Folder structure (additions)

```
core additions:
├── state/
│   ├── FixedVec3.java               # Q32.32 fixed-point vector — the ONLY numeric form for
│   │                                #   entity position/velocity in hashed state
│   ├── EntityKind.java              # enum: ITEM, GHOST (+ reserved: PROJECTILE, TNT)
│   └── PersistedEntityState.java    # (Task 9 type, now used): NetworkEntityId, kind,
│                                    #   typeId, FixedVec3 pos/vel, ageTicks, despawnTick,
│                                    #   payload (canonical bytes: item stack id+count for ITEM)
├── action/
│   ├── DropItemAction.java          # player drops item (stack ref by id+count)
│   └── PickupItemAction.java        # player picks up NetworkEntityId
└── event/                            # sealed RegionEvent grows (append-only):
    ├── EntityCreatedEvent / EntityUpdatedEvent / EntityRemovedEvent
    └── EntityTransferPreparedEvent / EntityTransferCommittedEvent

simulation additions:
├── entity/
│   ├── EntityStore.java             # sorted map NetworkEntityId → PersistedEntityState
│   │                                #   inside MutableRegionState; encoded into the root
│   ├── ItemEntityRules.java         # deterministic physics: fixed-point gravity/friction,
│   │                                #   ground rest on MVP terrain, merge rule, despawn clock
│   └── EntityIdAllocator.java       # deterministic ids: StableHash(region, version, seq)
│                                    #   — NOT UUID.randomUUID (determinism rule)
└── rules/EntityRuleSet.java         # RuleSet impl handling Drop/Pickup + per-batch entity tick

protocol additions (append-only tags):
├── entity/
│   ├── EntityTransferPrepare.java   # from-region, to-region, entity state, fromVersion
│   ├── EntityTransferAccept.java    # to-region committee acks (or gateway acks in server lanes)
│   ├── EntityTransferCommit.java    # both sub-deltas + certificate (reuses Task 8 atomic applier)
│   └── GhostEntityDelta.java        # mobCapture stream: entity upserts/removes as external
│                                    #   mutations (folded into ExternalDelta's entity list)

neoforge-mod additions:
├── common/entity/NetworkEntityIdAttachment.java   # persistent data attachment on entities;
│                                                  #   created on first sight, survives save/load
├── dedicated/entity/
│   ├── EntityCaptureBridge.java     # vanilla entity events in delegated+mobCapture regions →
│   │                                #   position/state deltas → InterferenceBuffer (entity lane)
│   ├── EntityTransferCoordinator.java  # runs the 12c protocol; barrier via Task 8 machinery
│   └── EntityDelegabilityRules.java # narrows ENTITY_PRESENT (see below)
└── dedicated/command/  (extend)     # /nodera entities <region> — table of tracked entities
```

## Class relationships

```
MutableRegionState (Task 3) ── grows ──► EntityStore
    root = hash(blocks ‖ entityTable ‖ scheduledEvents)   # encoding order fixed, documented

RuleSet (Task 3, interface) ◄── EntityRuleSet
    validate/apply DropItemAction, PickupItemAction
    + tickEntities(state, rng): advance ITEM physics one batch (BATCH_TICKS steps)
      — entity ticking is now part of execute(); Plan's "simple entity movement" scope

DelegabilityPolicy.ENTITY_PRESENT narrows (EntityDelegabilityRules):
    region entities ⊆ {ITEM}                          → delegable (12a lane)
    region entities ⊆ {ITEM} ∪ {GHOST-capable mobs}  → delegable iff mobCapture=true (12b)
    anything else (players excluded — they are not region state) → non-delegable (Task 11 rule)

EntityCaptureBridge (mobCapture mode)
    vanilla ticks mobs normally in the delegated region (random-tick suppression from
    Task 11 does NOT apply to entity ticking — only block randomTicks)
    ├─ mob position/pose/health changes  → GhostEntityDelta entries (throttled, per-tick coalesced)
    ├─ mob WORLD effects (creeper hole, enderman pickup, door break, trampling)
    │      → already caught by the Task 11 block guard → same ExternalDelta
    └─ committed between batches (Task 11 ordering rule) with reason EXTERNAL_MUTATION

EntityTransferCoordinator (12c) — item crosses region border:
    engine emits BorderEvent(entity, targetRegion) instead of moving it out of bounds
    ├─ both regions delegated: barrier both pipelines (Task 8 PAUSED_FOR_XR)
    │     Prepare → Accept → server builds two sub-deltas
    │     (remove-from-A @ verA, add-to-B @ verB with SAME NetworkEntityId)
    │     → one main-thread atomic apply → EntityTransferCommittedEvent both logs
    ├─ target is vanilla lane: entity materialized as a real vanilla entity (applier
    │     spawns it), removed from A's root — leaves the validated world
    └─ idempotent: replay of Commit is a no-op (entity id presence check both sides)
```

## Implementation details — simulation (12a)

- **Fixed-point everywhere**: `FixedVec3` Q32.32; gravity/friction constants defined as
  fixed-point literals in `ItemEntityRules` (documented values, not derived from vanilla
  floats). Item motion on flat MVP terrain: fall, rest, slide-none, merge when same
  stack + overlapping AABB (AABB in fixed-point), despawn at `ageTicks == 6000`.
  This is *Nodera item physics* — close to vanilla feel, not bit-matching vanilla.
- **Determinism**: entity iteration order = `NetworkEntityId` sort (ids are
  `StableHash`-derived, stable across replicas). Entity RNG draws use the Task 3
  per-action/per-tick reseeding discipline (`actionSequence` slot reserved value for
  entity ticks: `0x454E545F << 32 | localTickIndex`).
- **Pickup semantics**: `PickupItemAction` removes the entity from the region root and
  reports the stack in the delta's (new) `inventoryCredits` list — the applier credits
  the vanilla player inventory server-side. Player inventory itself stays **out** of
  the root (decision recorded: inventory validation is a later lane; crediting is a
  one-way effect like Task 6 block effects).

## Implementation details — NeoForge mod / server peer

- `NetworkEntityIdAttachment`: NeoForge data attachment, persistent, on every entity
  the system tracks. Mapping table region-scoped in coordinator memory; persisted via
  the Task 9 event log (EntityCreated events carry vanilla UUID ↔ NetworkEntityId).
- Applier extensions: entity sub-lists in `RegionDelta` (the Task 2 reserved lists come
  alive: `entityMutations`, `inventoryCredits`) — same two-pass atomic discipline.
  Spawning/removing vanilla `ItemEntity` counterparts happens in applier scope so the
  Task 11 guard sees them as legitimate.
- **Ender pearls (the named review item)**:
  - Pearl in flight = vanilla projectile → its region is not ITEM-only → without
    mobCapture the region simply revokes (Task 11 rule); with mobCapture the pearl is
    a GHOST and its flight streams as `GhostEntityDelta`.
  - Pearl crossing region border: 12c transfer for GHOSTs = trivial (ghost re-homed,
    no validation); delegated→vanilla crossing = materialize.
  - Pearl-induced chunk loading: already policy from Task 11 (foreign ticket
    respected; loaded-but-playerless regions stay vanilla lane).
  - Pearl teleport of the player: player state is server-owned (not region state) —
    teleport applies vanilla-side; the player's *actions* simply start routing to the
    new region's committee. Test included.
- `mobCapture` default **false** (ghost lane opt-in until soak-proven), config
  per-dimension. Flips to true, then becomes irrelevant, as Task 15 retires species
  from ghost to validated (ledger L-24).

## Acceptance criteria

1. **12a determinism**: jqwik + fixture: 3 replicas, tossed items over 200+ ticks
   (falls, merges, despawns) ⇒ identical roots every batch; entity table provably part
   of root (delete one entity in a test build ⇒ divergence detected).
2. **Pickup/drop end-to-end**: drop → committee-validated physics → pickup → vanilla
   inventory credited exactly once (idempotency under replayed commit).
3. **Delegability narrowing**: ITEM-only region delegable; zombie enters with
   `mobCapture=false` ⇒ graceful revoke (Task 11 path); with `mobCapture=true` ⇒ stays
   delegated, zombie ghost stream keeps replicas root-consistent, zombie door-break
   lands as block `ExternalDelta`.
4. **12c transfer**: item flung across delegated↔delegated border ⇒
   Prepared/Accepted/Committed event pairs in both region logs with one certificate; no
   dupe/loss when a peer is killed mid-transfer (idempotent replay test); Invariant 11
   test tagged `@Invariant(11)`.
5. **Pearl suite**: pearl flight across regions (ghost + materialize paths), pearl
   chunk-loading isolation (Task 11 re-test with entities present), pearl teleport →
   action routing follows the player.
6. Soak: mixed items+ghost-mobs world section, resync rate under threshold, ghost
   bandwidth measured and recorded (per mob per minute) in `Plan.md` notes.
