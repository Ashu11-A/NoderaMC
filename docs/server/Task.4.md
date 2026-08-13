# Server Task 4 — World I/O: Custody Reconciler, Chunk Gating, Save Boundary

<!-- AI-AGENT-INSTRUCTION: The plugin changes world-access BEHAVIOUR through supported seams. It does
     NOT replace RegionFileStorage/ChunkSerializer and it NEVER writes .mca behind the server's chunk
     system — that is fork territory and a corruption vector on every Paper update (LIMITATIONS.md
     §C). The world folder must stay openable by a plain Paper server with the plugin removed. Every
     repair goes through WorldMutationApplier. Stage 3 (an NMS adapter) is a LAST RESORT and arrives
     with a version matrix and a test or not at all. Keep this header accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** server · **Owns:** L-64 · **Last audit:** 2026-08-10
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

In progress, and only deliverable 6 has moved. **The cross-Folia-region delta now has a detector, a
refusal, and a commit path.** Before 2026-08-10 nothing on the plugin path could answer "are these
two regions written by one thread?", so the delta this task exists to handle could not be detected,
let alone refused — the plugin was four classes with no `world/` package at all.

What landed (`endpoints/paper-plugin/.../endpoint/paper/world/`):

- `NoderaFoliaRegionMap.shareExecutionThread` — one tick thread ⇒ always; same Folia section ⇒
  always, by ALIGN-1 arithmetic and no runtime call; a wider span ⇒ the platform's own answer via
  `FoliaOwnershipProbe` (reflection, so neither the compile classpath nor the unit tests need a
  Folia jar); **unanswerable ⇒ does not share**. This is [3](Task.3.md)'s deliverable 7, consumed
  here.
- **Stage 1** — `CrossRegionCommit.requireJointCriticalSection` throws
  `CrossRegionRefusedException` (code `NODERA-XREGION-REFUSED`) naming both regions, the transfer,
  that nothing was written, and the resync fix. Wired into `NoderaEndpointPlugin.onEnable`, which
  runs the **live ALIGN-1 gate self-check** [3](Task.3.md) asks for: it exercises the gate on the
  regions that nest in one Folia section and on one a section away, and logs what the gate actually
  did on the exponent this server is running. On a real Folia that second call **refuses**, which is
  L-64 stage 1 firing against the real regioniser at startup rather than being asserted about.
  `e2e-folia` F1 asserts all of it.
- **Stage 2** — `CrossRegionCommit.joint` runs the real `EntityTransferCoordinator` over
  `WorldMutationApplier`, certified by `EntityTransferCertificate` and journalled to the durable
  `TransferStore` through `TransferStoreJournal`. Nothing here is a new engine: the primitives
  existed and had **zero production call sites**; this is the first one.

What has not: the joint path is **not enabled on a running endpoint**, because nothing delegates a
region on this path yet ([2](Task.2.md), [3](Task.3.md)), so there is no live cross-region delta to
drive and no way to prove the commit on Folia's own threads. [L-64](LIMITATIONS.md) therefore stays
OPEN — see its row for exactly which clause is unmet. Deliverables 1–5, 7 and 8 are untouched.

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
| 6 | Cross-Folia-region deltas: refuse with a named error (stage 1), then joint-transfer commit (stage 2) | 🚧 stage 1 shipped and wired at enable; stage 2 shipped and proven headlessly over two real threads, **not** enabled on a live endpoint (no delegated region yet) |
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

- **Stage 1 (shipped 2026-08-10).** A multi-region delta whose regions do not share an execution
  thread is **refused** with a named error and the caller resyncs. Loud and correct beats fast and
  racy; [L-64](LIMITATIONS.md) records the gap with its exit test.
- **Stage 2 (shipped 2026-08-10; not live).** The delta becomes a **prepare/commit** across the two
  region threads, certified with the *joint transfer certificates* and journalled with the *durable
  transfer stages* that `storage` already carries for exactly this shape of problem. Either both
  sides commit or neither does. It is atomic in the sense that matters, not instantaneous — and
  ALIGN-1 keeps it rare, since four Nodera regions share every Folia section.

#### Why the joint commit cannot deadlock

Folia's regioniser **refuses recursive operation**, and an uncaught exception on a tick thread halts
the scheduler and stops the whole server. So the obvious shape — thread A takes region A and blocks
until thread B hands over region B — is not slow here, it is a server-wide stall. It is also
unnecessary, because the commit does not need both threads at once:

> **The commit does not join two threads. It moves both regions' authority onto a third.**

Each region thread is *sent* a task that captures its own committed snapshot and pipeline and
completes a future — it captures nothing about the other region and waits for nothing. When both
futures have completed, observed by composition on `CrossRegionCommit`'s own `nodera-xregion-commit`
thread (never a region thread), the whole coordinator runs there against the canonical applier,
touching no Bukkit state and therefore needing no region ownership. Each thread is then *sent* its
own certified delta to project.

A deadlock needs a cycle in the wait-for graph. Region threads never wait — every cross-thread step
is a message, never a `get`, a `join`, or a lock — so only one node in that graph has an outgoing
edge, and a graph like that has no cycle. The wait is additionally bounded by a park timeout, and a
park that never arrives resumes the side that did.

Atomicity lives on the canonical side, not the projection: both regions commit in one two-pass
compare-and-set on one thread. A projection that fails afterwards is repaired by the custody
reconciler (deliverable 4), never by half-committing.

### Adopt-from-network bootstrap

This is what makes an endpoint a **gateway into an existing network** rather than one more server:
given a world id, the endpoint fetches the newest archive from the swarm (`NODERA-ARCHIVE`), unpacks
it into the server's world folder **before the world loads**, adopts its signed `WorldIdentity`, and
joins the session as a `FULL`-custody member. An operator points at a world id and gets a server
serving it.

The reverse — generate a fresh world, certify genesis, publish, seed — is the existing
`NoderaHost.activate` flow re-expressed for Bukkit, with no new concepts.

## Files

- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/paper/world/{BukkitWorldView,EndpointChunkGate,CustodyReconciler,SaveBoundaryArchiver,WorldAdoptionService}.java`
- `endpoints/paper-plugin/src/main/java/dev/nodera/endpoint/paper/world/{NoderaFoliaRegionMap,FoliaOwnershipProbe,CrossRegionCommit,CrossRegionRefusedException,TransferStoreJournal}.java`

> **The package is `dev.nodera.endpoint.paper.world`, not `dev.nodera.endpoint.world`** as earlier
> drafts of this file said. `dev.nodera.endpoint.world` already exists in `:endpoint` (the per-world
> durable stores), and `:endpoint` is fat-jarred **inside** the plugin — so the original path would
> have split one package across two jars and merged it back at package time. The plugin's own tree
> is where a Paper-side class belongs anyway.

## Testing

- `BukkitWorldViewTest` — `MutableWorldView` conformance against the same contract
  `InMemoryWorldView` satisfies (block get/set, entity rows, credits, mutation scope rollback).
- `EndpointChunkGateTest` — a delta touching an un-arrived region aborts with **zero** writes; the
  same delta commits once the state arrives.
- `CustodyReconcilerTest` — a divergent region is repaired through the applier and logged; a repair
  the applier refuses is retried, never forced; a clean world produces no repairs at all.
- `NoderaFoliaRegionMapTest` (7) — one tick thread shares everything; two regions in one Folia
  section share **without asking the platform**; a wider span is the platform's answer and an
  unanswerable one is a refusal; two dimensions are never one thread; a splitting grid exponent is
  refused with the ALIGN-1 message.
- `CrossRegionCommitTest` (4) — stage 1: the refusal names both regions, the transfer, that nothing
  was written and the resync fix, and the durable `TransferStore` it was holding has **zero**
  records afterwards. Asserted positively on the message, not merely that something threw.
- `CrossFoliaRegionCommitIT` (4) — stage 1 refuses with a named error; stage 2 commits a joint
  transfer across two region threads atomically, and a failure on one side commits neither (two
  shapes: the target's state moving between certification and the paired CAS, and a region thread
  that never reaches its park point). **This is L-64's exit, and it is only half met**: the threads
  are real and separate, but they are not Folia's — see the suite's own header, and L-64's row, for
  what that does and does not prove. There is deliberately no `assumeTrue` in the file.
- `WorldAdoptionIT` — a world id on the network becomes a served world folder on a fresh endpoint,
  byte-exact against the seeder.
- Live (`e2e-endpoint.sh` P1, `e2e-folia.sh` F4): the endpoint adopts a world from the network; a
  world save appears as a new archive version on another peer within the configured interval.

## Acceptance criteria

1. ⬜ A chunk with no certified state is not editable and a delta touching it aborts with zero writes.
2. ⬜ A divergent world folder converges on the certified state through the applier, with every repair
   logged.
3. ⬜ A `world.save()` produces a new archive version another peer can fetch.
4. 🚧 A cross-Folia-region delta is refused (stage 1) / commits atomically (stage 2) — never
   partially. Stage 1 is met and wired at enable; stage 2's code and headless proof are met over two
   real threads; what is unmet is that those threads are Folia's on a running endpoint.
5. ⬜ An endpoint adopts a world from the network by id and serves it.
6. ⬜ Removing the plugin leaves a world a plain Paper server opens without complaint.

## Limitations

- **L-64** — the joint-transfer path exists and is proven headlessly; the row stays OPEN until it
  runs on two real Folia region threads on a delegated region.
