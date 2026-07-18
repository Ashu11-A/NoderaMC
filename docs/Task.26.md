# Task 26 — Multiplayer GUI: Torrent-Host World Creation, Server List + Search, Red/Gray Health, Network Stats (Phase 0–8, client)

**Phase:** 0–8 (cross-cutting, client) · **Depends on:** Tasks 19, 20, 21, 22, 23 (the torrent core)
· **Modules:** `neoforge-mod/client` (new GUI surfaces); reuses `diagnostics` view-models.

## Goal

The player-facing surface of the "torrent hosting" feature:
1. **Create-world option** — a "torrent hosting" toggle with an **encryption password** field; on
   enable, the world becomes a content-addressed, multi-seeder resource on the peer network
   (Tasks 19/21/23).
2. **Multiplayer page** — lists **all** servers: player-hosted torrent worlds, friends' worlds, and
   recently-joined worlds, with a **search** box to find worlds by name.
3. **Per-world network stats** — player count, stored-chunk count, and overall network reliability,
   read from the tracker (Task 20).
4. **Health colouring** — servers that have lost some data are **red**; completely unusable servers
   are **gray**; the 24 h decommission countdown (Task 22) is visible; a world with zero seeders for
   the retention window drops off the network.

## Context

- **Zero client GUI exists today.** The mod's surfaces are server→client packets (tab list, boss
  bars, action bar — Task 18); there is no `Screen`, no mixin, no `JoinMultiplayerScreen` hook, and
  `nodera.mixins.json` arrays are empty (LIMITATIONS L-43). This task establishes the client-GUI
  render pattern for the repo.
- The Minecraft-free `diagnostics` view-model (`DiagnosticsView`/`Panel`/`Row`/`Cell`/`Semantic`) +
  `Palette` (colour = policy) is the established pattern: keep view-models pure, render with a
  stateless adapter under `dev.nodera.mod.client`, route colour through `Palette`. A
  `TorrentWorldListView` model mirrors that.
- Acceptance is **GUI-deferred** like all Phase 0 client surfaces: compile against NeoForge 21.1.77
  + a Minecraft-free IT for the list model; the manual `runClient` pass is pending a GUI env
  (consistent with Task 18's deferral).

## Folder structure (additions)

```
diagnostics/src/main/java/dev/nodera/diagnostics/view/   # pure view-model (Minecraft-free)
└── TorrentWorldListView.java     # Panel/Row/Cell model: name, playerCount, storedChunks,
                                   #   reliability, health (HEALTHY/DEGRADED/DEAD), countdown

neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/   # new, Dist.CLIENT
├── package-info.java
├── MultiplayerScreenAddon.java   # ScreenEvent.Init/Render on JoinMultiplayerScreen (NeoForge bus)
├── TorrentWorldListWidget.java   # custom ServerSelectionList-style entry widget
├── WorldSearchBox.java           # name-filter EditBox
├── CreateTorrentWorldOption.java # "torrent hosting" toggle + password field on CreateWorldScreen
└── TrackerDataSource.java        # queries TrackerService (Task 20) → TorrentWorldListView

neoforge-mod/src/main/resources/
└── nodera.mixins.json            # declare any client mixins under "client": [...] (empty today)
```

## Implementation details — pure view-model (`diagnostics`)

- `TorrentWorldListView` builds a `Panel` of world `Row`s from tracker data: world name, online
  player count, stored-chunk count (distinct pieces held network-wide), mean reliability (Task 22),
  `WorldHealth` (HEALTHY / DEGRADED-red / DEAD-gray), and the 24 h countdown if active. Health →
  `Semantic` → `Palette` colour, exactly like the existing HUD. Headlessly testable.

## Implementation details — client GUI (`neoforge-mod/client`, Dist.CLIENT)

- **List + search:** hook `JoinMultiplayerScreen` via a NeoForge `ScreenEvent.Init.Post` listener
  (registered in `ClientBootstrap`, the existing client entrypoint) — add a `WorldSearchBox`
  (`EditBox`) and a `TorrentWorldListWidget` alongside vanilla's `ServerSelectionList`, populated by
  `TrackerDataSource` (queries the tracker peer from Task 20). Search filters rows by name
  client-side. This avoids a heavy mixin where an event hook suffices; a mixin on
  `ServerSelectionList` is the fallback, declared in `nodera.mixins.json` only if needed.
- **Create-world option:** extend `CreateWorldScreen` (event hook or mixin) with a "torrent hosting"
  toggle + password `EditBox`; on enable, the world is created with a `PieceManifest` (Task 19),
  content encryption on (Task 23), and registered with the tracker (Task 20). The host is
  `FULL_ARCHIVE` (physical backup) + a one-vote validator.
- **Colouring:** HEALTHY = green, DEGRADED (lost some data / under-replicated) = red, DEAD
  (zero seeders past 24 h) = gray — via `Palette`. The countdown renders in the row when active.
- **Dist discipline (AGENTS.md):** all `net.minecraft.client.*` under `dev.nodera.mod.client`,
  reachable only via `NoderaClientMod` (Dist.CLIENT); never classloaded on a dedicated server.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-43** — no client multiplayer GUI today. Exit: multiplayer page lists torrent-hosted worlds
  (player/friend/recent) with search; per-world player/chunk/reliability counters; red/gray health
  + 24 h countdown; create-world "torrent hosting" + password option; `runClient` acceptance (GUI
  env). OPEN until the GUI pass.

## Acceptance criteria

1. `TorrentWorldListViewTest` (Minecraft-free): tracker data → correct rows, counts, health, colour
   semantics; search filters by name.
2. Compiles against NeoForge 21.1.77; `nodera.mixins.json` valid; no client class loads on a
   dedicated server (Dist guard test).
3. `runClient` (GUI env, deferred): the multiplayer page shows torrent worlds with live counts +
   health; creating a torrent-hosted world with a password registers it on the tracker and seeds it.
4. `./gradlew check` green; L-43 → RETIRING (GUI acceptance pending the runClient env, like Task 18).
