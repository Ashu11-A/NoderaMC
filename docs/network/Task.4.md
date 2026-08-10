# Network Task 4 — Torrent Distribution Data Plane

<!-- AI-AGENT-INSTRUCTION: Pieces are cut at CANONICAL RECORD BOUNDARIES so the reassembled blob
     hashes to the engine's own StateRoot. Do not "optimise" the split to fixed-size blocks — that
     breaks the property that makes the swarm verifiable without trusting any seeder. Always
     hash-validate a piece BEFORE accepting it. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS — acceptance #6 (render on arrival) is proven headlessly and awaits a live
client run; everything else is complete
**Category:** network · **Owns:** L-33 · **Last audit:** 2026-08-10
**Depends on:** [network 1](Task.1.md), [network 3](Task.3.md), [engine 2](../engine/Task.2.md)
**Consumed by:** [network 6](Task.6.md), [network 8](Task.8.md), [network 9](Task.9.md), [worker 3](../peer/Task.3.md)

---

## Goal

Turn world data into a **swarm**. Split each region snapshot and event-log segment into addressable
sub-region pieces, each with its own hash; advertise them; fetch them from many seeders at once with
hash-validate-before-accept, retry-away-from-the-liar, and piece-level resume; and lock un-arrived
sections against both render and edit until they land.

## Status detail

Complete. `DistributionIT` reassembles a region from **3 seeders each holding under 40% of the
pieces** and proves the assembled blob hashes to the *engine's own* `StateRoot`.

The plane has a production consumer: the world-continuity lane files whole saves under
`PieceManifest` (with manifest-exchange message tags 51/52) and the worker seeds and fetches them —
`WorldContinuityIT` proves a shared world survives its host's death over the real tracker and
rendezvous binaries.

**The header used to say COMPLETED while acceptance #6 said ⏳, and the second one was right.**
Fixed on 2026-08-10 along with the two things it was hiding.

The **edit** half of the lock guard is now *installed*, not merely available. `WorldMutationApplier`
has always consulted a `ChunkEditability` seam in its verify pass — but both production appliers were
built with the one-argument constructor, i.e. `ALL_EDITABLE`, and the single production `download`
call passed `null` for the lock map, so nothing anywhere called `track` and the guard described a
state no map was ever in. `WorldArchiveService` now owns a `ChunkLockMap`, tracks a v2 manifest for
exactly as long as its region is in flight, and exposes `chunkEditability()`; `PeerNode` installs it
on the worker's validation lane. Absence fails **open** — an untracked region is fully editable,
because this applier is the choke point every world write in the process passes through and a wrong
"locked" would stop the game rather than a fetch.

The **render** half is built and proven headlessly. `RegionSnapshotSplitter.columnsIn` decodes the
columns a single verified piece carries — the property `PieceSplitter` has always cut for and nothing
ever spent — and the region fetch reports a growing, always-cumulative snapshot of what has verified.
`NODERA-FETCH-REGION` carries each of those to the game as a staged file named on an interim
`NODERA-PROGRESS` line, and `RegionApplyQueue.offerArriving` writes only the columns that are not
already on the ground. What is outstanding is the run in a real client (**L-33**).

## Dependencies

- [network 3](Task.3.md) — `ContentId` and the content store.
- [engine 2](../engine/Task.2.md) — the snapshot encoding whose record boundaries the splitter cuts.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `RegionSnapshotSplitter` — cuts at canonical record boundaries | ✅ |
| 2 | `PieceManifest` — root over index + length + hash, bound to the `StateRoot` | ✅ |
| 3 | `PieceSelector` — deterministic rarest-first with a rendezvous tie-break | ✅ |
| 4 | `PieceDownloader` / `PieceReassembler` — racing, bounded, resumable | ✅ |
| 5 | `ChunkLockMap` — fail-closed lock against render **and** edit | ✅ |
| 6 | `ContentTransferService` — serves under inflight and bandwidth bounds | ✅ |
| 7 | `WorldArchive` — whole-save packing filed under the piece plane | ✅ |
| 8 | Renderer consulting the lock map on arrival | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Cut at record boundaries so the swarm is verifiable without trusting anyone.** Because a piece is a
byte-exact slice of the canonical `RegionSnapshot` encoding, the reassembled blob hashes to the
committee's own `StateRoot`. A joiner therefore verifies the *world* rather than the *seeders*: it can
accept bytes from peers it has no reason to trust and still detect a single flipped bit.

**Hash-validate before accept, then retry away from the liar.** Validating after acceptance means the
corrupt piece is already in the store. Validating first turns a lying seeder into a routing decision
rather than a corruption event.

**Rarest-first is deterministic here, unlike BitTorrent.** Every peer computes the same selection
order from the same manifest, with a rendezvous tie-break. Deterministic selection means two joiners
do not stampede the same seeder, and it means the selection can be property-tested.

**Lock-until-arrived guards edit as well as render.** Rendering a half-arrived region shows holes;
*editing* one produces a delta against state that does not exist yet. The applier therefore aborts a
delta touching a locked chunk atomically, and halo positions fail closed.

**Bounded serving is not optional.** A seeder that serves without an inflight cap is a free
amplification vector. The bound produced a real bug once — bounded seeders silently dropped
over-budget requests and the downloader never re-issued them, stalling the plane — fixed by an
explicit `retryPending`.

## Files

- `peer/src/main/java/dev/nodera/distribution/{RegionSnapshotSplitter,PieceManifest,PieceSelector,PieceDownloader,PieceReassembler,ChunkLockMap,ContentTransferService,WorldArchive}.java`

## Testing

- `DistributionIT` — reassembly from 3 seeders each holding < 40%, hashing to the engine's root;
  a partial download resumes after a seeder disconnects.
- `PieceManifestTest`, `PieceSelectorTest` (deterministic rarest-first),
  `PieceReassemblerTest` (rejects chunks for another manifest and out-of-range indexes).
- `ChunkLockEditabilityTest` + `lockedChunkFailsClosedBeforeAnyWrite`.
- `ContentTransferBoundsTest` — a sustained multi-window transfer honours the configured cap within
  one piece of overshoot, with the cap proven to actually bind.
- `WorldContinuityIT` — the whole chain over the real service binaries, including host-worker death.

## Acceptance criteria

1. ✅ A region reassembles from seeders each holding a minority of the pieces and hashes to the
   engine's root.
2. ✅ A bad piece is rejected before acceptance and the downloader retries elsewhere.
3. ✅ Selection is deterministic and order-independent.
4. ✅ An un-arrived chunk is locked against edit, fail-closed — **through a production call path**
   since 2026-08-10 (`ArchiveLaneTest.ProductionApplierIsLockAwareTest`, whose central test fails
   when the applier is constructed without the lock map).
5. ✅ Serving is bounded, with a measured overshoot bound.
6. ⏳ Pieces **render** on arrival in a real client (**L-33**). Headless proof landed 2026-08-10 —
   `ArchiveLaneTest.RegionRendersOnArrivalTest` shows a paced region fetch handing over part of the
   region before its last piece verifies, over the real content plane; `RegionRoundTripTest` shows
   every piece decoding to its own columns alone. The outstanding half is a GUI run.

## Limitations

- **L-33** — the render-on-arrival half. See [`LIMITATIONS.md`](LIMITATIONS.md).
- **L-32** (no addressable pieces) and **L-57** (download cap overshoot) are RETIRED — see
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
