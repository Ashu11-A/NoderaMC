# 12 — Key Classes & File Reference

A cheat-sheet of every important class with its source path and (where
relevant) line references. Use this as a lookup table when reading the code.

All paths are relative to `/MultiPaper/` unless otherwise noted.

> **Reminder:** the game-side Java code lives **inside `.patch` files**. Each
> patch hunk's `+++ b/...` path names the file that will appear under
> `MultiPaper-Server/src/main/java/` (or `MultiPaper-API/...`) after
> `./gradlew applyPatches`. The references below cite the patch and line
> where the class is defined.

---

## Master side — `MultiPaper-Master/src/main/java/puregero/multipaper/server/`

### Bootstrap & connection

| Class | Path:Line | Role |
|---|---|---|
| `MultiPaperServer` | `MultiPaperServer.java:18` | `main()` entry point; Netty bootstrap; launches proxy. |
| `ServerConnection` | `ServerConnection.java:18` | Per-server connection; dispatches every master-bound message; tracks name/tps/tickTime/players; `channelInactive` cleans up. |
| `Player` | `Player.java` | Serialisable player snapshot (uuid, world, x/y/z, yaw/pitch). |
| `CircularTimer` | `CircularTimer.java:3` | Ring buffer (1200 samples) of tick times → rolling average in ms. |
| `CommandLineInput` | `CommandLineInput.java` | Stdin handler for `shutdown`/`exit`/`threaddump`. |
| `FileLocker` | `FileLocker.java:16` | In-flight write cache + atomic `Files.move`. |

### Coordination

| Class | Path:Line | Role |
|---|---|---|
| `ChunkSubscriptionManager` | `ChunkSubscriptionManager.java:13` | **The coordination brain.** Per-chunk ordered lock list (index 0 = owner) + subscriber set. |
| `EntitiesSubscriptionManager` | `EntitiesSubscriptionManager.java:12` | Entity-chunk subscriber registry. |
| `ChunkLockManager` | `ChunkLockManager.java:9` | `CompletableFuture`-based read/write barrier for chunk saves. 60 s safety timeout. |
| `EntitiesLockManager` | `EntitiesLockManager.java:9` | Same for entity saves. |

### Handlers (`handlers/`)

| Class | Path | Role |
|---|---|---|
| `ReadChunkHandler` | `handlers/ReadChunkHandler.java:12` | Chunk read; redirects to peer if owned elsewhere. |
| `ForceReadChunkHandler` | `handlers/ForceReadChunkHandler.java` | Like `ReadChunk` but ignores ownership. |
| `WriteChunkHandler` | `handlers/WriteChunkHandler.java:19` | Writes chunk bytes; merges transient entity NBT. |
| `LockChunkHandler` | `handlers/LockChunkHandler.java:9` | Become ticking owner (queue). |
| `UnlockChunkHandler` | `handlers/UnlockChunkHandler.java` | Release ownership. |
| `RequestChunkOwnershipHandler` | `handlers/RequestChunkOwnershipHandler.java:12` | Atomic group ownership takeover. |
| `SubscribeChunkHandler` / `UnsubscribeChunkHandler` | `handlers/SubscribeChunkHandler.java:10` | Subscriber bookkeeping. |
| `SyncChunkSubscribersHandler` | `handlers/SyncChunkSubscribersHandler.java` | Full subscriber list sync. |
| `SyncChunkOwnerToAllHandler` | `handlers/SyncChunkOwnerToAllHandler.java` | Broadcast owner update to every server. |
| `ChunkChangedStatusHandler` | `handlers/ChunkChangedStatusHandler.java` | Chunk status change notification. |
| `WillSaveChunkHandler` | `handlers/WillSaveChunkHandler.java` | Set the write barrier. |
| `SubscribeEntitiesHandler` / `UnsubscribeEntitiesHandler` / `SyncEntitiesSubscribersHandler` | `handlers/...` | Entity subscriptions. |
| `WillSaveEntitiesHandler` | `handlers/WillSaveEntitiesHandler.java` | Entity write barrier. |
| `ReadLevelHandler` / `WriteLevelHandler` | `handlers/ReadLevelHandler.java` | `level.dat`. |
| `ReadUidHandler` / `WriteUidHandler` | `handlers/ReadUidHandler.java` | `session.lock`. |
| `ReadPlayerHandler` / `WritePlayerHandler` | `handlers/ReadPlayerHandler.java` | Player `.dat` files. |
| `ReadStatsHandler` / `WriteStatsHandler` | `handlers/ReadStatsHandler.java` | Stats JSON. |
| `ReadAdvancementsHandler` / `WriteAdvancementsHandler` | `handlers/ReadAdvancementsHandler.java` | Advancement JSON. |
| `ReadJsonHandler` / `WriteJsonHandler` | `handlers/ReadJsonHandler.java` | `ops.json`, `whitelist.json`, etc. |
| `ReadDataHandler` / `WriteDataHandler` | `handlers/ReadDataHandler.java` | Arbitrary keyed blobs. |
| `CallDataStorageHandler` | `handlers/CallDataStorageHandler.java:21` | KV store (GET/SET/ADD/LIST), backed by `datastorage.yml`. |
| `UploadFileHandler` / `DownloadFileHandler` | `handlers/UploadFileHandler.java:16` | Plugin file sync IO. |
| `RequestFilesToSyncHandler` | `handlers/RequestFilesToSyncHandler.java:15` | Returns list of syncable files. |
| `PlayerConnectHandler` / `PlayerDisconnectHandler` | `handlers/PlayerConnectHandler.java` | Track player locations. |
| `RequestEntityIdBlockHandler` | `handlers/RequestEntityIdBlockHandler.java:14` | Hand out 4096-id blocks. |
| `StartHandler` / `SetPortHandler` | `handlers/StartHandler.java` | Server lifecycle. |
| `WriteTickTimeHandler` | `handlers/WriteTickTimeHandler.java:10` | Health → `ServerInfoUpdateMessage` broadcast. |
| `PingHandler` | `handlers/PingHandler.java` | Keepalive. |

### Proxy / BungeeCord / Velocity

| Class | Path:Line | Role |
|---|---|---|
| `ProxyServer` | `proxy/ProxyServer.java:16` | Built-in NIO load-balancing proxy. |
| `ProxiedConnection` | `proxy/ProxiedConnection.java:10` | Half-duplex byte pipe between player ↔ target server. |
| `HelloPacket` | `proxy/HelloPacket.java:8` | Rewrites the handshake to inject the player's real IP. |
| `MultiPaperBungee` | `bungee/MultiPaperBungee.java:23` | BungeeCord plugin wrapper (load balancing). |
| `MultiPaperVelocity` | `velocity/MultiPaperVelocity.java:28` | Velocity plugin wrapper (load balancing). |

### Storage utilities

| Class | Path:Line | Role |
|---|---|---|
| `RegionFileCache` | `util/RegionFileCache.java:33` | LRU (256) of `RegionFile`s; async chunk IO. |
| `RegionFile` | `util/RegionFile.java` | Single `.mca` file with serialised task queue. |
| `ChunkLock` / `EntitiesLock` | `util/ChunkLock.java` | Lock primitives. |
| `LogToFile` | `util/LogToFile.java` | Tee stdout/stderr to a file. |

---

## Protocol library — `MultiPaper-MasterMessagingProtocol/src/main/java/puregero/multipaper/mastermessagingprotocol/`

| Class | Path:Line | Role |
|---|---|---|
| `MessageBootstrap` | `MessageBootstrap.java:21` | Netty bootstrap (Epoll/Nio), pipeline setup. |
| `MessageEncoder` / `MessageDecoder` | `MessageEncoder.java:9` / `MessageDecoder.java:11` | Per-message (de)serialise + transaction ID. |
| `MessageLengthEncoder` / `MessageLengthDecoder` | `MessageLengthEncoder.java:7` / `MessageLengthDecoder.java:9` | VarInt length framing. |
| `Message` | `messages/Message.java:5` | Abstract base: transactionId, write, handle. |
| `Protocol` | `messages/Protocol.java:9` | id↔class registry (list-index IDs). |
| `MessageHandler` | `messages/MessageHandler.java:12` | `SimpleChannelInboundHandler`; transaction callback map; `DataStreamManager`. |
| `MasterBoundProtocol` | `messages/masterbound/MasterBoundProtocol.java:5` | 42 server→master message registrations. |
| `ServerBoundProtocol` | `messages/serverbound/ServerBoundProtocol.java:5` | 23 master→server message registrations. |
| `MasterBoundMessageHandler` | `messages/masterbound/MasterBoundMessageHandler.java:7` | Abstract visitor for master-bound messages. |
| `ServerBoundMessageHandler` | `messages/serverbound/ServerBoundMessageHandler.java:7` | Abstract visitor for server-bound messages. |
| `ExtendedByteBuf` | `ExtendedByteBuf.java:19` | VarInt/String/UUID/ChunkKey helpers + ByteBuf delegate. |
| `ChunkKey` | `ChunkKey.java:3` | Immutable `(world,x,z)` key with Mojang-style hash. |
| `DataStreamManager` | `datastream/DataStreamManager.java:14` | streamId → outbound/inbound stream map. |
| `OutboundDataStream` | `datastream/OutboundDataStream.java:12` | Chunks an InputStream into 64 KB `*DataStreamMessage`s. |
| `InboundDataStream` | `datastream/InboundDataStream.java:13` | Reassembles chunks; out-of-order tolerant. |

### Notable message classes

- **Master-bound** (`messages/masterbound/`): `HelloMessage`, `PingMessage`,
  `StartMessage`, `SetPortMessage`, `WriteTickTimeMessage`,
  `PlayerConnectMessage`, `PlayerDisconnectMessage`, `ReadChunkMessage`,
  `ForceReadChunkMessage`, `WriteChunkMessage`, `WillSaveChunkLaterMessage`,
  `LockChunkMessage`, `UnlockChunkMessage`, `RequestChunkOwnershipMessage`,
  `SubscribeChunkMessage`, `UnsubscribeChunkMessage`,
  `SyncChunkSubscribersMessage`, `ChunkChangedStatusMessage`,
  `SyncChunkOwnerToAllMessage`, `SubscribeEntitiesMessage`,
  `UnsubscribeEntitiesMessage`, `SyncEntitiesSubscribersMessage`,
  `WillSaveEntitiesLaterMessage`, `ReadLevelMessage`, `WriteLevelMessage`,
  `ReadUidMessage`, `WriteUidMessage`, `ReadPlayerMessage`,
  `WritePlayerMessage`, `ReadStatsMessage`, `WriteStatsMessage`,
  `ReadAdvancementsMessage`, `WriteAdvancementsMessage`, `ReadJsonMessage`,
  `WriteJsonMessage`, `ReadDataMessage`, `WriteDataMessage`,
  `CallDataStorageMessage`, `UploadFileMessage`, `DownloadFileMessage`,
  `RequestFilesToSyncMessage`, `RequestEntityIdBlockMessage`,
  `MasterBoundDataStreamMessage`.

- **Server-bound** (`messages/serverbound/`): `SetSecretMessage`,
  `ShutdownMessage`, `ServerStartedMessage`, `ServerInfoUpdateMessage`,
  `SetChunkOwnerMessage`, `AddChunkSubscriberMessage`,
  `RemoveChunkSubscriberMessage`, `ChunkSubscribersSyncMessage`,
  `ServerChangedChunkStatusMessage`, `ChunkLoadedOnAnotherServerMessage`,
  `AddEntitySubscriberMessage`, `RemoveEntitySubscriberMessage`,
  `EntitySubscribersSyncMessage`, `FileContentMessage`, `FilesToSyncMessage`,
  `DataMessageReply`, `BooleanMessageReply`, `NullableStringMessageReply`,
  `KeyValueStringMapMessageReply`, `IntegerPairMessageReply`,
  `DataStreamMessageReply`, `DataUpdateMessage`,
  `ServerBoundDataStreamMessage`.

---

## Game-side (server fork) — inside `patches/server/*.patch`

Paths below become real files under
`MultiPaper-Server/src/main/java/<pkg>/...` after `applyPatches`.

### Core (`puregero.multipaper`)

| Class | Patch:Line | Role |
|---|---|---|
| `MultiPaper` | `0007:112`, `0009:257`, `0016:1204` | Static facade: master connection, `isChunkLocal/External`, `readRegionFile(Async)`, `writeRegionFile`, `lockChunk/unlockChunk`, `broadcastPacketToExternalServers`, `runSync`, `onStart`. |
| `MultiPaperConnection` | `0007:131`, `0009:325`, `0016:1772` | Client-side master connection; handles `SetSecret`, `ServerStarted`, `SetChunkOwner`, subscriber messages; `sendAndAwaitReply`. |
| `ExternalServer` | `0007:57` | Represents another server (name, tps, tickTime, connection). |
| `ExternalServerConnection` | `0009:128`, `0012:508`, `0014:46` | P2P Netty channel; hijacks handshake; packet send/consolidation; `requestChunk`. |
| `ExternalPlayer` | `0012:419`, `0014:7` | Phantom `ServerPlayer` for foreign players; forwards packets. |
| `MultiPaperChunkHandler` | `0016:1515` | Block/entity/light update broadcast & apply; subscription lifecycle. |
| `MultiPaperWorldBorderHandler` | `0016:1950` | Syncs world-border changes. |
| `MultiPaperEntitiesHandler` | `0027:1057` | Entity ticking decisions; controlling passenger resolution. |
| `MultiPaperPlayerHandler` | `0027:1451` | Player-specific entity sync. |
| `MultiPaperInventoryHandler` | `0024:443` | Cross-server inventory/container/teleport. |
| `MultiPaperContainerHandler` | `0033` | Cross-server hopper / item transfers. |
| `MultiPaperExternalBlocksHandler` | `0032:111` | **Redstone safety**: atomic chunk-ownership takeover. |
| `MultiPaperIO` | `0092:97` | Event-based async chunk IO (replaces `RegionFileIOThread`). |
| `ChunkRegionKey` | `0092:34` | `(world, path, x, z)` key for IO scheduling. |
| `MultiPaperFileSyncer` | `0088:125` | Filesystem watcher → master upload. |

### Config (`puregero.multipaper.config`)

| Class | Patch:Line | Role |
|---|---|---|
| `MultiPaperConfiguration` | `0003:122` | `multipaper.yml` schema (nested `ConfigurationPart`). |
| `MultiPaperConfigurationLoader` | `0003:217` | Loads/saves/migrates config + `-D` overrides. |
| `MultiPaperPermissions` | `0003:394` | Registers `multipaper.*` permissions. |

### Commands (`puregero.multipaper.commands`)

| Class | Patch:Line | Role |
|---|---|---|
| `ServersCommand` | `0013:42` | `/servers` — list cluster + TPS/tickTime/player count. |
| `MPDebugCommand` | `0017:31` | `/mpdebug` — in-world chunk-ownership overlay. |
| `MPMapCommand` / `MapCommandBase` | `0056:31` / `0056:62` | `/mpmap` — ASCII map of nearby chunks. |
| `SListCommand` | `0068:31` | `/slist` — list online players and their servers. |

### P2P packets (`puregero.multipaper.externalserverprotocol`)

Created across `0009`, `0012`, `0014`, `0016`, `0024`, `0027`, `0030`, `0130`.

Base + transport: `ExternalServerPacket`, `ExternalServerPacketSerializer`
(id registry), `ExternalServerPacketEncoder`, `ExternalServerPacketDecoder`,
`ExternalServerPacketHandler`, `HelloPacket`, `SetCompressionPacket`,
`ZlibCompressionEncoder`, `ZlibCompressionDecoder`, `PacketConsolidationHandler`.

Per-purpose packets: `RequestChunkPacket`, `SendChunkPacket`, `SendTickListPacket`,
`SendUpdatePacket`, `SendPacketPacket`, `PlayerCreatePacket`, `PlayerRemovePacket`,
`PlayerChangeDimensionPacket`, `PlayerChangeGamemodePacket`, `PlayerRespawnPacket`,
`PlayerActionPacket`, `PlayerInventoryUpdatePacket`, `EntityUpdatePacket`,
`EntityUpdateNBTPacket`, `EntityUpdateWithDependenciesPacket`, `EntityRemovePacket`,
`RequestEntityPacket`, `RequestEntitiesPacket`, `SendEntitiesPacket`,
`SubscribeToWorldPacket`, `AddItemToContainerPacket`, `PullItemFromContainerPacket`,
`AddItemToEntityContainerPacket`, `PistonMoveBlockStartPacket`,
`PistonMoveBlockEndPacket`.

---

## Game-side API — inside `patches/api/*.patch`

These become real files under `MultiPaper-API/src/main/java/<pkg>/...`.

| Class | Patch | Role |
|---|---|---|
| `org.bukkit.MultiPaperNotificationManager` | `api/0001:106` | Pub/sub notification API (`on`, `notify`, `notifyOwningServer`). |
| `puregero.multipaper.MultiPaperDataStorage` | `api/0006:65` | Async KV store (`get`/`set`/`add`/`list`). |
| Bukkit additions | `api/0001`, `0005`, `0006`, `0007`, `0008`, `0009` | `Bukkit.getAllOnlinePlayers()`, `getLocalOnlinePlayers()`, `getLocalServerName()`, `getMultiPaperDataStorage()`, `getNotificationManager()`; `Player.isLocalPlayer()`; `Chunk.isLocalChunk()`; `Location.isChunkLocal()`; `Entity.isInLocalChunk()`; `Block.isInLocalChunk()`; `Entity.getTrackedPlayers(...)`. |

---

## Patch index (selected highlights)

| Patch | Lines | Topic |
|---|---|---|
| `0007-Add-MultiPaperConnection` | ~200 | Master connection bootstrap. |
| `0009-Add-peer-to-peer-connection` | ~800 | P2P pipeline + handshake hijack + zlib. |
| `0012-Add-ExternalPlayer` | ~600 | Phantom players. |
| `0014-Forward-ExternalPlayer-packets` | ~600 | `SendPacketPacket` tunnelling. |
| `0016-Add-chunk-syncing` | ~2500 | The big chunk sync patch. |
| `0024-Sync-inventories` | ~1000 | Cross-server inventory. |
| `0027-Sync-entities` | ~130 KB | Entity ticking, controlling passengers. |
| `0032-Add-redstone-chunk-lock` | ~270 | Atomic group ownership takeover. |
| `0033-Sync-hoppers` | — | Container sync. |
| `0088-Add-generic-file-syncing` | — | `MultiPaperFileSyncer`. |
| `0092-Use-event-based-chunk-IO` | — | Async chunk IO. |
| `0130-Sync-pistons` | — | Cross-server piston moves. |
| `0134`, `0139`, `0144` | — | Dupe fixes (Ikea V1, anvil enchant, slot sanity). |

Full list of all 150 server patches: `ls patches/server/`.

---

## Next

Continue to [13 — Plugin Development Guide](./13-plugin-development.md).
