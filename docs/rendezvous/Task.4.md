# Rendezvous Task 4 — Service Telemetry and NAT-Pair Statistics

<!-- AI-AGENT-INSTRUCTION: This task reports about the SERVICE. The rendezvous sees both ends of every
     connection attempt — the most identifying vantage point in the whole system — and none of it may
     enter a telemetry event: only counters and NAT-pair CLASSES over a window. Emission is OFF
     unless the operator configures an endpoint. Keep this header's status accurate.
     Context: the service's own counters + the NAT-pair punch statistics. Sub-deliverables 1-7 ✅;
     deliverable 8 (relayed byte VOLUME + median circuit DURATION, needs circuit-bridge metering
     plumbed into the reporter) remains ⬜ — the only open sub-item. Acceptance 5 defers to Task.3's
     population, not to code here. Key files: rendezvous/src/telemetry.rs:27 (windowed
     reporter), punch.rs:96 (PunchOutcomes inferred-success counter) + :47 (PairClass four classes),
     config.rs:63 (telemetry_endpoint, empty by default), circuit.rs:56 (CircuitMeter — has
     bytes_transferred but not yet exported). 71 Rust tests in the crate. Depends on: Task.1.md,
     ../telemetry/Task.1.md (endpoint + rendezvous.* events). Consumed by: Task.3.md, ../telemetry/Task.3.md. -->

**Status:** ✅ COMPLETED
**Category:** rendezvous · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [rendezvous 1](Task.1.md), [telemetry 1](../telemetry/Task.1.md)
**Consumed by:** [rendezvous 3](Task.3.md), [telemetry 3](../telemetry/Task.3.md)

---

## Goal

Answer the question the project cannot currently answer at all: **how often does hole punching
actually work, for which kinds of NAT, and how much traffic ends up on the relay instead?** — without
the service ever describing a single connection.

## Status detail

Core goal delivered and green (`cargo test -p nodera-rendezvous`, **71 tests** in the crate).

Landed: `telemetry_endpoint` / `telemetry_interval_seconds`, empty by default; `src/telemetry.rs`,
the windowed reporter; and the substantial half — `PairClass` + `PunchOutcomes` in `punch.rs`,
classifying each punch by the two ends' observed NAT hardness (does what a peer advertises match
where its packets arrive from?) and judging it by whether the same pair later opens a relay circuit.

That inference is stated in the code rather than hidden: this service never learns whether a dial
connected, so "punched and did not come back for a relay" is what success means here, and the bias
it carries is documented where the counter lives.

**Remaining sub-item (deliverable 8):** relay *byte volume* and *median circuit duration* are not yet
emitted. `CircuitMeter` already tracks `bytes_transferred` (`circuit.rs:68`) and the bridge logs it at
teardown (`wire.rs:419`), but neither reaches the reporter — `rendezvous.relay` carries only circuit
counts and denials. Closing this needs the bridge to hand its per-circuit byte total and duration into
the windowed counters the reporter drains. It is the single open sub-deliverable; it is tracked here
rather than as a limitation row because it is an additive metric, not a correctness gap.

## Dependencies

- [rendezvous 1](Task.1.md) — the service and its counters.
- [telemetry 1](../telemetry/Task.1.md) — the endpoint and the `rendezvous.*` events.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `telemetry_endpoint` configuration — **empty by default** | ✅ |
| 2 | `rendezvous.window` from the existing counters | ✅ |
| 3 | NAT-pair classification (`easy_easy` / `easy_hard` / `hard_hard` / `unknown`) from observed mapping behaviour | ✅ |
| 4 | `rendezvous.punch` — attempts and successes per pair class | ✅ |
| 5 | `rendezvous.relay` — circuits and denials per window | ✅ |
| 6 | `service.start` with version, OS, arch | ✅ |
| 7 | Bounded queue; an outage never affects punching or relaying | ✅ |
| 8 | Relayed *volume* and median circuit duration (needs metering in the circuit bridge) | ⬜ |

## Design

**This is the highest-value telemetry in the project, and the most dangerous.** A rendezvous service
watches both ends of every attempt to connect. That is precisely why its events are counters and
class labels only: no address, no node id, no namespace, no pair identity, no timing that could
correlate two peers. What leaves is "of 4,812 punch attempts between two hard NATs this hour, 38 %
succeeded" — a number that improves the product and describes nobody.

**NAT classification is a property of the service's own observations,** derived from how a peer's
mapping behaves across its registrations, and it is reported as one of four coarse classes. Anything
finer would begin to characterise individual networks.

**Punch success by pair class is the metric that unblocks [rendezvous 3](Task.3.md).** "Does hole
punching work across the real internet" is unanswerable from one developer's connection; it is
directly answerable from a population of attempts grouped by NAT pair.

**Relay share is a cost signal and a health signal.** A rising relay fraction means punching is
failing somewhere, and relay bandwidth is the one resource this service actually spends.

**Off unless configured, and invisible when it fails** — the same reasoning as
[tracker 4](../tracker/Task.4.md): running the binary is not consent, and reachability must never
depend on a dashboard.

## Files

- `rendezvous/src/telemetry.rs` — the reporter
- `rendezvous/src/punch.rs` — outcome + pair-class counters
- `rendezvous/src/config.rs` — `telemetry_endpoint`, `telemetry_interval_seconds`
- `rendezvous/src/circuit.rs` — `CircuitMeter` (byte/duration/idle source for D8)

## Testing

```bash
cd rust && cargo test -p nodera-rendezvous   # 71 tests in the crate, telemetry + punch outcomes included
```

- `telemetry::tests::telemetry_is_off_without_an_endpoint` — the loop returns rather than running.
- `punch::outcome_tests::a_pair_is_classified_from_both_sides_hardness` — the four classes, and the
  `unknown` fallback for a peer this service has never seen register.
- `punch::outcome_tests::a_relay_after_a_punch_counts_the_punch_as_a_failure` — the inference,
  pinned, including its symmetry: `(A,B)` and `(B,A)` are one pair.
- `punch::outcome_tests::draining_clears_the_window_and_the_pending_map` — a window is reported once
  and the pending map cannot grow per peer pair.
- `reporter::tests::a_window_event_renders_counters_and_labels_only` (in `nodera-telemetry`) — the
  rendered event carries no identity of any kind.

## Acceptance criteria

1. ✅ No telemetry without an explicitly configured endpoint.
2. ✅ No emitted field can identify a peer, a pair, or an address (`a_window_event_renders_counters_and_labels_only`;
   `PairClass` is the only pair-derived value and it has four possible values).
3. ✅ Punch success is reported per NAT-pair class (`a_relay_after_a_punch_counts_the_punch_as_a_failure`).
4. ✅ A telemetry outage leaves punching and relaying unchanged — the reporter is a separate task
   with a bounded queue.
5. ⏳ [rendezvous 3](Task.3.md) can cite population numbers once a deployment has collected them;
   the mechanism is in place, the population is not.

## Limitations

None owned. The stack-side gaps are in [`../telemetry/LIMITATIONS.md`](../telemetry/LIMITATIONS.md).
