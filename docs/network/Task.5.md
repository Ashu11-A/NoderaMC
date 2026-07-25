# Network Task 5 — Discovery, Multi-Bootstrap, Persistent Identity

<!-- AI-AGENT-INSTRUCTION: Every cache in this task is LRU-BOUNDED and keyed by remote input, which
     makes bounding a security property rather than a tidiness one. Never add an unbounded map here.
     The tracker SERVING role does not live in this task — it is a separate Rust service; this task
     owns only the client side and the peer-local caches. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (mod-side feed → [minecraft 4](../minecraft/Task.4.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 2](Task.2.md)
**Consumed by:** [tracker 2](../tracker/Task.2.md), [network 6](Task.6.md), [worker 1](../worker/Task.1.md)

---

## Goal

A peer must be able to join the mesh even when the peer that introduced it last time is gone, and it
must keep its identity across restarts so it rejoins the committees it belonged to. This task owns
the peer-local directory and inventory caches, the three independent bootstrap mechanisms, and
persistent identity.

## Status detail

Complete. `PeerDirectory` and piece-level `ArchiveInventory` are both LRU-bounded;
`BootstrapClient` joins through three independent mechanisms (a configured list, a `CachedPeerStore`
redial, and a signed `InvitationCodec`), proven with the original bootstrap **offline**;
`PersistentIdentityStore` keeps a returning peer's `NodeId` through an owner-only atomic
temp-and-move write.

The embedded Java tracker *serving* role that originally shipped here has been **deleted** — that
role now lives in the standalone Rust service ([`tracker/Task.1.md`](../tracker/Task.1.md)) — while
the directory and inventory remain as peer-local caches. Tracker and rendezvous endpoints are
additional bootstrap sources (mechanisms 4 and 5).

`PeerDiscoveryService` closes the loop: it sweeps every tracker and rendezvous per world and
introduces this node to each routable peer via `PeerRuntime.announceTo`. Before it existed, session
membership came exclusively from one bootstrap route plus its gossip, so a peer whose bootstrap was
unreachable never meshed no matter how many members were listed.

## Dependencies

- [network 2](Task.2.md) — the runtime this discovery feeds.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `PeerDirectory` — LRU-bounded peer cache | ✅ |
| 2 | `ArchiveInventory` — piece-level, LRU-bounded, gossip-ingested | ✅ |
| 3 | `BootstrapClient` — three independent mechanisms | ✅ |
| 4 | `InvitationCodec` — signed invitations | ✅ |
| 5 | `CachedPeerStore` — atomic persisted redial list | ✅ |
| 6 | `PersistentIdentityStore` — a returning peer keeps its `NodeId` | ✅ |
| 7 | `PeerDiscoveryService` — tracker + rendezvous sweeps → `announceTo` | ✅ |
| 8 | Mod-side tracker feed and multiplayer list | → [minecraft 4](../minecraft/Task.4.md) |

## Design

**Three mechanisms because one is a single point of failure.** A configured list is fine until the
listed host moves; a cached redial is fine until every cached peer is offline; an invitation is fine
until it expires. Any one of them alone reintroduces the centralisation the project exists to remove,
so all three are independent and any one suffices — proven by joining with the original bootstrap
offline.

**Identity persists or committees churn.** If a peer got a new `NodeId` every launch, it would leave
and rejoin every committee it belonged to on every restart, and reliability scores would never
accumulate. The store is owner-only and written atomically, because a half-written identity file is
worse than a missing one.

**Bounded caches are a security property.** Both caches are keyed by data other peers supply. An
unbounded map keyed by remote input is a memory-exhaustion vector, so both are LRU-bounded by
construction rather than by policy.

**Merge, never arbitrate.** Discovery results from several sources are merged. Choosing between
sources would make the discovery plane an authority, which the trust model forbids.

## Files

- `java/peer/src/main/java/dev/nodera/peer/discovery/{PeerDirectory,ArchiveInventory,BootstrapClient,InvitationCodec,CachedPeerStore,PersistentIdentityStore,PeerDiscoveryService,TrackerClient}.java`

## Testing

- `MultiBootstrapIT` — joins via each of the three mechanisms with the original bootstrap offline.
- `PersistentIdentityStoreTest` — a reload yields the same identity.
- LRU-bound tests on both caches; oversize and flood rejection.
- `TrackerEndpointTest`, `TrackerClientUdpTest` — scheme-aware endpoints with a real UDP path and
  TCP fallback.

## Acceptance criteria

1. ✅ A peer joins through any one of three independent mechanisms.
2. ✅ A returning peer keeps its `NodeId`.
3. ✅ Both caches are bounded and survive flood input.
4. ✅ Discovery merges sources and never arbitrates.
5. ⏳ The mod's multiplayer list renders live rows from this data
   ([minecraft 4](../minecraft/Task.4.md)).

## Limitations

None open. **L-28** (ephemeral identity) and **L-34** (no tracker/inventory/multi-bootstrap) are
RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
