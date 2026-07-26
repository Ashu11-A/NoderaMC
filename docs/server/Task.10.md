# Server Task 10 — Endpoint Telemetry and the Tenant Boundary

<!-- AI-AGENT-INSTRUCTION: This task reports about the ENDPOINT NODE, never about its tenants. A
     tenant did not install NoderaMC, has no companion app, and has never been asked anything — so
     there is no consent path that could cover them, and no per-tenant value may ever be emitted.
     The consent that matters here is the SERVER OPERATOR'S, and it is off by default. The privacy
     model is ../plans/Plan.6.md. Keep this header's status accurate. -->

**Status:** ⬜ NOT STARTED
**Category:** server · **Owns:** — (L-79 RETIRED 2026-07-26) · **Last audit:** 2026-07-26
**Depends on:** [server 2](Task.2.md), [network 12](../network/Task.12.md), [telemetry 1](../telemetry/Task.1.md)
**Consumed by:** [telemetry 3](../telemetry/Task.3.md), server operators

---

## Goal

A Paper/Folia endpoint reports what it is as a **node** — throughput, region custody, tick health,
platform — so the endpoint population is visible in the same dashboards as everyone else, while the
players connected to it remain entirely outside the pipeline.

## Status detail

Not started; the whole `server` category is not started. This task is specified now so that the
tenant boundary is designed in from the first line of the plugin rather than retrofitted.

## Dependencies

- [server 2](Task.2.md) — the embedded peer whose measurements are reported.
- [network 12](../network/Task.12.md) — the emitter core, reused unchanged.
- [telemetry 1](../telemetry/Task.1.md) — the endpoint and the `endpoint.*` events.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `telemetry` block in the plugin configuration — **disabled by default** | ⬜ |
| 2 | `endpoint.window` — platform, tenant count, node-player count, TPS bucket, plugin count | ⬜ |
| 3 | Node events reused from the peer lane: `net.traffic`, `region.ownership`, `engine.*` | ⬜ |
| 4 | A tenant-boundary test suite proving no per-tenant value can be emitted | ⬜ |
| 5 | Operator documentation: what is sent, what is never sent, how to turn it on | ⬜ |
| 6 | In-server notice so operators can tell their players what the server reports | ⬜ |

## Design

**A tenant cannot consent, so a tenant is never measured.** A player who joins an endpoint with an
unmodified client has installed nothing, agreed to nothing, and has no surface on which to be asked.
The only honest position is that no value derived from an individual tenant — not a name, not a
session length, not a play time, not a count of one — ever becomes telemetry. `endpoint.window`
carries a *tenant count*, which is a property of the server, in a bucket that cannot resolve to a
person.

**The consent that exists is the operator's, and it is off by default.** Running the plugin is not
agreement to report; the operator switches it on knowing that what is sent describes their server.

**Operators must be able to tell their players the truth.** Deliverable 6 exists because a server
owner will be asked "does this send my data anywhere?" and needs an answer they can point at. The
in-server notice states exactly what the endpoint reports about itself and that nothing per-player
is included.

**Everything else is the ordinary peer lane.** An endpoint is a node; its traffic, custody, and
engine health events are the same events a player's node emits, from the same emitter core. A second
implementation would be a second place for the tenant boundary to leak.

## Files

- The plugin's telemetry adapter (path settled by [server 1](Task.1.md))
- Reuses `dev.nodera.telemetry` from [network 12](../network/Task.12.md)

## Testing

- `TenantBoundaryTest` — a run with N tenants connected emits no event whose attributes vary with
  *which* tenants they are; the only tenant-derived value is a bucketed count.
- `telemetry_is_off_until_the_operator_enables_it`.
- `an_unreachable_endpoint_does_not_affect_tenant_gameplay` — the endpoint's own version of
  `TelemetryOutageIT`.
- Registry mirror against `nodera-telemetry --print-schema`.

## Acceptance criteria

1. ✅ No per-tenant value can be emitted, proven by test rather than by review: `TenantBoundary` accepts a population count and totals and nothing else, and `TenantBoundaryTest` (7) shows two crowds of the same size emitting identical attributes.
2. ⬜ Telemetry is off until the operator turns it on.
3. ⬜ Operators have a document and an in-server notice stating what is sent.
4. ⬜ An outage of the telemetry plane is invisible to tenants and to the node.

## Limitations

**L-79 is RETIRED** (2026-07-26): the mitigation — bucketing plus a floor below which
tenant-derived counts are not reported at all — lives in `dev.nodera.telemetry.TenantBoundary`, and
its evidence is in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

**This task's obligation:** the endpoint reporter MUST build its attributes through
`TenantBoundary.endpointAttributes(...)`. A reporter that assembles its own map would not be a
regression of L-79 — it would be a new limitation, because the property L-79 retired on is
"there is no parameter that could carry an identity", and that only holds for callers that go
through the boundary.
