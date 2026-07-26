# Network Task 2 — Peer Runtime: Membership, Gateway Election, Migration

<!-- AI-AGENT-INSTRUCTION: Gateway election must stay a DETERMINISTIC PURE FUNCTION of (population,
     capabilities, epoch) computed independently by every peer — never an election protocol with
     messages. If you find yourself adding a round of voting to pick a gateway, stop: the whole point
     is that no round is needed. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS (membership, election, and continuity landed; migration end-to-end remains)
**Category:** network · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 1](Task.1.md), [engine 5](../engine/Task.5.md)
**Consumed by:** [worker 1](../worker/Task.1.md), [minecraft 2](../minecraft/Task.2.md), [rendezvous 3](../rendezvous/Task.3.md)

---

## Goal

One `PeerRuntime` on every installation: capability-declared roles, full-mesh membership gossip,
heartbeats, deterministic **capability-weighted gateway election**, and session-gateway **migration**
so that losing the peer currently carrying the session is a hiccup rather than an outage. Committee
membership changes are certified by their predecessors — no single party, the server included,
rotates members.

## Status detail

**Landed.** Full-mesh membership gossip with keep-alives; capability-weighted deterministic
`GatewayElection`; a real-TCP continuity beta (`SessionContinuityIT` — the base peer disconnects and
the survivors stay connected after re-electing); `CommitteeManager` for authority-free certified
committee changes (old-committee quorum of approvals, loud degradation when the population is too
small); 3-of-4 quorum plumbing; `JoinAdmission` gating join **and** gossip ingest; per-peer traffic
attribution; and `PeerDiscoveryService` sweeps that introduce this node to every routable peer found
via trackers and rendezvous.

**Remaining:** session-gateway **migration** end to end — freeze, reconnect, exactly-once resubmit
with sequence dedupe — over direct, punched, and relayed paths, plus the recorded full-peer-down
demo. Cross-NAT runs ride [`rendezvous/Task.3.md`](../rendezvous/Task.3.md); live acceptance rides
[`minecraft/Task.2.md`](../minecraft/Task.2.md).

## Dependencies

- [network 1](Task.1.md) — the transport seam and the membership message family.
- [engine 5](../engine/Task.5.md) — the committee whose changes this runtime certifies.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `PeerRuntime` — roles, membership, gossip, heartbeats | ✅ |
| 2 | `GatewayElection` — capability-weighted, deterministic | ✅ |
| 3 | `SessionContinuityIT` — base-peer disconnection continuity over real TCP | ✅ |
| 4 | `CommitteeManager` — certified committee changes, rotation, resize | ✅ |
| 5 | `JoinAdmission` — gates join and gossip ingest | ✅ |
| 6 | `PeerDiscoveryService` — tracker + rendezvous sweeps feeding `announceTo` | ✅ |
| 7 | Session-gateway migration: freeze → reconnect → exactly-once resubmit | 🚧 (core landed: `GatewayHandover` + 12 tests; the runtime bind to gateway loss/election remains) |
| 8 | Recorded full-peer-down demo (3 peers, 15 min, roots equal) | ⏳ |

## Design

**Election without an election.** Every peer computes the gateway from the same inputs: rendezvous
hashing over (session, epoch, nodeId), weighted by a bounded pure-integer capability score (cores,
memory, inverse latency, reliability, each clamped to a bucket). Because the function is pure, no
messages are exchanged and no split-brain is possible — two peers that disagree about the gateway must
first disagree about the population, which the membership lane already reconciles. Equal-weight peers
rotate by the rendezvous score so duty spreads.

**Capability weighting, added later, deliberately.** The first version was rendezvous-hash only. That
was correct but wasteful: it handed sessions to underpowered peers as readily as to well-provisioned
ones. Weighting was added *without* breaking the order-independence property test, which is the
property that makes the whole approach safe.

**Committee changes need the old committee's quorum.** A membership change signed by the *new*
members proves nothing. Requiring the **old** committee's quorum of approvals means no single party
can rotate members in — including a dedicated server, which under this design is just a
well-provisioned peer with one non-authoritative vote.

**Degrade loudly.** When the population cannot staff a 3-of-4 committee, the system drops to 2-of-3
**and says so** — `isDegraded` is derived from the certified committee rather than a static flag, so
it cannot be stale.

**Merge, never arbitrate.** `PeerDiscoveryService` merges peers found across every tracker and
rendezvous; it never picks a winner between two sources. A discovery plane that arbitrates becomes an
authority, which rule 7 forbids.

## Files

- `java/peer/src/main/java/dev/nodera/peer/{PeerRuntime,GatewayElection,TickSync}.java`
- `java/peer/src/main/java/dev/nodera/peer/committee/CommitteeManager.java`
- `java/peer/src/main/java/dev/nodera/peer/discovery/PeerDiscoveryService.java`

## Testing

- `SessionContinuityIT` — real TCP; the base peer disconnects and the survivors re-elect and stay
  connected.
- `GatewayElectionTest` — bounded pure-integer weight; most-capable peer wins across epochs;
  equal-weight rotation preserved; bootstrap preference and deterministic tie-break unchanged; order
  independence.
- `CommitteeManager` tests — quorum-of-approvals change, lost-primary replacement under a bumped
  epoch, loud 2-of-3 degradation, too-small-to-staff rejection, link-by-link chain verification.
- `ResidentQuorumIT` — with two standing workers on the mesh, a player's logout leaves the committee
  at full strength and it keeps committing, with the counterfactual (no residents ⇒ committee of one)
  asserted beside it.
- Planned: `GatewayMigrationIT` twice — mesh on direct sockets and on pure relay.

## Acceptance criteria

1. ✅ Membership gossip converges and heals lost join-time gossip via anti-entropy.
2. ✅ Gateway election is deterministic, capability-weighted, and order-independent.
3. ✅ Committee changes require the old committee's quorum and verify link-by-link.
4. 🚧 `GatewayMigrationIT`: kill the gateway ⇒ certified election ⇒ reconnect within the freeze cap ⇒
   exactly-once resubmit — on direct sockets **and** on pure relay.
5. ⏳ `LatePeerCatchUpIT` and the recorded full-peer-down demo with equal roots.

## Limitations

- **L-30** — the P2P lane's certified-state half. See [`LIMITATIONS.md`](LIMITATIONS.md).
- **L-29** (unweighted election) is RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
