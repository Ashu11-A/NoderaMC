# Plan 8 — a shared world stops being cloned when its host leaves

## Why

Ten parallel audits of the world-sharing, persistence and departure paths. The live report was:
a host disconnects, the other players get a "Loading terrain" screen as if a new world were being
generated, the world turns up in their **singleplayer** list, players are teleported back to older
positions, two versions of the world now exist and the original owner cannot enter the new one.

Every one of those is the same fact seen from a different angle: **there is no handover.** There is
no successor election at the world-hosting level anywhere in the codebase. `CommitteeFailover`
promotes a region primary and `GatewayElection` picks a mesh coordinator, but neither has anything
to do with who hosts a Minecraft world. `NoderaContinuity.canTakeOver` consults only local state, so
every remaining player independently fetches the archive, unpacks it into their own vanilla
`saves/`, opens it, and re-shares it. One world becomes N divergent worlds that all claim the same
id.

The rest follows mechanically, and each part is separately broken.

---

## What the audits found

### A. The load screen is real world loading, and hiding it is the wrong lever

`NoderaContinuity.takeOverLocally` calls `mc.createWorldOpenFlows().openWorld(...)`, which runs
`GenericMessageScreen` ×3 → `ProgressScreen` → **`LevelLoadingScreen`** (the chunk-status grid, i.e.
vanilla's world-*generation* screen) → `ReceivingLevelScreen` ("Loading terrain…"). The flow *begins*
with `Minecraft.doWorldLoad` → `this.disconnect()`, which nulls `mc.level`.

Two independent reasons the suppression never fired:

- `NoderaContinuity.java:268-273` clears `SeamlessTakeover.ACTIVE` in a `finally` that runs
  immediately after `mc.execute` **queues** the open. By the time the first screen opens, the flag is
  already false and `shouldSuppress` returns false for everything.
- `LevelLoadingScreen` is not in the suppression list (`SeamlessTakeover.java:116-119`) anyway.

Fixing both would replace the screens with a **black frame**, because the level is nulled regardless.
Screen suppression cannot deliver "no loading screen" while the takeover is a world reopen.

### B. The clone is a filesystem fork, not an identity fork

`materialize` unpacks into `mc.gameDirectory/saves/<levelName> [<id8>]` — vanilla's singleplayer save
root. `nodera-world.dat` travels, and `ensureIdentity`'s author-mismatch branch correctly keeps the
owner's id, so the identity is right. What is wrong is that the world is now a first-class
singleplayer save on N machines.

`nodera-permissions.dat` is neither in nor out of `WorldArchive.defaultSaveFilter`, so it is
**excluded** — every clone boots with an empty grant set.

### C. The revert is a durability hole opened by Plan 7

`archive.streamIntervalTicks` now defaults to `0`, so the whole-save archive is packed at **share
time** and on a **clean** `ServerStopped`, and nowhere else. On an abrupt host death the newest
archive on the network dates from when the world was shared — potentially days.

`playerdata/` and `level.dat` *do* travel, so every player's position, inventory, advancements and
stats snap back to that same instant. The region-delta lane that replaced the periodic repack carries
blocks and entities, carries **no** player state, and has **no path back into a save file** —
`WorldArchive.unpackInto` is the only writer into a save directory in the whole repo.

Two amplifiers:

- `WorkerControlHandler.java:921` reports `newestManifest` — "the newest version I have *heard of*" —
  as the version of the bytes it is handing over. A stale blob is labelled fresh.
- With no local save, `materialize`'s freshness guard is a no-op and unpacks unconditionally.

### D. The owner is locked out of their own world

`MultiplayerWorldFeed.merge:125` refuses the network's liveness for any id in the local registry. The
owner's node still lists the world as hosted, their game is closed, so their own row stamps
`mcRoute=""` → `DEAD` → `joinable()` false → Join greyed out. **The owner's dead self-row shadows the
live clone.**

Two server-side holes make it worse, independently of the UI:

- `NoderaHost.deactivate` re-mints with the *pinned* id, and `WorkerControlHandler.java:945-959` then
  mints a private key and publishes a `WorldOwnership` claim for **someone else's world id**.
  Afterwards `localWorkerIsAuthor` returns true for a non-author.
- `seedArchive` has no author check, so a non-author's "Share" can publish a **plaintext** archive
  that supersedes an encrypted world's manifest.

### E. The multiplayer tab

- `MultiplayerWorldFeed.java:213` builds its tracker client from `CLIENT_TRACKER_ENDPOINTS` **config**
  while every other lane follows the worker's live list. Live log: the worker follows two trackers,
  the tab reads one.
- `WorldHostingService.java:833` silently drops from every announce any world with no `mcRoute` and no
  manifest — no log line, and `listed_on_trackers` keeps its stale value.
- A failed catalog poll overwrites `networkWorlds` with an **empty list** (the worker path correctly
  keeps its last snapshot; this one does the opposite).
- `seeding=false` is sticky, so any world this peer *ever* hosted keeps a `DEAD` local row that
  displaces the live tracker row.
- A replicated world is named after its own 64-char hex id, and every worker row is labelled with the
  **local** player as owner.
- `mcRoute` is the one liveness field with no lease: after a `kill -9` the worker advertises a dead
  game endpoint indefinitely.
- A `DEAD` world cannot be joined, so `NoderaContinuity.openFromNetwork` — written exactly for
  "the host is gone but the world is not" — is unreachable from the UI.

### F. Owner-only controls

`PauseScreenShareAddon` gates on `mc.hasSingleplayerServer()` alone. A joiner who materialised the
world *is* the integrated-server owner, so they get the full owner control panel — and are auto-opped
by `grantHostOperator`. The worker already emits an `owned` flag per world; `WorkerStateParser` drops
it. `NoderaSessionPayload` carries no author field, so a remote joiner has no ownership signal at all.

### G. Chunk streaming — the supply chain is done, the last metre is missing

Content-addressed chunk plane: `ChunkStamp` + `RegionChunkIndex` merkle root inside a v2
`PieceManifest`, pieces cut only at chunk-column boundaries with `pieceOfChunk`, an exact
`piecesChangedSince`, rarest-first verified fetch, `ChunkTicketService` able to pin arbitrary chunks.

**Nothing anywhere can write a `RegionSnapshot`/`ChunkColumnState` back into a live `ServerLevel`, an
`.mca` chunk, or a chunk packet.** The only Nodera→world write path is `ServerEntityWorldView`
`projectBlock`, one `level.setBlock` per committed mutation. Dead alongside it: `ChunkLockEditability`
(never installed), `ActivePlayerStream` (no caller), and the whole `SaveRegion`/`RegionSplitArchive`/
`seedSaveRegion` per-`.mca` lane (no caller anywhere).

A worker serving vanilla chunk packets directly would mean implementing handshake/login/config/play,
the global block palette, heightmaps, light and block entities — a Minecraft server, in a module
deliberately kept Minecraft-free. **Not proposed.**

---

## The plan

Ordered so each phase is shippable and the visible damage stops first. Phases 0–3 are corrections;
4–6 are the architecture the request actually asks for.

### Phase 0 — one successor, not N *(stops the clone and the fork)*

1. **Elect.** `SeamlessTakeover.begin()` consults the session membership and takes over only if this
   node is the elected successor — deterministic and identical on every peer (lowest NodeId among
   session members that hold a complete archive, reusing `GatewayElection`'s ordering discipline).
2. **Everyone else waits and re-dials.** Non-successors hold their ghost world, watch the tracker for
   the successor's new `mc/` route, and reconnect to it. One reconnect, not a fork.
3. **Fix the suppression ordering** (`finish()` after the open completes, not after it is queued) and
   add `LevelLoadingScreen`, so the successor's own transition is as quiet as it can be.
4. **Gate the owner UI** on the worker's `owned` flag: parse it in `WorkerStateParser` (one line — it
   is already on the wire) and consult it in `PauseScreenShareAddon` and `ShareWorldScreen`.

### Phase 1 — nothing reverts

1. **Seed player state on a cadence, separately from the whole save.** `playerdata/` and `level.dat`
   are small; a periodic delta of just those files restores the bound Plan 7 removed without
   reintroducing the whole-save repack that caused the 3 MB/s.
2. **Report the version of the bytes handed over**, not the newest version heard of
   (`WorkerControlHandler.java:921`).
3. **Refuse a stale blob even with no local save**: compare against the newest version the swarm
   advertises before unpacking.
4. Correct the two `WorldArchiver` comments that still promise continuous streaming.

### Phase 2 — the world stops being a singleplayer clone

1. The successor adopts the world in a **Nodera-scoped** location, not vanilla `saves/`, and the
   singleplayer list either omits it or shows it as a network world.
2. `nodera-permissions.dat` travels in the archive.
3. Close `deactivate`'s pinned re-mint and add the missing author check to `seedArchive`.

### Phase 3 — listing correctness

Worlds tab follows the worker's tracker list; keep the last catalog on a failed poll; un-stick
`seeding=false` so a dead local row cannot shadow a live network row; give `mcRoute` a lease; name
replicated worlds properly and stop labelling every row with the local player; allow Join on `DEAD`
via `openFromNetwork`; log the silent announce suppression and surface it in `NODERA-STATE`.

### Phase 4 — a shared world reads like a server

Parse the fields already emitted (`owned`, `seeders`, `players`, `total_bytes`); measure per-world
ping with `Reachability.measure`, whose latency value is currently computed and discarded in two
places; add the `WorldSelectionList` row hook (no mixin exists today) so the owner's shared world
shows status, players and ping on its own row instead of one centred summary line.

### Phase 5 — chunk streaming, for real

1. **Build the apply path** — the reverse of `LiveSnapshotExtractor`: `ChunkColumnState` →
   `LevelChunkSection`, using `PaletteMapper`, which already does the reverse palette lookup. This is
   the single missing piece; everything upstream of it exists and is tested.
2. Add a chunk-coordinate request message and a **partial** mode to `PieceDownloader` (it currently
   drives to every piece of a manifest and only completes on the whole blob).
3. Install `ChunkLockEditability` so a chunk is unusable until its piece verifies.
4. The successor then **streams the world in around its players** instead of unpacking an archive —
   which is what removes both the load screen and the revert.

### Phase 6 — a session endpoint that outlives its host

Route the game socket through `TunnelService` on a **surviving third peer**, so the address a client
is dialed to does not die with the host. Combined with Phase 0's election this turns the reconnect
into an authority swap behind a stable endpoint.

---

## The honest limit

"No loading screen at all" is not reachable while the authority behind the socket is a Minecraft
`IntegratedServer`: a vanilla client's encryption state, compression state and entity id are bound to
one TCP connection, and `publishServer` cannot be undone. Phases 0–2 turn an N-way fork with a
world-generation screen and a multi-day rollback into **one reconnect** with no data loss. Phases 5–6
are what reduce that reconnect to a swap the player does not see, and Phase 5's apply path is the
prerequisite for all of it.
