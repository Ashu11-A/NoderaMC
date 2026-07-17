# MultiPaper — Architecture Documentation

> **Version documented:** MultiPaper 2.12.3, targeting **Minecraft 1.20.1** (Purpur fork, `purpurRef = f6fd5f6`).

MultiPaper is a **Purpur** server fork that lets a single Minecraft world be
served by **multiple cooperating servers**, coordinated by an external
**MultiPaper-Master** process. Each server caches only the chunks that its
players need; the cluster works like a **content-distribution network (CDN) for
a Minecraft world**: chunks are owned, ticked, and synced peer-to-peer between
servers, while the master is the canonical store for world files and the
arbiter for *who ticks what*.

This directory contains a comprehensive breakdown of MultiPaper's architecture,
source layout, protocol, and operational behaviour. It is intended as context
for future contributors, plugin authors, and operators.

---

## At a glance

| Aspect | Value |
|---|---|
| Upstream | [Purpur 1.20.1](https://github.com/PurpurMC/Purpur) (a Paper/Spigot fork) |
| Build framework | [Paperweight](https://github.com/PaperMC/paperweight) patcher |
| Languages | Java 17 (server fork), Java (Master, MIT-licensed) |
| Networking | Netty 4.1 (master ↔ server) + a hijacked Minecraft handshake (peer-to-peer) |
| Default master port | `35353` |
| Patch count | 9 API patches + 150 server patches (plus `removed/` + `todo/`) |
| License | GPLv3 (fork) / MIT (Master) |

---

## Documentation index

| # | File | Topic |
|---|---|---|
| 01 | [Overview & Concepts](./01-overview.md) | The CDN-like clustering model, key terms, topology. |
| 02 | [Build System & Project Layout](./02-build-system.md) | Paperweight, `applyPatches`, subprojects, dev workflow. |
| 03 | [The MultiPaper-Master Module](./03-master-server.md) | Standalone coordinator process, Netty server, file store. |
| 04 | [Master Messaging Protocol](./04-messaging-protocol.md) | Wire format, message catalogue, data streams, framing. |
| 05 | [Chunk Synchronization & Ownership](./05-chunk-sync.md) | The heart of MultiPaper: chunk reads, locking, ticking ownership, subscriptions. |
| 06 | [Peer-to-Peer Server Communication](./06-peer-to-peer.md) | How servers dial each other through a hijacked Minecraft handshake, P2P packets, compression. |
| 07 | [Entity & Player Synchronization](./07-entity-player-sync.md) | `ExternalPlayer`, cross-server entity ticking, inventories, vehicles. |
| 08 | [Redstone Safety: Atomic Ownership Takeover](./08-redstone-safety.md) | `MultiPaperExternalBlocksHandler`, chunk-group migration, `managedBlock`. |
| 09 | [File & Data Synchronization](./09-file-sync.md) | `MultiPaperFileSyncer`, file sync rules, data storage KV. |
| 10 | [Proxy & Load Balancing](./10-proxy-load-balancing.md) | Built-in NIO proxy, BungeeCord/Velocity plugins, load algorithm. |
| 11 | [Configuration Reference (`multipaper.yml`)](./11-configuration.md) | All keys, defaults, `-D` overrides, master CLI. |
| 12 | [Key Classes & File Reference](./12-key-classes.md) | Cheat-sheet of every important class with `path:line` references. |
| 13 | [Plugin Development Guide](./13-plugin-development.md) | Writing multi-server plugins, the MultiPaper API, MultiLib. |

---

## How to read these docs

- Start with **[01 — Overview](./01-overview.md)** for the conceptual model.
- Read **[05 — Chunk Sync](./05-chunk-sync.md)** and **[06 — P2P](./06-peer-to-peer.md)** to understand the runtime.
- Operators should consult **[11 — Configuration](./11-configuration.md)**.
- Plugin authors should read **[13 — Plugin Development](./13-plugin-development.md)**.
- Use **[12 — Key Classes](./12-key-classes.md)** as a lookup table when reading source.

---

## Source layout reminder

The repo does **not** contain the generated `MultiPaper-API/` and
`MultiPaper-Server/` directories — those are produced by `./gradlew applyPatches`
from Purpur + the contents of `patches/`. The actual game-side Java code lives
inside the `.patch` files (each patch hunk's `+++ b/...` lines name the
resulting file path, e.g. `src/main/java/puregero/multipaper/MultiPaper.java`).
The two pre-existing real Gradle subprojects are:

- `MultiPaper-Master/` — the standalone coordinator (`puregero.multipaper.server`).
- `MultiPaper-MasterMessagingProtocol/` — the shared wire-protocol library.

See [02 — Build System](./02-build-system.md) for the full build flow.
