# Network Task 7 — Reliability, Storage Quotas, 24-Hour Retention

<!-- AI-AGENT-INSTRUCTION: The reliability score is PURE INTEGER math (basis points) so it is
     bit-identical across JVMs — it feeds placement and gateway election, which must agree on every
     peer. Never introduce a float here. The retention deadline is the ONE legitimate wall clock in
     this category; it sits outside consensus state and must stay there. Keep this header's status
     accurate. -->

**Status:** ✅ COMPLETED (live wiring → [minecraft 2](../minecraft/Task.2.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 6](Task.6.md)
**Consumed by:** [network 10](Task.10.md), [tracker 1](../tracker/Task.1.md), [app 3](../app/Task.3.md)

---

## Goal

Three coupled capabilities: a **multi-factor reliability score** that drives placement, gateway
election, and low-TPS handoff; **client storage quotas** so a player's disk cannot fill without
bound; and a coordinated **24-hour retention-before-drop** so a world with no seeders is not deleted
the moment everyone logs off.

## Status detail

Complete. `ReliabilityScorer` blends correctness, connectivity, uptime, availability, and
worlds-seeded in pure basis-point integer math, with slash-to-zero on equivocation and offline decay
toward the documented target. `BoundedClientWorldStore` honours a byte budget, evicts oldest-cold
first, **never** evicts an assigned region's current state, and signals repair on eviction.
`RetentionPolicy` runs a coordinated earliest-deadline 24-hour countdown on zero-seeder worlds,
cancels on seeder return, and drops at expiry — and the countdown is network-visible, riding every
tracker announce.

## Dependencies

- [network 6](Task.6.md) — placement, which consumes the score.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `ReliabilityScorer` + `ReliabilityFactors` + `ReliabilityConfig` | ✅ |
| 2 | Slash-to-zero on equivocation; offline decay toward the target | ✅ |
| 3 | `BoundedClientWorldStore` + `StorageQuotaManager` + `ArchiveEvictionPolicy` | ✅ |
| 4 | `RetentionPolicy` — coordinated earliest-deadline countdown | ✅ |
| 5 | Countdown surfaced in every tracker announce | ✅ |
| 6 | Live wiring: scorer fed by real link/heartbeat/seed-share data | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Pure integer math, because the score is consensus-adjacent.** Placement and gateway election both
consume the score, and both must produce identical results on every peer. A float blend would produce
platform-dependent last bits, which would produce different placements, which would produce phantom
repairs. Basis points remove the question.

**No single factor may dominate.** A peer with perfect uptime and terrible correctness must not
outrank a mostly-correct peer, and vice versa. The weighting is tested across configurations to prove
no factor alone determines the ranking.

**Slash to zero, decay to the middle.** Equivocation is proof of misbehaviour, so the score goes to
zero. Being offline is *not* proof of anything, so it decays toward the neutral target rather than to
zero — a peer that goes on holiday should not return indistinguishable from an attacker.

**The eviction policy has one hard rule.** It may evict anything except the **current state of an
assigned region**, because that is the one thing whose loss the committee cannot repair from
elsewhere. When only pinned data remains, the store raises a loud quota error rather than quietly
evicting the thing that matters.

**Eviction signals repair.** Dropping a replica silently reduces redundancy invisibly. The eviction
callback tells the repair lane, so the network restores the factor elsewhere.

**Coordinated, earliest deadline.** Retention uses the earliest proposed deadline across peers, so no
peer can unilaterally extend a world's life; return of a seeder cancels the countdown and clears the
surfaced deadline.

## Files

- `library/java/engine/src/main/java/dev/nodera/coordinator/{ReliabilityScorer,ReliabilityFactors,ReliabilityConfig}.java`
- `library/java/storage/src/main/java/dev/nodera/storage/client/{BoundedClientWorldStore,StorageQuotaManager,ArchiveEvictionPolicy}.java`
- `peer/src/main/java/dev/nodera/peer/archival/RetentionPolicy.java`

## Testing

- `ReliabilityScorerTest` — same-inputs determinism, slash-to-zero, the assignment-floor gate, no
  single-factor dominance across weight configurations, and offline decay converging to the target
  without going below it.
- `BoundedClientWorldStoreTest` — oldest-cold-first within budget; pinned assigned-region current
  state never evicted (a loud error when only pinned remains, or on an oversize blob); eviction
  signals repair including off-monitor callbacks.
- `RetentionIT` — zero-seeder countdown start, cancel-on-return clearing the surfaced deadline,
  earliest-deadline coordination, drop-at-expiry, fresh retention life on re-seed.

## Acceptance criteria

1. ✅ The score is bit-identical across JVMs and no factor dominates.
2. ✅ Equivocation slashes to zero; being offline decays to the neutral target.
3. ✅ The client store honours its budget and never evicts assigned-region current state.
4. ✅ Eviction signals repair.
5. ✅ Retention is coordinated, network-visible, cancellable, and drops at expiry.
6. ⏳ Live: the scorer fed by real connectivity and seed-share data.

## Limitations

None open. **L-36**, **L-37**, and **L-38** are RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). **L-56** (two connection settings that cannot be
honoured as specified) is owned by [`app/LIMITATIONS.md`](../app/LIMITATIONS.md).
