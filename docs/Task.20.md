# Task 20 — Tracker, Peer Directory, Archive Inventory, Multi-Bootstrap (Phase 6)

**Phase:** 6 · **Depends on:** Task 19 (pieces/manifest), Task 10 (gateway/libp2p plan) ·
**Modules:** `peer-runtime/discovery` (new); `protocol` membership/content additions; a tracker
role on the bootstrap/full-archive peer.

## Goal

Give the network a **BitTorrent-style tracker**: a service that, for a given world (genesis hash),
returns the list of peers currently online in it plus the seeders holding each manifest. Add a
durable **peer directory** and **archive inventory** (who holds what), and the **≥3 multi-bootstrap
mechanisms** so a brand-new client can discover the network even when the original host is offline.
This is the control plane the multiplayer UI (Task 26) reads to list worlds, player counts, chunk
counts, and reliability.

## Context

- Today discovery is one bootstrap `host:port` string (`NoderaSessionPayload`) + full-mesh gossip
  (`MembershipUpdate`). There is no tracker, no per-world peer list, no "who holds manifest X", no
  cached peer store, no invitation, no DNS/LAN seed (LIMITATIONS L-34; Plan §6 Phase 6 §"at least
  three bootstrap mechanisms"). `PeerSyncFlow` (Task 9) already says the multi-seeder fetch is the
  transport's job — this task is that job.
- The tracker is a **role**, not a central server: any `FULL_ARCHIVE`/`BOOTSTRAP`-capable peer runs
  it; the dedicated server is the preferred but not only tracker (same "preferred but not only"
  discipline as the bootstrap peer). Tracker responses are **self-verifying**: a peer list can be
  lied about by a malicious tracker, but state cannot — manifests/checkpoints verify by hash
  (Task 19 / Task 9).
- Membership gossip (Task 9/10 `PeerRuntime`) stays the liveness spine; the tracker indexes it.

## Folder structure (additions)

```
peer-runtime/src/main/java/dev/nodera/peer/discovery/
├── package-info.java
├── TrackerService.java           # per-world peer+seeder index; answers TrackerQuery
├── PeerDirectory.java            # durable NodeId → last-known route/caps/lastSeen (extends the Task 9
│                                 #   skeleton; CachedPeerStore backs it)
├── ArchiveInventory.java         # manifestRoot → (holder NodeId → pieceBitmap); fed by
│                                 #   ContentAvailability / InventoryAdvertisement (Task 19)
├── BootstrapClient.java          # extends Task 9's single-bootstrap dial: configured list →
│                                 #   CachedPeerStore redial → InvitationCodec
├── InvitationCodec.java          # signed base64 blob: networkId, genesis hash, addresses
└── CachedPeerStore.java          # on-disk peer addresses (client game-dir / server world-dir;
                                  #   extends the Task 9 skeleton)

protocol/src/main/java/dev/nodera/protocol/discovery/
├── TrackerQuery.java             # (Bytes genesisHash) → TrackerResponse
├── TrackerResponse.java          # (Bytes genesisHash, String worldName, List<PeerEntry> peers,
│                                 #   long worldPlayerCount, long storedChunks, int reliabilityBps,
│                                 #   WorldHealth health, long retentionDeadlineEpochMillis /*0=none*/)
│                                 #   reliability = quantised basis points 0..10000 — CanonicalWriter
│                                 #   has no float/double on purpose (determinism discipline)
└── InventoryAdvertisement.java   # periodic gossip: my holdings as (manifestRoot, pieceBitmap) pairs
                                  #   (the Task 19 ContentAvailability shape) → ArchiveInventory

core/identity additions:
└── NodeCapabilities gains Set<PeerRole> roles (WORLD_SEEDER/FULL_ARCHIVE/...); frozen ordinals
   already exist in PeerRole — put them on the wire now (L-29 closes partially).
```

## Class relationships

```
PeerRuntime membership gossip (Task 9/10)
        │ feeds
        ▼
PeerDirectory ◄── CachedPeerStore (disk)
        │
TrackerService ──► TrackerResponse {peers, playerCount, storedChunks, reliability, health}
        ▲                          │ read by multiplayer UI (Task 26)
        │                          ▼
TrackerQuery (genesisHash)    ArchiveInventory ◄── InventoryAdvertisement ◄── each peer
        │                                                          (manifestRoots I hold)
        ▼
BootstrapClient: configured-list → CachedPeerStore → InvitationCodec (≥3 mechanisms)
```

## Implementation details — `peer-runtime/discovery`

- **TrackerService** indexes membership by **world** (genesis hash), because a network may host
  several torrent-hosted worlds. A `TrackerQuery(genesisHash)` returns the online peers in that
  world plus, per manifest currently held by anyone, the holder set (from `ArchiveInventory`).
  Aggregate fields for the UI: `worldPlayerCount` (online peers in-world), `storedChunks` (count of
  distinct pieces with ≥1 holder, computed from the inventory's piece bitmaps), `reliabilityBps`
  (quantised mean holder reliability, Task 22), `WorldHealth` (`HEALTHY` / `DEGRADED`-red /
  `DEAD`-gray → rules the UI red/gray colouring in Task 26). A world's display **name** is
  directory metadata registered by the host (create-world screen Task 26, or server config), keyed
  by genesis hash — `GenesisManifest` stays name-free and frozen. `WorldHealth` is a frozen-ordinal
  enum in `core`, so `protocol` (wire) and the `diagnostics` view (Task 26) can both reference it
  without a layering violation; its Palette rows are HEALTHY→GREEN, DEGRADED→RED, DEAD→GRAY —
  deliberately distinct from the session `Health` enum, whose DEGRADED maps to YELLOW (Task 18).
- **Health → 24h drop (rule end).** `WorldHealth.DEAD` is gated by Task 22's coordinated 24h
  countdown: a world flips DEAD (gray) only after zero seeders for the retention window. The tracker
  exposes the countdown so the UI can show it.
- **ArchiveInventory** is the swarm's "who has what". Each peer periodically gossips an
  `InventoryAdvertisement` (its manifest roots, bounded by config; rarest-first weighting in Task 21
  reads this). Inventory is bounded (Caffeine by manifest count) so a remote peer cannot grow it
  unbounded (Plan §3.13).
- **Multi-bootstrap (≥3 mechanisms).** `BootstrapClient` tries, in order: (1) configured bootstrap
  list (`nodera-client.toml`, multiple entries — new config), (2) `CachedPeerStore` redial (addresses
  remembered from prior sessions), (3) `InvitationCodec` — a signed base64 blob a friend pastes
  (signed by any known peer; contains networkId, genesis hash, addresses). LAN multicast + DNS seeds
  stay backlog (note in LIMITATIONS, not this task). A `BootstrapResponse` is trusted only after the
  genesis hash + certificate chain check (state self-verifies; peers don't).
- **Persistent identity (L-28).** This task persists `NodeIdentity` (`server-identity.bin` / client
  game-dir) so a returning peer keeps its `NodeId` — required for a stable seeder identity and for
  the directory to mean anything across restarts.
- **Roles on the wire.** `NodeCapabilities.roles: Set<PeerRole>` is serialised now (the ordinals are
  frozen); the tracker privileges `FULL_ARCHIVE`/`WORLD_SEEDER` holders as seeders. (Closes part of
  L-29; full capability-weighted gateway election stays Task 9.)

## Implementation details — mod side (`neoforge-mod`)

- `NoderaPeerService` constructs `TrackerService` on `FULL_ARCHIVE`/`BOOTSTRAP` peers; the dedicated
  server enables it by default (`tracker.enabled`, default true). A client queries the tracker (via
  any reachable tracker peer) to populate the multiplayer list (Task 26).
- `PublicBootstrapEndpoint` (moved here from Task 10): binds the Task 9 `BootstrapService` on the
  dedicated server's public address; any `FULL_ARCHIVE`-capable community peer can enable it (config
  flag) — "preferred but not only" bootstrap.
- Config (`nodera-client.toml`): `bootstrap.list = []` (multi-entry), `tracker.preferred = ""`.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-34** — no tracker / archive-inventory / multi-bootstrap today. Exit: `TrackerQuery/Response`
  round-trip returns peers+seeders+counts+health; `ArchiveInventory` fed by advertisements;
  `BootstrapClient` joins via each of the 3 mechanisms in a headless IT (original bootstrap offline).
- **L-28** (ephemeral identity) → RETIRED here (`NodeIdentity` persisted + reloaded; returning peer
  keeps `NodeId`).
- **L-29** partial — roles + holdings now on the wire; full capability-weighted gateway election
  remains Task 9.
- NAT traversal (L-27) stays Task 10 (libp2p); the tracker works LAN/port-forward/VPN today.

## Acceptance criteria

1. `TrackerIT` (headless): a 5-peer mesh across 2 worlds; `TrackerQuery(genesisA)` returns exactly
   world-A's peers + their held manifests; counts and reliability match the live state.
2. `MultiBootstrapIT`: original bootstrap offline; a new client joins via (a) a configured alternate,
   (b) `CachedPeerStore`, (c) a pasted `InvitationCodec` blob — each reaches the mesh.
3. `NodeIdentity` persisted across a peer restart; the peer re-joins with the same `NodeId`.
4. `ArchiveInventory` bounded: a peer advertising 100k manifests cannot grow the receiver's
   inventory past its configured bound.
5. `./gradlew check` green; L-34 → RETIRING, L-28 → RETIRED; README/Tested updated.
