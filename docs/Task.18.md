# Task 18 — In-Game Observability & Diagnostics HUD (commands, telemetry, presentation redesign)

**Phase:** cross-cutting (0–8) · **Depends on:** current `peer-runtime` + `transport-socket`
(hard); Tasks 6/12 (soft — they *populate* the region/entity panels) · **Modules:** new
`diagnostics` (Minecraft-free), `neoforge-mod`, `peer-runtime`, `transport-socket`, `protocol` ·
**Sibling of:** #17 (the headless debugger — this is its player-facing counterpart)

---

## Goal

Give every player a live, **colour-coded** window into what Nodera is doing on their behalf,
using native Minecraft surfaces — **tab list**, **boss bars**, **action bar / chat**, and a
redesigned **slash-command** tree — and **redesign the command / debugging / data-reporting logic**
so there is exactly one pipeline: *capture → snapshot → view model → surface*.

A player (and an operator) can, at a glance, see:

1. **Data they send/receive** — bytes + message counts, per direction and per message type, with
   rolling rates.
2. **Chunks they own / manage** — the regions where they are primary / validator / replica, chunk
   counts, lease/epoch state.
3. **Zone alerts** — a notification when they walk into a region **outside their control**
   (foreign zone), and when they return to one they own.
4. **Entities they control** — the entities Nodera has delegated to this peer.
5. **Client + server detail** — the same model rendered for the local client peer *and* the
   server/bootstrap peer, through one command surface.

The redesign replaces the current ad-hoc `ServerBootstrap.status()/peers()` inline command with a
declarative command tree and a testable, Minecraft-free telemetry core.

---

## Design principles

1. **One snapshot, many surfaces.** Every peer produces a single immutable `TelemetrySnapshot`
   each sampling tick. Every surface (tab, boss bar, chat, action bar) renders *from that snapshot*
   via a Minecraft-free **view model** (`DiagnosticsView` → `Panel`/`Row`/`Cell`). No surface
   reads runtime state directly. This is what makes "redesign data reporting" concrete: capture and
   presentation are separated by a value type.
2. **Colour is policy, not decoration.** Semantic states (`OWNED`, `VALIDATING`, `REPLICA`,
   `FOREIGN`, `UNASSIGNED`, `GATEWAY`, `HEALTHY`, `DEGRADED`, `CRITICAL`, `TX`, `RX`) live as enums
   in `diagnostics` and are unit-tested. The renderer maps each to a `ChatFormatting`/RGB /
   `BossBarColor` in exactly one place (`Palette`).
3. **Minecraft-free where it can be tested.** All counting, aggregation, classification (incl.
   position → region → ownership), and view-model building live in `diagnostics` (plain Java). Only
   the thin renderers (`Component`/`ServerBossEvent`/packet) touch `net.minecraft.*`.
4. **Cheap and bounded.** Sample on a fixed cadence (default every 20 ticks / 1 s), diff boss-bar
   and tab state before sending, throttle zone checks to region-change edges. No per-tick
   allocation storms; all counters are `LongAdder`.
5. **Honest staging.** Region-ownership and entity-control panels render "unassigned" placeholders
   until Tasks 6/11/12 populate their providers (see §Staging). The *surfaces and the model* ship
   now; the *data* fills in as those lanes land — no structural block.

---

## Folder structure (additions)

```
diagnostics/                                   # NEW pure-Java module (nodera.java-library)
└── src/main/java/dev/nodera/diagnostics/
    ├── package-info.java
    ├── metric/
    │   ├── Direction.java                     # TX | RX
    │   ├── TrafficMeter.java                  # LongAdder byte+frame counters, per Direction
    │   ├── MessageCounters.java               # per message-type counts (by MessageCodec tag)
    │   └── RateWindow.java                    # rolling bytes/sec + msgs/sec (ring buffer)
    ├── model/
    │   ├── TelemetrySnapshot.java             # the single immutable per-tick aggregate
    │   ├── NetStats.java                      # tx/rx totals + rates + per-type breakdown
    │   ├── SessionInfo.java                   # epoch, gatewayId, self role, member summary
    │   ├── RegionOwnership.java               # owned/validating/replica RegionIds + chunk counts
    │   ├── EntityControl.java                 # controlled entity ids grouped by RegionId
    │   ├── PeerLink.java                      # per-peer: last-seen, keepalive count, up/down
    │   └── HealthStat.java                    # Health enum + reason
    ├── state/                                 # colour POLICY (semantic; MC-free, tested)
    │   ├── OwnershipState.java                # OWNED|VALIDATING|REPLICA|FOREIGN|UNASSIGNED
    │   ├── Health.java                        # HEALTHY|DEGRADED|CRITICAL
    │   └── Semantic.java                      # umbrella: TX,RX,GATEWAY,SELF, + the above
    ├── classify/
    │   └── ZoneClassifier.java                # (dim,blockX,blockZ, ownershipSet) → OwnershipState
    ├── source/                                # capture seams (impl'd by other modules)
    │   ├── DiagnosticsSource.java             # contribute(SnapshotBuilder)
    │   ├── RegionOwnershipProvider.java       # STUB now; Task 6 LeaseManager implements
    │   └── EntityControlProvider.java         # STUB now; Task 12 entity lane implements
    ├── view/                                  # MC-free presentation intermediate
    │   ├── DiagnosticsView.java               # ordered list of Panels
    │   ├── Panel.java                         # title + rows (a boss bar or a chat block or tab section)
    │   ├── Row.java                           # cells
    │   ├── Cell.java                          # text + Semantic (+ bold flag)
    │   └── ViewBuilder.java                   # TelemetrySnapshot → DiagnosticsView (the report logic)
    └── DiagnosticsCollector.java              # holds sources + counters; sample() → TelemetrySnapshot

peer-runtime/src/main/java/dev/nodera/peer/
├── metric/
│   └── MeteredPeerTransport.java             # PeerTransport decorator: counts raw bytes/frames → TrafficMeter
└── PeerRuntime.java                          # + message-type counters; implements DiagnosticsSource

neoforge-mod/src/main/java/dev/nodera/mod/debug/     # NEW package (replaces inline command logic)
├── DiagnosticsService.java                   # per-side: samples collector every N ticks, keeps latest per player
├── command/
│   ├── CommandTree.java                      # declarative brigadier DSL (path→executor→Component)
│   ├── NoderaCommand.java                    # SERVER /nodera tree (RegisterCommandsEvent)
│   └── NoderaClientCommand.java              # CLIENT /noderac tree (RegisterClientCommandsEvent, Dist.CLIENT)
├── render/
│   ├── Palette.java                          # Semantic → ChatFormatting/RGB + OwnershipState → BossBarColor
│   ├── ComponentRenderer.java                # Panel/Row/Cell → net.minecraft Component (chat tables)
│   ├── TabListRenderer.java                  # snapshot → ClientboundTabListPacket header/footer
│   ├── BossBarManager.java                   # per-player ServerBossEvent set (zone/health/net bars)
│   └── ActionBarNotifier.java                # zone-crossing + alert toasts (displayClientMessage)
└── ZoneWatcher.java                          # PlayerTickEvent.Post → region-change edge → notify + boss bar
```

---

## Data flow

```
        ┌───────────────── capture (per side, MC-free) ─────────────────┐
        │  MeteredPeerTransport ──bytes/frames──►  TrafficMeter         │
        │  PeerRuntime.dispatch ──msg type────►    MessageCounters      │
        │  RegionOwnershipProvider (T6)  ─┐                             │
        │  EntityControlProvider   (T12) ─┼─► DiagnosticsCollector      │
        │  SessionView / PeerLinks       ─┘        │ sample()           │
        └──────────────────────────────────────────┼────────────────────┘
                                                    ▼
                                       TelemetrySnapshot  (immutable)
                                                    │ ViewBuilder
                                                    ▼
                                         DiagnosticsView (Panels)
        ┌───────────────── present (MC-side, thin) ─────────┼───────────┐
        │  ComponentRenderer → /nodera & /noderac chat tables            │
        │  TabListRenderer   → tab header/footer (ClientboundTabListPacket)
        │  BossBarManager    → ServerBossEvent(s) per player            │
        │  ActionBarNotifier / ZoneWatcher → zone-crossing alerts        │
        └───────────────────────────────────────────────────────────────┘
```

Cadence: `DiagnosticsService` samples on `ServerTickEvent.Post` every `sampleTicks` (default 20).
Tab list + boss bars re-render only when the rendered `DiagnosticsView` differs from the last sent
(cheap structural diff). `ZoneWatcher` runs on `PlayerTickEvent.Post`, throttled to region-change
edges (tracks each player's last `RegionId`).

---

## The telemetry model (`diagnostics`)

`TelemetrySnapshot` (immutable record) fields:

- `long tick`, `NodeId self`, `boolean bootstrap`
- `SessionInfo session` — `epoch`, `gatewayId`, `self is gateway?`, `memberCount`, `List<PeerLink>`
- `NetStats net` — `bytesTx/bytesRx`, `framesTx/framesRx`, `bytesPerSecTx/Rx`, `msgsPerSecTx/Rx`,
  `Map<String,long[]> byType` (per `MessageCodec` type name → {tx,rx})
- `RegionOwnership regions` — `List<RegionId> primary/validator/replica`, `ownedChunks`,
  `Map<RegionId,{epoch,leaseExpiry}>` (empty until Task 6)
- `EntityControl entities` — `Map<RegionId,List<NetworkEntityId>>` (empty until Task 12)
- `HealthStat health`

`DiagnosticsCollector`: registers `DiagnosticsSource`s + owns the `TrafficMeter`/`MessageCounters`;
`sample(tick)` snapshots every source into an immutable `TelemetrySnapshot`. Thread-safe (counters
are `LongAdder`; sources read their own confined state).

`ZoneClassifier.classify(dim, blockX, blockZ, ownership)` → `OwnershipState`: maps a world position
to its `RegionId` (`RegionId.fromChunk`, block ≫4 → chunk, `floorDiv 8`) and looks it up against the
snapshot's ownership sets. Pure, unit-tested with negative coordinates (halo/border cases via
`RegionBounds`).

---

## Command tree redesign

`CommandTree` — a tiny declarative DSL over brigadier so subcommands are a *table*, not nested
builder chains (the "redesign command logic" deliverable). Each entry: `path`, `permissionLevel`,
`args?`, and an executor `Function<Ctx, DiagnosticsView>` — the tree wires brigadier + runs the
`ComponentRenderer` uniformly, so every command outputs the same colour-coded table format.

### Server `/nodera` (via `RegisterCommandsEvent`)

| Subcommand | Shows |
|---|---|
| `/nodera session` (alias `status`) | epoch, gateway (GOLD if self), member count, self role |
| `/nodera peers` | table: each peer — id (short), route, role, last-seen, keepalives, up/down colour |
| `/nodera net [type]` | TX/RX bytes+frames+rates; `type` → per-message-type breakdown |
| `/nodera regions` | regions you are primary(GREEN)/validator(AQUA)/replica(BLUE); chunk counts; lease/epoch |
| `/nodera zone` | the region at your current position + its `OwnershipState` (colour) |
| `/nodera entities` | entities delegated to this peer, grouped by region |
| `/nodera server` | server/bootstrap peer detail: uptime, bound P2P route, member table, aggregate net |
| `/nodera whois <player>` | that player's last-reported client snapshot (needs client report, staged) |
| `/nodera hud <tab\|bars\|alerts\|all> <on\|off>` | toggle a surface for the caller |
| `/nodera debug <sample-rate <n>\|verbose <on\|off>>` | operator tuning (op-only) |

### Client `/noderac` (via `RegisterClientCommandsEvent`, `Dist.CLIENT` only)

Client-local: reads the **client** `PeerRuntime`'s own snapshot without a server round-trip.
`net`, `session`, `zone`, `hud` — the player's own tx/rx and their own membership view. Useful when
the vanilla server connection is down but the P2P mesh is alive (the continuity case).

Permissions: read-only panels available to everyone; `debug`, `hud … all`, and `server` require
op / permission level 2.

---

## Presentation surfaces & colour spec

### Palette (`Semantic` → colour, one place)

| Semantic | Chat (`ChatFormatting`) | Boss bar (`BossBarColor`) | Meaning |
|---|---|---|---|
| `OWNED` / primary | `GREEN` | `GREEN` | you own & manage this |
| `VALIDATING` | `AQUA` | `BLUE` | you validate it |
| `REPLICA` | `BLUE` | `PURPLE` | you hold a replica |
| `FOREIGN` | `RED` | `RED` | **outside your control** |
| `UNASSIGNED` | `GRAY` | `WHITE` | no committee / not delegated |
| `GATEWAY` | `GOLD` | `YELLOW` | the session gateway |
| `HEALTHY` | `GREEN` | `GREEN` | — |
| `DEGRADED` | `YELLOW` | `YELLOW` | — |
| `CRITICAL` | `RED` | `RED` | — |
| `TX` | `LIGHT_PURPLE` | — | data sent |
| `RX` | `AQUA` | — | data received |
| headings | `WHITE` + **bold** | — | — |
| units/secondary | `DARK_GRAY` | — | — |

### Tab list (`TabListRenderer` → `ClientboundTabListPacket`)

Header: `NoderaMC` + session line — `epoch E · gateway <id|YOU gold> · N peers`.
Footer: net line — `▲ <tx>/s`(TX purple) ` ▼ <rx>/s`(RX aqua) ` · regions: <owned> owned / <val> val`
(GREEN/AQUA) ` · health <state>` (health colour). One packet per player on change.

### Boss bars (`BossBarManager`, per player, ≤3)

- **Zone bar** — name = current `RegionId` + state word; colour = `OwnershipState` mapping; progress
  = fraction of chunks in that region you own (or 1.0 for whole-region ownership). Updated by
  `ZoneWatcher` on region change.
- **Health bar** — session health; colour by `Health`; progress = quorum/liveness fraction.
- **Net bar** (opt-in via `/nodera hud bars on`) — progress = normalized throughput; name shows
  `▲tx ▼rx`. Diffed before update to avoid packet spam.

### Zone-crossing alerts (`ZoneWatcher` + `ActionBarNotifier`)

On the edge where a player's `RegionId` changes:
- entering `FOREIGN`/`UNASSIGNED` → action bar (RED, bold): `⚠ Entering unmanaged zone (region X,Z)`
  + one chat line with the region + who owns it (if known).
- entering `OWNED`/`VALIDATING` → action bar (GREEN): `✔ Entered your zone (region X,Z)`.
Debounced to region edges only; never per-tick.

### Chat tables (`ComponentRenderer`)

`Panel`→a titled block; `Row`→columns padded to a monospace grid; `Cell`→coloured text. Every
command renders through this, so output is uniform and colour-coded. Aligned with the client's
default font metrics (fixed column widths; values right-trimmed).

---

## Instrumentation (capture)

- `MeteredPeerTransport` (peer-runtime) wraps the concrete `PeerTransport`; increments
  `TrafficMeter` on every `send`/`sendStream` (TX bytes+frames) and in the delivered
  `MessageHandler.onMessage` path (RX). Transparent — implements the same seam.
- `PeerRuntime` increments `MessageCounters` keyed by `MessageCodec` type name on every decoded
  message (already the choke point), and implements `DiagnosticsSource` (session, peer links).
- `RegionOwnershipProvider` / `EntityControlProvider` are interfaces with **no-op stub impls**
  registered now; Task 6 (`LeaseManager`) and Task 12 (entity lane) provide real impls without any
  change to the surfaces.

## Networking (diagnostics payloads, `protocol`)

- `DiagnosticsPushPayload` (server → client, throttled ~every 20 ticks): the server's authoritative
  view of *this* player's owned regions + entity control + server health, so the client HUD shows
  server-truth for panels the client can't compute locally. NeoForge play payload, one string/blob.
- `ClientDiagnosticsReport` (client → server, opt-in, low rate): the client's own net stats, so
  `/nodera whois <player>` can answer. **Staged** (off by default; behind `/nodera debug`).
- New `MessageCodec` tags appended (24…) only if the P2P lane also carries diagnostics; the
  play-channel payloads above are NeoForge payloads, not `MessageCodec` frames.

---

## Staging (honest — what renders real data when)

| Panel | Now | When real |
|---|---|---|
| session / peers / gateway | **real** (peer-runtime) | — |
| net (tx/rx bytes+frames+rates, per type) | **real** (metered transport + counters) | — |
| zone (position → region → state) | **real geometry**; ownership = `UNASSIGNED` placeholder | Task 6 populates ownership |
| regions owned/managed | placeholder (empty sets) | Task 6 `LeaseManager` → `RegionOwnershipProvider` |
| entities controlled | placeholder (empty) | Task 12 entity lane → `EntityControlProvider` |
| whois (cross-player) | off | when `ClientDiagnosticsReport` enabled |

`LIMITATIONS.md` gets one §B entry (L-31): *in-game diagnostics ship session + net live; region and
entity panels are placeholders until Tasks 6/12 populate their providers* — owner T18, exit test:
in a committee scenario the `regions` panel shows `ownedChunks > 0` and the zone bar turns GREEN
inside an owned region.

---

## Migration from the current command

- Delete the inline `ServerBootstrap.status()/peers()` executors; `ServerBootstrap` keeps only
  lifecycle wiring and delegates command registration to `NoderaCommand.register(dispatcher)`.
- `/nodera status` and `/nodera peers` remain as **aliases** into the new tree (no breaking change
  for the beta scenario in `scripts/README.md`).
- `NoderaPeerService` gains `diagnosticsCollector()` accessors for server + client runtimes.

---

## Acceptance criteria

1. **Model + policy (unit, `diagnostics`):** `TrafficMeter`/`RateWindow` counter + rate math;
   `MessageCounters` per-type tallies; `ZoneClassifier` classification incl. negative coords and
   halo/border; `ViewBuilder` produces expected `Panel`/`Row`/`Cell` + `Semantic` for a crafted
   snapshot; `Palette` is total over `Semantic` (no unmapped enum).
2. **Capture (unit/IT, `peer-runtime`):** over `LoopbackTransport`, after a keep-alive exchange the
   `TelemetrySnapshot` shows `bytesTx/Rx > 0`, `framesTx/Rx > 0`, `SessionKeepAlive` in the per-type
   breakdown, and the correct member/gateway/epoch — a `DiagnosticsIT`.
3. **Command tree:** every subcommand in the table registers, runs, and returns a non-empty
   colour-coded `DiagnosticsView`; `/nodera` and `/noderac` share the renderer; permission gating
   enforced (op-only where specified).
4. **Surfaces (manual, on a real server via `scripts/`):** tab header/footer shows session + net;
   ≥1 boss bar reflects zone state with the mapped colour; walking across a region boundary fires
   the action-bar alert with the right colour on the edge; `/nodera net redstone`-style per-type
   works. Recorded in the Verification log with screenshots/log excerpts.
5. **Budget:** with HUD on, sampling every 20 ticks adds no measurable TPS regression in a 3-peer
   soak; tab/boss updates are diffed (no packet per tick).
6. `./gradlew check` green; README module table + `Tested.md` + `docs/Task.0.md` index + roadmap +
   `LIMITATIONS.md` (L-31) updated in the same PR.

## Verification log

Implementation landed on branch `feature/diagnostics-hud-#18`. `./gradlew check` green (243 tests,
+32 vs the prior 211).

**Shipped (acceptance #1–#3, #6):**
- New Minecraft-free `diagnostics` module: `metric` (`Direction`, `TrafficMeter`, `MessageCounters`,
  `RateWindow`), `model` (`TelemetrySnapshot` + sub-records), `state` (`OwnershipState`, `Health`,
  `Semantic`), `classify` (`ZoneClassifier`), `source` (`DiagnosticsSource` + `SnapshotBuilder` +
  `RegionOwnershipProvider`/`EntityControlProvider` stubs), `view` (`DiagnosticsView`/`Panel`/`Row`/
  `Cell` + `ViewBuilder`), `DiagnosticsCollector`. 30 unit tests (counter + rate math, per-type
  tallies, negative-coord classification, Panel/Row/Cell + Semantic assertions, rate + health
  derivation).
- `protocol`: `MessageCodec.typeName(tag)` + `KNOWN_TAGS` (frozen, append-only; +1 registry test).
- `peer-runtime`: `MeteredPeerTransport` decorator (TX bytes/frames on send, RX via handler wrap) +
  per-type `MessageCounters` on `PeerRuntime` (TX in `sendTo`, RX in decode) + `PeerRuntime` as a
  `DiagnosticsSource`. `DiagnosticsIT` over `LoopbackTransport` proves bytesTx/Rx > 0, framesTx/Rx >
  0, `SessionKeepAlive` in the per-type breakdown, and correct member/gateway/epoch.
- `neoforge-mod` `dev.nodera.mod.debug`: `Palette` (exhaustive Semantic→colour, compile-enforced
  totality), `ComponentRenderer`, `TabListRenderer` (`ClientboundTabListPacket`), `BossBarManager`
  (per-player diffed `ServerBossEvent`s), `ActionBarNotifier` (zone alerts), `ZoneWatcher`
  (`PlayerTickEvent.Post` edge detection), `DiagnosticsService` (`ServerTickEvent.Post` sampler +
  per-player HUD prefs), `command/CommandTree` + `NoderaCommand` (`/nodera session|status|peers|net
  [type]|regions|zone|entities|health|server|hud|whois|debug`) + `NoderaClientCommand` (`/noderac
  session|peers|net [type]`). Wired into `ServerBootstrap` (inline executors deleted; `status`/
  `peers` kept as aliases) + `NoderaPeerService` (metered transport + collectors + `serverDiagnostics()`
  accessor) + `ClientBootstrap` (`/noderac`).

**Honest staging (acceptance #4 manual, #5 budget):**
- Live now: session/peers/gateway, net tx/rx bytes+frames+rates + per-type breakdown, zone geometry,
  all surfaces + colours. LIMITATIONS L-31 covers the placeholder region/entity panels (Tasks 6/12).
- Deferred with `runServer`/`runClient`: the in-game surfaces are accepted by **compile against
  NeoForge 21.1.77** + the MC-free `DiagnosticsIT`; the manual on-server surface pass (screenshots,
  soak TPS) is pending a GUI env, consistent with Phase 0. `/noderac zone`/`hud` are deferred to that
  pass (zone needs the local player's world position, which the layering rule keeps under
  `dev.nodera.mod.client`). `whois` and the `ClientDiagnosticsReport` payload stay staged (off).
- Budget (#5): surfaces diff/throttle by construction — tab + boss bars mutate only on a
  signature change, zone alerts fire on region-change edges only, sampling defaults to 20 ticks.

**Adversarial-review remediation:** a 6-dimension find→verify review workflow (23 agents, 17
confirmed findings, 0 refuted) ran before commit and every finding was applied — `./gradlew check`
re-green at 253 tests:

- *Blocker:* `ViewBuilder.formatBytes`/`formatRate` threw `StringIndexOutOfBoundsException` for any
  byte value in [1024, 2047] (off-by-one in the binary-unit exponent) — the tab/boss-bar/net HUD hot
  path would have crashed ~1/s at 1–2 KiB/s. Fixed (`exp = unit / 10`) + boundary regression tests.
- *Net bar:* was unreachable individually and diverged from the "opt-in via `/nodera hud bars on`"
  spec. Adopted Design A — the net bar rides on `bars` (no separate `netBar` pref / `NET_BAR`
  surface); `/nodera hud bars on` now shows it as documented. Net-bar progress switched from a
  log1p formula (saturated at ~54 B/s) to a linear normalisation against a fixed 256 KiB/s ceiling.
- *Tab clear:* `/nodera hud tab off` now sends one empty `ClientboundTabListPacket` on the
  true→false edge so the stale header/footer is cleared (was asymmetric with the bars surface).
- *Dead code removed:* `BossBarManager.HudPref`, `DiagnosticsService.verbose` field/accessors,
  `DiagnosticsService.bossBars()`/`zoneWatcher()` accessors, the `barsMap()` indirection. The
  spec'd `/nodera debug verbose` surface stays, replying honestly that finer logging is staged.
- *DRY:* the MC→core dimension bridge (`player.level().dimension()` → `DimensionKey`) is now one
  shared `dev.nodera.mod.debug.Dimensions.of` instead of three copies; `zonePanel` computes the
  region once.
- *Coverage:* `MeteredPeerTransportTest` (exact TX/stream/RX counts + onPeerDown pass-through),
  `ViewBuilder` `formatRate`/`serverPanel`/populated `regions`+`entities`, `ZoneClassifier`↔
  `RegionBounds` consistency (locks the region-granular, non-halo-aware contract Task 6 hands off),
  and deterministic per-type TX (`PeerJoin`/`MembershipUpdate` == 1) in `DiagnosticsIT`.
