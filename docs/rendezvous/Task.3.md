# Rendezvous Task 3 — Live Cross-Internet Proof

<!-- AI-AGENT-INSTRUCTION: This task's whole value is that it runs on REAL NAT with REAL internet
     paths — a loopback or LAN run does not substitute for it and must not be recorded as satisfying
     it. Record the measured direct/punched/relayed mix; a number nobody wrote down is a number
     nobody can regress. Keep this header's status accurate.
     Context: the live soak that proves the mechanism on real NAT. No sub-deliverable is code — all
     are measurement runs blocked on the live environment. Key files the soak drives:
     peer/.../SessionContinuityIT.java (pure-relay run),
     library/java/transport/.../rendezvous/TransportSelector.java:57 (the metrics surface),
     rendezvous/src/telemetry.rs:27 + punch.rs:96 (the population numbers source). Numbers
     land in ../plans/Plan.0.md. Depends on: ../network/Task.2.md (migration machinery),
     ../minecraft/Task.1.md (live environment). Consumed by: the project's reachability claims. -->

**Status:** ⏳ BLOCKED (needs the live/NAT environment)
**Category:** rendezvous · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 2](../network/Task.2.md) (migration machinery), [minecraft 1](../minecraft/Task.1.md) (live environment)
**Consumed by:** the project's reachability claims

---

## Goal

Take the mechanism that is proven headlessly and over loopback, and measure it on the real internet
with real NAT: gateway migration and committee traffic with the mesh forced onto (a) direct sockets,
(b) pure relay, and (c) mixed paths — plus the selector metrics that show which mix actually occurred.

## Status detail

Blocked, not stalled. The mechanism is complete and proven: the service and the transport are green,
`RendezvousRelayIT` drives the real binary, and the encrypted circuit carries frames byte-exact. What
is missing is a live environment with genuine NAT — the same environment the mod's run harness
([`minecraft/Task.1.md`](../minecraft/Task.1.md)) is being built to provide — and the migration
machinery in [`network/Task.2.md`](../network/Task.2.md) that this soak is meant to exercise.

The measurement instrument ([Task 4](Task.4.md)) has landed: punch attempts and successes grouped by
NAT-pair class are collected and reportable. So Task 3 is no longer blocked on *how to answer* the
question — only on a population on a real network.

## Dependencies

- [network 2](../network/Task.2.md) — session-gateway migration end to end.
- [minecraft 1](../minecraft/Task.1.md) — the live/NAT environment and the client harness.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Session continuity run forced onto **pure relay** | ⏳ |
| 2 | Gateway migration on direct, pure-relay, and mixed meshes | ⏳ |
| 3 | A NAT-blocked pair falling back to a relay circuit transparently | ⏳ |
| 4 | Recorded `TransportSelector` metrics: the direct/punched/relayed mix | ⏳ |
| 5 | Cross-internet soak numbers recorded in the plan notes | ⏳ |

## Design

**Loopback proves the mechanism; the internet proves the assumption.** Everything the code does — the
state machine, the cipher, the reservation, the teardown — is exercised headlessly. What headless
testing cannot exercise is the *distribution of outcomes*: how often a punch succeeds, how often a
pair ends up relayed, what a relayed leg's latency does to committee round times. Those are facts
about the world, not about the code, and they can only be measured.

**Force the mesh onto each path deliberately.** A run that happens to be direct proves nothing about
relay correctness. The three configurations are separate runs with the path forced, because the
interesting claim — *correctness holds on a pure-relay path* — is only tested when a direct path is
impossible.

**Record the mix.** A selector that silently drifts toward relaying everything would look healthy in
every functional test while quietly costing the project its bandwidth story. The measured mix is the
regression signal.

**QUIC and connection migration are noted, not scheduled.** Hole punching is best-effort over TCP
simultaneous open today. QUIC would improve punch success rates and survive address changes; it is
recorded as a follow-up direction rather than a ledger row, because the current design's exit does not
depend on it.

## Files

- `peer/src/test/.../SessionContinuityIT.java` (to be run pure-relay)
- `library/java/transport/.../rendezvous/TransportSelector.java` (metrics)
- Numbers land in [`../plans/Plan.0.md`](../plans/Plan.0.md) notes

## Testing

- The pure-relay `SessionContinuityIT` run.
- Three forced-path migration runs with equal roots at the end.
- A recorded soak with real NAT on both sides, including at least one symmetric-NAT pair.

## Acceptance criteria

1. ⏳ Gateway migration and committee traffic pass on direct, pure-relay, and mixed meshes.
2. ⏳ A NAT-blocked pair falls back to a relay circuit transparently, with no call-site awareness.
3. ⏳ Selector metrics are recorded and the mix is documented.
4. ⏳ Both toolchains stay green and the numbers are committed.

## Limitations

None owned as a register row. The live-environment dependency is tracked as **L-45** in
[`../minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md); duplicating it here would create two
rows for one gap.
