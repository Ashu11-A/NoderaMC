# Plan 9 — the validated lane stops paying for itself out of the tick

## Why

Two live reports, one root: **work that belongs to the network is being done on the Minecraft server
thread.**

- Two players in one world. One walks into the other's region, and TPS on **both** drops from 20.0 to
  15.5 and stays there.
- A player breaks or places a block and a **loading screen** appears.

The second one is not a screen bug at all, and that is the important finding.

---

## What the audits found

### A. The TPS drop — the region root is recomputed from scratch, every tick

`LiveEntityLaneRuntime.tickEnd:652` → `InterferenceCommitter.onTickEnd:117` → `commitRegion:161`,
which takes the region's root as `hash(world.reExtract(region, …))` (`LiveEntityLaneRuntime.java:135`).
That encodes all 64 chunk columns and SHA-256s them. **A ghost moving is enough to trigger it**, and a
region commits whenever any entity mutation is buffered.

It then runs a **second** time for the same commit, in `applyExternal`
(`WorkerValidationService.java:1451`).

`reExtract` itself is cheap — `InMemoryWorldView.java:175` shares immutable column state. The cost is
entirely the hashing volume.

> Correction worth recording: I assumed `ChunkColumnState.encode` boxed 4096 `Integer`s per dense
> section. It does not — `ChunkColumnState.java:233` already writes them primitively. The only boxing
> is a `sectionCount`-sized palette list, which is negligible. The problem is how *often* a full
> region is hashed, not how it is encoded.

### B. Why it doubles when zones overlap, and why it never recovers

Overlap is what creates the peer replica, so this begins exactly at "Entered your zone". Each incoming
delta costs three more full-region passes — apply → `reExtract` → `hash` → deep `equals`
(`WorkerValidationService.java:1160-1170`) — under a `synchronized` block, and `CommitAnnounce` /
`ExternalDelta` are dispatched **onto the server thread** (`:850`, `:854`).

It stays at 15.5 because `ChunkTicketService.java:44,94` holds a radius-1 **block-ticking** ticket on
all 64 chunks of every delegated region. A validator seat is +64 permanently force-ticked chunks.

### C. The block-interaction "loading screen" is a disconnect this mod causes

There is no path from a block edit to a screen. There is exactly one path to "Reading world data…":
`ClientDisconnectMixin` → `SeamlessTakeover.begin` → `takeOverLocally` → `openWorld`. So the block
edit produced a **disconnect**.

`BlockCaptureBridge.onPlace/onBreak` (`server/shadow/BlockCaptureBridge.java:185,209`, documented
"server main thread") → `submit()` → `WorkerValidationService.proposeBatch` →
`round.done().await(voteTimeout + 500)` at `WorkerValidationService.java:781`, with
`voteTimeoutMillis = 5_000` (`LiveEntityLaneSession.java:92`).

**One block edit whose committee lacks a reachable majority freezes the tick loop for 5.5 seconds.**
A few of those exceed the vanilla keepalive, the client is kicked, and the takeover does exactly what
it was built to do. The loading screen is the symptom of a self-inflicted disconnect.

A second route to the same place: `BlockWriteGuard.java:96` deliberately rethrows
`AsyncWriteException` out of `LevelChunk.setBlockState` (`mixin/LevelChunkMixin.java:40`), so an
off-thread write into a delegated region crashes the tick.

---

## The plan

### Phase 1 — take the hashing off the tick *(fixes the TPS drop)*

1. **Cache the region root**, keyed on the identity of the column set plus the entity table. Columns
   are replaced wholesale on write (`InMemoryWorldView.java:127`, `:230`), so identity comparison is
   exactly correct — and a ghost-only tick dirties **no** column, so the root is reused and nothing is
   hashed at all.
2. **Delete the second hash** in `applyExternal`: the value was just computed.
3. **Flush the interference buffer on a dirty flag, immediately before `proposeBatch`**, instead of
   every `tickEnd`. The only contract is that the flush precede the next proposal for that region
   (`WorkerValidationService.java:1456`), and busy regions already defer arbitrarily many ticks —
   `InterferenceCommitter.onPipelineDecision` has **zero production callers**.
4. **Lower the validator ticket level.** Only a region's primary needs block-ticking residency; a
   validator needs the chunks readable, not simulated.

### Phase 2 — take the vote off the tick *(fixes the block-edit disconnect)*

1. **Make the block path fire-and-forget.** `submitBlockAction`'s return value is only logged
   (`BlockCaptureBridge.java:233`) and the forward path already ignores it
   (`WorkerValidationService.java:907`). Enqueue to the forward executor and return.
2. **Leave `submitDrop`/`submitPickup` synchronous.** Their `true` is what cancels vanilla
   (`EntityCaptureBridge.java:548`, `:565`), and changing that re-opens issues #33/#44.
3. Precedent, and its price, both already exist: `submitMove` (`LiveEntityLaneRuntime.java:270`) is
   optimistic and reconciles through committed state, having given up the `VanillaCancelGate`
   decision. Blocks can take the same deal; drops and pickups cannot.
4. **Contain `AsyncWriteException`** rather than letting it reach the tick loop. An off-thread write
   is a bug to log and refuse, not a reason to end everybody's session.

### Phase 3 — prove it, in the suite that missed it

1. A **TPS floor** stage: two players, one walks into the other's region, and server TPS must stay
   above a floor for the whole window. Nothing in the suite reads TPS today.
2. A **block-edit** stage: place and break blocks in a delegated region and assert the session
   survives — no disconnect, no takeover line in either log.

---

## Not in scope, and why

The loading screen when the **host leaves** is a different thing and is not fixable here: the takeover
re-opens a world, and `Minecraft.doWorldLoad` nulls the level before any screen is chosen, so hiding
screens yields a black window rather than the world. That is Plan 8 phases 5–6 — streaming into a live
level, or a session endpoint that outlives its host.

## Note on method

Three agents rather than five. Their findings were conclusive and mutually confirming — one of them
also refuted an assumption I had brought to the question — and two further audits would have cost
context that the fix itself needs.
