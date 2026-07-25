# Network Task 6 — Archive Placement, Replication, Repair

<!-- AI-AGENT-INSTRUCTION: Placement is a PURE FUNCTION of (content, peer set) so every peer computes
     the same expected-holder list with no coordinator. Never introduce a placement authority or a
     negotiated assignment. Repair is bounded and VERIFIES BEFORE RECORDING, then re-audits rather
     than trusting its own success — the MultiPaper repair-storm lesson. Keep this header's status
     accurate. -->

**Status:** ✅ COMPLETED (live churn soak → [minecraft 2](../minecraft/Task.2.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 4](Task.4.md), [network 5](Task.5.md)
**Consumed by:** [network 7](Task.7.md), [network 9](Task.9.md), [worker 3](../worker/Task.3.md)

---

## Goal

Guarantee world data is **redundantly spread** so that no single peer — the host included — is a
single point of loss. A deterministic placement policy assigns each piece and manifest to a holder
set sized to the network; a seed floor and a per-peer cap keep the load fair; and an audit-plus-repair
service restores missing replicas after churn.

## Status detail

Complete. `ArchiveRepairIT` re-replicates a killed ×5 manifest back to its factor with no data loss.

`WorldReplicationService` closed the last gap in the chain: announcing published *where* a world was,
but nothing replicated it. The service runs the placement policy over the tracker directory — a pure
function of (world, peer set), so every node computes the same expected-holder list with no
coordinator — and adopts the worlds this node is placed for, under a byte budget.

## Dependencies

- [network 4](Task.4.md) — pieces and manifests.
- [network 5](Task.5.md) — the inventory this audits against.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `RendezvousArchivePolicy` — deterministic top-R placement, host included but exempt from R | ✅ |
| 2 | `ReplicationFactors` — snapshot ×5, recent log ×4, compacted ×3, checkpoints/genesis everyone | ✅ |
| 3 | `SeedFloorPolicy` — floor `min(25%, R/N)`, cap `max(5%, 2·R/N)`, host exempt | ✅ |
| 4 | `ArchiveAuditTask` — expected versus live inventory diff → repair plan | ✅ |
| 5 | `ArchiveRepairService` — bounded, verify-before-record, re-audit-not-trust | ✅ |
| 6 | `ArchiveManager` — per-peer reconcile; never evicts an assigned region's current state | ✅ |
| 7 | `WorldReplicationService` — adopts placed worlds under a byte budget | ✅ |
| 8 | Live churn soak on a real mesh | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Placement must be computable, not negotiated.** Rendezvous hashing gives every peer the same
expected-holder list from (content id, peer set) with no messages and no coordinator. A negotiated
assignment would need a leader, and a leader is a single point of failure for exactly the property
this task provides.

**The host is included but exempt from R.** The host holds everything (rule 0), so counting it toward
the replication factor would mean losing the host drops the world below R in one step. Exempting it
means R replicas survive the host's departure — which is the failure this task exists to survive.

**Floor and cap are both dynamic in R/N.** A flat "seed 25%" is punishing on a large network and
useless on a small one. `min(25%, R/N)` keeps small networks fully covered and large ones cheap;
`max(5%, 2·R/N)` stops any single peer from holding a dangerous share once the network is big enough
for that to matter.

**Repair verifies before recording, then re-audits.** Recording a repair on the strength of a
successful request is how repair storms start: every peer believes the replica exists, the audit
disagrees next cycle, and everyone repairs again. Verifying the bytes before recording, and
re-auditing rather than trusting the write, is the MultiPaper lesson applied directly.

**Repair is bounded.** An unbounded repair reacts to churn by saturating the network at exactly the
moment the network is least healthy.

## Files

- `java/peer/src/main/java/dev/nodera/peer/archival/{RendezvousArchivePolicy,ReplicationFactors,SeedFloorPolicy,ArchiveAuditTask,ArchiveRepairService,ArchiveManager}.java`
- `java/peer/src/main/java/dev/nodera/peer/WorldReplicationService.java`

## Testing

- `ArchiveRepairIT` — a killed ×5 manifest is re-replicated to factor with no data loss.
- Placement property tests: same inputs ⇒ same holder list regardless of iteration order.
- Floor and cap unit tests across N, including the 5% asymptote at large N and host exemption.
- Audit tests: expected-versus-inventory diff produces a minimal repair plan.

## Acceptance criteria

1. ✅ Placement is deterministic, order-independent, and coordinator-free.
2. ✅ Losing the host still leaves R replicas.
3. ✅ The seed floor and per-peer cap hold across network sizes.
4. ✅ Repair verifies before recording and re-audits rather than trusting.
5. ✅ A node adopts and seeds the worlds it is placed for, under a byte budget.
6. ⏳ Live: a churn soak on a real mesh.

## Limitations

None open. **L-35** (no replication placement or repair) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
