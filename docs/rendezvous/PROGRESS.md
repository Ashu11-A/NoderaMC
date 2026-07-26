# Rendezvous — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the rendezvous category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old
     note. -->

**Category:** rendezvous · **Last audit:** 2026-07-25 · Tasks completed: **3 / 4**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The service binary | ✅ COMPLETED | 55 Rust tests; `RendezvousRelayIT` drives the real binary |
| [2](Task.2.md) | The Java transport | ✅ COMPLETED | Direct-first repaired 2026-07-24; renewal at half TTL |
| [3](Task.3.md) | Live cross-internet proof | ⏳ BLOCKED | Needs the live/NAT environment and the migration lane |
| [4](Task.4.md) | Service telemetry + NAT-pair statistics | ✅ COMPLETED | `PairClass` + `PunchOutcomes`; awaiting a population to measure |

---

## 2. Milestone notes (newest first)

### 2026-07-25 — Punch success becomes measurable, and the inference is stated out loud

`PairClass` + `PunchOutcomes` landed in `punch.rs`, with the reporter in `telemetry.rs`.

The honest part is worth recording. **This service never learns whether a dial connected** — it only
stamps a go-signal. What it does see is whether the same pair comes back for a *relay circuit*,
which is what peers do when a punch fails. So a punch followed by a relay is counted as a failure,
one that is not is counted as a success, and the bias that leaves (a pair that failed and gave up
counts as a success it did not earn) is written where the counter is defined rather than discovered
later by someone reading a graph.

NAT hardness comes from a comparison this service was already making: does the address a peer
advertises match the address its packets arrive from? One boolean per peer, four classes per pair,
and neither address survives the comparison.

The mechanism is in place; the *numbers* [task 3](Task.3.md) needs are still waiting for a
deployment with a population on it.

### 2026-07-25 — The measurement that unblocks the live proof

[Task 3](Task.3.md) has been blocked partly on something no code change fixes: "does hole punching
work across the real internet" cannot be answered from one developer's connection. [Task 4](Task.4.md)
makes it answerable — punch attempts and successes grouped by **NAT-pair class**, over a population.

This is simultaneously the most valuable telemetry in the project and the most dangerous, and the task
file says so. A rendezvous service watches both ends of every connection attempt; it is the single
most identifying vantage point in the system. So its events carry counters and four coarse class
labels and nothing else: no address, no node id, no namespace, no pair identity, no timing that could
correlate two peers. What leaves is "of 4,812 attempts between two hard NATs this hour, 38 %
succeeded" — a number that improves the product and describes nobody.

### 2026-07-24 — Direct-first was structurally unreachable, and is now repaired

Found by an end-to-end audit of the discovery chain, and worth recording in full because the code was
*correct in policy and inert in practice*.

`RendezvousPeerTransport` advertised **only** a RELAY candidate. `hasDirectCandidate` was therefore
false for every discovered peer, `Path.DIRECT` never entered the available set, and every byte on that
transport crossed the relay — the exact inversion the reference architecture warns about. The
selector's careful direct-over-punched-over-relayed preference had nothing to prefer.

Three fixes: the transport now publishes a **HOST candidate** derived from the direct transport's
listen route (which is why `listenRoute()` joined the `PeerTransport` seam); it substitutes that
address when a caller addresses a peer by node id alone; and it **renews its registration at half the
TTL** instead of silently vanishing from discovery after five minutes. The joiner is now wrapped in
the same transport as the host, so relay fallback stopped being one-sided — and therefore stopped
being useless.

The lesson generalises: a preference policy is inert unless something produces the option it prefers.
A test that asserts *a direct candidate exists when a direct listener exists* is worth more here than
one that asserts the ordering.

### 2026-07-19 — The service and transport land; L-23 and L-27 RETIRED

The standalone `nodera-rendezvous` service and the Java rendezvous transport closed the cross-NAT and
relay-fallback gap, superseding a jvm-libp2p transport plan that had never been built.

The service speaks the frozen rendezvous/relay family: signed-record registration with TTL and
trust-on-first-use identity binding, paged discovery, HMAC relay reservations validated statelessly,
and a tokio circuit bridge that meters bytes, duration, and idle time and tears down with a reason
code. The Java side composes direct-first and relay-fallback behind the same seam, with an
end-to-end cipher that leaves the relay carrying bytes it can neither read nor forge.

`RendezvousRelayIT` is the proof, and it uses the **real binary**: two relay-only peers register,
discover each other, exchange a `PeerJoin` and a keep-alive byte-exact across the encrypted circuit;
the reservation's byte ceiling tears the circuit down with the right reason; and the selector reports
the direct path when one is allowed.

Recorded at the time and still true: the *mechanism* is proven headlessly and over loopback; the real
cross-internet numbers ride [`Task.3.md`](Task.3.md) with the live environment.
