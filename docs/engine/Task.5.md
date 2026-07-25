# Engine Task 5 — Committee Validation (the MVP Gate)

<!-- AI-AGENT-INSTRUCTION: This is the project's MVP gate. Safety beats liveness here: when in
     doubt a region PAUSES, it never forks. Do not add a path that commits without a quorum
     certificate, and do not let a vote be counted for a member that did not sign it. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED (headless; live 3-client run → [minecraft 2](../minecraft/Task.2.md))
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [engine 4](Task.4.md)
**Consumed by:** [engine 6](Task.6.md), [network 10](../network/Task.10.md), [worker 4](../worker/Task.4.md), [minecraft 2](../minecraft/Task.2.md)

---

## Goal

Replace 100% re-execution by one authority with **committee validation**: a primary plus two
validators execute every batch, `ValidationVote`s are collected, a **2-of-3 quorum** on the resulting
state root commits, and only a sampled subset is independently spot-checked. Primary failover happens
under a new epoch. This task ends at the **MVP gate** — the canonical three-client scenario.

## Status detail

Complete headlessly, and **running out of game**: `WorkerValidationService` wires the committee stack
into the always-on worker, so three companion-only nodes with no Minecraft process form a committee
over the real transport, quorum-commit, persist the co-signed certificate on every member, and fail
over to epoch+1 after primary loss ([worker 4](../worker/Task.4.md)).

Proven headlessly by `CommitteeMvpIT` (quorum-commit, then primary failover under a bumped epoch),
`ByzantineWorkerTest` (lying ballots at the session), and `ByzantineMeshIT` (a genuinely adversarial
peer **on the wire**: a raw handler that reads the primary's proposal and answers dishonestly without
ever running the engine).

The live 3-client acceptance on real Minecraft clients is
[`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [engine 4](Task.4.md) — leases, epochs, and the applier the quorum commits through.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `CommitteeMember` / `CommitteeSession` — re-execute and sign your own root | ✅ |
| 2 | `QuorumPolicy` + `VoteCollector` — 2-of-3 on the root, not on the proposer | ✅ |
| 3 | `EquivocationDetector` — two conflicting signed votes ⇒ slash to zero | ✅ |
| 4 | `SpotCheckPolicy` — adaptive sampling by committee reliability | ✅ |
| 5 | `SpotCheckAuditor` | ✅ |
| 6 | `CommitteeFailover` — exactly one epoch bump, stale decisions rejected | ✅ |
| 7 | `VotePersistence` — durable prepare before ACCEPT | ✅ |
| 8 | Live 3-client acceptance | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Members vote on their own re-execution, not on the proposer's claim.** A validator that simply
signed whatever the primary sent would add cost and no safety. Each member runs the engine on the
same inputs and signs the root *it* computed; agreement is then evidence, not courtesy.

**Quorum on the root.** Votes are grouped by the root value. This makes the honest-majority argument
direct: a lying member produces a different root and lands in a minority group, so it cannot reach
quorum no matter how confidently it votes.

**Equivocation is slashing, not a warning.** Signing two different roots for the same
(region, version, epoch) is unforgeable proof of misbehaviour — the two signatures *are* the
evidence. The detector must not depend on wall-clock ordering (an early bug did, and missed the
evicted-voter case).

**Adaptive spot-checking is a cost decision with a measured bound.** A fixed sampling floor cost
about 12.5% re-execution forever. `SpotCheckPolicy` samples 1-in-N by committee reliability
(N = 4 below the assignment floor, 8 mid, 64 at ≥ 0.99), and the steady-state share is *measured*
rather than asserted: 120k committed versions across regions and server secrets hold proven
committees at ≤ 2% re-execution while fresh committees keep the tight floor.

**Safety over liveness.** Without quorum, a region pauses and waits — it never forks and never lets a
minority commit. Under partition this is the property that makes rejoin a forward sync rather than a
merge.

## Files

- `java/engine/src/main/java/dev/nodera/committee/{CommitteeMember,CommitteeSession,VotePersistence,SpotCheckAuditor,CommitteeFailover}.java`
- `java/engine/src/main/java/dev/nodera/consensus/{QuorumPolicy,VoteCollector,EquivocationDetector,SpotCheckPolicy}.java`
- `java/engine/src/test/java/dev/nodera/committee/{CommitteeMvpIT,ByzantineWorkerTest}.java`
- `java/peer/src/test/java/dev/nodera/peer/validation/ByzantineMeshIT.java`

## Testing

- `CommitteeMvpIT`: 2-of-3 quorum commit, then primary failover under epoch+1 with continuation.
- `ByzantineWorkerTest`: a lying validator is out-voted and penalised; a lying primary cannot
  commit; equivocation slashes.
- `ByzantineMeshIT` (over the wire): a fabricated root never reaches a certificate; a vote forged in
  an absent member's name buys no seat (the round times out instead of committing); an equivocating
  voter gets one seat, not two.
- `SpotCheckPolicyTest`: steady-state re-execution share ≤ 2% for proven committees; the ~25% floor
  holds for unproven ones.

## Acceptance criteria

1. ✅ A 2-of-3 quorum on re-executed roots commits the delta.
2. ✅ A lone liar cannot commit and is penalised; colluding liars are caught by spot-check.
3. ✅ Equivocation is detected and slashed without any wall-clock dependency.
4. ✅ Failover bumps exactly one epoch and the surviving committee continues.
5. ✅ The adaptive spot-check bound is measured, not asserted.
6. ⏳ Live: the three-client scenario on real clients — [minecraft 2](../minecraft/Task.2.md).

## Limitations

**L-30** (the P2P lane carries membership, not yet the full certified-state forward sync) is owned by
[`network/LIMITATIONS.md`](../network/LIMITATIONS.md); its committee half is green here.
