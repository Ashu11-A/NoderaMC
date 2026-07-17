# 04 — Master Messaging Protocol

The `MultiPaper-MasterMessagingProtocol` module is a **shared Netty library**
used by both ends of the master↔server TCP connection. It defines the wire
format, framing, message registry, transaction IDs, and a chunked "data
stream" abstraction for large payloads. The same classes ship inside both the
`multipaper.jar` (server fork) and the `multipaper-master.jar`.

Source root:
`MultiPaper-MasterMessagingProtocol/src/main/java/puregero/multipaper/mastermessagingprotocol/`.

> **Note** — the *peer-to-peer* protocol between two MultiPaper servers is a
> **different** thing, defined in `patches/server/0009-...patch` and friends.
> See [06 — Peer-to-Peer](./06-peer-to-peer.md). This file covers only the
> master↔server protocol.

---

## Wire framing

Each TCP message is framed with a VarInt length prefix:

```
┌────────────┬───────────────┬──────────────────┬───────────────┐
│ VarInt len │ VarInt txnId  │ VarInt messageId │ payload bytes │
└────────────┴───────────────┴──────────────────┴───────────────┘
```

- `MessageLengthEncoder` (`MessageLengthEncoder.java:7`) writes
  `VarInt(byteBuf.readableBytes())` then the bytes.
- `MessageLengthDecoder` (`MessageLengthDecoder.java:9`) is a
  `ByteToMessageDecoder` that reads the VarInt length and accumulates until it
  has a full frame, then passes the inner bytes downstream.
- `MessageEncoder` (`MessageEncoder.java:9`) writes
  `VarInt(transactionId)`, `VarInt(messageId)`, then the message payload.
- `MessageDecoder` (`MessageDecoder.java:11`) reverses it, looks up the class
  by `messageId`, instantiates, and calls `read(ExtendedByteBuf)`.

The pipeline is installed identically on both ends by
`MessageBootstrap.initChannel` (`MessageBootstrap.java:93-99`), but with the
directions swapped (master decodes master-bound; server decodes server-bound).

---

## Transaction IDs and async replies

Every outbound message carries a `transactionId`. The sender can register a
callback keyed by that ID via `MessageHandler.sendAndAwaitReply(...)`
(`MessageHandler.java:43-57`). When a reply with the same `transactionId`
arrives, the callback fires.

This is how chunk reads work, for example:

```java
CompletableFuture<MasterBoundMessage> future =
    connection.sendAndAwaitReply(new ReadChunkMessage(world, cx, cz));

future.thenAccept(reply -> {
    if (reply instanceof ChunkLoadedOnAnotherServerMessage) {
        // Pull the chunk from another server P2P.
    } else {
        // reply contains the deflated chunk bytes.
    }
});
```

The `MessageHandler` keeps a `Map<Integer, Consumer<…>>` of pending callbacks
(`MessageHandler.java:26-30`).

---

## Message base classes

```java
public abstract class Message<T extends MessageHandler<?>> {
    private final int transactionId;
    public abstract void write(ExtendedByteBuf buf);
    public abstract void handle(T handler);
}

public abstract class Protocol {
    private final List<Class<? extends Message>> messages = new ArrayList<>();
    public int getId(Class<? extends Message> clazz) { ... }   // list index
    public Class<? extends Message> getMessageClass(int id) { ... }
}
```

Message IDs are **list-index positions** in the `Protocol`'s ordered
registration list (`Protocol.java:19-30`). So adding a new message at the end
of the list keeps existing IDs stable.

Two concrete protocols:

- `MasterBoundProtocol` (`messages/masterbound/MasterBoundProtocol.java:5`) —
  **42** server→master message types.
- `ServerBoundProtocol` (`messages/serverbound/ServerBoundProtocol.java:5`) —
  **23** master→server message types.

Each direction has its own abstract handler
(`MasterBoundMessageHandler`, `ServerBoundMessageHandler`) with an abstract
`handle(XxxMessage)` method per message class. Netty's `MessageDecoder` calls
`message.handle(this)`, which dispatches via the visitor.

---

## `ExtendedByteBuf`

A thin wrapper over Netty's `ByteBuf` that adds the primitive helpers every
message uses (`ExtendedByteBuf.java:19`):

| Method | Encoding |
|---|---|
| `readVarInt` / `writeVarInt` | Minecraft-style VarInt (`:26-51`). |
| `readString` / `writeString` | UTF-8, VarInt length prefix (`:53-65`). |
| `readUUID` / `writeUUID` | Two longs, MSB then LSB (`:67-75`). |
| `readChunkKey` / `writeChunkKey` | UTF-8 world name + int `x` + int `z` (`:77-86`). |
| `readByteArray` / `writeByteArray` | VarInt length + bytes. |
| `readLongArray` / `writeLongArray` | VarInt count + longs. |
| (and ~900 lines delegating every other `ByteBuf` method) | |

The remaining ~900 lines just delegate every `ByteBuf` method so it acts as a
drop-in `ByteBuf`.

---

## `ChunkKey`

Immutable `(String world, int x, int z)` triple
(`ChunkKey.java:3`). Hash uses Mojang's `ChunkCoordIntPair` formula (`:27-31`)
so it matches `ChunkPos` exactly. This is the universal coordinate key used
across master, servers, and P2P packets.

---

## Data streams (for large payloads)

Some payloads are too big for a single in-memory message (file uploads,
downloads, big chunk snapshots). The protocol provides a chunked streaming
abstraction in the `datastream/` subpackage:

- `DataStreamManager` (`datastream/DataStreamManager.java:14`) — owns a
  `streamId → OutboundDataStream` / `streamId → InboundDataStream` map. One
  per `MessageHandler` (i.e. per connection).
- `OutboundDataStream` (`OutboundDataStream.java`) — reads from an
  `InputStream`, breaks it into 64 KB chunks, and writes them as
  `MasterBoundDataStreamMessage` / `ServerBoundDataStreamMessage` with a
  shared `streamId`.
- `InboundDataStream` (`InboundDataStream.java`) — reassembles chunks in
  order; can expose a `PipedInputStream` or copy to an `OutputStream`. An
  empty-data chunk signals end-of-stream (`:84-90`).
- Out-of-order arrival is tolerated with 250 ms retries
  (`DataStreamManager.java:39-52`).

Used by `UploadFileMessage`/`DownloadFileMessage` for plugin file sync and by
any large content transfer.

---

## Master-bound message catalogue (server → master)

42 messages registered in `MasterBoundProtocol.java:8-50`. Highlights:

### Handshake & lifecycle
| Message | Fields | Purpose |
|---|---|---|
| `HelloMessage` | name, serverUuid | Initial handshake; master then replies with `SetSecretMessage`. |
| `PingMessage` | — | Keepalive; 60 s timeout on the server side. |
| `StartMessage` | host, port | "I'm up — tell others to peer with me." |
| `SetPortMessage` | port | Register this server's port for the built-in proxy. |
| `WriteTickTimeMessage` | tickTime, tps | Health reporting; master broadcasts `ServerInfoUpdateMessage`. |
| `PlayerConnectMessage` / `PlayerDisconnectMessage` | uuid | Track which server hosts which player (prevents double-connect). |

### Chunk I/O
| Message | Fields | Purpose |
|---|---|---|
| `ReadChunkMessage` | world, path, cx, cz | `path ∈ {region, entities, poi}`. Reply = data or `ChunkLoadedOnAnotherServerMessage`. |
| `ForceReadChunkMessage` | same | Like read but **ignores** ownership; used for legacy flows. |
| `WriteChunkMessage` | world, path, cx, cz, data, isTransientEntities | Write chunk bytes. |
| `WillSaveChunkLaterMessage` | world, cx, cz | Set the write barrier. |
| `LockChunkMessage` / `UnlockChunkMessage` | world, cx, cz | Become / release ticking ownership. |
| `RequestChunkOwnershipMessage` | world, ChunkKey[] | Atomically acquire a whole group of chunks. |

### Chunk subscriptions
| Message | Fields | Purpose |
|---|---|---|
| `SubscribeChunkMessage` / `UnsubscribeChunkMessage` | world, cx, cz | Bookkeeping on master. |
| `SyncChunkSubscribersMessage` | world, cx, cz | "Send me the full subscriber list." |
| `ChunkChangedStatusMessage` | world, cx, cz, status | Notify status changes. |
| `SyncChunkOwnerToAllMessage` | world, cx, cz | Broadcast owner update to every server. |

### Entity subscriptions
`SubscribeEntitiesMessage`, `UnsubscribeEntitiesMessage`,
`SyncEntitiesSubscribersMessage` — analogous but for entity-chunk subscribers.

### Other world data
| Message | Purpose |
|---|---|
| `ReadLevelMessage` / `WriteLevelMessage` | `level.dat`. |
| `ReadUidMessage` / `WriteUidMessage` | `session.lock` / world UUID. |
| `ReadPlayerMessage` / `WritePlayerMessage` | `<world>/playerdata/<uuid>.dat`. |
| `ReadStatsMessage` / `WriteStatsMessage` | Player stats JSON. |
| `ReadAdvancementsMessage` / `WriteAdvancementsMessage` | Advancement JSON. |
| `ReadJsonMessage` / `WriteJsonMessage` | Generic JSON (ops/whitelist/bans). |
| `ReadDataMessage` / `WriteDataMessage` | Arbitrary keyed blobs. |
| `CallDataStorageMessage` | KV: action (GET/SET/ADD/LIST), key, value. |

### Files & misc
| Message | Purpose |
|---|---|
| `UploadFileMessage` / `DownloadFileMessage` | Plugin/config file sync. |
| `RequestFilesToSyncMessage` | "What files do I need to upload at startup?" |
| `RequestEntityIdBlockMessage` | Allocate 4096 entity IDs. |
| `MasterBoundDataStreamMessage` | Stream chunk for a data stream. |

---

## Server-bound message catalogue (master → server)

23 messages registered in `ServerBoundProtocol.java:8-30`. Highlights:

### Cluster state
| Message | Fields | Purpose |
|---|---|---|
| `SetSecretMessage` | secret (UUID) | P2P handshake token; sent immediately after `Hello`. |
| `ShutdownMessage` | — | Master-initiated cluster shutdown. |
| `ServerStartedMessage` | host, port | "Another server joined — dial it peer-to-peer." |
| `ServerInfoUpdateMessage` | name, avgTickTime, tps | Broadcast server health. |

### Chunk ownership & subscriptions
| Message | Fields | Purpose |
|---|---|---|
| `SetChunkOwnerMessage` | world, cx, cz, owner | "This server now ticks the chunk." |
| `AddChunkSubscriberMessage` / `RemoveChunkSubscriberMessage` | world, cx, cz, server | Incremental subscriber updates. |
| `ChunkSubscribersSyncMessage` | world, cx, cz, owner, subscribers[] | Full subscriber sync. |
| `ServerChangedChunkStatusMessage` | world, cx, cz, server, status | Status changed remotely. |
| `ChunkLoadedOnAnotherServerMessage` | serverName | Reply to `ReadChunkMessage` when another server has the chunk; client pulls it P2P. |

### Entity subscriptions
`AddEntitySubscriberMessage`, `RemoveEntitySubscriberMessage`,
`EntitySubscribersSyncMessage` — analogous.

### Files
| Message | Purpose |
|---|---|
| `FileContentMessage` | Master pushes a file content (real-time sync). |
| `FilesToSyncMessage` | Reply to `RequestFilesToSyncMessage` — list of `FileToSync` entries. |

### Generic replies
| Message | Purpose |
|---|---|
| `DataMessageReply` | Byte-array reply. |
| `BooleanMessageReply` | Boolean reply (e.g. ownership granted/denied). |
| `NullableStringMessageReply` | Reply with optional string. |
| `KeyValueStringMapMessageReply` | Reply with a `Map<String,String>` (used by `CallDataStorage` LIST). |
| `IntegerPairMessageReply` | Two ints (used by `RequestEntityIdBlock`: start + count). |
| `DataStreamMessageReply` | Reply that opens a data stream. |
| `DataUpdateMessage` | Notification of a KV value change. |

---

## Connection establishment

```
Server                                  Master
  │                                       │
  │── TCP connect ───────────────────────►│
  │                                       │
  │── HelloMessage(name, uuid) ──────────►│  (master records the connection)
  │                                       │
  │◄──────────────── SetSecretMessage ────│  (server stores the P2P secret)
  │                                       │
  │── StartMessage(host, port) ──────────►│  (master broadcasts ServerStarted)
  │                                       │
  │                                       │── ServerStartedMessage ───► (each other server)
  │                                       │
  │◄─── (peer-to-peer connections form via the Minecraft-handshake hack) ─►
  │                                       │
  │── normal master↔server traffic ──────►│
```

From this point on, the master is mostly a **control plane**: ownership
negotiation, file sync, KV, health. Bulk chunk content flows **directly between
servers** over the P2P link.

---

## Backpressure and threading

- Netty auto-reads and runs the handler on its event loop. The master uses
  epoll by default (`MessageBootstrap.java:45-53`); the server re-registers
  its P2P channels onto MultiPaper's own event loop (not Minecraft's tick
  thread) to keep P2P traffic off the main thread — see
  [06 — Peer-to-Peer](./06-peer-to-peer.md).
- Chunk writes are zlib-compressed by the sender before being written; reads
  are deflated in the same way.
- The `consolidation-delay` and `compression-threshold` peer-connection
  settings (see [11 — Configuration](./11-configuration.md)) tune how
  aggressively P2P packets are batched — this is **not** applied to the master
  protocol, which sends each message as soon as it is produced.

---

## Adding a new message

To extend the protocol:

1. Add a new class extending `MasterBoundMessage` (or `ServerBoundMessage`)
   with `write(ExtendedByteBuf)`, `read(ExtendedByteBuf)`, and
   `handle(...)` methods.
2. **Append** it to the appropriate `Protocol` registration list — **never**
   insert in the middle, since IDs are list indices and changing an ID is a
   wire-incompatible change.
3. Add an abstract `handle(XxxMessage)` method to the corresponding
   `MasterBoundMessageHandler` / `ServerBoundMessageHandler`.
4. Implement the handler on each side.

Because messages are versioned only by their position in the list, the master
and all servers must run **the same `masterVersion`** (see
[02 — Build System](./02-build-system.md)). A version mismatch will silently
deserialise messages as the wrong class.

---

## Next

Continue to [05 — Chunk Synchronization & Ownership](./05-chunk-sync.md).
