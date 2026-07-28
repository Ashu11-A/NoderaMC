# Tracker Task 2 — The Java Client: Announce Family + `TrackerClient`

<!-- AI-AGENT-INSTRUCTION: A tracker answer is a HINT. The client merges results from every configured
     endpoint and never arbitrates between them; it must degrade cleanly when an endpoint is
     unreachable. Do not add a "primary tracker" concept. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (periodic announce scheduling lives in [worker 3](../worker/Task.3.md))
**Category:** tracker · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [tracker 1](Task.1.md), [network 5](../network/Task.5.md)
**Consumed by:** [minecraft 4](../minecraft/Task.4.md), [worker 3](../worker/Task.3.md), [app 2](../app/Task.2.md)

---

## Goal

The peer side of the tracker: the announce message family, a client that announces to **every**
configured endpoint and merges query results, and the directory and route queries the multiplayer GUI
needs to actually join a listed world.

## Status detail

Complete. `TrackerClient` announces to every configured endpoint (a flat multi-tracker list with
merged query results and backoff on unreachable endpoints) and exposes the query API. Endpoints are
**scheme-aware** (`tcp://`, `udp://`; a bare host stays TCP) with a real UDP datagram path and a TCP
fallback when a UDP answer would exceed the bound.

Two additions made joining real rather than theoretical: a **directory** query
(`TrackerCatalogQuery`/`Response`) so the GUI can list worlds it has never seen, and a **full-route**
query (`TrackerRoutesQuery`/`Response`) so a row can be resolved to a live endpoint. A host's announce
carries its open Minecraft endpoint as an `mc/host:port` route claim.

The announce **loop** is constructed here but scheduled elsewhere: it belongs in the always-on worker
([`worker/Task.3.md`](../worker/Task.3.md)), so a host's world stays listed with Minecraft closed. A
tracker endpoint is also bootstrap mechanism #4 for [`network/Task.5.md`](../network/Task.5.md).

## Dependencies

- [tracker 1](Task.1.md) — the service being spoken to.
- [network 5](../network/Task.5.md) — the discovery plane this feeds and draws from.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `TrackerAnnounce` / `TrackerAnnounceAck` (appended tags) | ✅ |
| 2 | `TrackerClient` — announce to all endpoints, merged queries, backoff | ✅ |
| 3 | Scheme-aware endpoints with a UDP path and TCP fallback | ✅ |
| 4 | `TrackerCatalogQuery`/`Response` — the world directory | ✅ |
| 5 | `TrackerRoutesQuery`/`Response` — resolve a row to a live endpoint | ✅ |
| 6 | Retention countdown carried on every announce | ✅ |
| 7 | Announce loop scheduled on a timer | → [worker 3](../worker/Task.3.md) |
| 8 | GUI rows rendered from real responses | → [minecraft 4](../minecraft/Task.4.md) |

## Design

**Flat list, merged results, no primary.** Configuring a "primary" tracker would reintroduce a single
point of failure and invite the client to trust one answer over another. Announcing to all and merging
queries means an operator can add redundancy by adding a line to a config file.

**Backoff, not removal.** An unreachable endpoint is backed off rather than dropped, because the
common cause is a transient outage, and silently forgetting an operator's tracker is a surprising
behaviour to debug.

**Scheme-aware endpoints, with the bare form unchanged.** Adding UDP could have broken every existing
configuration. A bare host still means TCP; `udp://` opts in. When a UDP answer would exceed the
amplification bound the service stays silent and the client falls back to TCP — silence is a
recoverable signal, a truncated answer is a corrupt one.

**Directory and routes are separate queries on purpose.** Listing worlds is cheap and broad; resolving
a route is narrow and only needed for the row a player actually clicked. Splitting them keeps the
common case light.

**The world's *content* and the world's *game endpoint* are different things.** The `mc/host:port`
route claim is how a listed torrent world becomes joinable while a live host exists; the content plane
is what makes it recoverable when one does not.

## Files

- `java/transport/src/main/java/dev/nodera/protocol/discovery/{TrackerAnnounce,TrackerAnnounceAck,TrackerRoutesQuery}.java`
- `java/peer/src/main/java/dev/nodera/peer/discovery/TrackerClient.java` — announce/query/catalog/routes, plus `serviceDirectory`/`reportServiceScores` (Task 5) and `onDeletionNotice`/`publishDeletion`
- Mod config: `tracker.endpoints = []` in both TOML files

## Testing

- `TrackerServiceIT` — the client against the real binary.
- `TrackerEndpointTest` — scheme parsing, defaults, and the bare-host contract.
- `TrackerClientUdpTest` — the datagram path and the TCP fallback.
- Merge tests: results from several endpoints combine without arbitration; an unreachable endpoint
  backs off without failing the query.

## Acceptance criteria

1. ✅ Announces reach every configured endpoint; queries merge without arbitration.
2. ✅ An unreachable endpoint backs off and does not break discovery.
3. ✅ Scheme-aware endpoints work over TCP and UDP with a correct fallback.
4. ✅ A listed world can be resolved to a live route.
5. ⏳ The loop runs on its ack-paced timer from the worker ([worker 3](../worker/Task.3.md)) and rows
   render live in the GUI ([minecraft 4](../minecraft/Task.4.md)).

## Limitations

None owned. The unscheduled-announce gap is tracked with the mod's live GUI pass
([`minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md)).
