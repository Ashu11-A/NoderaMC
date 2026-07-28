# Minecraft Task 10 — A world is shown only when it can be played

<!-- AI-AGENT-INSTRUCTION: Two rules this task exists to preserve. (1) Nothing that can block —
     a socket connect, a tracker query, a relay reservation, a companion exchange — runs on the
     client render thread or the server main thread. (2) A world appears in the list because it is
     joinable, not because it is known. A change that re-introduces either is a regression however
     small it looks. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** minecraft · **Owns:** MC-JOIN-1 … MC-JOIN-6 · **Last audit:** 2026-07-28
**Depends on:** [minecraft 6](Task.6.md), [minecraft 7](Task.7.md), [worker 8](../worker/Task.8.md)
**Consumed by:** [minecraft 11](Task.11.md)

---

## Goal

A player opens the world list, sees only worlds that are actually joinable, clicks one, and either
enters it or is told why not — with the client responsive throughout. Player actions reach the
network on a default install instead of being discarded by a lane that is off.

## Status detail

**The reported "endless Migrating world…" is closed** (see `PROGRESS.md`,
2026-07-27). It was not a hang: a password-gated refusal was being misread as a dead host, and the
recovery screen it opened was unescapable, showed one hardcoded line for the whole fetch, and
blamed a missing seeder that was in fact seeding. Root cause was an event-ordering collision —
continuity replaces the disconnect screen on `Opening`, the password prompt attaches on
`Init.Post`. Proven by `ClientJoinPasswordsGateMarkerTest`; the live re-run is the remaining exit.

Opened 2026-07-27 from a functional audit. Three findings dominate.

**The capture lane is off by default.** `entity.laneAutoActivate` defaults `false`, and
`LiveEntityLaneRuntime.install()` is the only thing that installs `BlockCaptureBridge.Sink` and
`EntityCaptureBridge.Runtime`. With the flag off, both bridges sit on their `DISABLED` instances,
which answer every method by doing nothing. Block places and breaks, drops, pickups and mob ghosts
are all silently discarded. Everything else in this file is downstream of that.

**Turning it on is not enough**, because the first non-zombie entity permanently kills the region:
`entity.mobCaptureSpecies` defaults to `["minecraft:zombie"]` and any other mob, arrow, XP orb, boat
or item frame joining a region triggers `revokeForEntity`, which removes the region and releases its
chunk ticket. Nothing re-adds it. In a real world every region is revoked within seconds.

**Client and server derive different ownership plans.** The server plans with the resident worker
seats; the client plans without them, because `NoderaLanePlanPayload` carries no resident list.
Primaries agree, validator sets do not — which is the mechanical cause of the
"worker-held replicas: 0 / votes_cast=0" symptom, and it cannot be fixed on the client alone.

**Nothing anywhere expresses readiness.** There is no state enum, no `ready` flag, no published
transition. `MultiplayerWorldFeed.buildEntries` hardcodes `WorldHealth.HEALTHY` and
`reliability = 10_000` for the node's own worlds — even when `mc_route` is empty and the world has
never been certified — and the join button's `active` is set from "a row is selected". A world that
cannot be entered is offered exactly as confidently as one that can, which is what puts players on a
loading screen that never resolves.

## Dependencies

- [worker 8](../worker/Task.8.md) — a stability gate is meaningless while one world has several ids.
- [minecraft 7](Task.7.md) owns the companion gate whose `required=true` default is listed below.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | A readiness predicate — published ∧ reachable route ∧ certified — computed in one place | ⬜ |
| 2 | The world list filters on it; the join button gates on it | ⬜ |
| 3 | `buildEntries` reports the health it can prove, not `HEALTHY` unconditionally | ⬜ |
| 4 | Peer bring-up moves off the client render thread (`onServerSessionInfo`) | ⬜ |
| 5 | Host activation moves off the server main thread (`NoderaHost.activate`) | ⬜ |
| 6 | Every waiting screen has a working escape and a deadline | ✅ (`RehostScreen`) |
| 7 | The capture lane is on by default, with a species/dimension policy that does not revoke | ⬜ |
| 8 | `NoderaLanePlanPayload` carries residents, so both sides plan the same committee | ⬜ |
| 9 | Every `Server*Event` / `Client*Event` listener self-catches | ⬜ |

## Design

### Where the loading screen hangs

`ModNetworking` hops `onServerSessionInfo` onto the client main thread and then runs the entire peer
bring-up synchronously: a companion exchange (1.5 s connect + 1.5 s read), a relay reservation
iterating **every** endpoint at 5 s connect / 10 s read, a second pass to register, then the
bootstrap dial. With three unreachable relays configured that is roughly ninety seconds of a frozen
render thread while the player looks at "Joining world…". The host side pays the same bill on the
server thread during world load, plus a `Files.walk` of the whole save.

The fix is not shorter timeouts. It is that none of this belongs on a thread that has to paint.

`RehostScreen` compounded it, and was the reported symptom: `init()` added a Back button only when
`failure != null`, `shouldCloseOnEsc()` agreed, and `render()` drew a hardcoded "Migrating world…"
while the `status` field the fetch thread updates went unread. Fixed — the button is always there
(Cancel while working, Back after a failure), Esc always closes, the step is on screen, and a cancel
is honoured by the fetch thread instead of opening a world the player walked away from.

### Why the gate goes in the feed, not in the tracker

The tracker deliberately lists every tracked world, including one whose every seeder has gone silent;
that is the right behaviour for a directory, and peers verify what a service says rather than trusting
it. Readiness is therefore a **client-side** judgement, and `MultiplayerWorldFeed.snapshot()` is the
single point where the node's own worlds and the tracker catalog merge. All three inputs the
predicate needs already ride the `NODERA-STATE` JSON. No wire change is required for the local gate;
a network-wide readiness bit on the announce is a later, separate decision.

### The capture defaults

`mobCaptureDimensions=[]` and `mobCaptureSpecies=["minecraft:zombie"]` are test-shaped defaults that
became the shipped ones. Widening them is not enough on its own — the revocation path has to stop
being the response to an unrecognised entity, or every widening simply moves which mob kills the
region first.

## Files

| Path | Role |
|---|---|
| `.../client/multiplayer/MultiplayerWorldFeed.java` | the merge point; the gate goes here |
| `.../client/multiplayer/NoderaMultiplayerScreen.java` | join-button enablement |
| `.../common/NoderaHost.java` | host activation, ownership planning |
| `.../common/NoderaPeerService.java` | transport composition, announce |
| `.../common/ModNetworking.java` | session payload handling |
| `.../client/multiplayer/NoderaContinuity.java` | rehost screen and its deadline |
| `.../common/NoderaConfig.java` | lane and capture defaults |
| `.../server/ServerBootstrap.java`, `.../common/EntityCaptureBridge.java` | unguarded listeners |

## Testing

| Test | Proves |
|---|---|
| A headless `MultiplayerWorldFeed` test over crafted STATE JSON | deliverables 1–3 |
| `scripts/e2e-live.sh` with one relay pointed at a black hole | deliverable 4: the client stays responsive |
| A live run with the lane on and a cow in the region | deliverable 7 |
| `ClientJoinPasswordsGateMarkerTest` (3, green) | deliverable 6 — a refused join is distinguishable from a lost host, including when the password was merely wrong |

## Acceptance criteria

- [ ] A world whose host game is closed is not offered as joinable.
- [ ] With every configured relay unreachable, the client never blocks for more than one frame.
- [x] Every screen a player can reach while waiting can be left.
- [ ] A join refused at the password gate reaches the password prompt (headless proof green; live
      re-run outstanding).
- [ ] A default install captures block edits.
- [ ] A region survives a passive mob.

## Limitations

| Id | Statement | Exit test |
|---|---|---|
| MC-JOIN-1 | No readiness concept exists; every known world is offered | deliverable 1 |
| MC-JOIN-2 | Peer bring-up blocks the client render thread | deliverable 4 |
| MC-JOIN-3 | Host activation blocks the server main thread | deliverable 5 |
| MC-JOIN-4 | `RehostScreen` cannot be left while fetching | deliverable 6 |
| MC-JOIN-5 | Capture is off by default and revokes regions on unlisted entities | deliverable 7 |
| MC-JOIN-6 | Client and server derive different committee membership | deliverable 8 |
