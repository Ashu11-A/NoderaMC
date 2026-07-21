# Task 21 — Archive Placement, Replication, Repair (Phase 6): Redundant Backups, ≥25%-Seed, <5%-Per-Peer

**Phase:** 6 · **Depends on:** Task 19 (pieces), Task 20 (inventory/tracker) ·
**Modules:** `peer-runtime/archival` (new).

## Goal

Guarantee world data is **redundantly spread** across the peer network so that no single peer (the
host included) is a single point of loss. A deterministic rendezvous placement policy assigns each
piece/manifest to a holder set sized to the network; the **≥25%-seed floor** (each peer seeds at
least 25% of the network, dynamically adjusted as players join) and the **<5%-per-peer cap** (no one
holds more than 5% when the network is large enough) are enforced. An audit + repair service
re-creates missing replicas after peer loss. This realises the user's spec rules 0 (host = physical
backup), 1 (≥25% seed), and 3 (redundant backups, <5% per peer).

## Context

- Plan §3.12 / Task.9 already specify replication factors (current snapshot ×5, recent log ×4,
  compacted ×3, checkpoints/genesis everywhere) via an `ArchivePlacementPolicy` — but neither the
  policy nor any "who holds what" enforcement exists (LIMITATIONS L-35). Task 19 gives addressable
  pieces; Task 20 gives the inventory; this task decides **who should hold what** and fixes drift.
- Deterministic placement = rendezvous hashing over `(manifestRoot, nodeId)` (reuse
  `core/crypto/StableHash` + the `RendezvousPlacementPolicy` pattern from Task 6), so every peer
  independently computes the same expected holder set and converges without a central allocator.
- The host is `FULL_ARCHIVE` (holds everything) — the physical backup (rule 0). Client peers are
  `PARTIAL_ARCHIVE` and receive a bounded, deterministically-chosen shard each.

## Folder structure (additions)

```
peer-runtime/src/main/java/dev/nodera/peer/archival/
├── package-info.java
├── ArchivePlacementPolicy.java    # interface: expectedHolders(manifestRoot, eligible, networkSize)
├── RendezvousArchivePolicy.java   # impl: replication factor by object class; rendezvous order
├── ReplicationFactors.java        # snapshot=5, recentLog=4, compacted=3, checkpoint/genesis=all
├── SeedFloorPolicy.java           # dynamic floor min(25%, R/N) + cap max(5%, 2·R/N) per peer (see below)
├── ArchiveManager.java            # decides what THIS peer should hold; triggers fetch/evict to match
├── ArchiveAuditTask.java          # periodic: expected vs inventory (Task 20) → repair list
└── ArchiveRepairService.java      # for each missing replica: assign next-ranked peer → pull by hash → ack

protocol/src/main/java/dev/nodera/protocol/content/
├── ArchiveReplicaAssignment.java  # repair wire messages (moved from the Task 9 protocol list):
└── ArchiveReplicaAck.java         #   (manifestRoot, pieceIndexes, assignee) / ack — appended tags
```

## Class relationships

```
ArchivePlacementPolicy.expectedHolders(manifestRoot, eligible, N)  ← RendezvousArchivePolicy
        │                                                              ▲
        ▼                                                              │
ArchiveAuditTask: expected (policy)  vs  actual (ArchiveInventory, Task 20)
        │ missing/over-replicated
        ▼
ArchiveRepairService ──► assign next-ranked peer (ContentRequest, Task 19) ──► verify ──► ack
        │
ArchiveManager (local): reconcile THIS peer's holdings to its assigned shard
        ├─ fetch under-held pieces (downloader, Task 19)
        └─ evict over-held pieces (quota, Task 22) — never evict an assigned-region current piece
```

## Implementation details — `peer-runtime/archival`

- **Replication factor by object class** (`ReplicationFactors`): current snapshot ×5, recent log ×4,
  compacted history ×3, checkpoint/genesis = everyone. These are **floors**, not ceilings.
- **≥25%-seed floor (rule 1, dynamic).** With replication factor `R`, sustaining `R` full copies
  needs an average per-peer share of `R/N` of a world's piece-set. `SeedFloorPolicy` sets each
  peer's floor = `min(25%, R/N)` (plus whatever its assigned regions require): at small `N` the
  floor is the spec's 25% (it equals `R/N` exactly at `N = 20`, `R = 5`), and it decays as `R/N`
  once players join — the spec's "dynamically adjusted" clause. A peer below its floor is a
  "free-rider" and is deprioritised by the tracker (Task 22 reliability penalty).
- **<5%-per-peer cap (rule 3).** A flat 5% cap is arithmetically impossible until `N ≥ R/5%`: the
  capped shares must cover `R` full copies, not one (at ×5 that is 100 peers — the old "5% × 20 =
  100%" justification only funded a single copy). The cap is therefore dynamic too:
  `max(5%, 2·R/N)` (headroom factor 2 over the average, config) — ~50% at `N = 20`, tightening to
  the spec's 5% once `N ≥ 2R/5% = 200`. It binds `PARTIAL_ARCHIVE` peers beyond their own assigned
  regions; the `FULL_ARCHIVE` host is exempt by design (rule 0: it IS the redundant physical
  backup). Excess redistributes via the repair service. The floor `min(25%, R/N)` is always below
  the cap `max(5%, 2·R/N)`, so the two policies can never conflict; the tracker reports the
  currently effective floor/cap pair.
- **Deterministic holder set.** `RendezvousArchivePolicy.expectedHolders` ranks eligible peers by
  `StableHash(manifestRoot, nodeId)` and takes the top-R (R = factor) as the **expected** holders;
  every peer computes the same set. `ArchiveAuditTask` (every `archive.auditIntervalTicks`) diffs
  expected vs the live `ArchiveInventory` (Task 20) and emits a repair list: for a missing replica,
  assign the next-ranked peer, which pulls the piece by hash from any current holder (Task 19),
  verifies, and acks.
- **Repair storms bounded** (MultiPaper lesson): `repair.maxConcurrent`, `repair.bandwidthBudget`
  (config) rate-limit re-replication after a mass-disconnect.
- **Host as backup (rule 0).** The `FULL_ARCHIVE` host is always in every expected-holder set; it
  continuously receives pieces (Task 24 stream) so it is the physical fallback. It does **not** get
  extra consensus vote (Invariant 2). The host does not count toward `R`: the expected set is the
  host **plus** the top-`R` ranked `PARTIAL_ARCHIVE` peers, so losing the host still leaves `R`
  distributed replicas — no single point of loss, host included.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-35** — no replication placement / repair today. Exit: `RendezvousArchivePolicy` hits
  snap×5/log×4; ≥25%-seed floor enforced; <5%-per-peer cap enforced when N≥20; `ArchiveRepairIT`
  re-creates missing replicas after a peer kill with no data loss.
- **L-27** (NAT) still gates cross-internet placement; LAN/VPN works now.

## Acceptance criteria

1. `PlacementPropertyTest`: `expectedHolders` is a pure function of `(manifestRoot, eligibleSet, N)`;
   two peers compute the identical set; holds R = factor distinct peers.
2. `ArchiveRepairIT` (headless, 6 peers): kill 2 holders of a ×5 manifest → audit detects → repair
   re-replicates to 5 within `repair.maxConcurrent` budget; no piece drops below factor; data
   integrity (hash) preserved end-to-end.
3. `SeedFloorIT`: a peer below its floor (`min(25%, R/N)`) is flagged + reliability-penalised
   (Task 22 wiring); a peer above the cap (`max(5%, 2·R/N)`) triggers redistribution; both
   thresholds exercised at `N = 20` and `N = 200`; the `FULL_ARCHIVE` host is never flagged.
4. Host (`FULL_ARCHIVE`) holds 100% and is in every expected set, with no extra vote.
5. `./gradlew check` green; L-35 → RETIRING; README/Tested updated.
