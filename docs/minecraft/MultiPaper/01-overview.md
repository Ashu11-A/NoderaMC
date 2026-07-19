# 01 — Overview & Concepts

## What MultiPaper is

MultiPaper is a **horizontal-scaling fork of Purpur/Paper/Spigot**. Instead of
running a single Minecraft server process for your world, you run **N copies of
the same MultiPaper server** behind a load balancer, and an external process
called the **MultiPaper-Master** coordinates them so they all share **one
logical world**. The user-facing goal is to scale player capacity while
**keeping every vanilla mechanic intact** (large render distance, mob spawning,
redstone, mob farms, etc.).

The mental model that the project itself uses is:

> **MultiPaper works like a CDN.** Each server caches the chunks its players
> need, the servers keep each others' caches in sync, and the servers work
> together to ensure every chunk is ticked by exactly one of them.

---

## Topology

```
                       Players
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ MP Srv A │ │ MP Srv B │ │ MP Srv C │   ← N Purpur forks
        │ (subset  │ │ (subset  │ │ (subset  │     of the same world,
        │  of the  │ │  of the  │ │  of the  │     same multipaper.jar
        │  chunks) │ │  chunks) │ │  chunks) │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             │  P2P (peer-to-peer)     │
             └────────────┼────────────┘
                          │
                          │ master protocol (Netty :35353)
                          ▼
                 ┌──────────────────┐
                 │  MultiPaper-     │   ← Standalone Java/Netty process.
                 │  Master          │     Owns the on-disk world files,
                 │                  │     arbitrates chunk ownership,
                 │                  │     optionally hosts the built-in
                 │                  │     proxy/load-balancer.
                 └──────────────────┘
                          │
                          ▼
                    <world>/region/*.mca
                    <world>/level.dat
                    <world>/playerdata/*
                    synced-server-files/...
```

There are **two** communication planes:

1. **Master ↔ server** (TCP, custom Netty binary protocol on port `35353`).
   Used for chunk reads/writes, chunk ownership negotiation, file sync,
   player tracking, data storage KV, and metadata.
2. **Server ↔ server** (peer-to-peer). Used for the bulk of chunk content,
   block/entity/light updates, tick-list transfers, player packets, and
   entity synchronization. This is the actual hot path once a cluster is
   running.

See [04 — Messaging Protocol](./04-messaging-protocol.md) and
[06 — Peer-to-Peer](./06-peer-to-peer.md) for the details.

---

## Key concepts

### Chunk ownership

Every loaded chunk in the cluster has **exactly one owner** at any time: the
single server that is allowed to **tick** it (advance its entities, scheduled
ticks, redstone, etc.). Other servers may have the chunk **loaded for read**
(subscribed) but they must not tick it.

The master is the arbiter of ownership. It maintains, for every chunk, an
**ordered list of servers** that want to own it — **index 0 is the owner**.
This list lives in `ChunkSubscriptionManager` on the master
(`MultiPaper-Master/.../ChunkSubscriptionManager.java:13`). Ownership is
**first-come-first-served**: the first server to send a `LockChunkMessage` for
a chunk becomes its owner; subsequent requesters become *subscribers*.

### Chunk subscribers

A subscriber is a server that has a chunk loaded in memory but is **not** the
owner. When the owner mutates a block, block-entity, light, or entity inside
the chunk, it broadcasts the change packets only to its **subscribers**. The
subscribers replay those packets against their own local copy so their players
see the same world. This is what keeps all caches coherent without a central
server re-broadcasting.

Subscriber bookkeeping is two-sided:

- Master side: `ChunkSubscriptionManager` and `EntitiesSubscriptionManager`.
- Server side: `NewChunkHolder.externalSubscribers` / `externalOwner` fields,
  kept in sync via `SetChunkOwnerMessage`, `AddChunkSubscriberMessage`,
  `RemoveChunkSubscriberMessage`, `ChunkSubscribersSyncMessage`.

### External vs. local

Inside a MultiPaper server's code, the predicate `MultiPaper.isChunkLocal(...)`
(`patches/server/0016` at `:1284`) means *my server ticks this chunk*.
`isChunkExternal(...)` means *another server ticks it but I have it loaded*.
Almost every tick-path NMS edit checks one of these to decide whether to do
work locally or defer it.

The Bukkit API surfaces this to plugins as `Chunk.isLocalChunk()`,
`Entity.isInLocalChunk()`, `Location.isChunkLocal()`, `Block.isInLocalChunk()`,
`Player.isLocalPlayer()` — see [13 — Plugin Development](./13-plugin-development.md).

### The "external player"

Each player physically connects to **exactly one** MultiPaper server (their
"local" server). On every *other* server, that player exists as an
**`ExternalPlayer`** — a phantom `ServerPlayer` whose outgoing packets are
tunnelled to the player's real server over the P2P link (`SendPacketPacket`).
This is how a player on server A can be visible to, attack, or trade with
players on servers B and C without ever connecting there.

### Atomic chunk-group ownership (redstone safety)

Vanilla redstone, pistons, and flow often cross chunk borders. If two halves
of a contraption are owned by two different servers, the simulation would
diverge. MultiPaper detects, in `MultiPaperExternalBlocksHandler`
(`patches/server/0032:111`), when a tick scheduled in an external chunk needs
to fire, and asks the master to **atomically migrate the whole 3×3 group of
ticking chunks** to a single owner. The server can even `managedBlock` (block
its own tick thread) until the master replies, so redstone never crosses
owners mid-tick. See [08 — Redstone Safety](./08-redstone-safety.md).

### File & data sync

MultiPaper does not run a shared filesystem. Instead:

- World data (chunks, level.dat, player data, stats, advancements, JSON files)
  is funnelled through the master, which is the only process that touches the
  on-disk world.
- Plugin/configuration files are synchronised by the `MultiPaperFileSyncer`
  (`patches/server/0088:125`), which watches the filesystem and uploads files
  to the master; other servers download them at startup or in real time,
  depending on config. See [09 — File Sync](./09-file-sync.md).
- A simple key-value store (`CallDataStorage`) is available for plugins to
  share data without a database.

---

## Roles in the cluster

| Component | Role | Notes |
|---|---|---|
| **MultiPaper server** | Runs the Purpur fork (`multipaper.jar`). Hosts a subset of players and ticks a subset of chunks. | One per process. Multiple per cluster. |
| **MultiPaper-Master** (standalone) | Netty server (`multipaper-master.jar <port> [proxyPort]`). Owns world files; arbitrates ownership; broadcasts server health; optionally runs the built-in proxy. | Typically one per cluster, but can be run as a BungeeCord/Velocity plugin instead. |
| **Built-in proxy** | Optional lightweight TCP load balancer. Picks the least-busy server and pipes bytes through. | Run by the master process when a `proxyPort` is provided. |
| **BungeeCord / Velocity plugin** | Alternative load balancer. The master jar ships both plugin wrappers; they re-route `ServerConnectEvent` to the least-busy server. | Lets you reuse an existing proxy. |

The **master is not in the data hot path** once the cluster is up — chunk
content flows server-to-server. The master is in the **control path**: who owns
what, who is alive, who has the freshest copy of a chunk, where the world is on
disk.

---

## Why "no central ticking"

A critical design point: **only one server ticks any given chunk**. There is no
distributed simulation or consensus in MultiPaper. The cluster is *coordinated*
but each server is still a single-threaded Purpur server locally. When two
servers both have the same chunk loaded:

- The **owner** runs redstone, mob AI, scheduled ticks, entity physics, etc.
- The **subscribers** replay the resulting packet stream to keep their view of
  the world consistent; they do not simulate.

This is what makes MultiPaper a "scale-out" rather than a "scale-up" story:
doubling the player count by adding servers works **only if** chunks can be
partitioned between servers cleanly. Heavily concentrated activity (a single
busy spawn, a single huge redstone machine, a single crowded farm) does not
parallelise — it lives on whichever server owns that chunk.

---

## What MultiPaper is **not**

- **Not a distributed simulation.** Each chunk has one owner; there is no
  voting, no deterministic multi-execution, no consensus protocol.
- **Not transparent to plugins.** Plugins must be written or audited for
  multi-server safety (see [13 — Plugin Development](./13-plugin-development.md)).
- **Not a way to merge separate worlds.** All servers in a cluster must serve
  the **same** world, with the master as its canonical store.
- **Not high-availability on the master.** If the master dies, servers keep
  running off their caches for a while, but no new chunks can be loaded and
  nothing can be persisted. The master is a single point of failure for
  storage and ownership arbitration.

This contrasts sharply with Folia (see `../folia/`) which achieves scaling by
**multi-threading a single process** rather than clustering processes.

---

## Next

Continue to [02 — Build System & Project Layout](./02-build-system.md).
