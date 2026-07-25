<!-- AI-AGENT-INSTRUCTION: This is an ACTIVE PROGRAMME PLAN — the scoping document for the `server`
     category (docs/server/). Unlike Plan.0–Plan.4 it is NOT historical: it is the current
     specification of *why* the Paper/Folia endpoint is shaped the way it is. The per-task
     specifications live in docs/server/Task.<n>.md and they are the source of truth for scope and
     status; this file is the source of truth for the ARCHITECTURE and the LOCKED DECISIONS behind
     them. When a task file contradicts a locked decision here, the task file is the bug — unless the
     decision is explicitly re-opened in §11. Keep every AI-AGENT-INSTRUCTION comment intact. -->

> **Active programme plan.** Per-task specifications live in [`../server/Task.<n>.md`](../server/Task.0.md);
> the index is [`../ROADMAP.md`](../ROADMAP.md) and the documentation format is
> [`../README.md`](../README.md). This file becomes historical when server task 9 closes.

# Plan 5 — NoderaEndpoint: Paper/Folia servers as first-class Nodera nodes

**Status:** ⬜ NOT STARTED (all nine tasks) · **Opened:** 2026-07-25 · **Category:** [`server`](../server/Task.0.md)

---

## 0. The one-sentence version

A Paper or Folia server, running one plugin, becomes a **Nodera node that is also a Minecraft
server**: it runs the real peer in-process, holds 100 % of its world's chunks, holds committee seats,
seeds the archive — and simultaneously serves that same world over the **vanilla Minecraft protocol**
to players who have installed nothing at all.

---

## 1. Why this exists

Today NoderaMC is closed to anyone who has not installed a NeoForge mod. That is stated as a binding
assumption in [`docs/README.md`](../README.md) §1:

> **Assumption A0:** every player runs the Nodera mod and joins as a network peer. There is no
> vanilla-client population and no second-class lane; the handshake enforces it.

A0 was the right call while the engine, the wire, and the committee stack were being proven: it kept
exactly one lane to make correct. It is the wrong call for adoption. The population that runs Paper
and Folia servers is the population that already has worlds, plugins, communities, and hardware — and
none of it can reach Nodera.

This programme opens that door **without opening a second lane**. The reframing is small and it is the
whole design:

> **A0′ — the node/tenant split.** Every *node* on the Nodera network runs the Nodera peer and is a
> first-class member. A *player* is not necessarily a node. A player who runs the mod **is** their own
> node. A player who does not is a **tenant** of a node that is one — the Paper/Folia endpoint they
> connected to. There is still exactly one validated lane: a tenant's action enters it as an
> `ActionEnvelope` proposed and signed by the endpoint's node, on the tenant's behalf.

Nothing about validation changes. The engine is still the one engine. The committee still
re-executes. No vanilla client ever gets a vote, and no vanilla client ever needs one.

### 1.1 What a tenant gives up, stated plainly

A tenant cannot verify anything. They have no key, no engine, no snapshot. They trust their endpoint
the way a player on any Minecraft server trusts that server today.

That is **not** a regression, because the alternative on offer is "cannot play at all", and it is
**not** hidden: it is recorded in [`../server/LIMITATIONS.md`](../server/LIMITATIONS.md) §C as the
definition of tenancy, and the burn-down it *does* carry — making the endpoint auditable *by other
nodes*, so a lying endpoint is caught by the network rather than by its own tenants — is a §B row
with an exit test ([L-62](../server/LIMITATIONS.md)).

---

## 2. The two roles a Paper/Folia server plays

They are independent and a server may hold either or both.

| Role | Faces | What it means |
|---|---|---|
| **Endpoint** | unmodified players | Speaks the vanilla Minecraft protocol on a normal port. A player adds it to their server list and joins. The world they land in is a Nodera world |
| **Primary host** | the Nodera mesh | Runs `dev.nodera.peer.PeerRuntime` **in-process**, joins the world's session, holds committee seats, seeds the archive, and holds **100 % of the world's chunks** |

The endpoint role needs no protocol work — being a Minecraft server is the whole of it. Every
interesting decision in this plan belongs to the primary-host role, or to the seam between them.

---

## 3. The primary-host contract

The request that opened this programme states it directly: *primary hosts must be reliable and
maintain 100 % of the world chunks, constantly synchronizing with the peer.* Formalised as five
clauses, each of which is a testable property rather than an intention.

### C1 — Full custody

A node declares a **custody class** in its membership entry:

| Class | Who | Holds |
|---|---|---|
| `VIEW` | a player's node (mod or worker attached to a player) | only the regions its field-of-view disc covers |
| `FULL` | a primary host (a Paper/Folia endpoint, a dedicated archival peer) | **every** region of the world, loaded or not, at its current certified head |

`FULL` is a claim about *serving*: the node can answer a request for any region's current certified
state at any time, without loading a chunk into the game. It is backed by the peer's own
`WorldStore` + `WorldArchive`, not by the server's chunk cache — a chunk that is unloaded in
Minecraft is still held by the node.

### C2 — Continuous synchronisation, and it is checkable

Custody is a live claim, not a boot-time snapshot. Every announce carries a **custody digest**: a
Merkle root over `(RegionId → head SnapshotVersion)` for the whole world, in canonical `RegionOrder`.
Any peer may spot-check a random region against it through the existing archive-audit path
(`ArchiveAuditTask`, `ArchiveRepairService`).

A node whose spot-check fails is **downgraded to `VIEW`**, loudly, and keeps playing. The world stays
available; the claim does not. This is the same shape as every other trust decision in the project:
*verified, never trusted* ([`docs/README.md`](../README.md) §4.3 rule 7).

### C3 — Ownership without occupancy: tenant views are the endpoint's views

`ViewOwnershipPlanner` hands each region to the **nearest player node**. An endpoint has no player
view of its own — but its tenants are standing in the world, and they are *its* responsibility.

So the endpoint contributes **its tenants' discs, all of them, under its own single `NodeId`**, and a
node's distance to a region is the **minimum** over its views.

This is a real change to Minecraft-free code and it is the first deliverable that touches `core`:

```java
// today
static Map<RegionId, RegionClaim> plan(Map<NodeId, PlayerView> views, int maxCommitteeSize)

// after server task 3
static Map<RegionId, RegionClaim> plan(Map<NodeId, Collection<PlayerView>> views,
                                       int maxCommitteeSize,
                                       Collection<NodeId> residents)
```

Determinism is preserved: `min` over a sorted view list is deterministic, the existing `NodeId`
tiebreak is untouched, and every peer that knows the same views still computes the same plan. The
single-view overload stays as a delegating convenience so no existing caller changes.

### C4 — Capacity never buys authority; it only breaks ties

An endpoint that primaries every region because it is bigger is a central server wearing a costume,
and this project exists to not have one. So:

- **Distance decides.** A tenant's disc and a modded player's disc are ranked identically. If the
  modded player is nearer, the modded player's node is primary and the endpoint is a validator.
- **Custody class breaks ties only.** `FULL` outranks `VIEW` *only* when the geometric distance is
  equal, ahead of the `NodeId` tiebreak.

This mirrors the invariant the project already holds for dedicated servers: *the server is special in
capacity and availability, not in authority*.

### C5 — Standby primacy

When a region's primary node leaves, the endpoint is the natural successor: it already holds the
state, so promotion costs no transfer. That is the existing resident-validator and gateway-election
machinery, with the new property that the successor is never cold.

---

## 4. Folia: authority regions vs execution regions

This is the hardest part of the programme and the part most likely to be got wrong by conflating two
things that merely share a word.

> **Nodera regions are units of _authority_. Folia regions are units of _execution_. The plugin is
> the mapping between them.**

| | Nodera region | Folia region |
|---|---|---|
| Size | **8×8 chunks**, static grid | one or more **16×16-chunk sections**, dynamic |
| Identity | stable `RegionId`, leases, epochs | none — merge/split creates new objects |
| Purpose | who may commit state here | which thread ticks these chunks |
| Lifetime | forever | until the next merge or split |

### ALIGN-1 — the nesting invariant (load-bearing)

A Folia section is `2^gridExponent` chunks per side, anchored at chunk 0. A Nodera region is 8 chunks
per side, anchored at chunk 0. With the Folia default `grid-exponent = 4`:

```
section  s  covers chunks [16s , 16s+15]
region   m  covers chunks [ 8m ,  8m+7 ]
⇒ section s contains exactly regions m = 2s and m = 2s+1, whole, on each axis
⇒ 4 whole Nodera regions per Folia section, never a partial one
```

Generalised: **for any `grid-exponent ≥ 3`, every Nodera region lies entirely inside exactly one Folia
section, and therefore inside exactly one Folia region.**

The consequence is the thing that makes this programme tractable:

> **All Minecraft-side work for one Nodera region runs on exactly one Folia region thread, with no
> locking, for free.**

The plugin therefore **requires** `threaded-regions.grid-exponent ≥ 3` and refuses to enable with a
named, actionable message otherwise — never a silent degrade. (Folia's default is 4; the only way to
violate it is to deliberately set 0–2.)

### Where each kind of work runs

| Work | Thread context | How it gets there |
|---|---|---|
| Event capture for region `R` | the Folia region owning `R` | the event already fires there |
| `WorldMutationApplier.apply` for `R` | the Folia region owning `R` | `RegionScheduler.execute(world, R.centerChunkX, R.centerChunkZ, …)` |
| `reExtract` / root hashing for `R` | the Folia region owning `R` (read), hashing hopped off | extract on-region, hash on `AsyncScheduler` |
| Proposal, vote, transport I/O | the peer's own executors | `PeerRuntime` already owns them; never a tick thread |
| Announce, identity, permissions, chat, op | the global region | `GlobalRegionScheduler` |
| Archive packing + streaming | async | `AsyncScheduler` + the existing `WorldArchiver` cadence |

### The single-writer invariant on a server with no main thread

`WorldMutationApplier` documents itself as *"server main thread only (single writer); not
thread-safe"*. Folia has no main thread. The resolution is not to weaken the invariant but to read it
correctly: what it needs is **one writer per region**, and that is exactly what ALIGN-1 delivers.

- **One `WorldMutationApplier` instance per Nodera region**, pinned to that region's Folia region
  thread. Single-writer holds, per region, with no lock.
- **Cross-region deltas** (`applyAll` — entity handoff, contraption migration) are the exception: two
  Nodera regions may sit in different Folia regions, and Folia cannot put two region threads in one
  critical section.

  The answer is **not** a lock. `storage` already carries *joint transfer certificates* and *durable
  transfer stages* for exactly this shape of problem. A cross-Folia-region delta becomes a
  **prepare/commit** across the two region threads, certified jointly and journalled durably, which
  is atomic in the sense that matters (either both sides commit or neither does) even though it is
  not instantaneous. Until that lands, a delta spanning two Folia regions is **refused with a named
  error**, never partially applied ([L-64](../server/LIMITATIONS.md)).

### Paper without Folia

On plain Paper the whole mapping collapses to "one region, the main thread" — which is precisely how
Folia's own scheduler API behaves on Paper. One jar serves both: a `NoderaScheduler` seam with a
`FoliaSchedulers` and a `PaperSchedulers` implementation, selected once at enable time by probing for
`io.papermc.paper.threadedregions.RegionizedServer`.

Paper still gets real parallelism where it is available: everything that does **not** touch world
state — proposal building, canonical encoding, root hashing, transport — runs on the peer's pool, so
the main thread does capture and apply and nothing else.

---

## 5. World I/O: what the plugin changes, and the one thing it refuses

The request is that the plugin *"modify server behavior regarding how it accesses region files, saves
data, and processes entities, AI, and all Minecraft events."* All four are delivered. One
**mechanism** is refused, and the refusal is the reason the rest can be a plugin at all.

### 5.1 Refused: replacing the region-file format layer

Replacing `RegionFileStorage` / `ChunkSerializer` from a plugin means NMS reflection against internals
that move on every Paper build, and it means writing `.mca` from outside the server's own chunk
system. MultiPaper needed a **server fork** to do this safely. A plugin that does it is a corruption
vector, and the first Paper update turns it into data loss.

This is a locked decision (`docs/server/LIMITATIONS.md` §C), not a scoping shortcut. The escape hatch
— a `paperweight-userdev` NMS adapter behind a version pin — is specified as an **optional stage 3**
of [server task 4](../server/Task.4.md) and is gated behind its own limitation row, so that if it is
ever genuinely needed it arrives with a version matrix and a test, not as a surprise.

### 5.2 Delivered: custody, gating, and the save boundary

What actually has to change is *behaviour*, and every piece of it has a supported seam:

| Behaviour | Seam | Effect |
|---|---|---|
| **Chunk access is gated on certified state** | `WorldMutationApplier.ChunkEditability` (exists) + `ChunkLoadEvent` | a chunk whose Nodera region's certified state has not arrived is **not editable** — a delta touching it aborts before any write, and player edits are held, exactly like the async client pipeline already does |
| **Reads reconcile against the peer** | a custody reconciler over `RegionOrder` | on boot and continuously, per-region digests are compared with the peer's `WorldStore` heads; divergence is repaired **through `WorldMutationApplier`**, never by writing files behind the server |
| **Saves are the archive cadence** | `WorldSaveEvent`, `ChunkUnloadEvent`, `EntitiesUnloadEvent`, plus a scheduled `world.save()` | the existing continuous-streaming archive lane (issue #43) is driven from real save boundaries instead of a tick counter, so what the network holds is what the disk holds |
| **Foreign writes are certified, not suppressed** | the interference guard (exists) | a plugin's block write becomes a certified `ExternalDelta` — see §6 |
| **Entities, AI, and events** | the Bukkit event mirror | see §5.3 |

The net effect on the operator's world folder: **it stays a normal Minecraft world folder**, readable
by vanilla tools, restorable from a backup, and openable by a plain Paper server with the plugin
removed. That is a feature, not an accident.

### 5.3 The event mirror

The NeoForge mod's `EntityCaptureBridge` is the reference implementation. The plugin re-expresses it
against Bukkit/Paper events; the full mapping table is normative and lives in
[`../server/REFERENCE.md`](../server/REFERENCE.md) §4. Two rows in it are genuinely hard and are named
here because they carry limitation rows:

- **`EntityTickEvent.Pre` cancellation** (how a validated item's vanilla projection is frozen) has no
  Bukkit equivalent. The plugin pins the projection instead — unlimited lifetime, zeroed velocity,
  pickup routed through `EntityPickupItemEvent` — and reconciles rather than suppresses.
- **`LevelTicksMixin`** (how vanilla scheduled ticks are cancelled at the source in a delegated
  region) has no Bukkit equivalent at all. Until one exists, an endpoint's delegated regions run
  vanilla redstone and reconcile through the interference guard ([L-67](../server/LIMITATIONS.md)).

Mob AI is **vanilla-authoritative** on an endpoint, exactly as it is in the mod today: mobs are
captured as ghosts and emitted as external mutations. Deterministic mob AI is
[`engine/Task.11.md`](../engine/Task.11.md) and is not in this programme's scope. The difference an
endpoint makes is *breadth*: a modded player's node captures the mobs in its field of view, an
endpoint captures every mob in the world it holds.

---

## 6. "100 % compatible with plugins" — made falsifiable

An unqualified "100 %" cannot be tested, so the programme defines it as four clauses that can each go
red.

**PC-1 — API surface.** NoderaEndpoint adds no throwing override to any Bukkit/Paper/Folia API.
Anything a plugin could call before it can call after, with the same semantics. Proven by a
**compatibility corpus** — a pinned set of widely-deployed plugins loaded on an endpoint server and
exercised by a scripted drive, with a clean log as the exit.

**PC-2 — Event contract.** Every Bukkit event still fires, in the same order, with the same
cancellability. NoderaEndpoint listens at `MONITOR` for observation and only at `LOWEST`/`HIGH` where
it must gate. It never cancels an action another plugin has allowed *unless* certified state forbids
it — and when it does, it fires `NoderaRegionDeniedEvent` so the denial is visible rather than
mysterious.

**PC-3 — Write reconciliation.** A plugin that writes through the Bukkit API is a **foreign write
source**, and Nodera already has the answer: the interference guard converts foreign writes into
certified `ExternalDelta`s instead of suppressing them.

> **Plugins keep authority over the world; Nodera certifies what they did.**

A write that cannot be certified — because it raced a committed delta — is reverted *with an event*,
never silently. A write to a region this node does not primary is routed, and routing costs a tick;
that is the physics of a partitioned world and it is recorded as an envelope constraint, not a bug.

**PC-4 — Folia readiness.** The plugin declares `folia-supported: true` and never touches
`BukkitScheduler`. A third-party plugin that is *not* Folia-ready still fails to load on Folia —
that is Folia's rule and NoderaEndpoint neither adds to it nor works around it. On Paper, everything
loads exactly as before.

Plugins are never *required* to be Nodera-aware. An optional `NoderaEndpointAPI` is published through
the Bukkit services manager for plugins that want region ownership, custody, or committee state.

---

## 7. Who is admitted, and how

### 7.1 Unmodified clients — tenants

A vanilla player joins on a normal port. The endpoint:

1. resolves them to a **tenant id**: `NodeId` derived deterministically from
   `(endpointNodeId, playerUUID)` — stable across sessions, unique across endpoints, and never
   present in membership, so it can never be mistaken for a node;
2. proposes their actions as `ActionEnvelope`s **signed by the endpoint's key**, with the tenant id as
   the actor.

This is not a new mechanism. `LiveEntityLaneRuntime.submit` already builds
`NodeId actor = new NodeId(player.getUUID())`, calls
`validation.registerActor(actor, authority.publicKeyBytes())`, and signs with the host's identity —
the path that exists today for *"players whose clients have not announced a node yet"*. The programme
makes that transitional path **permanent, explicit, and auditable**: the endpoint publishes a signed
**tenant roster** so the mapping is a network fact rather than a local convention.

Optional later hardening — an `onBehalfOf` field on `ActionEnvelope` — is a frozen-contract change
(encoding-version bump, golden fixture, Rust mirror) and is deliberately **not** in the MVP.

**The world password for a tenant.** L-52's live-join gate runs in the configuration phase over a mod
payload; a vanilla client cannot answer it. On an endpoint the password becomes the endpoint's gate:
the tenant lands spawn-locked in a limbo and runs `/nodera join <password>`. The plaintext never
leaves the process and never reaches a log ([L-68](../server/LIMITATIONS.md)).

### 7.2 Modded clients — nodes

A NeoForge client with the Nodera mod that connects to an endpoint is **not a tenant**. It is its own
node: its actions are signed with its own key and forwarded to the region's primary exactly as they
are today. One world, two client classes, one lane.

Two mechanisms make it work:

- **The handshake rides plugin messaging.** NeoForge custom payloads do not exist on a Paper server,
  so the endpoint↔mod handshake uses the one channel both platforms speak: a plugin message on
  `nodera:v1`. The mod's existing payloads must therefore be declared **optional** so a modded client
  can still join a plain Paper server that has no plugin at all.
- **Admission is `registryFingerprint` equality.** The request's rule — *NeoForge players may join
  except where the host requires specific mods* — resolves to a test the codebase already has.
  `GenesisManifest.registryFingerprint` is what makes two peers agree on the block/item palette; a
  client whose fingerprint differs would compute different roots and diverge. So:

  > **A client is admitted iff its registry fingerprint matches the world's.**

  A cryptographic answer to a mod-compatibility question. The endpoint additionally publishes a
  human-readable required/allowed mod set so the refusal message names *which* mod, and advertises
  `vanillaFriendly` on the tracker: an endpoint declaring a non-empty required mod set cannot serve
  unmodified clients and says so before anyone tries.

> **Assumption stated, because the source rule is ambiguous:** "except in cases where the host
> requires specific mods that the connecting client has installed" is read as **mod-set gating** —
> the endpoint declares what a client must have and must not have, and the fingerprint enforces it. If
> the intent was the inverse (the host must accept whatever the client brings), the fingerprint test
> would have to be relaxed to a palette-superset check, which is a different and much larger piece of
> work. This assumption is recorded in [`../server/Task.7.md`](../server/Task.7.md) §Design and is the
> first thing to re-confirm before that task starts.

---

## 8. What this reuses (the point of doing it this way)

Almost none of this programme is new machinery. Named explicitly so the tasks stay honest about it:

| Need | Existing thing | Category |
|---|---|---|
| The peer the endpoint runs | `PeerRuntime`, `HeadlessPeerMain` | network, worker |
| The control plane | `ControlProtocol` v2 verbs (`HOST`/`MESH`/`SEED`/`ARCHIVE`/`STATE`) | worker |
| Region ownership | `PlayerView`, `PlayerViewRegionResolver`, `ViewOwnershipPlanner` | core |
| Committee validation | `WorkerValidationService`, `CommitteeManager`, quorum certificates | engine |
| The one world writer | `WorldMutationApplier` + `MutableWorldView` | engine |
| Chunk gating | `WorldMutationApplier.ChunkEditability`, `ChunkLockMap` | engine, peer |
| Foreign writes | the interference guard, `ExternalDelta` | engine |
| Cross-region atomicity | joint transfer certificates, durable transfer stages | storage |
| World identity, permissions, ops | `WorldIdentity`, `WorldPermissions`, `OperatorBridge` shape | storage, minecraft |
| Continuity | `WorldArchive`, `WorldArchiveService`, continuous streaming | peer, worker |
| Tenant-visible surfaces | tab list, boss bar, action bar, chat renderers | minecraft |
| Mixed-client evidence | `scripts/lib/e2e-main.sh`, the suite launcher and topology | minecraft |

The rule the `minecraft` category lives by applies here unchanged and is repeated in the server
charter: **this module wires; it never re-implements.**

---

## 9. Delivery — nine tasks

Each is a coherent deliverable with its own acceptance criteria. Full specifications in
[`../server/`](../server/Task.0.md).

| # | Task | Delivers | Depends on |
|---|---|---|---|
| [1](../server/Task.1.md) | Plugin skeleton, build lane, platform abstraction | `java/paper-plugin` builds one jar that enables on Paper **and** Folia; `NoderaScheduler` seam; ALIGN-1 preflight | — |
| [2](../server/Task.2.md) | Embedded peer + control plane | the real `PeerRuntime` in-process; identity; in-JVM control link; optional external worker attach | server 1 |
| [3](../server/Task.3.md) | Region custody and the ownership bridge | C1–C5; multi-view `ViewOwnershipPlanner`; the Nodera↔Folia region map | server 2 |
| [4](../server/Task.4.md) | World I/O: custody reconciler, chunk gating, save boundary | §5 in full, minus the refused mechanism | server 3 |
| [5](../server/Task.5.md) | Entity, mob, and event capture lane | the Bukkit mirror of `EntityCaptureBridge`, region-scheduled | server 4 |
| [6](../server/Task.6.md) | The vanilla endpoint: tenants | tenant ids, the signed roster, the limbo password gate, tenant-visible surfaces | server 5 |
| [7](../server/Task.7.md) | Modded clients on an endpoint | `nodera:v1` plugin-message handshake; fingerprint admission; mod-set publication | server 6 |
| [8](../server/Task.8.md) | Plugin compatibility contract | PC-1…PC-4, `NoderaEndpointAPI`, the corpus | server 5 |
| [9](../server/Task.9.md) | Live acceptance: mixed-client suites + CI | `e2e-endpoint.sh`, `e2e-folia.sh`, `e2e-plugins.sh`, matrix entries | server 8 |

**Ordering rationale.** 1→2 is a build lane and a process. 3 is where the design risk lives (ALIGN-1
and multi-view ownership) and it is deliberately *before* any Minecraft behaviour changes, so a wrong
answer is cheap. 4→5 are the two invasive layers, in dependency order. 6 and 7 are the two client
classes and 7 needs 6's identity work. 8 can start as soon as 5 lands. 9 closes.

**Biggest risk, front-loaded:** ALIGN-1. If the nesting invariant does not hold — a Folia build that
anchors sections differently, an operator running `grid-exponent` below 3 — the whole "free
per-region single-threading" property evaporates and the plugin needs real cross-thread coordination
for ordinary work. Task 1 preflights it and task 3 tests it, both before anything depends on it.

---

## 10. Version pins — the one unresolved external constraint

| Component | Pin |
|---|---|
| The mod (`minecraft` category) | Minecraft **1.21.1** · NeoForge 21.1.238 · Java 21 |
| The Folia study in [`../minecraft/folia/`](../minecraft/folia/) | Folia targeting Minecraft **26.1.2** · Java 25 |

A 1.21.1 client cannot join a 26.1.2 server, so the mixed-client suites must run against **Paper and
Folia builds of the same Minecraft version the mod pins**. The programme therefore pins **Paper
1.21.1** and **Folia 1.21.1**, resolved through the PaperMC v2 API at CI time.

The study documents the newest Folia because that is what was read; the API surface this plugin uses
(`RegionScheduler`, `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`,
`Bukkit.isOwnedByCurrentRegion`, `folia-supported`) has been stable across the 1.20/1.21 line, so the
study stays the correct architectural reference even though it is a version ahead.

**This must be confirmed against a real download before task 1 starts.** If no Folia 1.21.1 build
exists, the fallback — Paper-only endpoint suites plus a Folia suite pinned to the nearest available
1.21.x — ships with the gap recorded rather than papered over. Tracked as
[L-66](../server/LIMITATIONS.md).

---

## 11. Locked decisions

<!-- AI-AGENT-INSTRUCTION: These are LOCKED. A change proposal that violates one is a design
     regression and must be refused, not implemented. Re-opening one requires editing this section
     with a stated reason, in the same commit as the change. -->

1. **One engine, ever.** The plugin re-executes nothing. It calls the Java `engine` like every other
   consumer. A "fast path" that computes state a second way is forbidden in any language.
2. **Nodera regions stay 8×8 and static.** They are authority units. Folia's dynamic regions are
   execution units. Adopting Folia's partitioning as Nodera's would destroy region identity, and with
   it leases, epochs, and certificates.
3. **No region-file format replacement from a plugin.** §5.1. The escape hatch is a version-pinned
   NMS adapter, gated behind its own limitation row, and it is a last resort.
4. **Capacity never buys authority.** C4. `FULL` custody breaks ties; it never outranks distance.
5. **Tenants are not second-class in the lane.** A tenant's action goes through the same propose →
   re-execute → quorum → commit path as anyone's. The difference is who signs it, and that is stated
   on the wire, not hidden.
6. **The world folder stays a normal Minecraft world folder.** Removing the plugin must leave a world
   a plain Paper server can open.
7. **`folia-supported: true`, and never `BukkitScheduler`.** Not conditional, not behind a flag.
8. **Documentation is part of the deliverable.** Every task lands with its
   [`../server/`](../server/Task.0.md) files updated in the same commit
   ([`../README.md`](../README.md) §5).

---

## 12. Verification

| Level | What it proves |
|---|---|
| Minecraft-free unit tests (`java/paper-plugin` has none that need a server) | ALIGN-1 arithmetic, custody digests, tenant-id derivation, the scheduler seam's dispatch decisions, `NoderaScheduler` platform selection |
| `core`/`engine` unit tests | multi-view ownership planning; the min-distance rule; determinism under N views |
| Plugin ITs on a real Paper server | enable/disable, event mirror fidelity, chunk gating, foreign-write certification |
| `scripts/e2e-endpoint.sh` | **the headline drive:** an unmodified client and a modded client in the same world, one joining directly at the endpoint, one through the Nodera network, with an item passing between them exactly once |
| `scripts/e2e-folia.sh` | two Folia regions ticking in parallel, ALIGN-1 holding live, and **zero** `ensureTickThread` violations |
| `scripts/e2e-plugins.sh` | PC-1…PC-3 against the pinned corpus |

The three suites exist **now**, ahead of the code, and report `SKIPPED` naming
[L-61](../server/LIMITATIONS.md) until the plugin jar is built — the same discipline `e2e-mobs.sh`
already uses for L-60. A suite that asserts something no node in the topology can do proves nothing;
a suite that says exactly what is missing is a specification.
