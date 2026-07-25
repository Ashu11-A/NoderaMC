# Server — Category Charter

<!-- AI-AGENT-INSTRUCTION: This category is the PAPER/FOLIA PLUGIN, not a server fork and not a second
     peer implementation. It WIRES: every behaviour here has a green Minecraft-free twin in engine /
     network / core, and the plugin is adapters over existing seams. Three rules govern everything and
     must be refused if violated: (1) no second region engine in any language; (2) Nodera regions stay
     8×8 and static — Folia's dynamic regions are EXECUTION units, never authority units; (3) capacity
     never buys authority. The architecture and the locked decisions live in
     ../plans/Plan.5.md — read it before editing any task file here. Keep the task index in agreement
     with ../ROADMAP.md §2. -->

**Category:** `server` · **Status:** ⬜ NOT STARTED (0 of 9 tasks completed) ·
**Last audit:** 2026-07-25

---

## 1. What this category is

**NoderaEndpoint** — one Bukkit plugin that turns a **Paper** or **Folia** server into a Nodera node
that is *also* a Minecraft server.

Two roles, independent, either or both:

- **Endpoint.** It speaks the vanilla Minecraft protocol on a normal port, so a player who has
  installed nothing adds it to their server list and joins. The world they land in is a Nodera world.
- **Primary host.** It runs the real `dev.nodera.peer.PeerRuntime` **in-process**, joins the world's
  session, holds committee seats, seeds the archive, and holds **100 % of the world's chunks** at
  their current certified heads.

It is the project's answer to the population it currently cannot reach: everyone who already runs
Paper or Folia, with plugins, communities, and hardware, and who is not going to ship a client mod to
their players to get there.

## 2. The assumption this category changes

[`docs/README.md`](../README.md) §1 states **A0**: *every player runs the Nodera mod and joins as a
network peer; there is no vanilla-client population.* This category replaces it with **A0′**:

> Every **node** runs the Nodera peer and is a first-class member. A **player** is not necessarily a
> node. A player who runs the mod is their own node; a player who does not is a **tenant** of a node
> that is one — the endpoint they connected to. There is still exactly one validated lane: a tenant's
> action enters it as an `ActionEnvelope` proposed and signed by the endpoint's node, on the tenant's
> behalf.

Validation is untouched. The engine is still the one engine, the committee still re-executes, and no
unmodified client ever holds a vote — or needs one.

What a tenant gives up is stated, not hidden: they cannot verify anything, and they trust their
endpoint the way a player trusts any Minecraft server today. The burn-down that carries is making the
endpoint auditable **by other nodes** ([`LIMITATIONS.md`](LIMITATIONS.md) L-62), never by asking a
keyless client to check something it cannot.

## 3. The rule that governs everything here

> This module wires; it never re-implements.

The same rule the [`minecraft`](../minecraft/Task.0.md) category lives by, for the same reason: a
capability proven headlessly and then reimplemented for a second platform is a capability with two
behaviours and no guarantee they agree. If a seam is missing, add it in the owning category with a
headless test, then consume it here.

## 4. Architecture

```
        unmodified clients            NeoForge clients (Nodera mod)
                │                                  │
       vanilla protocol                  vanilla protocol + nodera:v1
                │                                  │
                ▼                                  ▼
   ┌──────────────────────────────────────────────────────────────┐
   │  Paper / Folia server  +  NoderaEndpoint plugin              │
   │                                                              │
   │   NoderaScheduler seam ── Folia: RegionScheduler per Nodera  │
   │        │                   region  (ALIGN-1: 1 Nodera region │
   │        │                   ⊂ 1 Folia region)                 │
   │        │                  Paper:  main thread                │
   │        ▼                                                     │
   │   event mirror ─► capture ─► propose ─┐                      │
   │   custody reconciler                  │                      │
   │   chunk-editability gate              ▼                      │
   │   WorldMutationApplier (one per Nodera region) ◄── commit    │
   │                                       ▲                      │
   │   ┌───────────────────────────────────┴──────────────────┐   │
   │   │  in-process PeerRuntime  (java/peer — the SAME peer  │   │
   │   │  the worker runs): membership, committee seats,      │   │
   │   │  WorldStore, WorldArchive, tracker + rendezvous      │   │
   │   └──────────────────────────┬───────────────────────────┘   │
   └──────────────────────────────┼───────────────────────────────┘
                                  │ PeerTransport
                     tracker · rendezvous · other nodes
```

The Nodera↔Folia region mapping is the load-bearing piece and is specified in
[`REFERENCE.md`](REFERENCE.md) §2 (**ALIGN-1**): with `threaded-regions.grid-exponent ≥ 3`, every
8×8-chunk Nodera region lies entirely inside exactly one Folia region, so all Minecraft-side work for
one Nodera region runs on exactly one Folia region thread with no locking.

## 5. Dependencies

**Depends on:** [`network/`](../network/Task.0.md) (the peer runtime it embeds),
[`engine/`](../engine/Task.0.md) (the validation stack and the single world writer it drives),
[`storage/` via network 3](../network/Task.3.md) (the world store, archives, joint transfer
certificates), [`worker/Task.2.md`](../worker/Task.2.md) (the control protocol it mirrors in-process),
[`tracker/Task.2.md`](../tracker/Task.2.md) and [`rendezvous/Task.2.md`](../rendezvous/Task.2.md) (the
services it dials), [`minecraft/Task.2.md`](../minecraft/Task.2.md) (the live-lane adapters this
category re-expresses against Bukkit).

**Consumed by:** server operators, their players, and
[`minecraft/Task.4.md`](../minecraft/Task.4.md) (an endpoint is a world source the multiplayer screen
lists like any other).

## 6. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Plugin skeleton, build lane, platform abstraction | ⬜ NOT STARTED |
| [2](Task.2.md) | Embedded peer + control plane | ⬜ NOT STARTED |
| [3](Task.3.md) | Region custody and the ownership bridge | ⬜ NOT STARTED |
| [4](Task.4.md) | World I/O: custody reconciler, chunk gating, save boundary | ⬜ NOT STARTED |
| [5](Task.5.md) | Entity, mob, and event capture lane | ⬜ NOT STARTED |
| [6](Task.6.md) | The vanilla endpoint: tenants | ⬜ NOT STARTED |
| [7](Task.7.md) | Modded clients on an endpoint | ⬜ NOT STARTED |
| [8](Task.8.md) | Plugin compatibility contract | ⬜ NOT STARTED |
| [9](Task.9.md) | Live acceptance: mixed-client suites + CI | ⬜ NOT STARTED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests and live suites: [`TESTING.md`](TESTING.md) ·
architecture reference: [`REFERENCE.md`](REFERENCE.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) ·
programme plan: [`../plans/Plan.5.md`](../plans/Plan.5.md).

## 7. Files

| Path | Contents |
|---|---|
| `java/paper-plugin/` | The plugin (Gradle module `:paper-plugin`, artifact `nodera-endpoint`) |
| `java/paper-plugin/src/main/resources/plugin.yml` | `folia-supported: true`, commands, the API version pin |
| `java/build-logic/src/main/kotlin/nodera.paper-plugin.gradle.kts` | The convention plugin: Paper API, shading, `runPaper`/`runFolia` |
| `java/core/.../region/ViewOwnershipPlanner.java` | Gains the multi-view overload (server task 3) |
| `scripts/lib/e2e-server.sh` | The Paper/Folia/vanilla-bot half of the live launcher |
| `scripts/lib/vanilla-bot.py` | The unmodified-client driver (vanilla protocol, offline mode) |
| `scripts/e2e-endpoint.sh` · `e2e-folia.sh` · `e2e-plugins.sh` | The three live suites |
| [`../minecraft/folia/`](../minecraft/folia/) · [`../minecraft/MultiPaper/`](../minecraft/MultiPaper/) | Prior-art studies this category depends on most |

## 8. Conventions specific to this category

- **Nodera regions are authority; Folia regions are execution.** Never conflate them. Region
  identity, leases, and epochs belong to the 8×8 static grid and nothing about Folia changes them.
- **Never `BukkitScheduler`.** Every scheduled unit of work goes through the `NoderaScheduler` seam,
  which resolves to Folia's four schedulers or to Paper's main thread. `folia-supported: true` is not
  conditional.
- **One `WorldMutationApplier` per Nodera region**, pinned to that region's execution thread. The
  single-writer invariant is preserved *per region*, which is what it actually requires.
- **No region-file format replacement.** The world folder stays a normal Minecraft world folder that
  a plain Paper server can open with the plugin removed. See [`LIMITATIONS.md`](LIMITATIONS.md) §C.
- **Plugins keep authority over the world; Nodera certifies what they did.** A foreign write becomes
  a certified `ExternalDelta`; a write that cannot be certified is reverted **with an event**, never
  silently.
- **Capacity never buys authority.** `FULL` custody breaks ties in the ownership plan; it never
  outranks geometric distance.
- **Every claim about parallelism is proven by a live suite, not by a design argument.** Folia's
  failure mode is a thread-context violation that only appears under real concurrency, and
  `e2e-folia.sh` greps for exactly it.
