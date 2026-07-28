# Rendezvous — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the rendezvous category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name), then reconcile ../ROADMAP.md §2. Never rewrite an old
     note. -->

**Category:** rendezvous · **Last audit:** 2026-07-28 · Tasks completed: **4 / 6** (1 blocked, 1 in
progress)

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | The service binary | ✅ COMPLETED | 71 Rust tests in the crate; `RendezvousRelayIT` drives the real binary |
| [2](Task.2.md) | The Java transport | ✅ COMPLETED | Direct-first repaired 2026-07-24; renewal at half TTL; 28 Java `@Test` |
| [3](Task.3.md) | Live cross-internet proof | ⏳ BLOCKED | Needs the live/NAT environment and the migration lane; instrument (Task 4) in place |
| [4](Task.4.md) | Service telemetry + NAT-pair statistics | ✅ COMPLETED | `PairClass` + `PunchOutcomes`; D8 (relay volume + median duration) still ⬜; awaiting a population to measure |
| [5](Task.5.md) | Discoverable, drainable, self-updating | 🚧 IN PROGRESS | Drain lane landed 2026-07-27; owns L-83; live numbers → Task 3 |
| [6](Task.6.md) | A published image, and a relay anyone can run | ✅ DONE | GHCR image + compose + SELF-HOSTING.md; 60s stop grace |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — Documentation sweep: status reconciliation, test counts verified

Category-wide documentation audit (no code change). Outcomes:

- **Test counts corrected against the source.** The crate previously documented as 67 Rust tests is in
  fact **71** (`62 #[test] + 9 #[tokio::test]`, the latter the real-socket suite in `wire.rs`
  including the five drain tests). The Java rendezvous package carries **28 `@Test`** across
  `dev.nodera.transport.rendezvous` (including `RendezvousRelayIT`). Counts updated in
  [`Task.1`](Task.1.md), [`Task.4`](Task.4.md), [`Task.5`](Task.5.md), [`TESTING.md`](TESTING.md).
- **Task.0 charter reconciled with its own index.** The header counted "3 of 5"; the index lists six
  tasks, four completed (1, 2, 4, 6), one blocked (3), one in progress (5). Header now reads
  "4 of 6", and §1 of this ledger now carries rows for Task 5 and Task 6, which it previously omitted.
- **Task.4 status detail tightened.** The task is ✅ COMPLETED on its binding acceptance (1-4 green;
  5 defers to Task 3's population), but deliverable 8 (relayed *byte volume* and *median circuit
  duration*) is genuinely unbuilt — `CircuitMeter` tracks `bytes_transferred` (`circuit.rs:68`) and
  the bridge logs it at teardown (`wire.rs:419`), but neither reaches the reporter. Named explicitly
  in [`Task.4`](Task.4.md) §Status detail as the one open sub-item; left out of the limitations
  register because it is an additive metric, not a correctness gap.
- **L-83 unchanged.** Its exit test (a circuit live at the deadline that resumes through a
  replacement relay) still does not exist; the row stays OPEN. No limitations retired, none opened.
- **Refactoring register created.** [`REFACTORING.md`](REFACTORING.md) records the manual candidates
  (jscpd flagged zero duplication inside these modules): the `RendezvousPeerTransport` god class, the
  879-line `wire.rs`, and the mechanical `apply_env` repetition in `config.rs` are the top three.

### 2026-07-27 — The drain that was only a log line

The service printed `draining on shutdown signal`, broke its accept loop, and returned from `main`.
Dropping the tokio runtime then killed every bridged circuit mid-frame, because the circuits were
detached `tokio::spawn` tasks nobody awaited. The word was in the log and nowhere in the behaviour.

A drain is now four obligations in a fixed order: refuse new work (a reservation granted after the stop
decision is a promise already broken), tell the peers already committed **on the control channels they
are holding**, tell the trackers, then wait for in-flight circuits inside `drain_grace_seconds`. The
in-flight guard in `run_reserved` is what makes the wait mean anything, and
`a_live_circuit_registers_as_in_flight_work` is what stops it regressing.

Two smaller decisions carry more weight than their size. A draining relay refuses a reservation with the
readable reason `draining` rather than closing the socket — the peer side treats a reason as "re-reserve
elsewhere now" and a close as "retry here", which is the difference between a migration and a stall. And
a draining relay keeps answering registration and discovery, because those are precisely the peers who
need to find each other in order to move.

The service also announces itself to trackers now, so an operator adding a relay reaches every peer
instead of nobody, and the announce ack hands it its own replacement list — which is what lets a drain
notice name somewhere to go without a discovery round trip at the worst possible moment.

71 Rust tests in the crate (8 `#[tokio::test]` in `wire.rs`, 5 of them the drain suite). **L-83**
opened for the honest remainder: a grace period can still expire with a circuit live, and the fix is
resumable transfers rather than a longer wait.

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
