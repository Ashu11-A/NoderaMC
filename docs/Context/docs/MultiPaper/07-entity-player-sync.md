# 07 — Entity & Player Synchronization

With chunk sync understood (see [05](./05-chunk-sync.md)), entity and player
sync are mostly applications of the same ideas:

- Entities are owned by the server that owns the chunk they live in.
- Other servers see those entities as **read-only ghosts**: they replay the
  owner's packets but don't tick the entities themselves.
- Players physically connect to exactly one server; everywhere else they are
  **`ExternalPlayer` phantoms** that tunnel their packets to the real server.

Source lives primarily in `patches/server/0027-Sync-entities.patch` and
`patches/server/0012-Add-ExternalPlayer.patch` / `0014-Forward-ExternalPlayer-packets.patch`.

---

## Entities

### Tick decision

`MultiPaperEntitiesHandler.tickEntity(entity)` (`0027:1140-1172`) is the
gatekeeper for entity ticking. It returns `true` only if **this server** is
allowed to tick the entity:

1. The entity's chunk must be **locally owned** (we are the owner).
2. The entity must not be driven by a foreign player — determined by
   `getControllingPassenger(entity)` (`0027:1103-1133`), which walks up the
   vehicle chain looking for a `ServerPlayer`. If that player is an
   `ExternalPlayer`, the entity is **not** ticked locally; the player's home
   server ticks it.

Special case: firework rockets fired by an external player as an elytra boost
are considered "controlled" by that player and travel with them.

When the function returns `false`, the entity is still in our world but we
just skip ticking it. The owner's `EntityUpdatePacket`s will arrive over P2P
and replay the movement against our local copy.

### Entity packets

| Packet | Purpose |
|---|---|
| `EntityUpdatePacket` | Position / rotation / velocity / metadata deltas. |
| `EntityUpdateNBTPacket` | Full NBT snapshot of an entity (used on first sight or periodic resync). |
| `EntityUpdateWithDependenciesPacket` | Entity update + its passengers and vehicle in one packet (atomic for stacked entities). |
| `EntityRemovePacket` | Entity despawned. |
| `RequestEntityPacket` / `RequestEntitiesPacket` | "I don't know about entity X, please send it." |
| `SendEntitiesPacket` | Reply with one or more entity NBT snapshots. |

### Entity IDs

Vanilla Minecraft assigns entity IDs per-server. MultiPaper needs IDs to be
**globally consistent** (so packets referencing entity ID `12345` mean the
same entity on every server). The master hands out ID **blocks** of 4096 each
(`RequestEntityIdBlockHandler.java:14`, size configurable via
`-Dentityid.block.size`); a server requests a new block when it runs low.

The current high-water mark is persisted in `lastblock.txt` on the master so
it survives restarts.

`sync-entity-ids` in `multipaper.yml` toggles the whole subsystem on or off.
It is needed for plugins that fake packets containing entity IDs.

### Persistent player and vehicle IDs

Two optional conveniences for cross-server teleport plugins:

- `persistent-player-entity-ids` (`patches/server/0141`) — a player has the
  same entity ID on every server (normally an `ExternalPlayer` would get a
  fresh ID wherever it's instantiated).
- `persistent-vehicle-entity-ids-seconds` (default 15) — a player's vehicle
  retains its entity ID for that many seconds after the player disconnects,
  so they can be seamlessly re-seated when reconnecting to a different
  server.

### Entity subscription

The entity equivalent of chunk subscribers is handled by
`EntitiesSubscriptionManager` on the master and by entity-chunk-level
subscriptions on the server. When an entity moves between chunks, the
subscription follows it. The owner broadcasts entity updates only to the
currently-subscribed set.

---

## Players

### The local player

A real `ServerPlayer` whose TCP connection terminates at **this** server. This
server ticks the player, owns their inventory, handles their chat, etc.

### The external player

For every other player in the cluster, this server creates an
**`ExternalPlayer`** (`patches/server/0012:419-507`). `ExternalPlayer extends
ServerPlayer`, so vanilla NMS code treats it like any other player — but it
isn't real. Its `connection` is an inner `ExternalPlayerConnection`
(`0014:19-39`) whose `send(...)` method intercepts every `Clientbound*` packet
that would normally go to the client and instead:

1. Skips packets that should remain local (abilities, teams, commands,
   scoreboards — these would duplicate or contradict what the player's home
   server is sending).
2. Wraps the rest in a `SendPacketPacket` and ships it over P2P to the
   player's home server, which forwards it to the actual client.

This is how a player on server A can see, attack, or trade with players on
servers B and C: from server B's perspective, the player on server A *is* an
`ExternalPlayer`, and packets flow A → B → player-connection-on-A.

### Player lifecycle

| Packet | Sent when |
|---|---|
| `PlayerCreatePacket` | A player joined the cluster (sent to every other server). |
| `PlayerRemovePacket` | A player left. |
| `PlayerChangeDimensionPacket` | The player changed dimensions. |
| `PlayerChangeGamemodePacket` | The player's gamemode changed. |
| `PlayerRespawnPacket` | The player respawned. |
| `PlayerActionPacket` | The player did something — forward the `Serverbound*` packet to their home server. |

The master tracks who is on which server via `PlayerConnectMessage` /
`PlayerDisconnectMessage` (`handlers/PlayerConnectHandler.java`,
`handlers/PlayerDisconnectHandler.java`). This is also how MultiPaper prevents
the same player from being connected to two servers at once.

### Inventory and containers

`MultiPaperInventoryHandler` (`patches/server/0024:443`) handles the gnarly
case of containers and inventories that span servers:

- A player on server A opens a chest that lives on server B.
- Server A shows the player a *phantom* inventory; the actual inventory state
  is owned by server B.
- Slot changes are propagated via `PlayerInventoryUpdatePacket` and
  `AddItemToContainerPacket` / `PullItemFromContainerPacket` /
  `AddItemToEntityContainerPacket` (`0033`).

This means hopper networks and item pipelines that cross chunk boundaries also
cross server boundaries, and MultiPaper transparently forwards the item
movements between owners.

### Teleporting between servers

Teleporting a player to a location that lives on another server is handled at
the proxy level (BungeeCord or Velocity, or MultiPaper's built-in proxy). The
master is told the player is disconnecting from one server and connecting to
another. Because all MultiPaper servers share the same world, the destination
server just loads the chunks and the player pops in.

`persistent-player-entity-ids` makes the cross-server teleport appear
seamless to plugins (the entity ID doesn't change).

### Movement throttling

`reduce-player-position-updates-in-unloaded-chunks`
(`MULTIPAPER_YAML.md:41-46`) throttles how often an external player's
position is broadcast to servers that don't have the player's chunk loaded.
This avoids flooding every server in the cluster with every other player's
movement packets. The trade-off is that `Player.getLocation()` for a foreign
player may return a slightly stale location on those servers.

---

## Death, damage, combat

Damage to an entity is processed by the **owner** of that entity. If a player
on server A attacks an entity that lives on server B, A sends a
`PlayerActionPacket` containing the `ServerboundInteractPacket` over P2P to B,
which applies the damage and broadcasts the result. This avoids the
distributed-state problem of two servers both thinking they own an entity's
health pool.

For PvP between two players on different servers, both players' health is
owned by their respective home servers; damage events cross the wire as action
packets.

---

## Scoreboards, advancements, statistics

- **Scoreboards** (`sync-scoreboards`, `patches/server/0084`) — synced across
  servers. Scoreboards are global state in vanilla; MultiPaper replicates them
  so teams and objectives appear consistent everywhere.
- **Advancements** (`patches/server/0043`) — synced in real time, so an
  advancement earned on one server appears on every server immediately.
- **Statistics** (`patches/server/0047`) — synced to the master and back.
- **Experience** (`patches/server/0048`) — XP is owned by the player's home
  server and replicated.
- **Hunger, potion effects, gamemode** (`0038`, `0039`) — same model.

---

## Player data files

Player `.dat` files live on the master under `<world>/playerdata/<uuid>.dat`
and are read/written via `ReadPlayerMessage` / `WritePlayerMessage`
(`handlers/ReadPlayerHandler.java`, `handlers/WritePlayerHandler.java`). This
ensures that a player logging into any server in the cluster gets the same
state.

---

## Next

Continue to [08 — Redstone Safety](./08-redstone-safety.md).
