# Task 19 — Torrent Distribution Data Plane (Phase 5–6): Piece Manifest, Multi-Seeder Transfer, Async Download + Hash-Validate + Lock-Until-Arrived

**Phase:** 5–6 · **Depends on:** Task 9 (`storage-api` `ContentId`/`ContentStore`), Task 4
(`protocol` codec + `transport-api`) · **Modules:** new Minecraft-free `distribution` module;
`protocol` additions; `core/state` manifest type.

## Goal

Turn world data into a swarm: split each region snapshot (and event-log segment) into
**addressable sub-region chunk-section pieces**, each with its own hash, advertise them via a
content manifest, and let a peer **fetch pieces from multiple seeders in parallel**, verify each by
hash before use, and **render/lock on arrival** — unfinished pieces are locked against editing so a
player never sees or mutates unverified state. This is the BitTorrent data plane that the tracker
(Task 20), replication (Task 21), encryption (Task 23), and the multiplayer UI (Task 26) build on.

The frozen `RegionSnapshot`/`StateRoot` and `ContentId`/`ContentStore` from Tasks 2/9 are reused
unchanged at the region layer; this task adds a **piece layer beneath them**. A region's canonical
blob is now described by a `PieceManifest` (a Merkle-style hash list over its pieces); the
`StateRoot` still commits the whole region, and the manifest root binds the pieces to it.

## Context

- The user "torrent hosting" spec rules 3 (<5% per peer), 6 (continuous stream), and 10 (async
  download, hash-validate, lock-until-arrived, timestamped-hash freshness) all require
  **fine-grained addressable pieces**. Today (`protocol/codec/ChunkedStreams`) chunking is only
  transport-level frame-splitting under the NeoForge `<32 KiB` serverbound cap — pieces are
  addressed by `(streamId, index)`, carry no own hash, and cannot be requested by hash from
  alternate seeders. This task closes that gap (LIMITATIONS L-32, L-33).
- Additive to committee validation (Task 7): seeders serve pieces; committees still re-execute and
  commit region state. The data plane carries committed content only.
- **Determinism discipline preserved:** piece hashes are SHA-256 over canonical bytes
  (`HashService`); piece selection is deterministic given `(manifest, holder set)` via
  `StableHash` rendezvous — no `Math.random`, no wall-clocks in the selection path (the engine ban
  in `simulation` does not apply here, but the same hygiene is kept).

## Folder structure (additions)

```
distribution/src/main/java/dev/nodera/distribution/
├── package-info.java
├── Piece.java                    # (ContentId blobHash, int index, long offset, long length, Bytes pieceHash)
├── PieceManifest.java            # (RegionId, SnapshotVersion, StateRoot regionRoot, List<Piece>,
│                                 #   Bytes manifestRoot, long totalLength, Compression) — Encodable
├── PieceSelector.java            # deterministic rarest-first + rendezvous-tiebreak over holder set
├── PieceDownloader.java          # async multi-seeder fetch: per-piece CompletableFuture, bounded in-flight
├── PieceReassembler.java         # join verified pieces → original blob; verify against manifestRoot
├── ChunkLockMap.java             # (region, piece-index) → lock state; un-arrived pieces locked vs edit
└── ContentTransferService.java   # serve (handle ContentRequest) + fetch (issue ContentRequest) seam

protocol/src/main/java/dev/nodera/protocol/content/   # new package
├── ContentRequest.java           # (Bytes manifestRoot or ContentId, List<Integer> pieceIndexes)
├── ContentChunk.java             # (Bytes manifestRoot, int index, Bytes ciphertext/payload, Bytes pieceHash)
└── ContentAvailability.java      # (NodeId, List<Bytes> manifestRoots) — what I hold (Task 20 inventory feed)

core/state additions:
└── (none — RegionSnapshot/StateRoot unchanged; PieceManifest is distribution-owned)
```

## Class relationships

```
RegionSnapshot (frozen, Task 2)
        │  split into chunk-section pieces
        ▼
PieceManifest  ──► Piece[] ──► ContentChunk (wire) ──► seeders (Task 20/21)
        │  manifestRoot = SHA-256 over sorted piece hashes
        │  regionRoot   = StateRoot of the whole region (unchanged)
        ▼
PieceDownloader ──► PieceReassembler ──► verified blob ──► PeerSyncFlow (Task 9) / renderer
        │  per-piece: fetch from any holder, verify pieceHash BEFORE accept
        │  ChunkLockMap: piece not yet verified ⇒ its chunk locked vs edit/render
```

Wire flow: a holder of a manifest answers `ContentRequest(manifestRoot, [0,3,7])` with one
`ContentChunk` per piece; the receiver verifies `pieceHash` and the reassembled blob's
`manifestRoot` before unlocking.

## Implementation details — `distribution` module (Minecraft-free, headlessly testable)

- **Piece granularity.** Default piece = one chunk **section** (16×16×16 blocks) canonicalised
  alone, so a region's 64 columns × N sections becomes many small addressable blobs. Piece size is
  bounded by config (`distribution.pieceTargetBytes`, default 24 KiB plaintext → fits one
  `StreamChunk`); oversized sections split at the section boundary never inside a `BlockMutation`.
- **PieceManifest** is `Encodable` with a frozen `MANIFEST` type tag (append to `TypeTags`).
  `manifestRoot = SHA-256` over the canonically-sorted list of piece hashes; `regionRoot` is the
  region's `StateRoot` (carried so a manifest is self-checking against committed truth). A
  checkpoint (Task 9) references `manifestRoot` in addition to the snapshot `ContentId`.
- **Hash-validate-before-accept (rule 10).** `PieceReassembler.accept(ContentChunk)` rejects any
  chunk whose `pieceHash ≠ SHA-256(payload)`; only verified pieces unlock in `ChunkLockMap`. The
  reassembled blob is rejected unless `SHA-256(blob) == manifest.totalLength`-root equals
  `manifestRoot`. **Timestamped freshness** (rule 10): a manifest carries the region `SnapshotVersion`
  and tick; a newer manifest (higher version) supersedes — a peer holding a region announces the new
  manifest + the seeders (Task 24 continuous stream).
- **Multi-seeder fetch (rule 3).** `PieceDownloader` holds the holder set per manifest (from Task 20
  `ContentAvailability`); `PieceSelector` picks the next piece **deterministic rarest-first**
  (fewest holders; tie-break by `StableHash(manifestRoot, index)`) so two fetchers don't all grab
  the same piece. In-flight bounded by config; each piece request is a `CompletableFuture` that can
  race two holders and take the first verified response.
- **Lock-until-arrived (rule 10).** `ChunkLockMap` exposes `isEditable(region, chunkSectionIndex)`;
  the mod-side renderer/`WorldMutationApplier` consult it: a section whose piece is unverified is
  locked — no render, no edit — preventing corruption from premature display.
- **Serve path.** `ContentTransferService` implements the transport `MessageHandler` for
  `ContentRequest`: answer from the local `ContentStore` (Task 9) by manifest root, one
  `ContentChunk` per requested index. Serve is bounded (`distribution.serveMaxInflight`,
  `distribution.serveBandwidthBudget`) so seeding cannot starve the simulation thread.

## Implementation details — `protocol`

- Append-only `MessageCodec` tags for `CONTENT_REQUEST`, `CONTENT_CHUNK`, `CONTENT_AVAILABILITY`
  (next free tags; update `MessageCodecTypeTagTest`). Each is an `Encodable` `NoderaMessage`.
- `ContentChunk` payload is opaque bytes — under Task 23 it is ciphertext; the piece hash is over
  the **ciphertext** so seeders verify without decrypting.

## Potential limitations (staged in `LIMITATIONS.md` §B; no permanent exclusions)

- **L-32** — world data moves whole-region only today; this task ships addressable pieces +
  multi-seeder swarm. Exit: piece manifest + `ContentRequest/Chunk/Availability` + deterministic
  rarest-first selection; resume-after-partial-download test green; bad-piece hash-reject test green.
- **L-33** — no async client chunk pipeline today; this task ships render-on-arrival +
  lock-until-arrived. Exit: un-arrived section locked vs edit; manifest-hash validates before any
  render; headless `DistributionIT` proves a region reassembles from 3 seeders each holding <40%.
- **A-4** — the "content-addressed multi-seeder transfer" mechanism (currently credited to T9/T10)
  is delivered here + Task 20; update A-4's owner to `4, 19, 20` when this lands.

## Acceptance criteria

1. `PieceManifest` round-trips canonically; `manifestRoot` is byte-stable; golden test pins the tag.
2. `DistributionIT` (headless, 3 in-memory peers): a region split into ≥8 pieces is reassembled from
   a holder set where **no single peer holds >40%** of the pieces; the verified blob's hash equals
   the manifest root and its `StateRoot` equals the engine's (Task 3) — no committee-layer change.
3. A corrupted piece (flipped byte) is rejected by `pieceHash` and never unlocks in `ChunkLockMap`;
   the downloader retries from an alternate holder.
4. Partial download + disconnect + resume completes the region (piece-level resumability).
5. Determinism: two `PieceSelector`s given the same `(manifest, holderSet)` request pieces in the
   same order (property test).
6. `./gradlew check` green; `distribution` module ✅ in README/Tested; L-32/L-33 → RETIRING.
