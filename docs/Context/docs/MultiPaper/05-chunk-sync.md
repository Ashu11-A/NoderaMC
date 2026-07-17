# 05 — Chunk Synchronization & Ownership

This is the heart of MultiPaper. Everything else — entity sync, player sync,
redstone safety — is layered on top of how chunks are loaded, locked, owned,
and kept coherent across servers.

Most of the source lives in `patches/server/0016-Add-chunk-syncing.patch`
(~2500 lines), with additions in `0032` (redstone) and `0092` (async IO). The
master-side counterpart is in `MultiPaper-Master/` (see
[03 — Master](./03-master-server.md)).

---

## The chunk lifecycle in a cluster

```
        ┌──────────────────────────────────────────────────────────┐
        │  Server A wants to load chunk (cx, cz)                    │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌──────────────────────────────────────────────────────────┐
        │  MultiPaper.readRegionFileAsync → ReadChunkMessage        │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌──────────────────────────────────────────────────────────┐
        │  Master: ChunkSubscriptionManager.getOwnerOrSubscriber   │
        └──────────────────────────────────────────────────────────┘
                          │                       │
              another srv │ has it                │ nobody has it
                          ▼                       ▼
        ┌────────────────────────────┐  ┌─────────────────────────────┐
        │ Reply:                     │  │ Master reads r.x.z.mca      │
        │ ChunkLoadedOnAnotherServer │  │ from its disk cache,        │
        │                            │  │ replies with bytes          │
        │ A then opens a P2P channel │  └─────────────────────────────┘
        │ to that server and pulls   │
        │ the chunk via              │
        │ RequestChunkPacket →       │
        │ SendChunkPacket            │
        └────────────────────────────┘
                                  │
                                  ▼
        ┌──────────────────────────────────────────────────────────┐
        │  Server A now has the chunk in memory.                    │
        │  It is a *subscriber* — not the owner — so it does not    │
        │  tick the chunk. It just replays incoming change packets. │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼ (player approaches, sim distance grows)
        ┌──────────────────────────────────────────────────────────┐
        │  Server A wants to TICK chunk (cx, cz): LockChunkMessage  │
        │                                                          │
        │  Master: ChunkSubscriptionManager.lock                   │
        │          • first-come-first-served                       │
        │          • if A is index 0 → A becomes owner             │
        │          • if someone else already owns it → A queues    │
        │                                                          │
        │  Master broadcasts SetChunkOwnerMessage to subscribers.  │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌──────────────────────────────────────────────────────────┐
        │  Owner ticks the chunk: entities, redstone, scheduled     │
        │  ticks, block physics. Each change is broadcast P2P to    │
        │  externalSubscribers via SendUpdatePacket.                │
        └──────────────────────────────────────────────────────────┘
                                  │
                                  ▼ (owner unloads the chunk / dies)
        ┌──────────────────────────────────────────────────────────┐
        │  UnlockChunkMessage or channelInactive on the master.     │
        │  Next server in the lock list becomes the owner.          │
        │  If nobody else had it loaded, the chunk is simply saved  │
        │  and forgotten.                                           │
        └──────────────────────────────────────────────────────────┘
```

---

## Where chunk IO gets redirected

Vanilla Paper/Purpur reads and writes chunks via `RegionFileIOThread` and the
`RegionFile` class. MultiPaper redirects those paths to the master via
`MultiPaper.readRegionFileAsync` and `MultiPaper.writeRegionFile`
(`patches/server/0016:1328-1390`).

```java
// Simplified
public static CompletableFuture<byte[]> readRegionFileAsync(String world, String path, int cx, int cz) {
    return connection.sendAndAwaitReply(new ReadChunkMessage(world, path, cx, cz))
        .thenApply(reply -> {
            if (reply instanceof ChunkLoadedOnAnotherServerMessage r) {
                // Pull from another server P2P. 20 s timeout with retry.
                return ExternalServerConnection.requestChunk(r.serverName, world, cx, cz);
            }
            return ((DataMessageReply) reply).data;
        });
}

public static void writeRegionFile(String world, String path, int cx, int cz, byte[] data) {
    connection.send(new WriteChunkMessage(world, path, cx, cz, zlibDeflate(data)));
}
```

The actual chunk save path uses the write barrier (`WillSaveChunkLater` →
`WriteChunk`) so concurrent readers don't see a half-written chunk — see
[03 — Master](./03-master-server.md).

### Event-based IO

`patches/server/0092-Use-event-based-chunk-IO.patch` replaces Paper's single
`RegionFileIOThread` with an async event-loop IO engine (`MultiPaperIO` at
`0092:97`) that runs up to 16 concurrent reads (`0092:34` defines
`ChunkRegionKey = (world, path, x, z)` for IO scheduling). This is enabled by
default (`optimizations.use-event-based-io = true`).

When disabled, all chunk IO funnels through one thread, so a single laggy peer
can stall every chunk read. With it enabled, multiple reads run concurrently
and a slow peer stalls only the reads that depend on it.

---

## Ownership state in NMS

Each chunk on each server has fields added by `0016` to `NewChunkHolder`:

| Field | Type | Meaning |
|---|---|---|
| `externalOwner` | `String` (server name) | The server that currently ticks this chunk. `null` if **we** are the owner. |
| `externalSubscribers` | `Set<String>` | Servers that have this chunk loaded for read. |
| `hasExternalLockRequest` | `boolean` | Have we sent a `LockChunkMessage` for this chunk that hasn't been answered yet? |

These fields are updated by `MultiPaperConnection.handle(SetChunkOwnerMessage)`
(`0016:1851-1885`), `AddChunkSubscriberMessage`, `RemoveChunkSubscriberMessage`,
and `ChunkSubscribersSyncMessage`.

### Ownership transfer semantics

When `SetChunkOwnerMessage` arrives and the new owner is **us**:

- We delay one tick before starting to tick the chunk, to let in-flight packets
  from the previous owner arrive and be applied.
- We start ticking normally afterwards.

When ownership transfers **away** from us:

- We send our current scheduled-tick list to the new owner
  (`SendTickListPacket`) so they pick up where we left off.
- We stop ticking the chunk.
- Subscribers (still including us if we still have it loaded) start replaying
  the new owner's updates.

### Local vs external predicates

```java
MultiPaper.isChunkLocal(world, cx, cz)    // we own it → we tick it
MultiPaper.isChunkExternal(world, cx, cz) // someone else owns it
```

These are checked **everywhere** in the patched NMS: in the entity tick loop,
the block-tick scheduler, scheduled-tick execution, hopper logic, piston
logic, etc. If a chunk is external, the operation is either skipped or
deferred to the owner (often via `SendTickListPacket`).

---

## Subscribers: keeping caches coherent

When the owner mutates a block, block-entity, or light level inside a chunk
that has subscribers, it broadcasts the change packet **directly** to those
subscribers over the P2P link — the master is not involved.

`MultiPaperChunkHandler` (`0016:1515-1771`) is the central class:

- `onBlockUpdate(packet)` (`0016:1603-1622`) — wraps the NMS packet in a
  `SendUpdatePacket` and sends it to each name in `externalSubscribers`.
- `handleBlockUpdate(packet, sourceServer)` (`:1626-1720`) — applies a
  received block/block-entity/light update to our local chunk. Can apply
  updates to unloaded chunks too (`setBlockInUnloadedChunk` `:1722-1728`),
  which is necessary because some subscribers have the chunk in memory but
  not loaded for ticking.
- `broadcastBlockEntityChange(...)` (`:1589-1601`) — batches block-entity
  changes for broadcast on the next tick (so multiple changes per tick
  collapse into one packet).
- `onLightUpdate(packet)` — forwards light updates to subscribers.

The same pattern exists for **entities** via `MultiPaperEntitiesHandler`
(`0027:1057`, see [07 — Entity & Player Sync](./07-entity-player-sync.md)).

### Initial subscription handshake

When a server first subscribes to a chunk (because it loaded it for read), the
master records the subscription and tells the owner via
`AddChunkSubscriberMessage`. The owner adds the new server to its
`externalSubscribers` set and starts broadcasting changes to it.

The full subscriber set is occasionally re-synchronised via
`ChunkSubscribersSyncMessage` (owner + subscribers list) to recover from any
desync.

---

## World border, tick lists, scheduled ticks

`MultiPaperWorldBorderHandler` (`0016:1950`) syncs world-border changes to
subscribers so all servers agree on the boundary.

Scheduled ticks (`ScheduledTick`, `LevelTicks`) are a key correctness issue: a
tick scheduled in chunk A but executing in chunk B (e.g. a piston extending
across the border) must run **on the owner of chunk B**, not on whoever
scheduled it. When ownership transfers, the old owner ships its outstanding
scheduled ticks to the new owner via `SendTickListPacket`. When a tick is
scheduled in an external chunk, `MultiPaperExternalBlocksHandler` either ships
it back to the owner or triggers an atomic ownership takeover (see
[08 — Redstone Safety](./08-redstone-safety.md)).

---

## Persistence

When a server saves a chunk, it goes through `WriteChunkMessage` to the
master, which writes the `.mca` file via `RegionFileCache`
(`MultiPaper-Master/.../util/RegionFileCache.java`). To prevent readers from
seeing a half-written chunk, the saving server first sends
`WillSaveChunkLaterMessage` — the master sets a `ChunkLockManager` barrier
that any concurrent `ReadChunk` will wait on.

A 60 s safety timeout (`ChunkLockManager.java:14`) ensures that a crashed
server can't permanently wedge a chunk: if the save doesn't arrive in 60 s,
the barrier is released and the read can proceed.

Entities follow the same pattern with `EntitiesLockManager`.

---

## Chunk ownership rules of thumb

These are not enforced by code but are the working invariants:

1. **One owner per chunk.** The master guarantees this.
2. **Subscribers != owners.** A subscriber never ticks.
3. **Subscribers always have the latest state.** They replay the owner's
   broadcast packets.
4. **A chunk may be loaded but have no owner** — e.g. it's outside every
   server's simulation distance. In that case no one ticks it.
5. **Ownership is sticky until released.** A server keeps owning a chunk until
   it either sends `UnlockChunkMessage` (typically on unload) or dies. The
   master does not preempt ownership.
6. **Atomic group takeover is opt-in.** Ordinary `LockChunkMessage` requests
   queue in order; only `RequestChunkOwnershipMessage` can grab a whole group
   at once, and only if the requester already owns at least one chunk in it.

---

## Operational impact

- **Concentrated load does not parallelise.** If 50 players all crowd into
  one chunk, that chunk is owned by exactly one server, and that server
  carries the full tick cost for it. MultiPaper helps with *spread-out*
  workloads, not concentrated ones.
- **Server hopping is cheap.** Adding more servers scales the player count
  roughly linearly as long as players spread out.
- **Render distance scales.** Because each server only ticks the chunks its
  players need, you can run a very large render distance without paying for it
  on every server — the cost is amortised across the cluster.

---

## Next

Continue to [06 — Peer-to-Peer Server Communication](./06-peer-to-peer.md).
