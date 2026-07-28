# Minecraft — Category Charter

<!-- AI-AGENT-INSTRUCTION: This module WIRES; it never re-implements. Every behaviour here has a green
     headless twin in the engine or network categories — the live lane is ADAPTERS over existing seams
     (MutableWorldView, CommitListener, capture sinks, providers). If a seam is missing, add it in the
     owning category, headless-tested, then consume it here. Mixins are a LAST RESORT: events first,
     and every mixin carries a "why an event was not enough" header plus a COMPATIBILITY.md note.
     Keep the task index in agreement with ../ROADMAP.md §2. -->

**Category:** `minecraft` · **Status:** 🚧 IN PROGRESS (3 of 8 tasks completed) ·
**Last audit:** 2026-07-25

---

## 1. What this category is

**The mod — the playable product.** One jar, identical on client and (optional) dedicated server,
that turns the proven headless stacks into a game:

- capture player actions into the validated lane and apply committed deltas through the single world
  applier;
- render the Nodera-native GUI — "Open to Nodera" in the vanilla LAN slot, a Nodera-only tabbed
  multiplayer screen with live tracker and rendezvous status and a torrent piece map, public-world
  badges with live player counts;
- host worlds from the pause menu with optional password encryption;
- persist signed per-world identity and the peer permission model;
- surface live diagnostics on native Minecraft surfaces;
- and **require** the always-on peer worker, so a player's node outlives the game process.

**No dedicated server is required anywhere.** An optional dedicated server is just a well-provisioned
archival peer with one non-authoritative vote.

## 2. The rule that governs everything here

> This module wires; it never re-implements.

Every behaviour has a green Minecraft-free twin. The live lane is adapters over existing seams. When
that rule is broken, the project loses its central property: a capability proven headlessly and then
*reimplemented* live is a capability with two behaviours and no guarantee they agree.

## 3. Architecture

```
java/neoforge-mod/src/main/java/dev/nodera/mod/
├── NoderaMod.java / NoderaClientMod.java   both-dist + client entrypoints
├── common/   config, networking, attachments, adapters, NoderaPeerService,
│             NoderaHost, NoderaWorldStore, CompanionGate/Client/Link/Protocol
├── server/   ServerBootstrap + the live adapters: shadow, coordinator, commit,
│             fallback, interference  (task 2 — seams owned by engine/network)
├── client/   ClientBootstrap, share/, multiplayer/, worldlist/, worker/
├── debug/    Palette, renderers (tab list, boss bar, action bar), ZoneWatcher,
│             DiagnosticsService, the /nodera + /noderac command tree
└── mixin/    LevelChunkMixin choke point, tick suppression, world-list entry
```

Dist discipline is enforced: `net.minecraft.client.*` only under `dev.nodera.mod.client`, guarded by
`Dist.CLIENT`, with a guard test proving a dedicated server never classloads it.

## 4. Dependencies

**Depends on:** [`engine/`](../engine/Task.0.md) (the validation stack it wires live),
[`network/`](../network/Task.0.md) (the network stack it wires live),
[`tracker/Task.2.md`](../tracker/Task.2.md) and [`rendezvous/Task.2.md`](../rendezvous/Task.2.md)
(the client sides it composes), [`worker/`](../worker/Task.0.md) (the process it requires).

**Consumed by:** players. **This module delivers the live halves of the engine and network
categories** — which is why so many of their tasks carry a "live evidence pending" clause pointing
here.

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Mod skeleton, build conventions, run harness | 🚧 IN PROGRESS |
| [2](Task.2.md) | The live validation lane | ⏳ BLOCKED |
| [3](Task.3.md) | Diagnostics HUD + command tree | ✅ COMPLETED |
| [4](Task.4.md) | Multiplayer + share GUI | 🚧 IN PROGRESS |
| [5](Task.5.md) | Decentralized host lane | 🚧 IN PROGRESS |
| [6](Task.6.md) | World identity + permissions (mod half) | 🚧 IN PROGRESS |
| [7](Task.7.md) | Companion presence gate | ✅ COMPLETED |
| [8](Task.8.md) | In-game telemetry + consent mirror | ✅ COMPLETED (headless) |
| [9](Task.9.md) | Profiling lane — the spark profiler | ✅ COMPLETED |
| [10](Task.10.md) | A world is shown only when it can be played | ⬜ NOT STARTED |
| [11](Task.11.md) | The mod's GUI, rebuilt on the vanilla layout API | ⬜ NOT STARTED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests and the live suites:
[`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

## 6. Files and references

| Path | Contents |
|---|---|
| `java/neoforge-mod/` | The mod (see the tree above) |
| `java/build-logic/` | The convention plugin, including the dev-run configuration |
| `COMPATIBILITY.md` (repo root) | The normative mod-compatibility contract |
| [`RESEARCH.md`](RESEARCH.md) | The origin research: what exists, what does not, and why this design |
| [`folia/`](folia/) · [`MultiPaper/`](MultiPaper/) | Prior-art studies: regionised ticking, thread-context guards, ownership takeover, write barriers, chunk sync |
| [`spark/`](spark/) | The spark profiler (lucko): how it works, how per-mod attribution is computed, and how Nodera drives it in test runs |
| [`upstream/`](upstream/) | The upstream sources those studies were derived from — pinned submodules, see `.gitmodules` |

Package architecture: [`java/neoforge-mod/README.md`](../../java/neoforge-mod/README.md),
[`java/build-logic/README.md`](../../java/build-logic/README.md).

## 7. Conventions specific to this category

- **Events first, mixins last.** Every mixin carries a "why an event was not enough" header and a
  `COMPATIBILITY.md` note. `LevelChunkMixin` is the **only** write choke point. Minecraft/NeoForge
  version churn breaks mixins — a minimal load-bearing set is the mitigation.
- **Dist discipline is non-negotiable.** A dedicated server must never classload a client class; the
  guard test stays green.
- **The gate fails closed with an actionable message** — never a stack trace, never a silent
  no-network degrade.
- **Passwords** are never serialized, never in `toString`, never sent beyond the loopback trust
  boundary. A password change is a full re-manifest, and the UI says so.
- **Version pins:** Minecraft 1.21.1, NeoForge 21.1.238, Java 21. Re-pin in a single dedicated commit,
  never mid-task.
- **Live suites are part of the deliverable.** Run them whenever a change touches the host, join,
  lane, or continuity surfaces: the headless gate cannot see config-gated lifecycle paths, and most of
  the defects this category has found were only catchable live.
