# Worker Task 4 — Out-of-Game Committee Validation

<!-- AI-AGENT-INSTRUCTION: This task adds NO new consensus code. It points the existing, tested
     committee stack at the worker's runtime. If you find yourself writing validation logic here,
     stop — it belongs in engine/Task.5.md and must be reused, not reimplemented. A Rust validator is
     forbidden by the single-engine rule. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (headless; live real-world region feed → [minecraft 2](../minecraft/Task.2.md))
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [engine 5](../engine/Task.5.md), [network 2](../network/Task.2.md)
**Consumed by:** [minecraft 2](../minecraft/Task.2.md), the mesh's quorum health

---

## Goal

The worker re-executes regions and casts votes: a **companion-only node with no Minecraft process**
participates in a committee quorum. This is what makes a mesh's validation capacity independent of who
happens to have the game open.

## Status detail

Complete headlessly, and it closed a real structural gap: before it, the whole validation stack was
runtime-unreferenced outside the mod, and the `simulationmsg` wire family had no live consumer.

`WorkerValidationService` runs committee re-execution **out of game** and participates in quorum over
the same `PeerTransport` the membership session rides. `WorkerQuorumValidationIT` proves the exit:
three **companion-only** worker nodes form a committee, the primary proposes, validators re-execute
with the engine and vote over the wire, the 2-of-3 quorum commits, every worker converges on the
byte-identical root **matching the reference engine**, and each persists the co-signed certificate in
its own store — certified state flowing peer to peer. Primary loss promotes a validator under epoch+1
and the surviving committee keeps committing.

The fallback lane runs here too: unassigned-region actions are classified and committed through the
server lane, and the soak ratio rides the worker's `STATE` telemetry.

`ResidentQuorumIT` proves the property this task exists for: with two standing workers on the mesh, a
player's logout leaves the committee at quorum size and it keeps committing, with the certificate
co-signed by a peer that has no Minecraft process. The counterfactual — no residents ⇒ a committee of
one — is asserted alongside, so the behaviour cannot silently regress.

## Dependencies

- [engine 5](../engine/Task.5.md) — the committee stack being reused verbatim.
- [network 2](../network/Task.2.md) — the runtime and transport it rides.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `WorkerValidationService` — committee re-execution over the peer transport | ✅ |
| 2 | Consumption of the `simulationmsg` wire family | ✅ |
| 3 | Certificate persistence per member | ✅ |
| 4 | Failover to epoch+1 on primary loss | ✅ |
| 5 | Fallback lane routing with soak metrics in `STATE` | ✅ |
| 6 | Resident seat assignment so workers hold committee seats | ✅ |
| 7 | Live real-world region feed | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**No new consensus code — that is the whole point.** The committee stack was already proven; what was
missing was a *host* for it outside the game. Reusing it verbatim means the worker cannot drift from
the mod's semantics, and any consensus bug has exactly one place to be fixed.

**Option B, locked.** The worker is the Java peer. A Rust-native validator is forbidden by the
single-engine determinism rule: a second implementation of the engine would have to be bit-identical
forever, which is a promise no one can keep. A Rust node could only seed, relay, and route — useful
later, never a validator.

**Residents make committees survive players.** Before resident seats, a region's committee was
whatever players happened to be standing in it, so a logout could collapse it to one member. Topping
committees up from the standing worker pool is what turns "validated by whoever is online" into
"validated by the network".

**A worker validator is not more trusted.** It votes on its own re-execution like any member, its
votes are signed, and it can be out-voted and slashed. Being always-on buys availability, not
authority.

## Files

- `java/peer/src/main/java/dev/nodera/peer/validation/WorkerValidationService.java`
- `java/peer/src/main/java/dev/nodera/headless/HeadlessPeerMain.java` (composition root)

## Testing

- `WorkerQuorumValidationIT` — three companion-only workers quorum-commit over the transport,
  converge on the reference-engine root, persist certificates, and fail over to epoch+1.
- `ResidentQuorumIT` — a committee holds full strength through a player's logout, with the
  no-resident counterfactual asserted.
- `ByzantineMeshIT` — an adversarial peer on the same mesh is outvoted and cannot forge a seat.
- `LiveLagHandoffIT` — the worker's forwarded-action latency drives the handoff lane.

## Acceptance criteria

1. ✅ A committee quorum containing only worker validators commits a region delta headlessly.
2. ✅ Every member converges on the byte-identical reference-engine root.
3. ✅ Certificates persist per member.
4. ✅ Primary loss promotes under exactly one epoch and the committee continues.
5. ✅ Committees survive a player's disconnect via resident seats.
6. ⏳ Live: the same lane fed by a real server's regions
   ([minecraft 2](../minecraft/Task.2.md)).

## Limitations

None open. **L-48** (a companion-only node cannot validate) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
