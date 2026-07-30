# Network Task 11 — Telemetry Core

<!-- AI-AGENT-INSTRUCTION: This task owns the MINECRAFT-FREE half of observability: meters, snapshots,
     and view models. Renderers belong to the mod. Colour is POLICY, expressed once in an exhaustive
     Palette — never an ad-hoc constant at a call site. A view model with no test is a view model that
     will lie on screen. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (renderers → [minecraft 3](../minecraft/Task.3.md), [minecraft 4](../minecraft/Task.4.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 1](Task.1.md)
**Consumed by:** [minecraft 3](../minecraft/Task.3.md), [minecraft 4](../minecraft/Task.4.md), [worker 2](../peer/Task.2.md), [app 2](../app/Task.2.md)

---

## Goal

Give every consumer — the in-game HUD, the multiplayer GUI, the worker's control endpoint, and the
companion dashboard — **one** source of truth about what this node is doing: traffic, rates, message
counts, tick skew, zones, and the per-world/per-peer view models the surfaces render.

## Status detail

Complete. One immutable `TelemetrySnapshot` per sampling tick; `TrafficMeter` via a
`MeteredPeerTransport` wrapper; per-type `MessageCounters`; `ZoneClassifier`; the
`DiagnosticsView`/`Panel`/`Row`/`Cell` view-model pattern with an exhaustive `Semantic`/`Palette`;
and the GUI view models the mod renders (`TorrentWorldListView`, `PublicWorldBadgeView`,
`PieceMapView`, `TrackerStatusView`, `RendezvousStatusView`).

`PeerTrafficMeter` gives per-peer totals and rates, which replaced hardcoded zeros in the companion's
peers panel. `TrafficDirectionSplitTest` pins that upload and download never share a field — a
symmetry seen live turned out to be real two-peer gossip rather than a display bug, and the test now
prevents that question from being asked again.

`RegionOwnership.Certification` distinguishes **CERTIFIED** (co-signed head) from **PENDING** (can
co-sign, has not yet) from **SOLO** (a committee of one), so a population shortfall reads as a
shortfall rather than a stalled commit.

## Dependencies

- [network 1](Task.1.md) — the transport the meters wrap.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `TelemetrySnapshot` — one immutable snapshot per sampling tick | ✅ |
| 2 | `TrafficMeter` + `MeteredPeerTransport` + `RateWindow` | ✅ |
| 3 | `MessageCounters` — per-type counts | ✅ |
| 4 | `PeerTrafficMeter` — per-peer totals and rates | ✅ |
| 5 | `ZoneClassifier` | ✅ |
| 6 | `DiagnosticsView`/`Panel`/`Row`/`Cell` + `Semantic`/`Palette` | ✅ |
| 7 | GUI view models (world list, badge, piece map, tracker/rendezvous status) | ✅ |
| 8 | `RegionOwnership.Certification` — CERTIFIED / PENDING / SOLO | ✅ |
| 9 | Renderers, commands, HUD | → [minecraft 3](../minecraft/Task.3.md) |

## Design

**View models are Minecraft-free so they can be tested.** A panel built inside a screen class can
only be checked by looking at it. A `DiagnosticsView` built from a `TelemetrySnapshot` is a pure
function with an assertable output, so the *content* of every surface is covered by ordinary unit
tests and the mod's job reduces to drawing it.

**Colour is policy, declared once.** An exhaustive `Semantic` → `Palette` mapping means adding a
state forces a colour decision at compile time instead of defaulting to whatever the nearest call site
used. World health deliberately has its own semantic values, distinct from session health, so a world
that lost its data colours red without turning the session indicator yellow.

**One snapshot per tick, immutable.** Surfaces that each poll their own counters disagree with each
other and with themselves between frames. A single immutable snapshot makes every surface consistent
by construction.

**Honest states, not flattering ones.** SOLO exists because a committee of one is a population
shortfall that no amount of waiting fixes; showing it as "pending" implies a stalled commit and sends
the reader looking for the wrong bug.

**Meters wrap the transport rather than instrumenting call sites.** `MeteredPeerTransport` sits behind
the same seam, so nothing needs to remember to report.

## Files

- `peer/src/main/java/dev/nodera/diagnostics/{TelemetrySnapshot,TrafficMeter,RateWindow,MessageCounters,PeerTrafficMeter,ZoneClassifier}.java`
- `peer/src/main/java/dev/nodera/diagnostics/view/`

## Testing

- View-model tests for every panel: rows, counts, health cells, deterministic order,
  case-insensitive search, pure-integer reliability formatting.
- `TorrentWorldListViewTest` — tracker rows with counts/reliability/health, countdown only while
  running, the world-health semantics distinct from session health.
- `TrafficDirectionSplitTest` — upload and download never share a field.
- `RegionCertificationTest` (8) — CERTIFIED / PENDING / SOLO.
- Meter tests with injected time; rate windows; per-type counters.

## Acceptance criteria

1. ✅ Every surface reads one immutable snapshot per sampling tick.
2. ✅ Every view model has a headless test asserting its content.
3. ✅ Colour is an exhaustive policy mapping, not per-call-site constants.
4. ✅ Region certification distinguishes a shortfall from a stall.
5. ⏳ The mod renders these models on native Minecraft surfaces
   ([minecraft 3](../minecraft/Task.3.md), [minecraft 4](../minecraft/Task.4.md)).

## Limitations

None open. **L-31** (HUD placeholder panels) is RETIRED — see
[`../minecraft/LIMITATIONS.fixed.md`](../minecraft/LIMITATIONS.fixed.md), where the rendering half
lives.
