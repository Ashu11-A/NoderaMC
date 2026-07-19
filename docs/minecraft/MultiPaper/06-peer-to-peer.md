# 06 — Peer-to-Peer Server Communication

MultiPaper servers do **not** send each other chunk content, entity updates, or
player packets through the master. Once the master has introduced two servers,
they open **direct peer-to-peer TCP connections** to each other and exchange
bulk data over them. This keeps the master off the hot path.

The P2P system is implemented in `patches/server/0009-Add-peer-to-peer-connection.patch`
and extended across many later patches (`0012`, `0014`, `0016`, `0024`, `0027`,
`0030`, `0130`, ...). The package is `puregero.multipaper.externalserverprotocol`.

---

## The handshake hack

MultiPaper's cleverest trick: **it doesn't open a separate TCP port for P2P
traffic.** Instead, servers connect to each other on the *ordinary Minecraft
server port* and **hijack the Minecraft handshake packet** to identify
themselves as a MultiPaper peer.

### The connection flow

1. Master tells server A about server B via `ServerStartedMessage(host, port)`.
2. Server A opens a normal TCP connection to `host:port`.
3. Server A sends the standard `ClientIntentionPacket` (Minecraft handshake),
   but with the **hostname field set to `<original address>\0<secret>`**
   (`0009:185-197`). The secret is the cluster-wide `SECRET` UUID that the
   master handed out via `SetSecretMessage`.
4. Server B's `ServerHandshakePacketListenerImpl.handleIntention`
   (`0009:84-97`) detects the secret suffix, **yanks the `Connection` object
   out of Minecraft's pending-connection list**, and wraps it in an
   `ExternalServerConnection`.
5. Server B replies with a Minecraft handshake "success" so the vanilla state
   machine advances, then immediately swaps out the Netty pipeline.

This trick means:

- **No extra port.** Only the Minecraft port needs to be reachable.
- **No firewall reconfiguration** when adding servers.
- The same TCP listener does double duty (Minecraft clients + P2P peers).

The downside is that the wire format on these connections is **not** Minecraft
protocol — it's MultiPaper's own.

---

## Pipeline swap

Once a connection is recognised as a peer link, the vanilla Minecraft Netty
handlers (decoder/encryptor/compressor/etc.) are stripped and replaced with
MultiPaper's own pipeline (`ExternalServerConnection.setupPipeline`,
`0009:200-227`):

```
socket
  │
  ├── (stripped: Minecraft decoder/encoder/compressor/encryptor)
  │
  ├── MessageLengthEncoder          (VarInt framing, same lib as master)
  ├── ExternalServerPacketDecoder   (decodes ExternalServerPacket subclasses)
  ├── MessageLengthDecoder
  ├── ExternalServerPacketEncoder
  │
  ├── ZlibCompressionEncoder        (optional, gated by compression-threshold)
  ├── ZlibCompressionDecoder
  │
  ├── PacketConsolidationHandler    (batches writes for consolidation-delay ms)
  │
  └── ExternalServerPacketHandler   (dispatches to packet.handle(ctx))
```

Crucially, the channel is **re-registered onto MultiPaper's own Netty event
loop** (`0009:218-226`) instead of Minecraft's. This means P2P traffic runs on
a separate thread pool from the Minecraft tick loop — a flood of P2P packets
can't stall ticking.

Compression defaults to **zlib** via Velocity's native compressor when the
payload exceeds `peer-connection.compression-threshold` bytes (default 1024).
Compression is disabled automatically on loopback connections (server ↔
itself).

---

## Identity and duplicate connections

Two servers can end up with multiple connections to each other (retry races,
partial failures, etc.). The `HelloPacket` (`0009:644-665`) carries a
`connectionId = System.nanoTime()` so each side can identify which connection
is which. If a duplicate appears, both sides agree on which one to keep (the
lower nanoTime wins) and close the other.

`ExternalServer` (`0007:57`) represents the *other* server:

```java
public class ExternalServer {
    String name;
    double tps;
    long tickTime;
    ExternalServerConnection connection;  // may be null if not yet connected
    boolean isMyself();                   // convenience for broadcast filters
}
```

`MultiPaper.broadcastPacketToExternalServers(world, cx, cz, packet)`
(`0009:313-322`) is the main broadcast primitive — it iterates over
`MultiPaper.getExternalServers()` and skips servers that aren't subscribed to
the relevant chunk.

---

## Packet catalogue

Every P2P message is a subclass of `ExternalServerPacket`
(`puregero.multipaper.externalserverprotocol.ExternalServerPacket`) and is
registered in `ExternalServerPacketSerializer` (the id↔class map). Major
packets (created across patches `0009`, `0012`, `0014`, `0016`, `0024`,
`0027`, `0030`, `0130`):

### Connection setup
| Packet | Purpose |
|---|---|
| `HelloPacket` | Identity + `connectionId`. |
| `SetCompressionPacket` | Negotiate compression threshold. |

### Chunk content (peer fetches)
| Packet | Purpose |
|---|---|
| `RequestChunkPacket` | "Please send me chunk (cx, cz) of `world`." (`0016:2061`) |
| `SendChunkPacket` | Reply with chunk bytes + heightmap + light. (`0016:2213`) |
| `SendTickListPacket` | Transfer scheduled-tick list on ownership change. (`0016:2341`) |
| `SendUpdatePacket` | Wraps any NMS `Packet<?>` to be replayed on subscribers (block, light, entity, etc.). |

### Players
| Packet | Purpose |
|---|---|
| `PlayerCreatePacket` / `PlayerRemovePacket` | A foreign player just appeared / disappeared. |
| `PlayerChangeDimensionPacket` | Foreign player changed dimension. |
| `PlayerChangeGamemodePacket` | Foreign player's gamemode changed. |
| `PlayerRespawnPacket` | Foreign player respawned. |
| `PlayerActionPacket` | Forwards a `Serverbound*` action packet (e.g. movement) to the player's owning server. |
| `PlayerInventoryUpdatePacket` | Slot-level inventory update. |
| `SendPacketPacket` | "Here is a `Clientbound*` packet to forward to your real player." (used by `ExternalPlayer`) |

### Entities
| Packet | Purpose |
|---|---|
| `EntityUpdatePacket` | Entity delta (position, rotation, velocity, metadata). |
| `EntityUpdateNBTPacket` | Entity NBT (e.g. full resync). |
| `EntityUpdateWithDependenciesPacket` | Entity + its passengers / vehicle. |
| `EntityRemovePacket` | Entity despawned. |
| `RequestEntityPacket` / `RequestEntitiesPacket` | "Send me entity X." |
| `SendEntitiesPacket` | Reply with entity data. |

### Containers / vehicles
| Packet | Purpose |
|---|---|
| `AddItemToContainerPacket` / `PullItemFromContainerPacket` | Cross-server hopper / dispenser interactions (`0033`). |
| `AddItemToEntityContainerPacket` | e.g. minecart with chest. |
| `PistonMoveBlockStartPacket` / `PistonMoveBlockEndPacket` | Cross-server piston moves (`0130`). |
| `SubscribeToWorldPacket` | Express interest in a world's entity stream. |

---

## How packets are routed

The typical broadcast pattern in `MultiPaperChunkHandler` is:

```java
// Server is the owner; a block changed.
for (String subscriber : chunkHolder.externalSubscribers) {
    ExternalServer server = MultiPaper.getExternalServer(subscriber);
    if (server != null && server.getConnection() != null) {
        server.getConnection().send(new SendUpdatePacket(packet));
    }
}
```

`PacketConsolidationHandler` (`0009:710-775`) batches outbound writes for
`consolidation-delay` ms (default 2 ms when compression is on) so the zlib
compressor has a bigger payload to work with. Set this to 0 to disable
batching (lower latency, more bandwidth).

---

## Reliability and ordering

MultiPaper's P2P protocol is **TCP-based**, so within a single
connection, packets arrive in order. There is no protocol-level retry beyond
TCP's own retransmission — if a connection dies, in-flight state is
considered lost and reconciliation happens at a higher level:

- Chunk subscriptions are recovered from the master
  (`ChunkSubscribersSyncMessage`).
- Entity state is recovered via `RequestEntityPacket` / `SendEntitiesPacket`.
- Player state is the responsibility of the player's home server.

The 20 s retry on `requestChunk` (`0016:1365-1374`) handles the case where a
peer hasn't yet loaded the chunk it was supposed to have.

---

## Where P2P is **not** used

P2P carries **cache content and broadcasts**. It does **not** carry:

- Chunk **reads** from disk — those go through the master (because the master
  is the canonical store).
- Chunk **writes** — also through the master.
- Chunk **ownership** decisions — through the master.
- File sync — through the master (`UploadFile` / `DownloadFile`).
- KV store — through the master (`CallDataStorage`).
- Player login — through the master (`PlayerConnect` / `PlayerDisconnect`).

The master is the **control plane**; P2P is the **data plane for live state**.

---

## Observability

The `/servers` command (`patches/server/0013`) lists every server in the
cluster with its TPS, tick duration, and player count. `/slist` (`0068`) shows
every online player and which server they're on. `/mpdebug` (`0017`) renders
an in-world overlay of chunk ownership. `/mpmap` (`0056`) shows a map of
nearby chunks. See [13 — Plugin Development](./13-plugin-development.md) for
the user-facing API.

---

## Next

Continue to [07 — Entity & Player Synchronization](./07-entity-player-sync.md).
