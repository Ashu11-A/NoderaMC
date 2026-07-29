# Network — Category Charter

<!-- AI-AGENT-INSTRUCTION: This is the CHARTER for the `network` documentation category. It defines
     the component, its architecture, its dependency edges, and the index of its 11 tasks. The
     `PeerTransport` seam described here is sacred: no call site anywhere in the project may know
     which transport carried a message. Keep the task index in agreement with ../ROADMAP.md §2. -->

**Category:** `network` · **Status:** 🚧 IN PROGRESS (11 of 14 tasks completed) ·
**Last audit:** 2026-07-28

---

## 1. What this category is

Everything between the engine and the screen: the frozen **wire protocol** and the **transport
seam**; a `PeerRuntime` on every installation with membership, heartbeats, and deterministic
capability-weighted gateway election and migration; canonical state as a **certified event log plus
checkpoints** (never "the server's `ServerLevel`"); and the **torrent-hosting** feature — a world
becomes a shared, content-addressed, multi-seeder resource with addressable hashed pieces,
deterministic rarest-first swarm fetch, redundant placement with audit and repair, multi-factor
reliability with quotas and retention, per-world ciphertext encryption, crash-safe continuous
streaming, and tick-lag-driven region handoff.

**Seeders store and propagate only.** The active region's committee still re-executes and commits;
the data plane adds no new trust. That distinction is the reason a peer can hold a world's bytes
without being able to alter its state, and it is why encryption at rest (task 8) does not weaken
validation at all.

## 2. Architecture

```
  ┌───────────────────────────────────────────────────────────────┐
  │ wire (task 1): NoderaMessage · MessageCodec (append-only tags) │
  │                ChunkedStreams (zstd) · handshake              │
  └───────────────────────────────┬───────────────────────────────┘
                                  │  PeerTransport seam
        ┌─────────────────────────┼─────────────────────────┐
   SocketPeerTransport   RendezvousPeerTransport     LoopbackTransport
     (LAN / direct)        (NAT reach — rendezvous)       (tests)
                                  │
  ┌───────────────────────────────┴───────────────────────────────┐
  │ PeerRuntime (task 2): membership · gossip · gateway election  │
  ├───────────────────────────────────────────────────────────────┤
  │ storage (task 3) │ torrent plane (tasks 4, 6, 7, 8, 9)        │
  │ event log +      │ pieces · manifests · placement · repair    │
  │ checkpoints +    │ reliability · quotas · retention · crypto  │
  │ content store    │ streaming · emergency flush                │
  ├──────────────────┴────────────────────────────────────────────┤
  │ discovery (task 5) │ tick-lag handoff (10) │ telemetry (11)   │
  └───────────────────────────────────────────────────────────────┘
```

## 3. The torrent-hosting rule catalogue (binding — do not renumber)

The user-facing hosting specification is referenced throughout this category as "rule N":

| Rule | Meaning | Owning task |
|---|---|---|
| 0 | The host is the world's physical backup (`FULL_ARCHIVE` holds everything) | [6](Task.6.md) |
| 1 | Every peer seeds ≥ 25% of the network's data, adjusted as players join | [6](Task.6.md) |
| 2 | Reliability = connectivity + uptime + availability + worlds-seeded, weighted | [7](Task.7.md) |
| 3 | Redundant backups spread across peers; < 5% per peer when the network is large | [4](Task.4.md), [6](Task.6.md) |
| 5 | On Minecraft close or crash, emergency-flush unshared pieces to the network | [9](Task.9.md) (full form: [worker](../worker/Task.0.md)) |
| 6 | An active player continuously streams their chunks to the swarm | [9](Task.9.md) |
| 7 | Mob/entity/redstone exchange over P2P, batched away from 20 tps | engine, riding [4](Task.4.md) |
| 9 | A tick-lag metric governs region-boundary sync; low-TPS peers hand off regions | [10](Task.10.md) |
| 10 | Async download: hash-validate before use, lock-until-arrived, freshness | [4](Task.4.md) |
| — | Per-world encryption password; seeders hold ciphertext | [8](Task.8.md) |
| — | Tracker/server list, health colours, 24 h retention | [5](Task.5.md), [7](Task.7.md), [tracker](../tracker/Task.0.md) |

## 4. Dependencies

**Depends on:** [`engine/Task.1.md`](../engine/Task.1.md) for the types and the canonical encoding;
[`engine/Task.5.md`](../engine/Task.5.md) for the committee whose certificates the storage lane
persists.

**Consumed by:** [`tracker/`](../tracker/Task.0.md) and [`rendezvous/`](../rendezvous/Task.0.md)
(their services speak this wire), [`worker/`](../worker/Task.0.md) (which *is* this peer runtime run
as a process), [`minecraft/`](../minecraft/Task.0.md) (which delivers every live half).

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Wire protocol, transport seam, chunked streams, handshake | ✅ COMPLETED |
| [2](Task.2.md) | Peer runtime: membership, gateway election, migration | 🚧 IN PROGRESS |
| [3](Task.3.md) | Event-sourced + durable storage | ✅ COMPLETED |
| [4](Task.4.md) | Torrent distribution data plane | ✅ COMPLETED |
| [5](Task.5.md) | Discovery, multi-bootstrap, persistent identity | ✅ COMPLETED |
| [6](Task.6.md) | Archive placement, replication, repair | ✅ COMPLETED |
| [7](Task.7.md) | Reliability, storage quotas, 24 h retention | ✅ COMPLETED |
| [8](Task.8.md) | Per-world content encryption | ✅ COMPLETED |
| [9](Task.9.md) | Crash safety + active-player stream | ✅ COMPLETED |
| [10](Task.10.md) | Tick-lag / TPS metric + low-TPS region handoff | ✅ COMPLETED |
| [11](Task.11.md) | Telemetry core | ✅ COMPLETED |
| [12](Task.12.md) | Telemetry emitter core | ✅ COMPLETED |
| [13](Task.13.md) | Measured service selection on the peer | 🚧 IN PROGRESS |
| [14](Task.14.md) | Cross-version wire protocol ([`plans/Plan.7.md`](../plans/Plan.7.md)) | 🚧 IN PROGRESS |
| [15](Task.15.md) | Structural benchmarking + structural code report | ✅ COMPLETED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) ·
network architecture reference: [`REFERENCE.md`](REFERENCE.md).

## 6. Files

| Path | Contents |
|---|---|
| `java/transport/` | `dev.nodera.protocol` (frozen wire), `dev.nodera.transport` (seam), `.socket`, `.rendezvous` |
| `java/storage/` | `dev.nodera.storage` (seam), `.event`, `.rocksdb`, `.client` |
| `java/peer/` | `dev.nodera.peer` (runtime, discovery, archival, committee, control), `dev.nodera.distribution`, `dev.nodera.diagnostics`, `dev.nodera.headless` |
| `rust/nodera-codec/` | Byte-exact canonical-encoding port + Ed25519 verify + tag mirror |
| `fixtures/wire/` | Golden canonical frames — Java emits, Rust re-encodes byte-exactly |

Package architecture: [`java/transport/README.md`](../../java/transport/README.md),
[`java/storage/README.md`](../../java/storage/README.md),
[`java/peer/README.md`](../../java/peer/README.md),
[`rust/nodera-codec/README.md`](../../rust/nodera-codec/README.md).

## 7. Conventions specific to this category

- **The `PeerTransport` seam is sacred.** No call site may know which transport carried a message —
  loopback, socket, rendezvous-relayed, or the in-game NeoForge relay.
- **Frozen wire discipline.** Tags append-only, never renumbered. Body-version bumps only with
  dual-version decoders (`SessionKeepAlive` v1/v2 is the precedent). Every appended tag lands with a
  Java golden fixture and the Rust re-encode test **in the same commit**.
- **Trust model.** Peers verify everything: tracker and rendezvous answers are hints, state verifies
  by hash and signature. A lying service can hide peers; it can never forge state.
- **No wall clocks in anything feeding consensus.** Metrics take injected time. The one legitimate
  wall clock is the retention deadline (task 7), which sits outside consensus state and is documented
  as such.
- **No unbounded map keyed by remote input.** Every cache is LRU-bounded, every payload is
  size-capped before allocation.
