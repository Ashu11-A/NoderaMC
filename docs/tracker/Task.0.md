# Tracker — Category Charter

<!-- AI-AGENT-INSTRUCTION: The tracker is DISCOVERY INFRASTRUCTURE, NEVER AUTHORITY. No endpoint here
     may become load-bearing for correctness: peers verify everything by hash and signature and treat
     every answer as a hint. The service holds no signing keys — it verifies only. If a change would
     let the tracker's answer affect world state, it is wrong. Keep the task index in agreement with
     ../ROADMAP.md §2. -->

**Category:** `tracker` · **Status:** 🚧 IN PROGRESS (5 of 6 tasks completed) ·
**Last audit:** 2026-07-28

---

## 1. What this category is

Always-on world and peer discovery that **does not die with any player**. An operator runs the
`nodera-tracker` binary on a public host; peers **announce** signed records (identity, roles, world
genesis hash, routes, held manifests, reliability) and **query** per-world swarms. The multiplayer
GUI lists worlds — with player and chunk counts, reliability, red/gray health, and the 24-hour
retention countdown — **even when every seeder of a world is offline**.

That last property is the reason the service exists as a separate binary at all. An embedded tracker
inside a Java peer could only list what its host peer could still see; when the host went away, so
did the world list.

## 2. Architecture

```
   peer ──announce(signed)──►┌──────────────────────────┐
   peer ──announce(signed)──►│  nodera-tracker (Rust)   │
                             │  registry: genesisHash → │
   peer ──query(world)──────►│    Swarm{PeerRecord…}    │──► TrackerResponse
                             │  TTL sweep · quotas      │    counts · health
                             │  sampling + seeder floor │    retention countdown
                             └──────────────────────────┘
             u32-length-framed TCP and UDP, decoded by nodera-codec
```

- **The swarm identifier is the world's genesis hash** — the content-addressed analogue of a torrent
  `info_hash`. World display names are directory metadata keyed by that hash; the genesis manifest
  itself stays name-free and frozen.
- **Identity, not IP, keys a record.** Routes come from the signed record; the observed source address
  is appended only as a low-priority hint, so a peer cannot be relocated by whoever last connected on
  its behalf.
- **The ack paces announce traffic** (`nextAnnounceAfterSeconds`), so the service controls its own
  load rather than hoping clients behave.
- **Zero persistence by default.** Peers re-announce within one interval after a restart, so the
  registry rebuilds itself; an optional persistence directory only keeps display-name metadata.

## 3. Dependencies

**Depends on:** [`network/Task.1.md`](../network/Task.1.md) — the canonical encoding and framing this
service speaks, and the `nodera-codec` crate that decodes it.

**Consumed by:** [`minecraft/Task.4.md`](../minecraft/Task.4.md) (the multiplayer world list),
[`worker/Task.3.md`](../peer/Task.3.md) (the announce loop that keeps a host's world listed with
Minecraft closed), [`app/Task.2.md`](../app/Task.2.md) (the dashboard's trackers panel).

## 4. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | The `nodera-tracker` service binary | ✅ COMPLETED |
| [2](Task.2.md) | The Java client: announce family + `TrackerClient` | ✅ COMPLETED |
| [3](Task.3.md) | Operations hardening | 🚧 IN PROGRESS |
| [4](Task.4.md) | Service telemetry | ✅ COMPLETED |
| [5](Task.5.md) | The service directory and the scoring plane | ✅ COMPLETED |
| [6](Task.6.md) | A published image, and a tracker anyone can run | ✅ COMPLETED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

**Reference architecture:** [`REFERENCE.md`](REFERENCE.md) — announce lifecycle, swarm model,
expiry, sampling, and abuse defences. The task files bind that reference to Nodera.

## 5. Files

| Path | Contents |
|---|---|
| `tracker/src/` | The service: `main`, `config`, `registry`, `announce`, `query`, `health`, `deletion`, `limits`, `wire`, `services` (the service directory), `service` (dispatch), `telemetry` (Task 4); `bin/nodera-query.rs` (Task 6 diagnostic) |
| `library/rust/nodera-service/src/` | Shared with the rendezvous: `identity`, `directory`, `drain`, `lifecycle`, `update` |
| `library/java/transport/.../protocol/discovery/` | `TrackerAnnounce`, `TrackerAnnounceAck` and the query family |
| `peer/.../discovery/TrackerClient.java` | Announce loop, query API, scheme-aware endpoints |
| `peer/src/test/.../TrackerServiceIT.java` | Drives the **real** release binary from Java |

Package architecture: [`tracker/README.md`](../../tracker/README.md).

## 6. Conventions specific to this category

- **The peers' native canonical encoding *is* the protocol.** No HTTP, no second serialization. A new
  message means a Java record, a tag, a golden fixture, and the Rust mirror — in one commit.
- **The service holds no signing keys over anything but its own address.** It verifies
  (`ed25519-dalek`) peer and service records, and signs exactly one kind of value: its own
  `ServiceRecord`, so a drain notice cannot be forged and a peer can pin the trackers that worked
  ([`Task.5.md`](Task.5.md) §Design). Nothing it signs is authority over world state. Self-registration
  only, for peers and services alike.
- **Never let the tracker become authority.** Degradation paths are tested: tracker down ⇒ discovery
  degrades, mesh and state unaffected.
- **Abuse controls are structural**, not configuration afterthoughts: per-IP and per-identity quotas,
  record-size caps, bounded world and peer counts, and **no full-scrape endpoint**.
- **The UDP surface is bounded against reflection amplification** and answers silently rather than
  truncating when a response would exceed the bound; the Java client falls back to TCP.
