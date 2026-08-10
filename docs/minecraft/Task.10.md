# Minecraft Task 10 — A world is shown only when it can be played

<!-- AI-AGENT-INSTRUCTION: Two rules this task exists to preserve. (1) Nothing that can block —
     a socket connect, a tracker query, a relay reservation, a companion exchange — runs on the
     client render thread or the server main thread. (2) A world appears in the list because it is
     joinable, not because it is known. A change that re-introduces either is a regression however
     small it looks. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** minecraft · **Owns:** MC-JOIN-1 … MC-JOIN-6 · **Last audit:** 2026-08-10
**Depends on:** [minecraft 6](Task.6.md), [minecraft 7](Task.7.md), [worker 8](../peer/Task.8.md)
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

> **Half of deliverable 7 landed on 2026-07-29 in `f4ad09e`, and the decision behind it was
> confirmed on 2026-08-06 in [#236](https://github.com/Ashu11-A/NoderaMC/issues/236).**
> `revokeForEntity` no longer has a call site, and the method itself is gone: an entity this build's
> engine does not model is **left to vanilla** in `EntityCaptureBridge.captureJoin` — not captured,
> not refused — and the region goes on validating blocks and modelled entities. Determinism is
> untouched, because the capture list is config and identical on every node, so no committee member
> captures it either; blocks such an entity changes still arrive through `BlockWriteGuard`'s CONVERT
> mode. The whole refusal lane retired with it: `ObserverRefusals` is deleted and
> `RegionRefusal.Reason.NON_DELEGABLE_ENTITY` (wire code 1) is reserved with no sender. **Still
> open in this deliverable:** the defaults themselves — `mobCaptureDimensions=[]` and
> `mobCaptureSpecies=["minecraft:zombie"]` are the test-shaped defaults described below, and the
> acceptance criterion "a region survives a passive mob" wants a live run of the rewritten
> `MobsScenario` G2b to close.

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

- [worker 8](../peer/Task.8.md) — a stability gate is meaningless while one world has several ids.
- [minecraft 7](Task.7.md) owns the companion gate whose `required=true` default is listed below.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | A readiness predicate — published ∧ reachable route ∧ certified — computed in one place | ⬜ |
| 2 | The world list filters on it; the join button gates on it | ⬜ |
| 3 | `buildEntries` reports the health it can prove, not `HEALTHY` unconditionally | ⬜ |
| 4 | Peer bring-up moves off the client render thread (`onServerSessionInfo`) | 🚧 landed 2026-08-10 (#167): the hand-off is thread-affine work only and the rest runs on `nodera-client-bringup`, cancellable by generation. Proven headlessly (5 tests) and by `ContinuityScenario` S2d; the black-holed-relay run itself is still a harness gap |
| 5 | Host activation moves off the server main thread (`NoderaHost.activate`) | ⬜ |
| 6 | Every waiting screen has a working escape and a deadline | ✅ (`RehostScreen`) |
| 7 | The capture lane is on by default, with a species/dimension policy that does not revoke | 🚧 the *does not revoke* half landed 2026-07-29 (#236); the defaults and the live run remain |
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

**The client half landed 2026-08-10 (#167).** `handleSessionOnClient` now keeps only what has to be
on the client thread — the `isHosting()` guard and reading `context.player()`'s UUID — and
`NoderaPeerService.onServerSessionInfo` records the session's world id, bootstrap route and freshly
generated identity under the monitor before handing everything else to a `nodera-client-bringup`
daemon thread, copying `NoderaHost.armJoinGate`'s shape. Three consequences worth knowing:

- **The announce moved with it.** It needs a live runtime, so it is posted from the bring-up thread
  once one exists. `IPayloadContext.reply` sends on the listener the handler was invoked with, which
  is not what a thread running after the handler returned should be holding, so the announce goes
  through `PacketDistributor.sendToServer` — `NoderaNodeAnnouncePayload` is registered
  `playToServer`, which is what makes that direction legal.
- **Cancellation is by generation, not by the monitor.** `stopClient` bumps a counter and returns;
  the in-flight bring-up notices at its next checkpoint and discards what it built. Guarding with
  the monitor would have moved the stall from the render thread to the disconnect path.
- **The hand-off measures itself.** It logs the microseconds it held the client thread, which is the
  quantity the acceptance criterion bounds, and is what `ContinuityScenario` S2d asserts.

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

That second half is done (2026-07-29, `f4ad09e`; decision recorded in #236): an unrecognised entity
is left to vanilla instead of refusing the region, so widening the defaults is now a safe change
rather than a change of which mob kills the region first. The defaults themselves are untouched.

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
| `ClientBringUpIsOffTheRenderThreadTest` (3, green) | deliverable 4: the hand-off returns while the bring-up is still inside its slow step, on `nodera-client-bringup` and not on the caller |
| `NoderaPeerServiceBringUpCancellationTest` (2, green) | deliverable 4: a `stopClient` mid-bring-up leaves no runtime, transport, identity or delegation behind, and does not wedge the next join |
| `ContinuityScenario` S2d (live; **not yet run** — the box could not host it) | deliverable 4: player B's client thread is held for less than one tick by the session hand-off |
| A live run with the lane on and a cow in the region | deliverable 7 |
| `MobsScenario` G2b (rewritten 2026-08-06, **not yet run live**) | deliverable 7's refusal half: an unmodelled species is left to vanilla and the region keeps validating |
| `ClientJoinPasswordsGateMarkerTest` (3, green) | deliverable 6 — a refused join is distinguishable from a lost host, including when the password was merely wrong |

## Acceptance criteria

- [ ] A world whose host game is closed is not offered as joinable.
- [ ] With every configured relay unreachable, the client never blocks for more than one frame.
      *(The bring-up is off the client thread and asserted there headlessly and live; what is not yet
      reproduced is the unreachable relay set — see MC-JOIN-2's row for the harness gap.)*
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
