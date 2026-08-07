# Network Task 7 — Reliability, Storage Quotas, 24-Hour Retention

<!-- AI-AGENT-INSTRUCTION: The reliability score is PURE INTEGER math (basis points) so it is
     bit-identical across JVMs — it feeds placement and gateway election, which must agree on every
     peer. Never introduce a float here. The retention deadline is the ONE legitimate wall clock in
     this category; it sits outside consensus state and must stay there. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS — the quota and retention halves are complete; the reliability half is
open again (L-36 reopened 2026-08-06). Live wiring → [minecraft 2](../minecraft/Task.2.md)
**Category:** network · **Owns:** L-36 (retired 2026-07-23, REOPENED 2026-08-06 — its exit test was
deleted, and it had never covered production) · **Last audit:** 2026-08-06
**Depends on:** [network 6](Task.6.md)
**Consumed by:** [network 10](Task.10.md), [tracker 1](../tracker/Task.1.md), [frontend 3](../frontend/Task.3.md)

---

## Goal

Three coupled capabilities: a **multi-factor reliability score** that drives placement, gateway
election, and low-TPS handoff; **client storage quotas** so a player's disk cannot fill without
bound; and a coordinated **24-hour retention-before-drop** so a world with no seeders is not deleted
the moment everyone logs off.

## Status detail

**The reliability half is open again as of 2026-08-06 and deliverables 1 and 2 are withdrawn.**
`ReliabilityScorer`, `ReliabilityFactors`, `ReliabilityConfig` and `ReliabilityScorerTest` were
deleted in commit `24e6f0e` (issue #210). They were correct, tested code that no production entry
point could reach: a whole-tree search at `24e6f0e^` found no caller outside the classes themselves
and their one test, and no reflective, service-loader or entry-point construction either. So nothing
observable was lost — but the multi-factor score never ran in a shipped build, which means the task's
first two deliverables described a capability the product never had. What reliability actually is in
this build is `ReliabilityLedger`: one scalar per node, moved by a single proposal-outcome EMA, which
is exactly the state L-36 was written to describe. See the decision note below and the reopened row in
[`LIMITATIONS.md`](LIMITATIONS.md) §B before picking this up.

The other two halves stand and are unaffected. `BoundedClientWorldStore` honours a byte budget, evicts oldest-cold
first, **never** evicts an assigned region's current state, and signals repair on eviction.
`RetentionPolicy` runs a coordinated earliest-deadline 24-hour countdown on zero-seeder worlds,
cancels on seeder return, and drops at expiry — and the countdown is network-visible, riding every
tracker announce.

## Dependencies

- [network 6](Task.6.md) — placement, which consumes the score.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `ReliabilityScorer` + `ReliabilityFactors` + `ReliabilityConfig` | ⬜ **WITHDRAWN 2026-08-06** — built, tested, never reachable from production; deleted in `24e6f0e`. Re-doing it as it was would not close L-36 |
| 2 | Slash-to-zero on equivocation; offline decay toward the target | ⬜ **WITHDRAWN 2026-08-06** — same deletion. The surviving `ReliabilityLedger.slash` also has no production caller, because the only class that called it (`CommitteeSession`) went in the same commit |
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

- ~~`library/java/engine/src/main/java/dev/nodera/coordinator/{ReliabilityScorer,ReliabilityFactors,ReliabilityConfig}.java`~~
  — **deleted 2026-08-06** (`24e6f0e`, issue #210). What reliability is in this build lives in
  `library/java/engine/src/main/java/dev/nodera/coordinator/ReliabilityLedger.java`
- `library/java/storage/src/main/java/dev/nodera/storage/client/{BoundedClientWorldStore,StorageQuotaManager,ArchiveEvictionPolicy}.java`
- `peer/src/main/java/dev/nodera/peer/archival/RetentionPolicy.java`

## Testing

- ~~`ReliabilityScorerTest`~~ — deleted 2026-08-06 with the classes it covered. It pinned same-inputs
  determinism, slash-to-zero, the assignment-floor gate, no single-factor dominance across weight
  configurations, and offline decay converging to the target without going below it — all of it over
  a scorer no production code constructed. `ReliabilityLedgerTest` and `CommitteeScoringTest` are
  what remain, and they cover the single EMA rather than a blend.
- `BoundedClientWorldStoreTest` — oldest-cold-first within budget; pinned assigned-region current
  state never evicted (a loud error when only pinned remains, or on an oversize blob); eviction
  signals repair including off-monitor callbacks.
- `RetentionIT` — zero-seeder countdown start, cancel-on-return clearing the surfaced deadline,
  earliest-deadline coordination, drop-at-expiry, fresh retention life on re-seed.

## Acceptance criteria

1. ⬜ The score is bit-identical across JVMs and no factor dominates. **Unticked 2026-08-06:** this
   was ticked on `ReliabilityScorerTest`, which drove a class production never constructed. There is
   only one factor in the shipped build, so "no factor dominates" is not a statement it can make.
2. ⬜ Equivocation slashes to zero; being offline decays to the neutral target. **Unticked
   2026-08-06:** `ReliabilityLedger.slash` still implements the rule, but its only caller was deleted
   with the rest of the central-coordinator design, and offline decay went with `ReliabilityScorer`.
3. ✅ The client store honours its budget and never evicts assigned-region current state.
4. ✅ Eviction signals repair.
5. ✅ Retention is coordinated, network-visible, cancellable, and drops at expiry.
6. ⏳ Live: the scorer fed by real connectivity and seed-share data. Now blocked on criteria 1 and 2
   rather than on a live run — there is no scorer to feed.

## Limitations

**L-37** and **L-38** are RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

- **L-36** — retired 2026-07-23, **REOPENED 2026-08-06**. `ReliabilityScorer`, `ReliabilityFactors`,
  `ReliabilityConfig` and `ReliabilityScorerTest` were all deleted on that date (`24e6f0e`, issue
  #210) as unreachable from production, and a check on the commit before the deletion shows they had
  never been reachable: no Java file outside the four named them, and there was no reflective,
  `ServiceLoader`, annotation-driven or entry-point construction either. **The deletion removed no
  behaviour and this reopening does not ask for it to be undone** — that is the whole difference
  between this row and a regression. What it does ask is that the register stop claiming a
  multi-factor score the product has never had. The current row and the exit test it now waits on are
  in [`LIMITATIONS.md`](LIMITATIONS.md) §B; the original evidence is preserved under "Withdrawn
  retirement" in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

  **Decision note for whoever takes this up.** Do not begin by restoring the deleted files. The
  scorer was never the missing part; the missing parts are a producer for four of its five signals
  and a consumer for its output, and without both, restoring it recreates exactly the situation that
  cost this row its retirement. Two concrete hooks already exist and are worth reading first:
  `NodeCapabilities.reliability` is a wire field that travels on every identity and is weighted by
  `GatewayElection` — and no production code ever sets it, so every peer advertises the same
  constant; and `ReliabilityLedger` is genuinely wired on its write side (`WorkerValidationService`
  folds committed-round agreement in through `CommitteeScoring.apply`, `CommitteeFailover` writes the
  lag penalty, and `DurableCoordinatorState` persists it across restarts) while nothing production
  reads it to decide anything — `eligibleForAssignment` has no non-test caller and the one production
  read of `score` formats a log line. Closing the gap between those two is the shape of the work: the
  ledger is where the correctness signal already lands, and gateway election is where a blended score
  would first be consulted. The basis-point integer arithmetic from the deleted scorer should be
  reused rather than reinvented — the reason the score must be integral, that placement and election
  have to agree on every peer, has not changed. **L-56** (two connection settings that cannot be
honoured as specified) is owned by [`frontend/LIMITATIONS.md`](../frontend/LIMITATIONS.md).
