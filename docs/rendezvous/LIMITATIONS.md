# Rendezvous — Limitations Register

<!-- AI-AGENT-INSTRUCTION: NORMATIVE for the rendezvous category. "Permanent" is banned. Every §B row
     has an owning task and an EXIT TEST. Never delete a row — move it to LIMITATIONS.fixed.md with
     its evidence. Note §C: several properties that LOOK like limitations are the design working
     correctly (a relayed steady state, an untrusted service). Do not convert them into §B rows. -->

**Category:** rendezvous · **Last audit:** 2026-07-28 · Open or retiring rows: **0**

Status values: `OPEN` → `RETIRING` → `RETIRED` (row moves to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-8 | Some NAT pairs cannot be punched (symmetric NAT on both sides, carrier-grade NAT) | The relayed path is a **first-class** fallback, not a failure mode: correctness holds on a pure-relay path, and the relay is metered and end-to-end encrypted so it is safe to depend on | [1](Task.1.md), [2](Task.2.md) |

A-8 is an envelope constraint and has no exit test to run: it does not retire, because no amount of code
makes a symmetric-NAT pair punchable. It is listed so the *mechanism* stays honest, and the mechanism is
the tested one — `RendezvousRelayIT` drives two relay-only peers end to end through the real binary, and
the retired L-23/L-27 rows are its evidence. Re-checked 2026-07-28. The only thing that would falsify it
is a call site above the transport behaving differently on a relayed path than on a direct one; that is a
bug in the caller, and the reading guide below says so.

---

## §B — Staged capabilities

None open. L-83 retired on 2026-07-28 — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). Writing its
exit test first, as that row's last trace proposed, found the one part that did **not** compose: a cut
circuit and a departed peer arrive as the same `onPeerDown`, so the download dropped the holdings of a
peer that had merely migrated and was then left with nobody to ask.

The remaining work in this category — real cross-internet numbers and the pure-relay continuity run —
is [`Task.3.md`](Task.3.md), which is **blocked on a real cross-internet/NAT environment**. The
single-machine live harness that used to block it (L-45) is retired and green in CI; what Task 3 still
needs is two genuinely separate networks, which no CI runner provides. Tracked there, not duplicated
into a register row here.

---

## §C — By design (not limitations, and must not be "fixed")

<!-- AI-AGENT-INSTRUCTION: Do NOT convert these into §B rows. Each is the design working as intended.
     A proposal that makes the rendezvous service more trusted, or that treats a relayed path as a
     failure to be eliminated, is a design regression. -->

| Property | Why it is not a limitation |
|---|---|
| Hole punching is best-effort (TCP simultaneous open) | RELAYED is a legal steady state, tested as such. QUIC would raise punch success rates and survive address changes; it is a recorded follow-up direction, not a gap in the current design |
| The service can refuse an introduction | It cannot impersonate (records are Ed25519-signed with trust-on-first-use identity binding) or read traffic (X25519 + AES-GCM before any application byte). Refusal degrades reachability, never correctness |
| The relay sees traffic volumes and timing | It sees counts, never payloads — the explicit privacy floor. Metadata resistance beyond that is outside this service's scope |
| Relay limits can cut a circuit | That is the abuse boundary working: no reservation ⇒ no connect, and a circuit that exceeds its byte, duration, or idle budget is torn down **with a reason code** the Java side surfaces |
| The `Rendezvous*Policy` classes elsewhere are unrelated | `RendezvousPlacementPolicy` and `RendezvousArchivePolicy` are rendezvous *hashing*, in the engine and network categories. The name collision is flagged here to prevent an over-eager cleanup from merging two unrelated concepts |

---

## Reading guide for the implementing model

- The governing rule: **the service introduces and forwards; peers authenticate each other end to
  end and treat everything it says as a hint.**
- A change that makes a relayed path behave differently from a direct one at any call site above the
  transport is a bug, not an optimisation.
- Reference: [`REFERENCE.md`](REFERENCE.md) §4 (planes), §7 (connection lifecycle), §8 (security),
  §12 (fallback policy).
