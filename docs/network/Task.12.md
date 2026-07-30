# Network Task 12 — Telemetry Emitter Core

<!-- AI-AGENT-INSTRUCTION: This task owns the MINECRAFT-FREE half of emission: the event model, the
     bucketing, the consent gate, the bounded spool, and the sender. Two rules: (1) when consent is
     off, nothing is COLLECTED — not collected-and-dropped; (2) nothing here may block, slow, or fail
     a peer operation, ever. A telemetry call on a hot path must be a bounded, non-blocking enqueue.
     The privacy model is ../plans/Plan.6.md. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** network · **Owns:** L-76 · **Last audit:** 2026-07-28
**Depends on:** [network 11](Task.11.md), [telemetry 1](../telemetry/Task.1.md)
**Consumed by:** [worker 5](../peer/Task.5.md), [minecraft 8](../minecraft/Task.8.md), [server 10](../server/Task.10.md)

---

## Goal

One Minecraft-free library that turns the numbers [network 11](Task.11.md) already computes into
consented, bucketed telemetry events, holds them in a bounded spool, and ships them to the ingest
service — with a consent gate strict enough that "off" means *nothing is measured*, and an isolation
guarantee strong enough that the whole telemetry plane can be down, slow, or hostile without a peer
noticing.

## Status detail

Complete and green: **21 tests** (`TelemetryEmitterTest`) plus the cross-language mirror
(`TelemetryRegistryMirrorTest`, run against the real `nodera-telemetry --print-schema`).

Landed in `peer/src/main/java/dev/nodera/telemetry/`: the three-valued consent gate; a typed
`TelemetryEvent` whose only string setter is checked against `TelemetryRegistry`; `Buckets` as the
single place a measurement is coarsened; `SnapshotProjector` turning the diagnostics snapshot into
windowed `net.traffic` / `region.ownership` / `engine.tick` events; a bounded oldest-dropped spool
that survives a restart; `TelemetrySender` over `Frames`; and `InstallId`, regenerated on every
grant.

The decisive test is `consentDeniedProducesNoEventsAtAll` — a denied node builds nothing, which is
the acceptance criterion the whole design is shaped around.

## Dependencies

- [network 11](Task.11.md) — the meters and snapshots every peer event is derived from.
- [telemetry 1](../telemetry/Task.1.md) — the registry and wire contract being emitted against.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `TelemetryConsent` — a single gate object; `DENIED` short-circuits collection at the source | ✅ |
| 2 | `TelemetryEvent` + typed value setters mirroring the registry's closed domains | ✅ |
| 3 | `Buckets` — the one place a size, duration, or rate becomes a bucket index | ✅ |
| 4 | `SnapshotProjector` — `TelemetrySnapshot` → `engine.tick`/`net.traffic`/`region.ownership` | ✅ |
| 5 | `TelemetrySpool` — bounded, oldest-dropped, survives restart | ✅ |
| 6 | `TelemetrySender` — batch, frame, submit, honour the reply's refusal reasons | ✅ |
| 7 | `InstallId` — 128-bit random, stored locally, regenerated on consent grant | ✅ |
| 8 | Registry-mirror test against `nodera-telemetry --print-schema` | ✅ |

## Design

**Consent gates collection, not transmission.** A design that collects into a buffer and discards it
when consent is off is one flag away from shipping data nobody agreed to, and it means the CPU cost
of measurement is paid by people who declined. `TelemetryConsent.DENIED` makes every recording call a
no-op at the call site, so an off node builds no events at all.

**A new install id on every grant.** Turning telemetry off and on again produces a *new*
installation as far as the pipeline is concerned. This means a user who revokes consent cannot be
re-linked to their earlier reports by anything the client sends — the revocation is real at the
identifier level, not only at the transport level.

**Bucketing happens here, once.** If each call site bucketed its own values, the buckets would drift
and two events would disagree about what "large" means. `Buckets` is the only place a raw number
becomes an index, and its boundaries are the ones documented in the registry.

**The spool is bounded and drops the oldest.** Unbounded means a node that has been offline for a
month either loses its worlds to disk pressure or ships a month-old backlog the ingest service will
refuse as stale. A bounded ring, oldest-dropped, makes the failure mode "we lose old telemetry",
which is the correct thing to lose.

**Nothing on a hot path may block.** Recording is a bounded enqueue with no I/O, no lock a game
thread can contend on, and no allocation proportional to anything. Sending happens on the worker's
own scheduler, never on a tick.

**Refusal reasons are logged, not retried blindly.** When ingest reports `unknown_event` or
`bad_value`, the emitter is wrong and a retry loop would spend a node's bandwidth being wrong faster.
The reasons are surfaced to the operator and the batch is dropped.

**Sampling is per-event-type and declared.** High-frequency events (`engine.tick`, `net.traffic`)
report one windowed summary per interval rather than per occurrence; rare, high-value events
(`engine.divergence`, `error.report`) are never sampled.

## Files

- `peer/src/main/java/dev/nodera/telemetry/{TelemetryConsent,TelemetryEvent,Buckets,SnapshotProjector,TelemetrySpool,TelemetrySender,InstallId}.java`
- Tests alongside, in `peer/src/test/java/dev/nodera/telemetry/`

## Testing

- `TelemetryConsentTest` — with consent denied, a projector run produces **zero** events and touches
  no meter (proven with a recording double, not by inspecting output).
- `BucketsTest` — boundary values land in the documented bucket; a value outside the range is
  clamped to the outermost *bucket*, never to a fabricated measurement.
- `TelemetrySpoolTest` — bound respected, oldest dropped, contents survive a restart.
- `TelemetrySenderTest` — batches are framed correctly; a refusal reply is logged and not retried.
- `TelemetryRegistryMirrorTest` — every event and attribute this module can emit exists in
  `nodera-telemetry --print-schema`, with the same value kinds. The cross-language mirror that stops
  the two sides drifting, in the same spirit as the wire tag mirror.
- `InstallIdTest` — a consent revoke-and-grant cycle yields a different id.

## Acceptance criteria

1. ✅ Consent denied ⇒ no event object is ever constructed.
2. ✅ Every emitted event validates against the shipped registry (mirror test green).
3. ✅ The spool is bounded and drops oldest-first.
4. ✅ Recording never blocks, allocates unboundedly, or touches disk on a caller thread.
5. ✅ A revoke/grant cycle produces a new install id.

## Limitations

Owns **L-76**, now **RETIRING** rather than retired. The emitter exists and is proven headlessly,
but the row's exit clause asks for evidence *from the wild* — which needs a population that has
opted in. It retires when the first dashboard answers a [`Plan.6`](../plans/Plan.6.md) §1.1 question
from real reports.
