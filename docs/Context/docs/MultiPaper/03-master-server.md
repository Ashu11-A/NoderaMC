# 03 — The MultiPaper-Master Module

The **MultiPaper-Master** is a standalone Java/Netty process that owns the
on-disk world files and arbitrates chunk ownership for the cluster. It is the
control-plane authority for MultiPaper. Although it is typically run as its
own process, the same jar can also run as a **BungeeCord** or **Velocity**
plugin so it can be co-located with your proxy.

This file describes its entry point, lifecycle, internal structure, storage
layout, and how it answers the requests that servers make of it.

Source root: `MultiPaper-Master/src/main/java/puregero/multipaper/server/`.

---

## Entry point and CLI

`MultiPaperServer.main(String[] args)` (`MultiPaperServer.java:22-60`):

```
java -jar multipaper-master.jar [address:]port [proxyPort]
```

- `port` defaults to `35353` if omitted.
- `address` is optional; defaults to `0.0.0.0`.
- `proxyPort` is optional; if provided, the built-in proxy server is started
  on that port (see [10 — Proxy & Load Balancing](./10-proxy-load-balancing.md)).

Startup sequence:

1. Parse args, optionally enable `LogToFile`.
2. If `proxyPort` was given: `setupGracefulShutdown()` and
   `ProxyServer.openServer(proxyPort)` (`MultiPaperServer.java:45-53`).
3. `new MultiPaperServer(address, port)` (`:55`) — opens the master Netty
   server.
4. `new CommandLineInput().run()` (`:57`) — blocks the main thread on stdin
   for the interactive commands `shutdown`, `exit`, `threaddump`.

The constructor (`:80-97`) calls
`super(new MasterBoundProtocol(), new ServerBoundProtocol(), channel -> …)`
and `listenOn(...)`. Netty is set to use **epoll** when available
(`MessageBootstrap.java:45-53`), falling back to NIO.

A single shared secret is generated at startup:

```java
public static final UUID SECRET = UUID.randomUUID();   // MultiPaperServer.java:20
```

This secret is sent to every connecting server via `SetSecretMessage` and is
later used as the **peer-to-peer handshake token** that servers embed in their
Minecraft handshake hostname when dialling each other. See
[06 — Peer-to-Peer](./06-peer-to-peer.md).

---

## Netty pipeline

The master uses the pipeline set up by `MessageBootstrap.initChannel`
(`MultiPaper-MasterMessagingProtocol/.../MessageBootstrap.java:93`):

```
socket
  │
  ├── MessageLengthEncoder           (VarInt length prefix, outbound)
  ├── MessageLengthDecoder           (Accumulates bytes until full frame)
  ├── MessageEncoder<MasterBound>    (Encodes server→master messages on read side)
  ├── MessageDecoder<ServerBound>    (Decodes master→server messages)
  │
  └── ServerConnection               (per-server handler + dispatcher)
```

One `ServerConnection` is created per connected MultiPaper server. It:

- Tracks the server's `name`, `tps`, `CircularTimer` of tick times, `host`,
  `port`, and `playerUUIDs`.
- Holds a static `connectionMap` (name → connection, including dead ones) and a
  live `connections` list (`ServerConnection.java:18-130`).
- Implements `MasterBoundMessageHandler` — every inbound `handle(...)`
  delegates to a dedicated `handlers.*Handler` class (`:211-414`).
- Exposes `send`, `sendReply`, `broadcastAll`, `broadcastOthers`.
- On `channelInactive` (`:138-147`) cleans up all of this server's chunk and
  entity subscriptions and releases its chunk locks, so ownership passes to
  the next server in the lock list.

---

## Coordination: chunk ownership & subscriptions

The two most important classes on the master:

### `ChunkSubscriptionManager` (`ChunkSubscriptionManager.java:13`)

The **coordination brain**. Two maps keyed by `ChunkKey`:

- `chunkLocks` — an **ordered** list of servers that want to tick the chunk;
  **index 0 is the owner**, the rest are waiting (`:18`).
- `chunkSubscribers` — the set of servers that have the chunk loaded for read.

Key methods:

- `lock(server, key, force)` (`:52-91`) — adds `server` to the lock list
  (first-come-first-served unless `force=true`, which is used by
  `RequestChunkOwnershipHandler` to jump to index 0). On owner change,
  broadcasts `SetChunkOwnerMessage` to all subscribers.
- `subscribe(server, key)` / `unsubscribe(server, key)` (`:139-197`) — adds /
  removes from the subscriber set and notifies peers via
  `AddChunkSubscriberMessage` / `RemoveChunkSubscriberMessage`.
- `syncSubscribers(server, key)` (`:215-226`) — sends a full
  `ChunkSubscribersSyncMessage` (owner + subscriber list) to one server.
- `getOwnerOrSubscriber(key)` — used by `ReadChunkHandler` to decide where to
  send a chunk read.

### `EntitiesSubscriptionManager` (`EntitiesSubscriptionManager.java:12`)

Same idea but for entity-chunk subscriptions. Used to route entity update
broadcasts so that the right servers receive entity packets.

### `ChunkLockManager` and `EntitiesLockManager`

Read/write barriers so a chunk that is **about to be saved** by one server
isn't concurrently read by another. They are `CompletableFuture`-based:

- `WillSaveChunkLater` from a server → `lockUntilWrite`.
- A `ReadChunk` from another server → `waitForLock` blocks the reply until the
  save completes (or until the 60 s safety timeout,
  `ChunkLockManager.java:14`).
- `WriteChunk` from the saver → `writtenChunk` resolves the future.

Same pattern for entities.

---

## File storage layout

The master stores the world **exactly the way a normal Minecraft server does**,
relative to its own working directory. Dimension path resolution is in
`ReadChunkHandler.getWorldDir` (`handlers/ReadChunkHandler.java:57-69`):

```
<worldName>/region/r.x.z.mca          (overworld)
<worldName>/entities/r.x.z.mca
<worldName>/poi/r.x.z.mca
<worldName>_nether/DIM-1/region/...
<worldName>_the_end/DIM1/region/...
<worldName>/level.dat                  (ReadLevelHandler / WriteLevelHandler)
<worldName>/playerdata/<uuid>.dat      (ReadPlayerHandler / WritePlayerHandler)
<worldName>/stats/<uuid>.json          (ReadStatsHandler / WriteStatsHandler)
<worldName>/advancements/<uuid>.json   (ReadAdvancementsHandler / WriteAdvancementsHandler)
<worldName>/session.lock               (ReadUidHandler / WriteUidHandler)
```

Plugin files synced via `MultiPaperFileSyncer` go under `synced-server-files/`
(`handlers/UploadFileHandler.java:17`).

### Region file I/O

`util/RegionFileCache.java` (`:33`) is an LRU cache (default 256 entries,
configurable via `-Dmax.regionfile.cache.size`) of `util/RegionFile` objects,
one per `.mca` file. It exposes async
`getChunkDeflatedDataAsync(world, cx, cz)` / `putChunkDeflatedDataAsync(...)`
methods (`:144-173`) that run on the region file's own serialization queue.
Chunks are stored as zlib-deflated NBT, same as vanilla.

### Atomic file writes

`FileLocker.java` (`:16`) caches in-flight writes and uses
`Files.move(..., ATOMIC_MOVE)` to make file replacements atomic, so a crash
during write never leaves a torn file. This is important because the master is
the **single point of persistence** for the cluster.

### Key-value store

`handlers/CallDataStorageHandler.java` (`:21`) implements a tiny KV store
backed by `datastorage.yml` on disk. The store is saved atomically every
15 seconds and on shutdown (`:108-154`). Plugins can call `get`, `set`, `add`,
`list` via the `MultiPaperDataStorage` Bukkit API — see
[13 — Plugin Development](./13-plugin-development.md).

### Entity ID allocation

To keep entity IDs consistent across the cluster, the master hands out
**4096-id blocks** per request (`handlers/RequestEntityIdBlockHandler.java:14`,
size configurable via `-Dentityid.block.size`). The current high-water mark is
persisted to `lastblock.txt` (`:45, :60`) so it survives restarts.

---

## How the master answers common requests

### "I need to load chunk X"

Server → `ReadChunkMessage`. Master `ReadChunkHandler` (`:12-69`):

1. Looks up `ChunkSubscriptionManager.getOwnerOrSubscriber`.
2. If another server has it, replies
   `ChunkLoadedOnAnotherServerMessage(ownerName)` — the requester then pulls
   the chunk **peer-to-peer** via `RequestChunkPacket`/`SendChunkPacket`.
3. Otherwise reads the chunk from the region file (deflated NBT) and replies
   with the data.
4. Subscribes the requester for future changes to that chunk.

### "I want to tick chunk X"

Server → `LockChunkMessage`. Master `LockChunkHandler` (`:9`) calls
`ChunkSubscriptionManager.lock(server, key, force=false)` and lets the
`SetChunkOwnerMessage` broadcast propagate to subscribers.

If another server already owns the chunk, this server is added to the *lock
list* and notified. It will only become the owner when the current owner
unlocks (e.g. by unloading the chunk) or dies.

### "I want to atomically own this whole group of chunks"

Server → `RequestChunkOwnershipMessage(group)`. Master
`RequestChunkOwnershipHandler` (`:12-35`) grants the group only if the
requester **already owns at least one** chunk in it. This is the redstone
safety primitive — see [08 — Redstone Safety](./08-redstone-safety.md).

### "I'm about to save chunk X" / "I saved chunk X"

Server → `WillSaveChunkLaterMessage` / `WriteChunkMessage`. Master sets/clears
a `ChunkLockManager` lock and writes the bytes through `RegionFileCache`.

### "I changed a block inside chunk X"

The master is **not** involved. The owning server broadcasts the change
**peer-to-peer** directly to its `externalSubscribers`. This is the key
optimisation that keeps the master out of the hot path.

### "Server health"

Each server periodically sends `WriteTickTimeMessage(tickTime, tps)`. The
master `WriteTickTimeHandler` (`:10`) updates the server's `CircularTimer` and
broadcasts `ServerInfoUpdateMessage` to all servers. This is what powers
`/servers`, `/mpdebug`, and the load-balancer weights.

---

## The 38 handlers

Every master-bound message has a dedicated handler class in
`puregero.multipaper.server.handlers`. They follow a uniform shape:

```java
public class XxxHandler {
    public static void handle(MasterBoundMessageHandler ctx, XxxMessage message) { ... }
}
```

and `ServerConnection.handle(message)` dispatches to them. The full list
(`ServerConnection.java:211-414`):

**Chunk I/O**: `ReadChunkHandler`, `ForceReadChunkHandler`, `WriteChunkHandler`,
`LockChunkHandler`, `UnlockChunkHandler`, `RequestChunkOwnershipHandler`,
`SubscribeChunkHandler`, `UnsubscribeChunkHandler`, `SyncChunkSubscribersHandler`,
`SyncChunkOwnerToAllHandler`, `ChunkChangedStatusHandler`,
`WillSaveChunkHandler`.

**Entity I/O & subscriptions**: `SubscribeEntitiesHandler`,
`UnsubscribeEntitiesHandler`, `SyncEntitiesSubscribersHandler`,
`WillSaveEntitiesHandler`.

**Other world data**: `ReadLevelHandler`/`WriteLevelHandler`,
`ReadUidHandler`/`WriteUidHandler`, `ReadPlayerHandler`/`WritePlayerHandler`,
`ReadStatsHandler`/`WriteStatsHandler`,
`ReadAdvancementsHandler`/`WriteAdvancementsHandler`,
`ReadJsonHandler`/`WriteJsonHandler`,
`ReadDataHandler`/`WriteDataHandler`,
`CallDataStorageHandler`.

**Files**: `UploadFileHandler`, `DownloadFileHandler`,
`RequestFilesToSyncHandler`.

**Players & cluster**: `PlayerConnectHandler`, `PlayerDisconnectHandler`,
`StartHandler`, `SetPortHandler`, `WriteTickTimeHandler`, `PingHandler`.

**Identity**: `RequestEntityIdBlockHandler`.

---

## Lifecycle as a BungeeCord / Velocity plugin

The same jar can be dropped into a proxy's `plugins/` directory.

- `bungee/MultiPaperBungee.java` — BungeeCord plugin; on the master's behalf,
  listens for `ServerConnectEvent` and reroutes the player to the
  least-busy MultiPaper server (lowest `CircularTimer.averageInMillis()`).
- `velocity/MultiPaperVelocity.java` — Velocity plugin; same logic on
  `ServerPreConnectEvent`.

When running as a proxy plugin, the master server is started inside the
proxy JVM (the plugin opens the master port itself). The proxy can still also
run the built-in proxy port — but typically you would let the host proxy
(BungeeCord/Velocity) handle player connections in that case.

See [10 — Proxy & Load Balancing](./10-proxy-load-balancing.md).

---

## Failure behaviour

- **A MultiPaper server dies** — its TCP connection drops, `channelInactive`
  fires on the master (`ServerConnection.java:138-147`), and the master
  releases all the dead server's chunk and entity subscriptions and locks.
  Other servers with a chunk loaded take over ticking via the lock list;
  chunks that nobody had loaded will be re-read from disk by the next server
  that needs them.
- **The master dies** — already-connected servers keep running for as long as
  they can serve from in-memory state, but: no new chunks can be loaded from
  disk, nothing can be persisted, no ownership changes can occur, no new
  servers can join. The master is the **storage and control-plane SPOF**.

---

## Next

Continue to [04 — Master Messaging Protocol](./04-messaging-protocol.md).
