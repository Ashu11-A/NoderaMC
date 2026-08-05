# Worker Task 5 — The Node's Single Telemetry Emitter

<!-- AI-AGENT-INSTRUCTION: This task makes the worker the ONE emitter for a player's node. Do not add
     a second sender in the mod or the app — they hand events to the worker. Two rules: (1) the
     worker never sends without a consent value it received explicitly; (2) an unreachable telemetry
     endpoint must be invisible to hosting, seeding, and validation. The privacy model is
     ../plans/Plan.6.md. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** worker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 12](../network/Task.12.md), [worker 2](Task.2.md), [telemetry 1](../telemetry/Task.1.md)
**Consumed by:** [frontend 5](../frontend/Task.5.md), [minecraft 8](../minecraft/Task.8.md), [telemetry 3](../telemetry/Task.3.md)

---

## Goal

The always-on worker becomes the single place a player's node measures itself and talks to the
telemetry service: it holds the consent decision, collects from the running peer, accepts events
handed to it by the mod and the app, batches, sends, and reports its own status back over the control
protocol.

## Status detail

Complete and green: `WorkerTelemetryServiceTest` (7) and `TelemetryVerbIT` (4, over the real control
socket), plus `scripts/e2e-telemetry.sh`, which drives the actual worker distribution against the
actual collector binary.

Landed: `WorkerTelemetryService` (consent record, scheduled windowed collection, jittered batching,
bounded spool, best-effort send); `TelemetryConsentStore` beside the worker's identity;
`NODERA-TELEMETRY GET | SET | EVENT`; the `telemetry` block in `NODERA-STATE`; and
`NODERA_TELEMETRY_ENDPOINT` / `_INTERVAL_SECONDS` / `_DIR`, all empty by default.

The isolation property has live evidence: suite step **T6** kills the collector, then compares the
worker's whole `NODERA-STATE` answer field by field — everything except the telemetry block and the
clock is unchanged, and `PROBE`/`IDENTITY` keep answering.

## Dependencies

- [network 12](../network/Task.12.md) — the emitter core it drives.
- [worker 2](Task.2.md) — the control protocol v2 this extends.
- [telemetry 1](../telemetry/Task.1.md) — the endpoint it submits to.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `NODERA-TELEMETRY` control verb: `GET` (status) / `SET <granted\|denied>` / `EVENT <base64 json>` | ✅ |
| 2 | Consent persisted beside the worker's identity; **absent ⇒ denied** | ✅ |
| 3 | Scheduled collection: windowed `engine.tick` / `net.traffic` / `region.ownership` | ✅ |
| 4 | Event intake from the mod and the app (they never send to the network themselves) | ✅ |
| 5 | Batch + send on a jittered interval; spool while offline | ✅ |
| 6 | `telemetry` block in the `NODERA-STATE` JSON: consent, queued, sent, last error | ✅ |
| 7 | `telemetry_endpoint` configuration, empty by default | ✅ |
| 8 | The outage lane — the whole plane down changes nothing observable (`e2e-telemetry.sh` T6) | ✅ |

## Design

**One emitter per node, and it is the worker.** The mod, the app, and the worker all observe things
worth reporting. Three emitters would mean three consent checks, three spools, and three chances to
disagree about whether the user said yes — and the mod's and app's lifetimes are both shorter than
the node's, so events from a closed game would simply be lost. The worker outlives both.

**Absent consent is denied consent.** A worker that has never been told anything sends nothing. This
makes the default safe for every path that reaches a worker without the app: a manual launch, a
container, a `scripts/dev.sh` run, an upgrade from a version that predates telemetry.

**Consent lives with the worker, not with the app.** The app is a UI over a node that runs without
it. Storing the decision in the app would mean a headless node's consent state depended on whether a
window had been opened, which is exactly the kind of ambiguity a consent record must not have.

**A jittered send interval.** Every node sending on the minute produces a synchronised thundering
herd against one ingest service, and a traffic pattern that is itself a fingerprint.

**Sending is best-effort and invisible.** A failed submit increments a counter and is surfaced in
`NODERA-STATE`; it never retries in a way that competes with piece transfer, never logs at a level
that fills an operator's console, and never affects a hosting or validation decision.

**Revocation takes effect at collection.** `SET denied` stops the collectors, clears the spool, and
drops the install id — not just the sender. A user who turns telemetry off has stopped being
measured, not stopped being uploaded.

## Files

- `peer/src/main/java/dev/nodera/headless/WorkerTelemetryService.java`
- `peer/src/main/java/dev/nodera/telemetry/TelemetryConsentStore.java` (the consent record lives beside the worker's identity but in the `:peer` telemetry package)
- `peer/src/main/java/dev/nodera/peer/control/ControlProtocol.java` (the `TELEMETRY` verb)
- `peer/src/main/java/dev/nodera/headless/WorkerControlHandler.java` (state block, verb handling)

## Testing

- `TelemetryVerbIT` — `GET` before any `SET` reports denied; `SET granted` then `GET` round-trips;
  an unknown argument is refused without changing state.
- `WorkerTelemetryServiceTest` — with consent denied, no collector runs and the spool stays empty.
- `TelemetryOutageIT` — with the endpoint pointed at a closed port for the whole run, a world is
  hosted, joined, seeded, and validated with **byte-identical** outcomes to the same scenario with
  telemetry disabled. The decisive test for `../plans/Plan.6.md` D10.
- `TelemetryRevocationTest` — after `SET denied`, the spool is empty and the stored install id is
  gone.
- `WorkerStateParserTest` extension — the `telemetry` block parses on the mod side.

## Acceptance criteria

1. ✅ A worker that was never asked sends nothing (suite step T1: two windows, empty spool).
2. ✅ Consent survives a worker restart and is readable by the app and the mod.
3. ✅ Revocation stops collection, clears the spool, and drops the install id (T5).
4. ✅ An unreachable endpoint is invisible to the node (T6, the outage lane).
5. ✅ The mod and the app have no network path to the telemetry service of their own.

## Limitations

**L-77 is RETIRED** — the worker records a consent decision, it survives a restart, and the outage
lane is green. Moved to [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) with the suite step names as
its evidence.
