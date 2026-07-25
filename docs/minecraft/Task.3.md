# Minecraft Task 3 — Diagnostics HUD + Command Tree

<!-- AI-AGENT-INSTRUCTION: Colour is POLICY, expressed through the exhaustive Palette — never an
     ad-hoc constant at a call site. The renderers must stay dumb: they draw view models built and
     tested in the network category. A panel that computes its own state is a panel that can disagree
     with the node. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-25
**Depends on:** [network 11](../network/Task.11.md), [task 1](Task.1.md)
**Consumed by:** players, and every debugging session

---

## Goal

Give every player a live, colour-coded window into what Nodera is doing on their behalf — on native
Minecraft surfaces (tab list, boss bar, action bar) and through a discoverable command tree — without
inventing a second source of truth.

## Status detail

Complete. One pipeline: capture → telemetry snapshot → view model → surface. The `/nodera` and
`/noderac` command trees are declarative, the renderers draw view models built in the network
category, and colour comes from an exhaustive palette so adding a state forces a colour decision at
compile time.

Both data halves that once rendered as placeholders now have live providers: the entities panel and
the region-ownership panel are fed from the live lanes, with the empty placeholder rendering only when
no lane is active — which is correct behaviour rather than a gap.

Two additions earned their place by being diagnostic rather than decorative: `/nodera selftest` walks
the whole command tree per player, executes each command, validates the response, benchmarks it, and
writes a report; and a client stall reporter names the screen a stuck client is sitting on, which is
the single fact every opaque CI failure had been missing — a client boots, ticks its game loop for the
whole timeout, and never says what it is looking at.

## Dependencies

- [network 11](../network/Task.11.md) — the telemetry snapshots and view models being rendered.
- [task 1](Task.1.md) — the environment for the manual surface pass.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `Palette` + `ComponentRenderer` — colour as exhaustive policy | ✅ |
| 2 | Tab-list, boss-bar, and action-bar renderers | ✅ |
| 3 | `ZoneWatcher` + zone alerts | ✅ |
| 4 | `DiagnosticsService` — the snapshot pipeline into the mod | ✅ |
| 5 | Declarative `CommandTree` + `/nodera` and `/noderac` | ✅ |
| 6 | Live entity and region-ownership providers | ✅ |
| 7 | `/nodera selftest` — tree walk, per-command validation, benchmark, report | ✅ |
| 8 | `ClientStallReporter` — names the screen a stuck client is on | ✅ |

## Design

**One pipeline, one truth.** Surfaces that each compute their own state disagree with each other and
with the node. Everything renders the same immutable snapshot, so the tab list and the boss bar cannot
tell different stories about the same tick.

**Colour is policy, declared once.** An exhaustive semantic-to-palette mapping means a new state is a
compile error until someone decides what it looks like. It also keeps world health visually distinct
from session health — a world that lost its data colours red without turning the session indicator
yellow.

**A declarative command tree, because the tree is also a test surface.** Because the commands are
data, `/nodera selftest` can walk them, execute each one as a player, validate the response, and
benchmark it. A hand-written command tree could not be enumerated, and the self-test would have to be
maintained separately from the commands it tests.

**Diagnostics should name the thing you cannot see.** The stall reporter exists because live CI
failures were opaque in a specific way: the client was alive and ticking, so nothing looked wrong,
and the one fact that would have explained it — which screen it was sitting on — was never logged.
Logging it after ten seconds, and repeating every thirty while it does not change, turns an
unreproducible timeout into a one-line diagnosis.

**An empty panel is information.** When no validation lane is active, an empty ownership panel is
correct. The distinction between "nothing to show" and "we cannot tell" is exactly what the honest
certification states (certified / pending / solo) exist to preserve.

## Files

- `java/neoforge-mod/src/main/java/dev/nodera/mod/debug/` (palette, renderers, zone watcher,
  diagnostics service, `command/`)
- `java/neoforge-mod/src/main/java/dev/nodera/mod/client/ClientStallReporter.java`

## Testing

- Renderer and command-tree unit tests on the ordinary gate; `ClientStallReporterTest`.
- The `e2e-commands.sh` suite: two players × every command with response validation, plus the in-game
  self-test tree walk and benchmark, with per-command JSON and Markdown reports.
- Manual surface pass: colours correct on each surface, no TPS regression.

## Acceptance criteria

1. ✅ Every surface renders from one immutable snapshot per sampling tick.
2. ✅ Colour is exhaustive policy, not per-call-site constants.
3. ✅ The command tree is declarative and self-testable.
4. ✅ Live providers replace placeholder panels; an empty panel means "no lane active".
5. ✅ A stuck client reports the screen it is on.

## Limitations

None open. **L-31** (HUD placeholder panels) is RETIRED — see
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
