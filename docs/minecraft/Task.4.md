# Minecraft Task 4 — Multiplayer + Share GUI

<!-- AI-AGENT-INSTRUCTION: The screens RENDER view models built and tested in the network category —
     do not compute list state, health, or search results in a screen class. "Open to Nodera" REPLACES
     the vanilla LAN slot rather than sitting beside it. A row a player can see but cannot join is not
     a feature; the join flow is part of this task, not a follow-up. Keep this header's status
     accurate. -->

**Status:** 🚧 IN PROGRESS (built and wired; live GUI acceptance pending)
**Category:** minecraft · **Owns:** L-43, L-46 · **Last audit:** 2026-07-28
**Depends on:** [task 1](Task.1.md), [tracker 2](../tracker/Task.2.md), [rendezvous 2](../rendezvous/Task.2.md), [worker 2](../peer/Task.2.md)
**Consumed by:** players

---

## Goal

The player-facing surface of the decentralized hosting model: a Nodera-native multiplayer screen that
**replaces** vanilla surfaces rather than sitting beside them — a world list with search, health
colours and retention countdowns, live tracker and rendezvous status, a torrent piece map, a
public-world badge with live player counts, and a create-world flow with a torrent-hosting toggle and
password.

And, decisively: a **join flow** that turns a listed world into a session.

## Status detail

Built and wired. The title screen's Realms slot is now "Nodera Network"; the multiplayer screen is a
tabbed Nodera-only surface (Worlds, Trackers, Rendezvous) with a scrollable list, search, health rows,
and a status footer; the Worlds tab is the worker's hosted worlds ∪ the tracker directory; the piece
map renders a real grid from the worker's piece bitmap, with scrolling (it previously stopped drawing
at the bottom edge, saying nothing); create-world parks share options that are consumed on the new
world's first start.

The **join flow** exists end to end: a row resolves through a route query to a live game endpoint and
hands off to the vanilla connect screen, with explicit error screens for a host that is offline or a
bad route. When a listed world has seeders but no live endpoint, it is **materialised from the peer
network** instead of showing a dead end.

All four data feeds are live-wired: worlds, trackers, rendezvous, and the single-player world-list
badge, which reads the same worker state the multiplayer tab uses.

**Remaining:** the presentation itself is unverified in a GUI environment — the LAN-slot placement,
the per-row badge geometry, the tab layout, and the piece map filling green as pieces arrive. That is
**L-46**, and it exits with [task 1](Task.1.md)'s harness. The tracker feed also wants the announce
loop on a timer, which moves into the worker.

## Dependencies

- [task 1](Task.1.md) — the GUI environment for acceptance.
- [tracker 2](../tracker/Task.2.md) — the directory and route queries.
- [worker 2](../peer/Task.2.md) — hosted worlds, piece bitmaps, and status.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | "Open to Nodera" in the vanilla LAN slot | ✅ built |
| 2 | Tabbed Nodera-only multiplayer screen (Worlds / Trackers / Rendezvous) | ✅ built |
| 3 | Scrollable world list with search, health rows, and status footer | ✅ built |
| 4 | Worlds tab = worker hosted worlds ∪ tracker directory | ✅ |
| 5 | Piece-map widget with a real source and scrolling | ✅ |
| 6 | Public-world badge with a live player count | 🚧 (screen-level summary; per-row needs a mixin — [task 6](Task.6.md)) |
| 7 | Create-world torrent toggle + independent password field | ✅ built |
| 8 | Join flow: row → route resolve → connect, with honest errors | ✅ |
| 9 | Network-first materialisation when a world has seeders but no host | ✅ |
| 10 | Live GUI acceptance pass | 🚧 (**L-46**) |

## Design

**Replace, do not append.** A Nodera button beside the vanilla server list would present the
decentralized model as an add-on. Taking the LAN slot and the Realms slot states the model plainly:
this is how you play with others here.

**A row you cannot join is a list, not a feature.** The join flow is part of this task because the
alternative — a beautiful list with a dead end — was the actual state of the world for a while, and it
read as "broken" to anyone who tried it.

**Network-first, not host-first.** A listed world with seeders but no live game endpoint is
*materialised from the peer network* rather than refused. The remaining "connect to whoever currently
runs the integrated server" hop is interim scaffolding over the vanilla session protocol; the model it
converges to is region-committee ownership, at which point no player is "the" server at all.

**Screens draw, they do not decide.** List state, search, health semantics, and the piece grid are all
view models built and unit-tested in the network category. That is why the GUI gap is about
*presentation* — placement, geometry, colour on screen — and not about correctness.

**Say what is wrong.** A host that is offline, a bad route, or a world with no seeders each get an
explicit terminal screen. A silent failure in a join flow is indistinguishable from a broken network.

## Files

- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/`
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/worldlist/`
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/share/`

## Testing

- View models are tested headlessly in the network category; the screens are compile-clean against the
  pinned NeoForge and covered by the mod's own unit tests where they hold logic (piece parsing, feed
  assembly).
- `WorkerPiecesParserTest`, `PieceMapFeedTest`.
- Live: the GUI acceptance pass rides [task 1](Task.1.md)'s harness — one environment unlocks this
  task, [task 3](Task.3.md)'s surface pass, [task 5](Task.5.md), and [task 6](Task.6.md) together.

## Acceptance criteria

1. ✅ The multiplayer page lists worlds with counts, health, and retention countdowns.
2. ✅ The join flow works end to end, with honest errors.
3. ✅ The piece map has a real source and scrolls.
4. 🚧 One "Open to Nodera" button occupies the LAN slot, the world list shows a live public count, the
   three tabs work, and the piece map fills green as pieces arrive — in a real client (**L-43**,
   **L-46** exits).

## Limitations

- **L-43** — the multiplayer GUI's live pass (RETIRING).
- **L-46** — piece map, tabs, and badge unverified live (OPEN).

See [`LIMITATIONS.md`](LIMITATIONS.md).
