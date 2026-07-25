# Server — Architecture Reference

<!-- AI-AGENT-INSTRUCTION: NORMATIVE reference for the `server` category. §2 (ALIGN-1) and §4 (the
     event mapping) are the two sections implementers consult most and the two most expensive to get
     wrong. A row in §4 that differs from dev.nodera.mod.server.entity.EntityCaptureBridge is a BUG
     unless this file records the difference and the reason. Do not add a mapping row without stating
     its fidelity (exact / equivalent / approximation) and, for an approximation, the limitation row
     that carries it. -->

**Category:** server · **Last audit:** 2026-07-25 · Programme plan:
[`../plans/Plan.5.md`](../plans/Plan.5.md)

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **Endpoint** | A Paper/Folia server running `nodera-endpoint`, serving a Nodera world over the vanilla protocol |
| **Node** | A key-holding member of a world's session. An endpoint is one node, whatever its player count |
| **Tenant** | A player with no key, playing through an endpoint. Never a node, never a committee seat |
| **Custody class** | `FULL` (holds every region's certified head) or `VIEW` (holds only what its views cover) |
| **Nodera region** | 8×8 chunks, static grid, stable `RegionId`. A unit of **authority** |
| **Folia region** | One or more 16×16-chunk sections, dynamic, no stable identity. A unit of **execution** |
| **ALIGN-1** | The invariant that every Nodera region nests inside exactly one Folia region |

---

## 2. ALIGN-1 — the region nesting invariant

A Folia section is `2^g` chunks per side (`g = threaded-regions.grid-exponent`, default 4), anchored
at chunk 0. A Nodera region is 8 chunks per side, anchored at chunk 0.

```
section s covers chunks [ s·2^g , s·2^g + 2^g − 1 ]
region  m covers chunks [ m·8   , m·8   + 7       ]

g = 4 ⇒ section s contains regions m = 2s and m = 2s+1, whole, on each axis
      ⇒ 4 whole Nodera regions per section, never a partial one
```

**Statement.** For `g ≥ 3`, every Nodera region lies entirely inside exactly one Folia section, and
therefore inside exactly one Folia region — because a Folia region owns whole sections.

**Consequence.** All Minecraft-side work for one Nodera region runs on **exactly one Folia region
thread**, with no locking and no coordination. This is what makes the whole category tractable: the
`WorldMutationApplier` single-writer invariant holds per region for free, capture never needs another
region's data, and per-region parallelism is a property of the platform rather than something the
plugin has to build.

**Enforcement.** Preflighted at enable ([server 1](Task.1.md)) — the plugin **refuses to enable**
below `g = 3` with the fix named in the message — and asserted live ([server 3](Task.3.md)) by
cross-checking `Bukkit.isOwnedByCurrentRegion` on all four corners of every region handed out.

**Why refusing beats degrading.** A Nodera region split across two Folia region threads means two
threads writing one authority unit. That is corruption with a delay fuse, and it would surface as an
unreproducible state-root divergence days later.

---

## 3. Thread-context map

| Work | Runs on | Dispatched by |
|---|---|---|
| Event capture for region `R` | the Folia region owning `R` | the event already fires there |
| `WorldMutationApplier.apply` for `R` | the Folia region owning `R` | `NoderaScheduler.onRegion(R, …)` |
| `reExtract` for `R` | the Folia region owning `R` | `NoderaScheduler.onRegion(R, …)` |
| Root hashing, canonical encoding | any async thread | `NoderaScheduler.async` |
| Proposal, vote, transport I/O | the peer's own executors | `PeerRuntime` (never a tick thread) |
| Tracker announce, rendezvous registration | the global region | `NoderaScheduler.global` |
| World identity, permissions, ops, chat | the global region | `NoderaScheduler.global` |
| Archive packing + streaming | any async thread | `NoderaScheduler.async` |
| Entity-following work | the region owning the entity | `NoderaScheduler.onEntity` |

On Paper every row above collapses to the main thread except `async`, which uses the peer's pool.
`BukkitScheduler` is referenced nowhere, on either platform.

**The failure ladder is fixed** — on Folia an uncaught exception on a tick thread halts the scheduler
and stops the whole server:

> drop the entity's capture → log once → keep the region → keep the server.

Every listener method and every scheduled task body carries a top-level
`catch (RuntimeException | LinkageError)`, enforced by an ArchUnit rule.

---

## 4. Event mapping: NeoForge mod → Bukkit endpoint

`dev.nodera.mod.server.entity.EntityCaptureBridge` is the reference implementation. A row that differs
from it in behaviour is a bug unless the difference is recorded here.

**Fidelity** — `exact`: same signal, same timing · `equivalent`: different mechanism, same observable
behaviour · `approximation`: the observable behaviour differs and a limitation row carries it.

| Mod hook | Endpoint mechanism | Fidelity | Notes |
|---|---|---|---|
| `EntityJoinLevelEvent` | `EntityAddToWorldEvent` (Paper) | exact | deferred to the region tick, as the mod defers to `ServerTickEvent.Post` |
| `EntityLeaveLevelEvent` | `EntityRemoveFromWorldEvent` (Paper) | exact | removal reason preserved; `UNLOADED_TO_CHUNK` / `CHANGED_DIMENSION` still suppress the removal capture |
| `EntityTickEvent.Post` | `EntityScheduler` sampling on the owning region | equivalent | ghost sampling is rate-shaped by `GhostUpdatePolicy` either way |
| `EntityTickEvent.Pre` **cancel** | projection pinning: unlimited lifetime, zeroed velocity, held pickup delay | **approximation** | Bukkit cannot cancel a tick — [L-69](LIMITATIONS.md) |
| `ItemTossEvent` | `PlayerDropItemEvent` | exact | `VanillaCancelGate` contract unchanged: cancel only on the synchronous local-primary path |
| `ItemEntityPickupEvent.Pre` | `EntityPickupItemEvent` / `PlayerAttemptPickupItemEvent` | exact | |
| `EntityTeleportEvent.EnderPearl` | `PlayerTeleportEvent`, cause `ENDER_PEARL` | exact | `PEARL:` evidence lines preserved verbatim so the pearl drive greps identically |
| `ServerTickEvent.Post` | per-region repeating task, period 1 | **better** | per region rather than per server: a busy region cannot delay another's lane |
| `PlayerEvent.PlayerLoggedInEvent` | `PlayerJoinEvent` | exact | |
| `PlayerEvent.PlayerLoggedOutEvent` | `PlayerQuitEvent` | exact | de-op on logout, view removed, ownership re-planned |
| `ServerStartedEvent` | `onEnable` + first `ServerLoadEvent` | equivalent | on Folia, the global-region equivalent of `RegionizedServerInitEvent` |
| `ServerStoppingEvent` / `ServerStoppedEvent` | `onDisable` + `WorldSaveEvent` | equivalent | the final archive flush rides the last save, not the last tick |
| `RegisterCommandsEvent` | `plugin.yml` + the Paper Brigadier lifecycle API | equivalent | |
| `LevelTicksMixin` (scheduled-tick suppression) | reconcile through the interference guard | **approximation** | no Bukkit twin at all — [L-67](LIMITATIONS.md) |
| `LevelChunkMixin` (the write choke point) | `ChunkEditability` + the foreign-write bridge | equivalent | a plugin's write is certified, not suppressed — see [server 8](Task.8.md) |
| NeoForge custom payloads | plugin messaging on `nodera:v1` | equivalent | NeoForge payloads do not exist on Paper — see [server 7](Task.7.md) |
| `PacketDistributor.sendToPlayer` | `player.sendPluginMessage(plugin, "nodera:v1", bytes)` | exact | |

**Not mapped, and deliberately.** Mixins have no place in a Bukkit plugin: every row above is an event
or a scheduler, which is why an endpoint carries none of the mod's A-7 version-churn exposure.

---

## 5. The custody digest

```
CustodyDigest = MerkleRoot over  [ (RegionId, head SnapshotVersion) … ]  in canonical RegionOrder
```

Rides every tracker announce and every membership gossip alongside the node's `CustodyClass`.

| Property | Why |
|---|---|
| Canonical `RegionOrder` | two honest nodes with equal state produce equal roots |
| Merkle, not a flat hash | a peer can spot-check **one** region with an inclusion proof instead of pulling the world |
| Advertised, never trusted | a failing spot-check downgrades the claim to `VIEW`; the world stays available |

Spot-checking reuses [`network/Task.6.md`](../network/Task.6.md)'s `ArchiveAuditTask` /
`ArchiveRepairService` path — a new claim, not a new mechanism.

---

## 6. Ownership ranking

For each region, covering nodes are ranked by:

1. **geometric distance** to the region centre — for a node with several views (an endpoint with
   several tenants), the **minimum** over its views;
2. **custody class** — `FULL` before `VIEW`, **only on an exact distance tie**;
3. **`NodeId`** — the existing deterministic tiebreak.

Nearest is primary; the next `maxCommitteeSize − 1` are validators; resident validators (nodes with no
view at all) top up the seats geometry leaves empty.

> **Capacity never buys authority.** A modded player standing closer than any tenant primaries the
> region and the endpoint validates it. Custody class breaks ties; it never outranks distance.

> **N tenants grow an endpoint's footprint, never its weight.** One endpoint contributes one node, one
> key, and one seat per region regardless of player count. A synthetic node per tenant would be a
> Sybil attack an honest operator would trip over by accident.

---

## 7. Identity on an endpoint

| | Tenant | Modded client | Endpoint |
|---|---|---|---|
| Ed25519 key | none | its own | its own |
| Actor on an envelope | `tenantId` | its own `NodeId` | its own `NodeId` |
| Who signs | the endpoint | itself | itself |
| In membership | never | yes | yes |
| Committee seat | never | yes, by geometry | yes, by geometry (over its tenants' views) |
| Can verify | no | yes | yes |

```
tenantId = NodeId( UUIDv5( namespace = endpointNodeId , name = playerUUID ) )
```

Deterministic across sessions, unique across endpoints, never present in membership.

---

## 8. The `nodera:v1` plugin-message handshake

```
client → server   HELLO     protocolVersion, nodeId, publicKey, registryFingerprint
server → client   WELCOME   worldId, worldIdentity, challenge, sessionRoute, requiredMods
client → server   ANNOUNCE  sig( challenge ‖ nodeId ‖ publicKey ‖ playerUUID )
server → client   LANEPLAN  worldSeed, rulesVersion, registryFingerprint, genesisRoot, members
server → client   REFUSE    reason, offendingMods
```

Admission is `registryFingerprint` **equality** — a cryptographic answer to a mod-compatibility
question, using the value that already makes two peers agree on the palette. A refusal always carries
a human-readable reason naming the offending mods.

---

## 9. Configuration (`plugins/NoderaEndpoint/nodera-endpoint.yml`)

```yaml
peer:
  mode: embedded            # embedded | external
  control-port: 25610       # the loopback ControlProtocol v2 endpoint
  identity-file: identity.bin
  p2p:
    bind: 0.0.0.0
    port: 25620
    advertise: auto

world:
  id: ""                    # empty = publish this server's own world; set = adopt it from the network
  custody: FULL             # FULL | VIEW
  password: ""              # empty = no join gate
  listed: true              # announce to the tracker

discovery:
  trackers:   ["tracker.example:25600"]
  rendezvous: ["rendezvous.example:25601"]

lane:
  entity-capture: true
  mob-capture-dimensions: ["minecraft:overworld"]
  archive-stream-interval-ticks: 2400

mods:
  required: []              # non-empty ⇒ vanillaFriendly: false
  allowed:  []
```

Every key is read from config; the defaults are the `NoderaConstants` values
([`docs/README.md`](../README.md) §4.7). Code reads config; tests read constants.

---

## 10. Control verbs on an endpoint

The endpoint answers the **same** `ControlProtocol` v2 table the headless worker answers, so every
existing tool works unchanged:

```bash
control_verb 25610 'NODERA-PROBE 2'     # → NODERA-OK 2 <version>
control_verb 25610 'NODERA-STATE 2'     # → live JSON incl. custody, tenants, regions
control_verb 25610 'NODERA-PIECES 2 <worldIdHex>'
```

`ControlProtocol` remains the single source of truth. The endpoint adds a fifth **handler**, never a
fifth definition — a protocol change still lands in `ControlProtocol`, the mod's `CompanionProtocol`,
the app's `control.rs`, and now the endpoint's handler, in one commit.

`NODERA-STATE` gains two endpoint-only fields, additively:

```json
{ "custody": {"class": "FULL", "digest": "<hex>", "regions": 4096},
  "tenants": [{"tenant_id": "…", "player": "…", "since_ms": 12345}] }
```

---

## 11. Prior art

| Source | What is taken |
|---|---|
| [`../minecraft/folia/`](../minecraft/folia/) | The regionised-threading model, the four schedulers, the thread-context guards, and the failure semantics (an uncaught tick-thread exception stops the server) |
| [`../minecraft/MultiPaper/`](../minecraft/MultiPaper/) | The chunk-ownership-between-processes model, and the demonstration that format-level chunk-file interception needs a **fork** — which is precisely why this category refuses it |

Neither project validates anything. Committee re-execution plus quorum certificates remains Nodera's
novel layer, and an endpoint is a participant in it rather than an exception to it.
