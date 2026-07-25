# Minecraft — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the minecraft category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name or live observation), then reconcile ../ROADMAP.md §2 and
     the root README bar. Live observations count as evidence here ONLY when they name the log line
     or artifact that showed them. Never rewrite an old note. -->

**Category:** minecraft · **Last audit:** 2026-07-25 · Tasks completed: **2 / 7**

Tests and live suites: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) ·
retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Skeleton, build, run harness | 🚧 IN PROGRESS | Dev runs + 8 scripted suites work; CI under a headless display remains (L-45) |
| [2](Task.2.md) | Live validation lane | ⏳ BLOCKED | Entity lane and ownership run live; capture/mixin lane and repeatable evidence remain |
| [3](Task.3.md) | Diagnostics HUD + commands | ✅ COMPLETED | Live providers replaced the placeholders (L-31 retired) |
| [4](Task.4.md) | Multiplayer + share GUI | 🚧 IN PROGRESS | Built and feed-wired; live presentation pass remains (L-43, L-46) |
| [5](Task.5.md) | Decentralized host lane | 🚧 IN PROGRESS | Genesis, continuity, and re-key landed; live re-key and join gate remain (L-51, L-52) |
| [6](Task.6.md) | World identity + permissions | 🚧 IN PROGRESS | Identity, grants, and ban enforcement landed; world-list mixin remains (L-49) |
| [7](Task.7.md) | Companion presence gate | ✅ COMPLETED | Defaults on; verified both ways in CI |

---

## 2. Milestone notes (newest first)

### 2026-07-25 — Mesh population and boundary independence (three live-play defects)

Three defects reported from real play shared one root: *a region's committee was whatever players
happened to be standing in it, and nothing on the live lane ever noticed when its primary stopped
keeping up.*

1. **Workers hold committee seats through a player's disconnect.** The resident lane now tops every
   committee up from the standing worker pool and hands each resident its seats.
2. **A laggy player no longer wedges its regions for everyone.** The handoff lane existed and was
   tested but had **no live call site** — a client thousands of ticks behind stayed primary of every
   region its view covered.
3. **Region status stopped reading as a permanent "unsigned".** Certified / pending / **solo** now
   distinguishes a co-signed head from a population shortfall no amount of waiting fixes.

Two latent faults surfaced en route and were fixed: a commit announcement for a **revoked** replica
threw an illegal pipeline transition out of the peer state thread (killing it), and a newer-epoch
assignment re-activated a **live** replica from the genesis snapshot, rewinding it to v0 and failing
every later check.

### 2026-07-24 — Live-play bug bundle

Four defects from real sessions, each with a fix that generalised:

- **Host exit hung on "Saving World"** — the final archive seed was synchronous. Replaced by
  continuous streaming with a bounded final flush, a freshness guard, and exit progress.
- **Item drop/pickup desync** — vanilla was being cancelled against an *unconfirmed* commit. The rule
  is now: cancel vanilla only on the synchronous local-primary path, pinned Minecraft-free by a
  dedicated gate class so it is testable without a game.
- **A P2P bind failure crashed the integrated server.** NeoForge's event bus does **not** isolate
  listener exceptions, so code reachable from server events must self-catch. Three layers of
  containment plus an ephemeral-port retry.
- **Command and telemetry surfaces** — `/tps`, operator aliases, and correct host targeting.

### 2026-07-23 — The world stops dying with its host

The acceptance was blunt: *the host disconnects — the world must not die with them.* A share now packs
the save into the canonical archive, the worker seeds it to the swarm, and a client that loses its
host swaps the vanilla disconnect screen for a recovery flow: fetch, unpack, re-open, auto-re-share.
**The joiner becomes the world's next host with the same world id.**

The scripted continuity series passed **all seven stages** on a live display: share → tracker and
rendezvous listing → second client in-world → an 11.2 MB archive on the network → host killed → the
joiner recovered, re-opened, and re-hosted the world **in 3 seconds**.

En route it caught four real defects that no headless test could have: dead client-ready and publish
wiring (auto-re-shared worlds were never joinable), Mojang session auth versus dev accounts, and a
piece-plane stall where bounded seeders silently dropped over-budget requests and the downloader never
re-issued them.

Same period, the **no-host ownership** model landed: every joining client announces its own peer node,
the session broadcasts the deterministic plan inputs, and every member derives the identical
field-of-view ownership plan — *each player's client re-executes and votes on its own region set*. An
action captured on a non-owner is forwarded to the owning player's node, so the capture point is a
courier with no authority. Live evidence: the entity lane live on 13 regions across 2 member nodes,
with the joiner's own validation lane active on 13 regions.

### 2026-07-22 — The run harness and the join path

The `runs { }` block landed with the NeoForge pin reconciled to the version the real client runs, the
project modules joined to the mod definition (module isolation otherwise hides them at dev-run time),
and runtime libraries aligned with Minecraft's strict pins. `runServer` boots to `Done` with the host
lane self-activating; `runClient` reaches the title screen.

The join path became real rather than decorative: a tracker directory query lists worlds this node has
never seen, a route query resolves a row to a live endpoint, "Open to Nodera" actually publishes the
integrated server and hands the endpoint and live player count to the worker, and the redesigned
two-tab multiplayer screen joins through the vanilla connect screen.

The entity lane was proven live the same week: *entity lane live on 12 region(s)* with the mesh formed
and zero errors, then an RCON-driven drive showing 239 validated and ghost entities across 12
delegated regions.

### 2026-07-21 — Identity, authorship, and the companion gate

The worker mints a signed world identity that the mod persists per world; a shared world re-shares
automatically on load; only the author can change the password; and the sharing player becomes
operator. The presence gate defaults **on**, so Minecraft refuses to start without the peer worker —
with an actionable message and version-skew classification rather than a stack trace.

### 2026-07-20 — Decentralization: the dedicated server stops being required

The dist gate was removed, so a player's integrated server runs the same host lane and shares a world
from a pause-menu button. The host service became role-driven rather than dist-driven, and the
development script stopped installing or running a Minecraft server at all.
