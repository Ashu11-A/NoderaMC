# App Task 8 — One Subject Per Screen

<!-- AI-AGENT-INSTRUCTION: Three rules this task exists to hold. (1) A screen owns ONE subject —
     node-wide facts never appear under a per-world heading, and per-world facts never appear on a
     node screen. (2) Nothing on screen may be a sentence invented in the component: if it is not a
     value the backend supplied, it is not data. (3) There is exactly ONE link readout in the app,
     in the top bar, and it states facts rather than describing a healthy state. Do not reintroduce
     a status pill. Keep this header accurate. -->

**Status:** ✅ COMPLETED
**Category:** app · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [app 6](Task.6.md), [app 7](Task.7.md)
**Consumed by:** [app 3](Task.3.md)

---

## Goal

Make every screen answer one question, out of real data, without teaching.

## What the audit found

Three agents inventoried the Java control plane, the Rust API and the screens before anything was
changed. The screen audit is what this task was built from:

- **The dashboard carried four subjects in one scroll** — transport diagnostics, node networking
  stats, two world lists, and an action surface — with worker memory arriving from a fourth IPC
  channel at the bottom.
- **Three of the world view's six tabs were not about the world.** Peers and Discovery rendered
  node-wide data; the Peers tab's own hint admitted "membership is node-wide, not per world". A card
  titled "This node's share of it" showed node-wide availability and node-wide share ratio.
- **Two vocabularies for one link state.** The top bar said `Connected`/`Alone`/`Peer offline`; forty
  pixels below, a band said `Live`/`Polling`/`Connecting`/`Peer offline`. They could read "Connected"
  and "Live" simultaneously, or disagree.
- **Values that were sentences.** "What the person on the other end gets" was four invented rows.
  The directory invented `Live game` / `Shared world` from a piece count while never showing the
  `health` the tracker actually reports. The privacy disclosure fetched the collector's schema,
  labelled the section "read from the collector", and rendered a bundled list anyway.
- **Dead surface**: four registered commands with no caller, one event nobody listened to, and
  eleven struct fields never rendered — including every `SystemStats` field except two.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Eight screens, one subject each; the rail groups node screens above app screens | ✅ |
| 2 | Settings pinned to the bottom of the rail, behind a rule, with About | ✅ |
| 3 | **Peer console** — the worker's output as a screen, with RAM / CPU / pid | ✅ |
| 4 | **About** — this build, plus a generated licence manifest for every direct dependency | ✅ |
| 5 | One link readout, stating facts; the `Live` pill and the second vocabulary removed | ✅ |
| 6 | Every invented value replaced with real data or deleted | ✅ |
| 7 | Dead commands, events and shims retired or wired | ✅ |

## The screens

| Screen | The one question it answers |
|---|---|
| Overview | What is this peer doing right now? |
| Worlds | What does this machine put on the network? (including the LAN decisions) |
| Join a world | What are other peers offering, and how do I get in? |
| Peers | Who am I connected to, and how am I findable? |
| Peer console | What is the worker saying, and what is it costing? |
| Minecraft mod | Is the mod installed, and where? |
| About | What is this build made of? |
| Settings | What do I want changed? |

A world's own page keeps three tabs — Info, Content, Sharing — and nothing node-wide. Peers and
Discovery moved to the Peers screen, which is where they were always true.

## Design

**The rail is the architecture.** Six node screens in the top group, then a rule, then About and
Settings. The separation is not decoration: everything above the rule describes the node, everything
below describes the application, and a person looking for "where do I change something" should not
have to scan past six things that are not that.

**One link readout, and it states facts.** `updated 3s ago`, and the worker version on Overview. It
adds words only when something is wrong (`Reconnecting`, or the worker's own error). A healthy link
does not need to be described, and describing it in two places is how two descriptions end up
disagreeing on screen.

**A value that is not from the backend is not data.** Rows that were sentences are deleted rather
than reworded. Where a real field existed but was unused — the tracker's `health`, the collector's
schema text, every `SystemStats` field — it is now rendered.

**Unknown stays unknown.** Carried forward from [app 6](Task.6.md) and extended to the licence list:
a package whose licence could not be read locally shows as `—`, never inferred from a sibling or
defaulted to the common one. A wrong attribution is worse than a missing one.

**The licence list is generated.** `scripts/collect-licenses.py` reads `rust/*/Cargo.toml`,
`ui/package.json` and `gradle/libs.versions.toml`, resolves each licence from the package's own
metadata (cargo registry cache, `node_modules`, cached Maven POM), and writes `licences.json`, which
`api::about` embeds with `include_str!`. Embedded rather than read at runtime so the screen cannot
show one build's dependencies while running another's. Current output: 44 direct dependencies —
15 Rust, 11 npm, 18 Java — of which 4 have no locally readable licence.

**Prose was cut, not softened.** Hints now name a unit or a source and stop. The instruction lists
("After installing", the LAN card's how-to) are gone; the consent modal keeps its bullets because a
consent dialog is the one place where telling somebody what they are agreeing to *is* the function.

## Files

- `rust/nodera-app/ui/src/{Overview,Worlds,Peers,Console,About,Network}.tsx` — new or renamed
- `rust/nodera-app/ui/src/{App,World,Lan,ModInstall,Consent,api,ipc,network}.tsx|ts` — reworked
- `rust/nodera-app/src/api/about.rs`, `rust/nodera-app/licences.json`
- `scripts/collect-licenses.py`
- Deleted: `Dashboard.tsx` (split into Overview / Worlds / Peers; its subscription hook moved to
  `api.ts`)

## Testing

- `api::about` (4) — the embedded manifest parses and is not empty; every package names itself and a
  known ecosystem; an unreadable licence stays empty rather than being guessed; the build describes
  itself from its own manifest.
- `tsc --noEmit` and the production bundle build are the frontend's gate; the screens carry no logic
  worth a unit test that is not already covered in Rust, and the values they render are asserted
  where they are produced (`api::model`, `api::store`, `api::events`).

## Acceptance criteria

1. ✅ Every screen answers one question; no node-wide data under a per-world heading.
2. ✅ Settings is at the bottom of the rail, separated from the node screens.
3. ✅ The Java peer console is its own screen and shows the worker's resident memory.
4. ✅ About lists every direct dependency with a licence read from its own metadata.
5. ✅ No value on screen is invented in the component.
6. ✅ One link readout, no `Live` pill.
