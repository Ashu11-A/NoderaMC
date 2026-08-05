# Plan 10 — region hashing replaces version counting, and chunks stop being lost

## Context

Worlds are identified, compared and transferred by a monotonic counter. `SnapshotVersion` decides
which of two copies of a region is newer, names files on disk, rides in the control-plane JSON, and
is fed into the hybrid logical clock as if it were a timestamp. A counter cannot answer "do we hold
the same terrain?", so the answer is always "assume not", and the system re-transfers whole worlds
and silently discards whichever side has the lower number.

The machinery to fix this already exists and is half-wired. `RegionChunkIndex` (TypeTag 124) is a
merkle root over per-column content hashes, deliberately excluding the clock so two peers with
identical terrain agree in 32 bytes; `ChunkStamp` (123) pairs each column's hash with an `Hlc` (122)
reading. `WorldArchiveService.seedRegion` builds one on every seed — and `WorldArchiveService.java:645`
reads it **only to log a chunk count**. `RegionChunkIndex.changedSince`, `mergeWith`, `ChunkStamp.merge`
and `PieceManifest.hasChunkIndex` have **zero production callers**. Every freshness decision still
goes through the counter.

Three consequences, all reported from live play:

1. **Chunk compatibility.** Two peers holding byte-identical terrain disagree because their counters
   differ, so nothing is ever recognised as already-held.
2. **Data loss.** `WorldArchiveService.java:1543-1548` and `NoderaContinuity.java:635-638` are
   whole-world last-writer-wins by counter: two peers advancing from one base produce two archives
   and every edit in the lower-numbered one is discarded. On the live plane, one block whose live
   state differs from its CAS guard aborts an entire 64-chunk region delta
   (`WorldMutationApplier.java:152-154`), and block proposals are dropped by a silent
   `ThreadPoolExecutor.DiscardPolicy` (`LiveEntityLaneRuntime.java:126-135`).
3. **Chunk generation.** Every region lane activates on `EntityLaneBootstrap.initialSnapshot` —
   64 columns of **all air**. Every committed snapshot pushed over `NODERA-SEED-REGION` is air plus
   whatever blocks were captured since activation, never the real world. Only
   `RegionDepartureHandoff` ever seeds real terrain, and only when a player logs out of a region
   nobody else covers.

Intended outcome: a region's identity is the merkle root of its chunk content; the HLC decides
recency without ever entering that root; peers fetch only the columns that differ; two divergent
copies merge at block granularity instead of one being discarded; and lanes activate on terrain
that exists.

**User decisions taken** (asked and answered): merge is a **3-way block merge inside the column**
against a retained common ancestor, with the column HLC breaking ties only on positions both sides
touched. `ContentCipher` gets a **nonce v2 keyed on column content**, with v1 decode retained.

**Delivery**: current branch `feat/launcher-redesign`, one commit per phase.

---

## The cut line — what "remove the v\<number\> system" means here

`SnapshotVersion` cannot be deleted. Three hard blocks, each verified:

| Site | Why a hash cannot replace it |
|---|---|
| `RocksWorldStore.java:325,341-347,366-373` | key is `regionKey(16) ++ version(u64 BE)`; `seekForPrev` and ordered prefix iteration need a total order |
| `ContentCipher.nonceFor` (`crypto/symmetric/ContentCipher.java:64-80`) | AES-GCM nonce; changing the derivation changes every ciphertext and every `ContentId` |
| `NetworkEntityId.allocate(region, version, seq)` | version is the only separator between two entities at the same `seq` across commits; changing it forks entity ids into a consensus split |

So the counter is **demoted, not deleted**. The rule, enforced by a guard test rather than a rename:

> `SnapshotVersion` is a per-region chain height. It may be compared only between peers seated on
> the same region's committee at the same epoch. It may never decide which of two independently
> produced copies of a region is newer, never appear in a filename, never appear in control-plane
> JSON, and never be fed into an `Hlc`.

A rename to `ChainHeight` is **not** worth its diff: record component names feed
`WireSchemaGeneratorTest` → generated `library/rust/nodera-codec/src/kinds.rs`, so renaming
`baseVersion`/`resultingVersion`/`haveVersion` churns generated Rust for no semantic gain across
~60 sites. Javadoc plus an ArchUnit test holds the line; a rename does not.

The last clause is the one that bites today: `ChunkStampBook.derivedFrom(SnapshotVersion, long tick)`
(`library/java/core/.../state/ChunkStampBook.java:111-113`) returns
`new Hlc(tick, version.value(), Hlc.ZERO.origin())` — the chain height used directly as a clock
counter, and it is the per-column fallback stamp at `RegionSnapshotSplitter.java:253`. Its stated
justification ("so two peers packing the same snapshot produce the same root") is wrong:
`computeRoot` excludes the `Hlc` entirely, so root determinism is unconditional. A region that
happens to sit at v900 outranks a genuinely-edited column from a peer at v3.

---

## Phase 0 — freeze the shapes while nothing consumes them

**No wire fixtures exist for `PieceManifest`, `RegionChunkIndex`, `ChunkStamp` or `Hlc`** — verified:
`fixtures/wire/java-only/` holds 56 files, all wire *kinds*; manifests ride kind 52 as opaque
`Bytes`. Combined with V2's zero consumers, every shape change below is free **now** and expensive
the moment anyone reads a V2 manifest.

- `library/java/core/.../state/RegionChunkIndex.java` — drop the `SnapshotVersion version` field
  (it exists only to be picked by `mergeWith:227`, comparing two unrelated chain heights). Add
  `Hlc newestStamp()` for the places that genuinely need one scalar.
- `ChunkStampBook.derivedFrom` loses its `SnapshotVersion` parameter; `RegionSnapshotSplitter.java:253`
  passes the region's own clock reading.
- `ChunkStamp.merge` (`:95`) — when content differs and the two HLCs compare equal (the
  `Hlc.ZERO`/untouched case), it currently returns `this`, so two peers merging the same pair
  disagree. Add a lexicographic tie-break on `contentHash`: arbitrary, but identical on every peer,
  which is the only property that matters.
- `HybridClock.observe` (`:74-88`) — **clamp**. It is already transitively live via
  `ChunkStampBook.adopt` → `WorldArchiveService.restampAgainstPrevious`, with no drift bound: one
  remote `Hlc` at `Long.MAX_VALUE-1` pins a node's clock forever. Under "most recent wins" that is a
  one-packet griefing primitive. Reject readings more than `NoderaConstants.MAX_CLOCK_SKEW_MILLIS`
  (5 min) ahead of local wall time, count and log.
- **Region pieces become one piece per chunk column** (64 pieces) in `RegionSnapshotSplitter`; the
  `.nar` archive lane keeps its 256 KiB target. `pieceOfChunk` becomes the identity map,
  `piecesChangedSince` becomes `changedSince` mapped 1:1, and `ChunkLockMap.isChunkEditable` becomes
  exact. `blob == RegionSnapshot.encode(...)` is preserved, so `regionRoot = SHA-256(blob)` and the
  `ContentId` identity are untouched. Persist `pieceOfChunk` on `PieceManifest` V2 while it is free.

Tests: root determinism across two independently built indexes; merge tie-break symmetry
(`merge(a,b).equals(merge(b,a))`); clock clamp rejects a far-future reading and keeps counting.

## Phase 1 — fast, incremental region hashing

`LiveEntityLaneRuntime.java:153-157` SHA-256s the entire re-extracted region on every commit; a
dense region is ~25 MB, and Plan 9 could only reduce how *often* this runs, not what it costs.

Put the cache where the single writer already is —
`library/java/engine/.../coordinator/InMemoryWorldView.java`, which owns
`Map<RegionId, Map<Long, Column>>` and is the only mutator. Per region add `columnEncodings`,
`columnHashes` and `dirtyColumns`; `setBlock` (`:118`) already computes the column key, so the write
path costs one `dirtyColumns.add(key)` and hashes nothing.

New on `MutableWorldView`:

```
RegionChunkIndex chunkIndex(RegionId region, ChunkStampBook book);
```

rebuilding only dirty columns, then `RegionChunkIndex.of(region, stamps)`. Typical cost per commit
is 1–3 of 64 columns. `reExtract` assembles from the cache, re-encoding only dirty columns, and
stays **byte-identical** — pinned by a test asserting `assembled.equals(fromScratchEncode)`.

The linear SHA-256 over the whole blob still runs: a linear hash is not decomposable, and replacing
the consensus `StateRoot` with a merkle root is a `RegionSnapshot.STATE_ENCODING_VERSION` bump plus
a coordinated release. Out of scope; noted.

Per-column stamps get maintained at the two moments a column actually changes:
`ChunkStampBook.touch` inside the `MutationGuard` in `LiveEntityLaneRuntime` (reached from
`BlockWriteGuard.blocks`), and in `ServerEntityWorldView.setBlock` (`:144-147`) so a validator
applying a committed delta stamps too. One `ChunkStampBook` per world, owned by
`LiveEntityLaneRuntime`.

## Phase 2 — stop losing edits on the live plane

Independently valuable; touches no wire fixture and needs no coordination.

- **Rebase before proposing** (`InterferenceCommitter.java:216-218`, where `RecordedMutation` becomes
  `BlockMutation`): read `world.getBlock(region, pos)` and either correct
  `expectedPreviousStateId` or drop *that one mutation* with a counter and a log line. "One stale
  block kills 64 chunks" becomes "one stale block is dropped, 63 chunks commit". This is
  uncertified state; rebasing here is free and correct.
- **The certified CAS stays all-or-nothing.** `WorldMutationApplier`'s contract is unchanged and all
  16 tests in `WorldMutationApplierTest` stay green — a partially applied *certified* delta produces
  a state root nobody signed, which is a silent fork and worse than a dropped delta. Merge goes
  above it (Phase 5), rebase below it (here).
- **Resync instead of throwing.** When `applier.apply` fails on a certified delta the delta is not
  wrong, this node's state is. Transition the replica to resync and send `ResyncRequest` — kind 14,
  decoder and `fixtures/wire/java-only/resync-request.bin` both exist, **zero senders**. Same for
  `WorkerValidationService.java:1144-1150`, whose comment already says "must resync, not apply
  blindly" while nothing schedules one. Closes the permanent-stale-validator bug.
- **Stop discarding proposals silently** (`LiveEntityLaneRuntime.java:126-135`): on rejection,
  re-dirty the region and count it, rather than `DiscardPolicy` with no logging.
- **Foreign writes on a non-primary** (`InterferenceCommitter.commitRegion:230` →
  `commitExternal:1394-1398` throws → retried and refused every 10 ticks forever): forward to the
  primary instead of rethrowing into `recordResync()`.
- `abortUncommitted:2054-2062` re-buffers the batch instead of dropping it.
  `WorkerValidationServiceTest.java:128` pins the current behaviour — review it as a deliberate
  contract change, not a test fix.

## Phase 3 — close the transmission loop

Nothing today downloads a region's pieces or applies them to a level; the only live write is one
`BlockPos` at a time via `ServerEntityWorldView.java:177`.

**Fetch (worker).** New `WorldArchiveService.fetchRegion(worldIdHex, region, wantIndexRoot, held)`:
resolve the `PieceManifest` by root, compute `held == null ? all : chunkIndex().changedSince(held)`,
run `PieceDownloader` with a **real `ChunkLockMap`** (`WorldArchiveService.java:1594` passes `null`
today) over that subset, take unchanged columns from the locally held previous blob, verify
`SHA-256(assembled) == regionRoot`, decode, and `ChunkStampBook.adopt` each arriving stamp — the
real `HybridClock.observe` call site.

New control verb mirroring `NODERA-SEED-REGION` (`ControlProtocol.java:152`,
`ControlServer.java:349-354`):

```
NODERA-FETCH-REGION <ver> <worldId> <regionX> <regionZ> <haveIndexRootB64>
  → NODERA-OK <snapshotPathB64> <indexRootB64>
```

Piece plane stays in the worker, level writes stay in the mod, no new module dependency. An old
worker returns `err(...)` for an unknown verb, so it degrades to "no fetch" rather than crashing.

**Apply (mod).** New `endpoints/neoforge-mod/.../server/shadow/LiveSnapshotApplier.java` — the exact
inverse of `LiveSnapshotExtractor`, same package, same thread rule:
`level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true)` → `chunk.getSection(i)` →
`section.setBlockState(x, y, z, state, false)` in the same y·z·x order the extractor reads, with
`PaletteMapper.stateOf(id)` for the mapping (it already logs rather than guessing on an unmappable
id). Then `chunk.setUnsaved(true)`, `Heightmap.primeHeightmaps`, relight, and
`ClientboundLevelChunkWithLightPacket` to tracking players (needs a small `ChunkMap` accessor mixin,
the idiom `LevelChunkMixin`/`LevelTicksMixin` already use).

Two details that are easy to get wrong and expensive to miss:
- **Server thread only.** `BlockWriteGuard.blocks:66-107` routes off-thread writes to
  `verdictChecked`, which throws `AsyncWriteException` and, by design, rethrows it.
- **Run inside `applierScope`** — the `Consumer<Runnable>` `ServerEntityWorldView.projectBlock`
  uses at `:177`. Without it, applying a fetched region generates up to 98,304 *foreign-write*
  interference records per column and proposes them all straight back to the committee.

Budget it: 64 columns × 24 sections × 4096 = 6.3 M writes. New `RegionApplyQueue` driven from the
existing server tick hook, `apply.columnsPerTick` default 1–2, with `ChunkLockMap` marking each
column un-editable until it lands. Wire `ChunkLockEditability` (zero production callers) into
`new WorldMutationApplier(world)` at `WorkerValidationService.java:365` — its missing call site, and
what makes `ApplyResult.abortedLockedChunk` reachable. **Skip fast**: a column whose live content
hash already equals the arriving `ChunkStamp.contentHash` is not written, which makes a full-region
apply nearly free when one chunk changed.

**Wire change**: `RegionAssigned` (kind 5) gains `Bytes baseIndexRoot` under a retrofitted
bodyVersion — kind 5 currently hardcodes the global `ENCODING_VERSION`; retrofit using the
`RegionProposal` idiom at `MessageCodec.java:506-524` / `:1090-1111`, where the decoded bodyVersion
is stored back on the record so a re-encode is byte-identical. Regenerate
`fixtures/wire/java-only/region-assigned.bin` deliberately with
`-Dnodera.fixtures.regenerate=true`. **This phase needs a coordinated release**: old peers decode v1
and ignore the field; new peers receiving a v1 assignment must refuse the seat unless a local
manifest exists.

## Phase 4 — activate on real terrain

**Must not land before Phase 3.** `WorkerValidationService.java:1771-1774` states the reason in its
own Javadoc: the base comes from `initialSnapshot` *because* it is "byte-identical to the primary's,
which is what makes the first `prevRoot` comparison line up without any state transfer". Give the
server real terrain while workers still derive air and **every region loses quorum**.

- **Server** (`NoderaHost.java:1153-1155`): `ChunkTicketService.hold` → wait for residency →
  `LiveSnapshotExtractor.extract` → `RegionSeedSpool.offer` (already reaches `seedRegion` and builds
  the index) → *then* send `RegionAssigned` carrying the resulting root. `LiveSnapshotExtractor` is
  server-thread-only and this runs on `nodera-entity-lane-boot`, so hop via `server.execute(...)` +
  a future. **Gate activation on `missingChunks() == 0`** — `extract` returns all-air for
  non-resident chunks, so skipping this re-creates the bug with extra steps.
- **Worker** (`WorkerValidationService.java:1810`) and **client**
  (`ClientValidationLane.java:137-138`): adopt-or-refuse. Look for a locally held manifest with the
  assigned root, else fetch it before activating; if it cannot be obtained within a deadline,
  **decline the seat** via `RegionRefusal` (kind 61, exists) rather than activating on air and
  diverging from every proposal it will ever see.
- Keep `EntityLaneBootstrap.initialSnapshot` for tests; remove all three production call sites and
  add a guard test asserting zero production references.

## Phase 5 — 3-way merge on rejoin

Two peers that each committed on their own chain have different roots. Reconcile instead of
discarding.

New `peer/src/main/java/dev/nodera/distribution/RegionMerger.java`:

```
record ColumnMerge(int chunkX, int chunkZ, ChunkColumnState result, int tookLocal, int tookRemote, int contested)
record MergeOutcome(RegionChunkIndex merged, List<ChunkStamp> toFetch, List<ColumnMerge> columns)

static MergeOutcome reconcile(RegionChunkIndex local, RegionChunkIndex remote);
static ColumnMerge mergeColumn(ChunkColumnState ancestor, ChunkColumnState mine, ChunkColumnState theirs,
                               Hlc mineStamp, Hlc theirsStamp);
```

`reconcile` is `local.mergeWith(remote)` + `changedSince` — both currently dead, this is their
production call site. For a column both sides changed, `mergeColumn` walks the three columns in
canonical order and, per position: unchanged on one side → take the other; changed on both to the
**same** state → take it (this is the user's "identical modifications" case, and it is not a
conflict); changed on both to different states → **newer column HLC wins**, counted as `contested`.
No per-block clocks are stored anywhere; the ancestor makes them unnecessary.

The ancestor is the last root both sides agreed on. `WorldArchiveService` already retains prior
manifests per world (`trimRegionToRetention`); pin the most recent **announced** root per region as
the merge base so it survives retention trimming.

Also in this phase:
- `NoderaContinuity` (`:522-527,545,619-640`) stops polling `"version":n` and comparing counters;
  it compares index roots and merges. `<save>/nodera/seeded-version` becomes `seeded-index`
  (root + newest stamp), keeping the existing fail-safe: unreadable ⇒ keep the local save.
- `WorldArchiveService.java:504,536,1364,1573,1942,1950` — the `versions.lastKey()+1` ladder and
  every "offered ≤ held ⇒ reject" become root-compare-and-merge. This is the fix for whole-world
  last-writer-wins.
- `PieceManifest.isSupersededBy:372-375` is deleted, not reimplemented. The replacement for
  `a.version > b.version` is `roots equal ⇒ done, else merge`. Never "theirs is older, discard".
- Delete `ActivePlayerStream` (574 lines, zero external references), `EmergencyFlush`,
  `PeerShutdownHook` — `docs/network/REFACTORING.md` already marks them redundant, and
  `ActivePlayerStream:331-334` is one of the silent-drop sites.

## Phase 6 — encrypted dedup and enforcement

- **`ContentCipher` nonce v2** (user-approved): derive from the **column content hash** instead of
  the chain height, so an unchanged column encrypts identically forever and per-column pieces
  actually dedupe on encrypted worlds. Keep the v1 derivation on the decrypt path, selected by the
  manifest's frame version, so everything already seeded still opens. Encrypted worlds re-seed once.
  `EncryptedRegion.java:58-59,144,191,205` are the four call sites; the compact-constructor
  validation at `:58` must accept either derivation.
- **The guard test.** ArchUnit (the repo already runs one for `dev.nodera.simulation..`
  determinism): fail on any reference to `SnapshotVersion` from `dev.nodera.distribution`,
  `dev.nodera.mod.client.multiplayer`, and the archive-freshness half of `dev.nodera.headless`,
  outside a named allowlist. Javadoc the rule on `SnapshotVersion` itself.
- Version out of the remaining filenames: `ManifestIndexStore.java:87-89,155`
  (`__<version>.manifest` → `__<manifestRootHex>.manifest`) and `RegionSeedSpool.java:187-189`
  (`region-X_Z-vN.bin` → root-prefixed). Both are discardable caches — a format change is a cache
  miss, not a failure.
- Throttle `RegionSeedSpool.offer` on **index-root change** rather than on a 30 s timer; it writes a
  whole ~25 MB region blob per offer and the content plane dedupes the pieces but not the spool file.
- Docs: `docs/network/REFERENCE.md` (kind 5 v2, `NODERA-FETCH-REGION`), `docs/network/PROGRESS.md`,
  and the `LIMITATIONS.md` rows — **L-33 is the exit row for Phase 3** ("no region-piece fetch at
  all"). The exit-test column is the truth, not the prose.

---

## Verification

```bash
./gradlew check                      # full gate, not just touched modules
cd rust && cargo test                # nodera-app is a separate, excluded workspace
scripts/nodera-test.sh run chunk-continuity
```

Verify by **exit code**, never by piping to `grep`/`tail` — that reports the filter's status. Stop
stray Gradle daemons before a live suite (`./gradlew --stop`) and never run Gradle during one. Read
the *skipped* column: an `assumeTrue` guard on a wrong path is a SKIP, not a pass.

New scenario stages in `ChunkContinuityScenario`:
- **Identity**: two peers holding the same terrain report the same index root, at different chain
  heights. This is the chunk-compatibility fix, asserted directly.
- **Delta**: edit one chunk; assert exactly the pieces covering that column transfer, and that a
  no-op re-seed transfers **zero** bytes.
- **Merge**: partition two peers, edit *different* blocks in the *same* column on each, rejoin, and
  assert **both** edits survive and `contested == 0`. Then repeat editing the *same* position and
  assert the newer HLC wins and nothing else changed.
- **Terrain**: assert a freshly activated region's snapshot is not all air (the Phase 4 exit test).
- Extend `RunReport` with per-peer bytes; no report aggregates bandwidth today.

---

## Risks and refusals

1. **`SnapshotVersion` is not deleted** — RocksDB ordered iteration, the AES-GCM nonce, and entity-id
   allocation all require it. Deleting it makes every seeded encrypted piece unfetchable and forks
   entity ids into a consensus split. It is demoted and fenced instead.
2. **Timestamps stay out of the region root.** The request asks for hashing that "utilises
   timestamps"; the HLC must travel *beside* the hash, never inside it. `computeRoot` already gets
   this right. Put the clock in the root and two peers with identical terrain never agree on 32
   bytes — the exact failure the index exists to end.
3. **The certified CAS is not relaxed** (see Phase 2).
4. **Phase 4 before Phase 3 breaks every region.** Sequencing is not a preference here.
5. **Clock trust is bounded.** "Most recent wins" plus an unbounded `HybridClock.observe` is a
   griefing primitive; the Phase 0 clamp is a prerequisite, not polish.
6. **Every new type lands with its production call site in the same commit.** This repo's documented
   dominant defect is "implemented, tested, zero production callers", and this plan reactivates five
   types that already have it (`changedSince`, `mergeWith`, `ChunkStamp.merge`, `HybridClock.observe`,
   `ChunkLockEditability`).
7. **Phase 3 is the only coordinated release.** Everything else is fixture-free and
   backwards-compatible.

---

## Outcome — what landed, and what the work changed about the plan

Seven commits, `e04b1c3` … `6348948`, each with `./gradlew check` and `cargo test` green.

| Phase | Commit | State |
|---|---|---|
| 0 — freeze the hashing shapes | `e04b1c3` | landed |
| 1 — incremental region hashing | `aec4a25` | landed |
| 2 — stop losing edits on the live plane | `38cdb53` | landed, one item withdrawn |
| 3 — close the transmission loop | `9663c38` | landed |
| 5 — 3-way merge on rejoin | `3939747` | landed |
| 4 — activate on real terrain | `0d73085` | landed, one item deviated |
| 6 — dedup and the version fence | `6348948` | landed, one item refused |

Phases 4 and 5 were swapped: the merge needs no wire change and phase 4 does, so the
coordinated-release surface was kept to one commit.

### Four things the work refuted

**The CAS rebase in phase 2 was wrong, and a test caught it.** The plan had the interference
committer correct a stale compare-and-set guard against the live world before proposing. CONVERT
means the foreign write has *already landed*, so the local world always holds the mutation's target
while the peers that apply the delta hold the state it started from. Reading the local world would
name a guard no replica has and — since target and live are equal there — classify every
interference mutation as already-applied and discard it. Removed; the reasoning is a comment at the
line.

**The AES-GCM nonce cannot be re-keyed on content.** The user approved deriving it from column
content so encrypted worlds would get piece reuse. `regionRoot` is SHA-256 over the region blob and
the blob's frame header carries the version, so a "content-derived" nonce still moves with the chain
height. Deriving from each column's plaintext hash instead would require publishing those hashes
beside the ciphertext — a confirmation oracle. The v1 derivation stands; encrypted worlds keep no
cross-version dedup, and that is now a stated limitation rather than an aspiration.

**`EntityLaneBootstrap.initialSnapshot` keeps its production call sites.** The plan called for
removing all three plus a guard test asserting zero callers. It is no longer how a committee is
normally seated, but it is exactly right as the no-base fallback: a region the host cannot read
completely names no base, and every member derives the same all-air one. Everyone agreeing on
nothing beats disagreeing about something, and a zero-callers guard would have forced the worse
design.

**`abortUncommitted` was left alone.** The plan wanted a failed quorum to re-buffer its batch. Block
events are never cancelled, so every edit is recorded as interference *as well as* proposed as an
action — the world state survives a dropped batch either way, and re-buffering risks applying it
twice.

### Two bugs the phase-0 change introduced, found by its own tests

With per-column cuts the frame header and the entity record became pieces no column claims, so
"fetch everything" silently omitted them and a changed region did not fetch the header that commits
its own version and tick. Structural pieces now ride along whenever anything else does — and
deliberately not when nothing changed, so a re-seed of untouched terrain still costs zero bytes.

### Still open

- **L-33** is not retired. Its two named blockers are built (the region-piece fetch lane, and a
  chunk→piece index that travels with the manifest); what remains is the edit guard on the new lane
  — `ChunkLockEditability` still has no production construction — and render-on-arrival in a GUI.
- **The archive lane keeps its version ladder.** A whole-save `.nar` has no chunk index, so "which
  of these two is newer" cannot be answered from content there. Merging `.mca` files is what the
  region lane exists to avoid.
- **Live verification.** Everything above is proven headless. The live suite is the exit test.
