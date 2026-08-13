# Server Task 5 — Entity, Mob, and Event Capture Lane

<!-- AI-AGENT-INSTRUCTION: This is the Bukkit MIRROR of dev.nodera.mod.server.entity.EntityCaptureBridge
     — read that class before touching anything here; behaviour that differs from it is a bug unless
     the difference is recorded in REFERENCE.md §4 with a reason. Every handler runs on a region
     thread and must self-catch: a capture failure must degrade one entity, never a region and never
     the server. Two NeoForge hooks have NO Bukkit twin (entity tick cancellation, scheduled-tick
     suppression) — they are approximated and carry limitation rows; do not claim parity. Keep this
     header accurate. -->

**Status:** 🚧 IN PROGRESS — deliverable 6 (validated-item projection pinning) is built, wired and
unit-proven; its **input** is not, because nothing on the endpoint path delegates a region yet
(tasks 2/3). Deliverable 7 is **measured and refused**: the two thresholds L-67's exit clause hides
are derived below, and they show the clause cannot be satisfied as written.
**Category:** server · **Owns:** L-67, L-69 · **Last audit:** 2026-08-10
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

Two NeoForge hooks the mod relies on have **no Bukkit equivalent** and are approximated rather than
matched — [L-67](LIMITATIONS.md) (scheduled-tick suppression) and [L-69](LIMITATIONS.md)
(validated-item tick suppression). Deliverables 1–5 and 8 are not started.

**Deliverable 6 landed on 2026-08-10** (issue #180). `ProjectionPinner` + `BukkitProjections` +
`ProjectionListener` are in `endpoints/paper-plugin`, wired from `NoderaEndpointPlugin.onEnable`,
and `ProjectionPinnerTest` runs the pin over a simulated hour inside `./gradlew check`. What is
missing is the pin's **input**: nothing on the endpoint path delegates a region, so
`regionIsDelegated` answers `false` for every location, no item is validated, and none is pinned. A
server running this build behaves exactly as one without the plugin, which is the only honest
behaviour while there is no validated lane. The plugin says so at enable, in one line, and the two
live stages are gated on that line's absence.

**Deliverable 7 was measured rather than built** (issue #177). The mechanism L-67 describes —
reconcile every redstone edge through the interference guard — already exists in `:engine`
(`MutationGuard` → `InterferenceBuffer` → `InterferenceCommitter`), and the Bukkit event funnel into
it is server task 8's `ForeignWriteBridge`, not a second bridge here. What was missing was the two
numbers the exit clause is stated in. They are derived below, and the derivation is the finding: at
the current revocation bound the clause's two halves cannot both hold.

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
| 6 | Validated-item projection pinning (the `EntityTickEvent.Pre` approximation) | 🚧 built and wired; nothing delegates a region to feed it (tasks 2/3) |
| 7 | Redstone/scheduled-tick reconciliation (the `LevelTicksMixin` approximation) | 🚧 mechanism is `:engine`'s interference guard; thresholds derived below, and they refuse the exit clause as written |
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
unlimited lifetime, zeroed velocity re-applied on the region tick, vanilla pickup denied, and the
pickup intent routed to the validated lane. The observable exit is what actually matters to a player —
the item neither despawns nor drifts, and picking it up credits exactly once.

**Scheduled ticks ([L-67](LIMITATIONS.md)).** The mod cancels vanilla scheduled ticks in a delegated
region at the source, so the engine is the region's only scheduler. Bukkit has no equivalent. The
endpoint runs vanilla redstone in delegated regions and **reconciles through the interference guard** —
correct, but it converts every redstone edge into an external mutation, which is a throughput cost
under load. The exit test is a delegated region under sustained redstone load committing without a
resync storm.

Neither approximation is described as parity anywhere, in code or in a log line.

### The pin, precisely (deliverable 6)

`ProjectionPinner` is Minecraft-free; `BukkitProjections` is the adapter and `ProjectionListener` is
the two events. Each region tick, for each pinned projection, on **that region's own
`RegionScheduler`** and never the global one:

| Written every tick | Value | Why that value |
|---|---|---|
| `ticksLived` | `1` | Vanilla despawns at 6000. `setTicksLived` refuses `0` — nothing has lived for no ticks — so `1` is the youngest an item can be told it is |
| velocity | zero | The canonical item is the only thing that moves; this one is a picture of it |
| `pickupDelay` | `Short.MAX_VALUE` (32767) | Vanilla's "never" sentinel. Not `Integer.MAX_VALUE`: the field is a short and Bukkit clamps, so a larger constant silently becomes this one |
| `canMobPickup` | `false` | A hopper or a mob taking a validated item is a credit the lane never authorised |
| position | re-anchored **only past the band** | See below |

**Two corrections to the design as it was written here before.**

1. *"Pickup routed through `EntityPickupItemEvent`"* cannot be the credit path on its own, because
   `EntityPickupItemEvent` does not fire while `pickupDelay > 0` — so holding a maximal pickup delay
   and waiting for that event means the item is never picked up at all, and L-69's own exit clause
   ("picking it up credits exactly once") becomes unreachable. The pin therefore detects the intent
   on the **region tick**, from a player inside `PICKUP_RADIUS_BLOCKS`, and hands it to the lane.
   The `EntityPickupItemEvent` handler is kept and **cancels**: pickup delay is a mutable field any
   plugin may zero, and the cancel is what makes exactly-once survive a plugin the endpoint has
   never heard of.
2. *"Zeroed velocity re-applied on the region tick"* does not by itself bound drift. The platform
   integrates the entity **before** the region task runs, so each tick can still move the item by
   whatever velocity it had — up to about 0.4 blocks for a falling item — and gravity puts the
   velocity straight back on the next tick. Writing the position back every tick would bound it, and
   would be client-visible jitter. So the pin uses a **hysteresis band**:

   - `DRIFT_TOLERANCE_BLOCKS = 0.5` — below this, nothing but velocity is written;
   - `MAX_OBSERVED_DRIFT_BLOCKS = 1.0` — the band plus one tick of unrestrained motion, rounded up.
     This is the number the live stage asserts, and it is deliberately not zero.

   `ProjectionPinnerTest` drives 72,000 ticks (an hour, twelve despawn ages) against a fake that
   keeps moving after every velocity write, and asserts both bounds.

**Exactly-once is a property of the claim, not of the events.** `ProjectionPinner.claim` is the only
credit path: the first caller for an item id takes it, the projection is unpinned and removed only
after the lane has **accepted**, a lane that declines releases the claim rather than burning it, and
a re-entrant claim from inside the lane's own credit is refused. Two players on the same tick, a
duplicate event, or a listener that turns a credit back into a pickup therefore cannot double-credit.

### The two thresholds L-67's exit clause hides (deliverable 7)

> *"a delegated region under sustained redstone load commits for five minutes with the **resync count
> under threshold**, and no region is revoked for **interference rate**"*

Neither threshold was stated anywhere in the repository. Both already exist in production code, and
this is where they are recorded:

| Threshold | Value | Where it lives | Derivation |
|---|---|---|---|
| **Resync** | **100 basis points** — resyncs at most **1%** of all commit outcomes | `EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS` (`:engine`) | The entity lane's own Task-12 exit bar. F5 is the same measurement (`resyncs / (commits + resyncs)`) under a different load, so it takes the same bar rather than inventing a second one |
| **Interference rate** | **60** foreign writes per **1200**-tick window (one minute at 20 TPS) | `NoderaConstants.INTERFERENCE_REVOKE_RATE` / `INTERFERENCE_RATE_WINDOW_TICKS`, compared at `DelegabilityPolicy:163` | Strictly above the bound, a region is demoted with `Reason.INTERFERENCE_RATE_HIGH` |

**And the derivation refuses the clause.** A two-tick repeater clock — the ordinary case, not a
stress test — toggles a block every two ticks: 0.5 edges a tick, **600 a window**. On an endpoint
every one of those is a foreign write, because there is no mixin to cancel the scheduled tick. So a
*single* vanilla redstone clock in a delegated region is **ten times** the revocation bound, and the
region is demoted within one window. F5's first clause ("under sustained redstone load") and its
second ("no region is revoked for interference rate") cannot both hold at
`INTERFERENCE_REVOKE_RATE = 60`.

On the modded host the same clock costs **zero**: `LevelTicksMixin` cancels at
`LevelTicks.schedule`, so the guard never sees an edge. The gap between 600 and 0 is exactly what
L-67 is a row about, now as a number.

`InterferenceThroughputTest` (`:engine`, in `check`) asserts all of it headlessly: the certificate
rate follows the **commit cadence** and not the write volume — sixty-four times the load costs the
same number of certificates, which is what makes the approximation a latency cost rather than an
unbounded one — and one ordinary clock exceeds the revocation bound tenfold while the modded host's
does not. If either constant moves, one of those fails and points back at this table.

**What this needs is a decision, not code.** Three options, none of which an implementing agent
should take alone:

1. **Make the rate source-aware.** `InterferenceStats.ratePerWindow` is source-blind, but
   `MutationSource` already distinguishes `SCHEDULED` from `ENTITY`/`NEIGHBOR`/`UNKNOWN`, and
   per-source totals are already tracked. On a platform that *cannot* suppress scheduled ticks,
   `SCHEDULED` is structural rather than evidence of a misbehaving plugin, so excluding it from the
   revocation rate restores the bound's meaning — "something is fighting the lane" — instead of
   raising it until it means nothing. **Recommended.**
2. **Give endpoints a higher bound.** Simple, and it makes the number arbitrary: it would have to be
   set above whatever redstone an operator happens to build, which is not a bound.
3. **Convert L-67 to §C.** Accept that a delegated region on an endpoint may not carry sustained
   redstone, and say so. Honest, and it gives up a capability the mod has.

Whichever is chosen, L-67's exit clause has to be rewritten around it. Until then the row is
**unmeasurable rather than unmet**, and F5 says so by failing at its first assertion.

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

Built (2026-08-10), under `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/paper/entity/`
— the package is `…endpoint.paper.entity`, matching `…endpoint.paper.{compat,world}`, not the
`…endpoint.entity` this list used to name:

- `ProjectionPinner.java` — the pin, with no Bukkit types in it
- `BukkitProjections.java` — the `Item` adapter and the `RegionScheduler` dispatch
- `ProjectionListener.java` — `ItemSpawnEvent` (pin) and `EntityPickupItemEvent` (cancel the vanilla
  credit)

Still to come:

- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/paper/entity/{EndpointCaptureBridge,EndpointEntityLaneRuntime,BukkitEntityAdapters,RegionTickDriver}.java`

There is **no `RedstoneReconciler`**, and there should not be one. Deliverable 7's mechanism is
`:engine`'s interference guard, and the Bukkit event funnel into it is server task 8's
`ForeignWriteBridge` — a second bridge here would be a second copy of the same decision, which is
what the "one bridge, one reference implementation" rule above exists to prevent.

## Testing

- `BukkitEntityAdaptersTest` — a Bukkit entity and the mod's `MinecraftEntityAdapters` produce the
  **same** `PersistedEntityState` bytes for the same logical entity. This is the anti-drift test and
  it is the most important one in the task.
- `EndpointCaptureBridgeTest` — the mapping table, driven by fake events: join/leave/drop/pickup/
  teleport reach the same runtime calls the mod's bridge makes.
- `CaptureFailureContainmentTest` — a throwing runtime drops one entity's capture and nothing else;
  an ArchUnit rule proves every handler self-catches.
- `ProjectionPinnerTest` ✅ (12) — a pinned item survives 72,000 ticks (an hour, twelve despawn
  ages); drift stays inside `MAX_OBSERVED_DRIFT_BLOCKS` against a fake that keeps moving after every
  velocity write; a settled item is never teleported (the jitter the band exists to avoid); a
  foreign plugin clearing the pickup delay is overwritten every tick; ten takers credit once; a
  declining lane leaves the item claimable; a **re-entrant** claim is refused; a throwing projection
  drops one item and nothing else.
- `InterferenceThroughputTest` (in `:engine`) ✅ (4) — F5's headless twin: the certificate rate
  follows the commit cadence and ignores the write volume, sixty-four times the load costs the same
  number of certificates, one ordinary redstone clock exceeds the revocation bound tenfold, and the
  same clock on the modded host costs nothing. **This is where the two thresholds above are pinned.**
- Live (`e2e-endpoint.sh` P5): a tenant drops an item, a modded player picks it up, credited
  **exactly once** — the cross-client-class exactly-once assertion. ⬜ not written.
- Live (`e2e-folia.sh` F5) 🚧 — a delegated region under redstone load commits for five minutes with
  the resync count under threshold and nothing revoked. **This is L-67's exit.** Written 2026-08-10;
  **fails at its first assertion**, because nothing delegates a region (tasks 2/3).
- Live (`e2e-endpoint.sh` P6) 🚧 — a validated item neither despawns nor drifts over a **five-minute**
  hold window and credits **exactly once**. **This is L-69's exit.** Written 2026-08-10; **fails at
  its first assertion**, for the same reason.

  The hold window is five minutes rather than ten because five minutes *is* vanilla's despawn age
  (6000 ticks): a shorter window cannot distinguish a pinned item from an unpinned one, and a longer
  one asserts nothing more. The drift assertion is a **number** (1.0 blocks) rather than "the item is
  still there", which a projection that despawned and was respawned also satisfies.

**A gap F5 names and cannot close on its own:** the endpoint's `NODERA-STATE` reports
`validation.divergences` and `validation.committee_commits` but **no resync count**, so the resync
threshold in the table above has nothing to be compared against over the wire. Exposing
`EntityLaneSoakMetrics`' resync rate on `NODERA-STATE` is a prerequisite of F5, and the stage says so
in its own failure message rather than quietly skipping the clause.

## Acceptance criteria

1. ⬜ Every row of [`REFERENCE.md`](REFERENCE.md) §4 is implemented or explicitly recorded as an
   approximation with a limitation row.
2. ⬜ Bukkit and NeoForge adapters produce byte-identical `PersistedEntityState` for the same entity.
3. ⬜ A capture failure degrades one entity; the region survives and the server survives.
4. ⬜ Ghost capture, cross-region transfer, and revocation all fire on an endpoint.
5. 🚧 A validated item neither despawns nor drifts, and pickup credits exactly once. Proven
   headlessly by `ProjectionPinnerTest`; unproven live, because nothing delegates a region.
6. ⬜ Zero `ensureTickThread` / off-thread-access exceptions in a full Folia suite run.

## Limitations

- **L-67** — vanilla scheduled ticks cannot be cancelled at the source; delegated regions reconcile.
  **Unmeasurable rather than unmet.** The mechanism exists in `:engine`; the two thresholds its exit
  clause is stated in are derived in §Design; and the derivation shows the clause's two halves cannot
  both hold at `INTERFERENCE_REVOKE_RATE = 60`. Retiring the row needs a decision (§Design, three
  options, option 1 recommended) and a delegated region on the endpoint path (tasks 2/3), in that
  order. It is additionally behind **L-66/[#182]**: the clause names a Folia build of the mod's
  pinned Minecraft version, and no such build exists — see [Task.1.md](Task.1.md).
- **L-69** — validated items keep a vanilla-ticking projection, pinned rather than suppressed.
  **The pin is built, wired and unit-proven; the row does not retire**, because its exit clause says
  "a validated item on an endpoint" and nothing on the endpoint path validates one yet (tasks 2/3).
  P6 exists and fails at that gate rather than measuring vanilla and calling it the lane.
