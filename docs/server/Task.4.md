# Server Task 4 — World I/O: Custody Reconciler, Chunk Gating, Save Boundary

<!-- AI-AGENT-INSTRUCTION: The plugin changes world-access BEHAVIOUR through supported seams. It does
     NOT replace RegionFileStorage/ChunkSerializer and it NEVER writes .mca behind the server's chunk
     system — that is fork territory and a corruption vector on every Paper update (LIMITATIONS.md
     §C). The world folder must stay openable by a plain Paper server with the plugin removed. Every
     repair goes through WorldMutationApplier. Stage 3 (an NMS adapter) is a LAST RESORT and arrives
     with a version matrix and a test or not at all. Keep this header accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** L-64 · **Last audit:** 2026-07-25
**Depends on:** [server 3](Task.3.md), [engine 6](../engine/Task.6.md), [network 3](../network/Task.3.md), [network 9](../network/Task.9.md)
**Consumed by:** [server 5](Task.5.md), [server 8](Task.8.md)

---

## Goal

Change how the server accesses, gates, and persists world data, so that "this endpoint holds 100 % of
the world and stays synchronized with its peer" is a mechanism rather than a promise:

- a chunk whose Nodera region has no certified state yet is **not editable**, and a delta touching it
  aborts before any write;
- disk and the peer's `WorldStore` are **continuously reconciled**, with the certified state winning
  and every repair going through the single world writer;
- **saves are the archive cadence** — what the network holds is what the disk holds, at real save
  boundaries rather than a tick counter;
- and a delta spanning two Folia regions is handled correctly instead of racing.

## Status detail

Not started.

## Dependencies

- [server 3](Task.3.md) — custody, the region map, and the ownership plan.
- [engine 6](../engine/Task.6.md) — `WorldMutationApplier`, `MutableWorldView`, `ChunkEditability`.
- [network 3](../network/Task.3.md) — the event-sourced and durable `WorldStore` tiers.
- [network 9](../network/Task.9.md) — crash safety and the active-player stream.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `BukkitWorldView` — `MutableWorldView` over a Bukkit `World`, one instance per Nodera region | ⬜ |
| 2 | One `WorldMutationApplier` per Nodera region, pinned to that region's execution thread | ⬜ |
| 3 | `EndpointChunkGate` — `ChunkEditability` wired to certified-state arrival + `ChunkLoadEvent` | ⬜ |
| 4 | `CustodyReconciler` — boot walk + continuous per-region digest comparison, repair via the applier | ⬜ |
| 5 | Save-boundary archive cadence (`WorldSaveEvent`, `ChunkUnloadEvent`, `EntitiesUnloadEvent`, timer) | ⬜ |
| 6 | Cross-Folia-region deltas: refuse with a named error (stage 1), then joint-transfer commit (stage 2) | ⬜ |
| 7 | Adopt-from-network world bootstrap (`NODERA-ARCHIVE` fetch → unpack → adopt identity → join session) | ⬜ |
| 8 | *(optional, gated)* stage 3: a version-pinned `paperweight-userdev` chunk-IO adapter | ⬜ |

## Design

### What is refused, and why the rest is possible

Replacing `RegionFileStorage` / `ChunkSerializer` from a plugin means NMS reflection against
internals that move on every Paper build, and writing `.mca` from outside the server's own chunk
system. MultiPaper needed a **server fork** to do that safely. A plugin that does it is a corruption
vector, and the first Paper update turns it into data loss.

So the plugin changes *behaviour*, not the file format, and the consequence is a feature:

> **The world folder stays a normal Minecraft world folder** — readable by vanilla tools, restorable
> from a backup, and openable by a plain Paper server with the plugin removed.

Stage 3 exists as a documented escape hatch, not as a plan: if a future requirement genuinely cannot
be met at the behaviour layer, an NMS adapter arrives behind a version pin, a compatibility matrix,
and its own limitation row — never as a quiet reflection call added to a class.

### The four behaviour changes, each on a supported seam

| Behaviour | Seam | Effect |
|---|---|---|
| **Access is gated on certified state** | `WorldMutationApplier.ChunkEditability` + `ChunkLoadEvent` | a chunk whose region's certified state has not arrived fails closed: a delta touching it **aborts before any write**, and a player edit there is held and retried, exactly as the async client chunk pipeline already behaves (L-33's shape) |
| **Reads reconcile against the peer** | `CustodyReconciler` over `RegionOrder` | per-region digests compared with `WorldStore` heads on boot and continuously; divergence repaired **through `WorldMutationApplier`** |
| **Saves drive the archive** | `WorldSaveEvent`, `ChunkUnloadEvent`, `EntitiesUnloadEvent`, scheduled `world.save()` | the continuous-streaming archive lane (issue #43) fires at real save boundaries, so the network copy tracks the disk rather than a tick count |
| **Foreign writes are certified** | the interference guard | see [server 8](Task.8.md); a plugin's write becomes a certified `ExternalDelta` |

### Divergence: the certified state wins, and the repair is auditable

A world folder can drift from the peer's certified heads — an operator restored a backup, a plugin
wrote while the node was down, a crash lost the last save. The reconciler's rule is simple and never
negotiated:

> **Certified state wins. The repair is applied through `WorldMutationApplier`, region by region, and
> every repaired region is logged with its before/after head.**

Never a file copy, never a bulk overwrite. A repair that the applier refuses (a CAS mismatch under
concurrent play) is retried on the next pass rather than forced — the applier's all-or-nothing
contract is what keeps a partial repair impossible.

### The cross-Folia-region delta

Two Nodera regions may sit in different Folia regions, and Folia cannot put two region threads in one
critical section. The answer is **not** a lock.

- **Stage 1 (ships first).** A multi-region delta whose regions do not share an execution thread is
  **refused** with a named error and the caller resyncs. Loud and correct beats fast and racy;
  [L-64](LIMITATIONS.md) records the gap with its exit test.
- **Stage 2.** The delta becomes a **prepare/commit** across the two region threads, certified with
  the *joint transfer certificates* and journalled with the *durable transfer stages* that
  `storage` already carries for exactly this shape of problem. Either both sides commit or neither
  does. It is atomic in the sense that matters, not instantaneous — and ALIGN-1 keeps it rare, since
  four Nodera regions share every Folia section.

### Adopt-from-network bootstrap

This is what makes an endpoint a **gateway into an existing network** rather than one more server:
given a world id, the endpoint fetches the newest archive from the swarm (`NODERA-ARCHIVE`), unpacks
it into the server's world folder **before the world loads**, adopts its signed `WorldIdentity`, and
joins the session as a `FULL`-custody member. An operator points at a world id and gets a server
serving it.

The reverse — generate a fresh world, certify genesis, publish, seed — is the existing
`NoderaHost.activate` flow re-expressed for Bukkit, with no new concepts.

## Files

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/world/{BukkitWorldView,EndpointChunkGate,CustodyReconciler,SaveBoundaryArchiver,WorldAdoptionService}.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/world/CrossRegionCommit.java`

## Testing

- `BukkitWorldViewTest` — `MutableWorldView` conformance against the same contract
  `InMemoryWorldView` satisfies (block get/set, entity rows, credits, mutation scope rollback).
- `EndpointChunkGateTest` — a delta touching an un-arrived region aborts with **zero** writes; the
  same delta commits once the state arrives.
- `CustodyReconcilerTest` — a divergent region is repaired through the applier and logged; a repair
  the applier refuses is retried, never forced; a clean world produces no repairs at all.
- `CrossFoliaRegionCommitIT` — stage 1 refuses with a named error; stage 2 commits a joint transfer
  across two Folia region threads atomically, and a failure on one side commits neither.
  **This is L-64's exit.**
- `WorldAdoptionIT` — a world id on the network becomes a served world folder on a fresh endpoint,
  byte-exact against the seeder.
- Live (`e2e-endpoint.sh` P1, `e2e-folia.sh` F4): the endpoint adopts a world from the network; a
  world save appears as a new archive version on another peer within the configured interval.

## Acceptance criteria

1. ⬜ A chunk with no certified state is not editable and a delta touching it aborts with zero writes.
2. ⬜ A divergent world folder converges on the certified state through the applier, with every repair
   logged.
3. ⬜ A `world.save()` produces a new archive version another peer can fetch.
4. ⬜ A cross-Folia-region delta is refused (stage 1) / commits atomically (stage 2) — never partially.
5. ⬜ An endpoint adopts a world from the network by id and serves it.
6. ⬜ Removing the plugin leaves a world a plain Paper server opens without complaint.

## Limitations

- **L-64** — a delta spanning two Folia regions is refused until the joint-transfer path lands.
