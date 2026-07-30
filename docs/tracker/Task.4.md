# Tracker Task 4 — Service Telemetry

<!-- AI-AGENT-INSTRUCTION: This task reports about the SERVICE, never about the peers using it. The
     tracker sees announce sources — addresses, node ids, world hashes — and none of that may enter a
     telemetry event: only counters over a window. An operator who runs this binary has consented to
     nothing, so emission is OFF unless they configure an endpoint. Keep this header's status
     accurate. -->

**Status:** ✅ COMPLETED
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [tracker 1](Task.1.md), [telemetry 1](../telemetry/Task.1.md)
**Consumed by:** [telemetry 3](../telemetry/Task.3.md), community operators

---

## Goal

A tracker operator can point the service at a telemetry endpoint and get their own throughput,
rejection, health, and latency numbers into a dashboard — with the guarantee that nothing about any
individual peer or world leaves the process.

## Status detail

Complete and green. The crate now carries **109 `#[test]`s** (2 of them in `telemetry.rs`); the
decisive one for this task is `telemetry_is_off_without_an_endpoint`.

Landed: `telemetry_endpoint` / `telemetry_interval_seconds` in `nodera-tracker.toml`, both empty or
inert by default; `src/telemetry.rs`, a windowed reporter on its own task; `Tracker::world_health_counts`
for the health row; and the shared `nodera_telemetry::reporter`, whose `ServiceEvent` can only hold
numbers and `'static` labels — so a value derived from a request is not representable.

`telemetry_is_off_without_an_endpoint` is the decisive test: the loop *returns* rather than looping
when no endpoint is configured, so a default deployment opens no socket at all.

## Dependencies

- [tracker 1](Task.1.md) — the counters being reported.
- [telemetry 1](../telemetry/Task.1.md) — the endpoint and the registry's `tracker.*` events.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `telemetry_endpoint` configuration — **empty by default**, meaning off | ✅ |
| 2 | Windowed reporter: `tracker.window` once per interval from the existing counters | ✅ |
| 3 | `tracker.world_health` — healthy/degraded/dead world counts | ✅ |
| 4 | `service.latency` — p50/p95/p99 over the window | ⬜ |
| 5 | `service.start` with version, OS, arch | ✅ |
| 6 | Bounded queue; a telemetry outage never affects announce or query handling | ✅ |
| 7 | Deployment note in the operator documentation ([tracker 3](Task.3.md)) | ✅ [`SELF-HOSTING.md`](SELF-HOSTING.md) §3 documents `NODERA_TRACKER_TELEMETRY_ENDPOINT` |

## Design

**Off unless configured.** Someone who downloads and runs a tracker binary has agreed to run a
tracker, not to report to the project. The absence of an endpoint is the absence of consent, and the
default configuration ships without one. The project's own deployments set it.

**Counters over a window, never per-request rows.** A per-announce event would be a log of who
announced what and when — a record of peers and worlds by another name. `tracker.window` is one row
per interval containing totals, which answers "is the service healthy and how much traffic does it
carry" without describing anybody.

**Nothing peer-identifying, by construction.** The tracker sees source addresses, node ids, and
genesis hashes. None of them appear in the registry's `tracker.*` events, and the receiver would
refuse them anyway — the belt and the braces both hold.

**The reporter shares the maintenance tick.** The service already sweeps and logs on an interval;
the reporter rides that rather than adding a second timer with a second cadence to reason about.

**A telemetry outage is invisible.** Sends are best-effort from a bounded spool, on the service's own
task. The property that matters — a tracker outage degrades discovery only — must not gain a new
failure mode because a dashboard is unreachable.

## Files

- `tracker/src/telemetry.rs` — the reporter
- `tracker/src/config.rs` — `telemetry_endpoint`, `telemetry_interval_seconds`
- `tracker/src/service.rs` — counter access for the window

## Testing

```bash
cd rust && cargo test -p nodera-tracker      # 109 tests in the crate, telemetry included
```

- `telemetry::tests::telemetry_is_off_without_an_endpoint` — the loop returns instead of looping, so
  a default deployment spawns no work and opens no socket.
- `reporter::tests::a_window_event_renders_counters_and_labels_only` — the serialised event is
  asserted **as a string**: counters and labels, no address, no node id, no genesis hash.
- `reporter::tests::an_unreachable_collector_keeps_the_events` — a dead collector leaves the events
  queued and the error recorded; nothing panics and nothing blocks.
- `reporter::tests::the_queue_is_bounded_and_drops_the_oldest` — a long outage cannot grow memory.
- `reporter::tests::a_reporter_without_an_endpoint_is_completely_inert` — a thousand recorded events
  with no endpoint produce an empty queue.

## Acceptance criteria

1. ✅ No telemetry is emitted without an explicitly configured endpoint.
2. ✅ No emitted field can identify a peer, a world, or an address — enforced by the type of
   `ServiceEvent`, and asserted on the rendered JSON in `a_window_event_renders_counters_and_labels_only`.
3. ✅ A telemetry outage leaves announce/query behaviour unchanged: the reporter runs on its own
   task and `an_unreachable_collector_keeps_the_events` proves it neither blocks nor panics.
4. ✅ Operator documentation states what is sent and how to turn it off — [`SELF-HOSTING.md`](SELF-HOSTING.md) §3 (`NODERA_TRACKER_TELEMETRY_ENDPOINT`, "windowed counters only; never a peer, a world or an address").

## Limitations

None owned. The stack-side gaps are in [`../telemetry/LIMITATIONS.md`](../telemetry/LIMITATIONS.md).
