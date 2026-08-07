# Network Task 5 — Discovery, Multi-Bootstrap, Persistent Identity

<!-- AI-AGENT-INSTRUCTION: Any cache this task grows is keyed by remote input, which makes bounding a
     security property rather than a tidiness one. Never add an unbounded map here. The tracker
     SERVING role does not live in this task — it is a separate Rust service; this task owns only the
     client side. The peer-local caches this task once owned were deleted on 2026-08-06; do not
     restore their names here without restoring the classes. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (mod-side feed → [minecraft 4](../minecraft/Task.4.md)) — **with one
property no longer proven:** the second discovery resolver and its multi-bootstrap test were deleted
on 2026-08-06, see Status detail
**Category:** network · **Owns:** — · **Last audit:** 2026-08-06
**Depends on:** [network 2](Task.2.md)
**Consumed by:** [tracker 2](../tracker/Task.2.md), [network 6](Task.6.md), [worker 1](../peer/Task.1.md)

---

## Goal

A peer must be able to join the mesh even when the peer that introduced it last time is gone, and it
must keep its identity across restarts so it rejoins the committees it belonged to. This task owns
the client side of discovery — the tracker and rendezvous sweeps a joining peer runs — and
persistent identity. It once also owned a set of peer-local caches and a second bootstrap resolver;
those were deleted, and what took the property over is below.

## Status detail

What ships. `PersistentIdentityStore` keeps a returning peer's `NodeId` through an owner-only atomic
temp-and-move write. `PeerDiscoveryService` is the discovery resolver production runs: it sweeps
every tracker and rendezvous per world and introduces this node to each routable peer via
`PeerRuntime.announceTo`, with `TrackerClient` and `RendezvousDirectory` underneath it. Before that
service existed, session membership came exclusively from one bootstrap route plus its gossip, so a
peer whose bootstrap was unreachable never meshed no matter how many members were listed.

What was deleted, and by what it was superseded. The second discovery resolver this task originally
shipped is gone: `BootstrapClient`, `InvitationCodec` and `ArchiveInventory` in commit `0b02aa5`,
`PeerDirectory` and `CachedPeerStore` in commit `f0df119` (both Plan 11 round 2, issue #210). They
were a parallel resolver with no production call site, superseded by `PeerDiscoveryService` plus
`TrackerClient`; the piece-level seeder index the inventory held did not vanish but moved, into the
Rust tracker's `registry.rs`, fed by the `ManifestHolding` list on each `TrackerAnnounce` and
answered as `ManifestSeeders`, with `cargo test -p nodera-tracker` as its coverage.

What that cost, and it is a real gap rather than bookkeeping. `MultiBootstrapIT` went with those
classes, and it was the only test in the tree asserting that **no single host is a gatekeeper** — a
new client reaching the mesh with the original bootstrap **offline**, by each of three independent
routes. The deletion is defensible and **L-34** stays retired, because a peer no longer learns the
mesh through single-bootstrap gossip alone; the assertion is what is missing. The exit test owed is
a multi-tracker join written against `PeerDiscoveryService`, showing a new peer reaches the mesh
with its first tracker down. This account is the same one kept in
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) L-34 and [`TESTING.md`](TESTING.md) §2.1, which are
the documents to update if it changes.

The embedded Java tracker *serving* role that originally shipped here was also deleted earlier —
that role now lives in the standalone Rust service ([`tracker/Task.1.md`](../tracker/Task.1.md)).
Tracker and rendezvous endpoints are the bootstrap sources that remain.

## Dependencies

- [network 2](Task.2.md) — the runtime this discovery feeds.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `PeerDirectory` — LRU-bounded peer cache | ❌ superseded — deleted `f0df119`; no production path consulted it, and discovery is `PeerDiscoveryService` + `TrackerClient` + `RendezvousDirectory` |
| 2 | `ArchiveInventory` — piece-level, LRU-bounded, gossip-ingested | ❌ superseded — deleted `0b02aa5`; the seeder index moved to the Rust tracker's `registry.rs`, answered as `ManifestSeeders` |
| 3 | `BootstrapClient` — three independent mechanisms | ❌ superseded — deleted `0b02aa5`; the resolver's job is `PeerDiscoveryService` + `TrackerClient`, but **the three-independent-mechanisms property is no longer proven by anything in the tree**: `MultiBootstrapIT` was its only assertion and went with it (L-34, [`TESTING.md`](TESTING.md) §2.1) |
| 4 | `InvitationCodec` — signed invitations | ❌ superseded — deleted `0b02aa5`; nothing but `BootstrapClient` ever read a pasted invitation |
| 5 | `CachedPeerStore` — atomic persisted redial list | ❌ superseded — deleted `f0df119` with `BootstrapClient`, its only reader |
| 6 | `PersistentIdentityStore` — a returning peer keeps its `NodeId` | ✅ |
| 7 | `PeerDiscoveryService` — tracker + rendezvous sweeps → `announceTo` | ✅ |
| 8 | Mod-side tracker feed and multiplayer list | → [minecraft 4](../minecraft/Task.4.md) |

## Design

**More than one route in, because one is a single point of failure.** A configured list is fine until
the listed host moves; a cached redial is fine until every cached peer is offline; an invitation is
fine until it expires. Any one of them alone reintroduces the centralisation the project exists to
remove. The reasoning stands; the implementation changed. The three mechanisms named above were a
resolver of their own and were deleted on 2026-08-06, and the routes that remain are the trackers
and rendezvous services `PeerDiscoveryService` sweeps — plural by configuration, with
`PinnedTrackerEndpointsTest` proving a companion config push cannot remove the operator's tracker.
Plural is not the same as proven: nothing currently asserts that a *new* client joins when its first
tracker is down, which is the gap L-34 records.

**Identity persists or committees churn.** If a peer got a new `NodeId` every launch, it would leave
and rejoin every committee it belonged to on every restart, and reliability scores would never
accumulate. The store is owner-only and written atomically, because a half-written identity file is
worse than a missing one.

**Bounded caches are a security property.** The two caches this rule was written for —
`PeerDirectory` and `ArchiveInventory` — are deleted, but the rule outlives them and applies to
anything that replaces them. A cache in this lane is keyed by data other peers supply, and an
unbounded map keyed by remote input is a memory-exhaustion vector, so bounding belongs in the
construction rather than in a policy somebody remembers to apply.

**Merge, never arbitrate.** Discovery results from several sources are merged. Choosing between
sources would make the discovery plane an authority, which the trust model forbids.

## Files

- `peer/src/main/java/dev/nodera/peer/discovery/{PersistentIdentityStore,PeerDiscoveryService,TrackerClient,TrackerLookup,RendezvousDirectory}.java`
- ~~`peer/src/main/java/dev/nodera/peer/discovery/{ArchiveInventory,BootstrapClient,InvitationCodec}.java`~~ — deleted 2026-08-06 (`0b02aa5`)
- ~~`peer/src/main/java/dev/nodera/peer/discovery/{PeerDirectory,CachedPeerStore}.java`~~ — deleted 2026-08-06 (`f0df119`)

## Testing

- `PersistentIdentityStoreTest` — a reload yields the same identity.
- `TrackerEndpointTest`, `TrackerClientUdpTest` — scheme-aware endpoints with a real UDP path and
  TCP fallback.
- `PinnedTrackerEndpointsTest` — a companion config push may add trackers but may not remove the
  operator's.
- ~~`MultiBootstrapIT`~~ — **deleted 2026-08-06 with the classes it covered** (`0b02aa5`). It joined
  via each of the three mechanisms with the original bootstrap offline, and it was the only test of
  that property; nothing replaces it. See L-34 in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) and
  [`TESTING.md`](TESTING.md) §2.1 for the exit test that is owed.
- ~~LRU-bound tests on both caches; oversize and flood rejection~~ — deleted with `PeerDirectory` and
  `ArchiveInventory`; there is no cache left in this lane to bound.

## Acceptance criteria

1. ⏳ A peer joins through any one of several independent routes. **Unproven since 2026-08-06:** the
   three mechanisms were deleted with their resolver and `MultiBootstrapIT` went with them, so no
   test asserts that a new client joins when its first route is down. Trackers and rendezvous
   services are still plural, which is a configuration, not an assertion.
2. ✅ A returning peer keeps its `NodeId`.
3. ❌ Both caches are bounded and survive flood input — **superseded:** both caches are deleted. The
   rule stands for anything that replaces them (see Design).
4. ✅ Discovery merges sources and never arbitrates.
5. ⏳ The mod's multiplayer list renders live rows from this data
   ([minecraft 4](../minecraft/Task.4.md)).

## Limitations

None open. **L-28** (ephemeral identity) and **L-34** (no tracker/inventory/multi-bootstrap) are
RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). L-34's evidence was rewritten on
2026-08-06: the row stays retired, but the multi-bootstrap half of it now names an exit test that
has to be written rather than one that exists.
