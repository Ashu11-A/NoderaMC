# Task 31 — Nodera-Native GUI Redesign: "Open to Nodera", Public-World Player Counts, Nodera-Only Multiplayer Screen + Piece-Map View (Phase 6, `neoforge-mod` client)

**Phase:** 6 (client surface for the player-hosted torrent world) · **Depends on:** Tasks 26
(multiplayer view model + screen shell), 28 (`TrackerClient` live feed), 29 (`transport-rendezvous`),
30 (player-hosted host lane + Share), 19 (`PieceManifest`/`ChunkLockMap` for the piece-map),
20 (`TrackerQuery`/`WorldHealth`) · **Modules:** `neoforge-mod` (client), `diagnostics` (new view
models — the Minecraft-free half), `distribution`/`peer-runtime` (read-only piece/seed state feeds).

> **This is the visual/UX consolidation task.** Task 26 built compile-only screen *shells*; Task 30
> made the host lane *functional*. Task 31 turns the surface into a coherent, Nodera-native GUI: it
> **replaces** vanilla surfaces rather than sitting beside them, and adds the player-facing
> observability the torrent model needs (public-world player counts, a per-world piece map like a
> torrent client's chunk grid). It supersedes the *presentation* deferrals of L-43 and folds in the
> Task 26 screen shell.

## Goal

Make the Nodera client GUI feel like a first-class system, not an overlay bolted onto vanilla:

1. **"Open to Nodera" replaces "Open to LAN"** in the pause menu — the vanilla LAN button is removed
   and Nodera's share action takes its slot (not an extra button beside it, which is the current
   Task 30b state).
2. **Single-player world list shows a public/connected-players indicator** — a shared world is
   public, so its row shows an icon + live count of peers currently connected to it.
3. **Multiplayer screen is Nodera-only** — vanilla's Direct Connect / Add Server / server-list
   buttons are removed; the screen is tabbed (Worlds · Trackers · Rendezvous) and shows live tracker
   + rendezvous health. A player who has shared worlds sees **their own** worlds listed.
4. **Per-world piece map** — selecting a world opens a torrent-client-style grid of its pieces
   (green = synchronized/held, plus other states), so a player can see how much of a world they hold
   and how synced the swarm is.

## Context — where the code stands today (audited 2026-07-20)

- **Pause menu (request #2, "which button is shown?"):** `PauseScreenShareAddon.java:39-46` *adds* a
  separate "Share to Nodera…" button below the vanilla column via `event.addListener(...)`. The
  vanilla **"Open to LAN"** button (`menu.shareToLan`) is untouched, so **both** appear today. The
  addon only shows when `Minecraft.hasSingleplayerServer()`.
- **Multiplayer screen (requests #3/#4):** `MultiplayerScreenAddon.java:35-52` *adds* a
  `WorldSearchBox` + `TorrentWorldListWidget` beside vanilla's server list on `JoinMultiplayerScreen`;
  vanilla buttons remain. The list is fed by `worldSupplier`, which defaults to `List::of` and has
  **zero callers** (`:24,30`) — the list is always empty. Own-hosted worlds are therefore not shown.
- **Single-player world list (request #3):** no addon exists. A grep for
  `SelectWorldScreen|WorldSelectionList|LevelSummary` across `neoforge-mod/src` returns **nothing** —
  greenfield.
- **Live feeds now exist (Task 28/30):** `NoderaPeerService.serverTrackerClient()` /
  `clientTrackerClient()` expose a `TrackerClient` that can `query` a world's peers + seeders + counts
  + `WorldHealth` (Task 20/28). The host announces name + route (`NoderaPeerService.sendAnnounce`).
  `isHosting()` / `hostRoute()` / `hostOptions()` report the local host state. These are the data
  sources this task's GUI reads — the plumbing exists; only the GUI consumption is missing.
- **Piece state (request #4 map):** `distribution` owns `PieceManifest` (addressable, hashed pieces),
  `PieceSelector`/`PieceReassembler`, and `ChunkLockMap` (arrived-vs-locked). `peer-runtime/discovery`
  `ArchiveInventory` holds piece-level held/absent state per peer. No view model surfaces it yet.
- **View-model precedent:** `diagnostics/view/TorrentWorldListView` (Task 26) is the pure,
  Minecraft-free pattern to follow — build new view models there, keep Screen classes thin.
- **No `runClient` Gradle run** (L-45) — every screen here is proven headlessly (view models) +
  compile-clean, exactly like Task 26; the live GUI pass rides the same deferred lane.

## Folder structure (additions / changes)

```
diagnostics/src/main/java/dev/nodera/diagnostics/view/
├── TorrentWorldListView.java     # KEEP (Task 26) — extend rows with connectedPlayers + isOwnWorld
├── PieceMapView.java             # NEW: pure view model for the per-world piece grid
│                                 #   (piece index → state: HELD/SYNCING/MISSING/LOCKED/VERIFYING)
├── TrackerStatusView.java        # NEW: tracker endpoints → reachable/last-ack/world-count rows
└── RendezvousStatusView.java     # NEW: rendezvous endpoints → registered/relay/reservation rows

neoforge-mod/src/main/java/dev/nodera/mod/client/
├── share/
│   └── PauseScreenShareAddon.java   # CHANGE: remove vanilla "Open to LAN", take its slot,
│                                    #   relabel "Open to Nodera"
├── worldlist/                       # NEW package (Dist.CLIENT)
│   ├── package-info.java
│   ├── SelectWorldScreenAddon.java  # ScreenEvent hook on SelectWorldScreen: public/player-count icon
│   └── PublicWorldBadge.java        # renders the icon + count for a shared world's row
├── multiplayer/
│   ├── NoderaMultiplayerScreen.java # NEW: a full replacement Screen (tabbed), NOT an addon
│   ├── MultiplayerScreenAddon.java  # CHANGE: redirect JoinMultiplayerScreen → NoderaMultiplayerScreen
│   │                                #   (or remove vanilla buttons in place — see 31c decision)
│   ├── TrackerTab.java / RendezvousTab.java / WorldsTab.java   # NEW tab bodies
│   ├── PieceMapWidget.java          # NEW: renders PieceMapView as a colored grid
│   └── (existing widgets reused: TorrentWorldListWidget, WorldSearchBox, TrackerDataSource)
└── multiplayer/PieceMapDataSource.java   # NEW: unpacks distribution/inventory state → PieceMapView

resources/assets/nodera/lang/en_us.json   # nodera.open/worldlist/multiplayer.tabs/piecemap.* keys
resources/assets/nodera/textures/gui/      # NEW: public-world icon, piece-state swatches (or draw with fill())
```

## Implementation details

### 31a — "Open to Nodera" replaces "Open to LAN" (request #2)

1. In `PauseScreenShareAddon.onScreenInit`, locate the vanilla LAN button among the screen's
   listeners — a `Button` whose `getMessage()` equals `Component.translatable("menu.shareToLan")` —
   capture its bounds (x/y/width/height), and **remove it** via `event.removeListener(button)`.
2. Add the Nodera button **at the captured bounds** (so it takes the vanilla slot, not a new row),
   labelled **"Open to Nodera"** (`nodera.open.button`) / **"Nodera: Sharing…"**
   (`nodera.open.button.sharing`) by `isHosting()`. Click → `ShareWorldScreen` (unchanged).
3. If the vanilla LAN button is absent (some screens/states), fall back to the current
   add-at-computed-slot behaviour so the button never disappears entirely.
4. Rename the `nodera.share.button*` label usage to `nodera.open.button*`; keep `ShareWorldScreen`'s
   internal keys.

### 31b — Public-world player-count indicator on the single-player world list (request #3)

1. New `SelectWorldScreenAddon` hooks `ScreenEvent.Init.Post` on `SelectWorldScreen` (client-only).
   For each listed world row whose save name matches a **currently-shared** world
   (`NoderaPeerService.isHosting()` + host world name, and — post-Task-32 — any world the local
   headless peer is hosting), render a `PublicWorldBadge`: a "public" icon + the live connected-peer
   count.
2. The count is `TrackerClient.query(worldId).peerCount()` (Task 20 `TrackerResponse`) for the shared
   world, refreshed on a bounded cadence (cache the last value; never block the render thread on a
   network query — query on a daemon and read the cached snapshot).
3. Because vanilla's `WorldSelectionList.Entry` is not trivially decorated via events alone, this may
   need a **mixin** into `WorldSelectionList$WorldListEntry.render` (the first mixin in the mod —
   `nodera.mixins.json` currently empty). Keep it minimal and load-bearing per A-7; document it in
   `COMPATIBILITY.md`. Alternative (no mixin): an overlay `Renderable` added to the screen that draws
   badges at each visible row's computed y — evaluate both; prefer the overlay if it renders cleanly.
4. `PieceMapView`/health colouring reuses the Task 26 `Semantic`/`Palette` world-health values.

### 31c — Nodera-only multiplayer screen with tabs (request #4)

**Decision gate (see Open Questions):** either (A) replace `JoinMultiplayerScreen` wholesale with a
new `NoderaMultiplayerScreen` (`mc.setScreen(...)` from the addon, or a mixin redirect), or (B) keep
`JoinMultiplayerScreen` as the host and *remove* its vanilla widgets (Direct Connect / Add Server /
Edit / server list) in `Init.Post`, replacing them with the Nodera tab bar. (A) is cleaner and
matches "keep only the Nodera system"; (B) is less invasive. Spec assumes **(A)**.

1. `NoderaMultiplayerScreen` with a tab bar: **Worlds · Trackers · Rendezvous**.
   - **Worlds tab:** the `TorrentWorldListWidget` fed live from `clientTrackerClient().query(...)`
     across all configured trackers, merged + de-duplicated by `worldId`. **Own shared worlds are
     included** (query returns them because the host announces them; also union the local
     `isHosting()` world so a just-shared world shows before the first tracker round-trip). Search box
     retained. Each row: name, connected players, held-pieces %, reliability, `WorldHealth` colour,
     24-h countdown (Task 22).
   - **Trackers tab:** `TrackerStatusView` — one row per configured endpoint: reachable? last ack
     time, worlds indexed, per-IP quota state (from the announce ack path, `NoderaPeerService`).
   - **Rendezvous tab:** `RendezvousStatusView` — one row per rendezvous endpoint: registered?
     active relay reservations, bytes relayed, punch success (from `RendezvousPeerTransport`
     metrics / `TransportSelector` path reports, Task 29).
2. **Remove vanilla buttons** (decision A: they don't exist on the new screen; decision B:
   `event.removeListener` each). Keep a small "Direct connect (advanced)" affordance only if the
   Open Questions answer says to — default is fully Nodera-only per the request.
3. Selecting a world row enables a **"View pieces"** button → the piece-map view (31d).
4. Join flow: selecting + "Join" resolves the host route via the tracker (Task 28) →
   rendezvous/socket dial (Task 29/30) → `NoderaPeerService.onServerSessionInfo(route)`.

### 31d — Per-world piece map (torrent-chunk view) (request #4)

1. `PieceMapView` (pure, in `diagnostics`): given a `PieceManifest` (piece count + hashes) and a
   per-piece state source, produce a grid model — `List<PieceCell>` where each cell has an index and a
   `PieceState`:
   - `HELD` (green) — piece present + hash-verified locally,
   - `SYNCING` (yellow) — in-flight download,
   - `VERIFYING` — arrived, hash check pending,
   - `MISSING` (gray) — not held, available in swarm,
   - `LOCKED` (red/hatch) — `ChunkLockMap` locked (un-arrived section, fail-closed),
   - `RARE` (blue tint overlay) — swarm availability below the rarest-first threshold.
   Include swarm aggregates: total pieces, held %, seeders, availability histogram.
2. `PieceMapDataSource` (mod side) unpacks the live state: held pieces from the local content store /
   `ArchiveInventory`, in-flight from the `PieceDownloader`, swarm availability from the tracker /
   `ContentAvailability` gossip. Read-only; never mutates distribution state.
3. `PieceMapWidget` renders the grid with `guiGraphics.fill(...)` swatches (no texture needed for MVP)
   + a legend + the aggregates line. Hover a cell → tooltip (index, state, holders). Bounded cell
   size; scroll for large manifests.
4. Categories are extensible — the `PieceState` enum is the single place to add future states
   (e.g. `ENCRYPTED_NO_KEY` when the world is password-locked and no key is held).

## Testing strategy

### Headless (JUnit, the gate — no Minecraft), first

1. `PieceMapViewTest` — manifest + state source → correct cell states; held-% aggregate; a locked
   section shows `LOCKED`; a fully-held manifest is all-green; deterministic ordering.
2. `TorrentWorldListViewTest` (extend Task 26) — rows carry `connectedPlayers` + `isOwnWorld`; an own
   world is present even with the tracker empty (local-union rule); merge/de-dup by `worldId` across
   trackers.
3. `TrackerStatusViewTest` / `RendezvousStatusViewTest` — endpoint rows reflect reachable/last-ack /
   registered/relay-bytes from a fake metrics source.
4. Dist-classload guard: every new `client/*` class is `Dist.CLIENT`-only (never loads on a dedicated
   server), matching the Task 26 guard test.

### Real-Minecraft-client acceptance (deferred with L-45, GUI env)

- `runClient` (needs the missing `neoforge { runs {} }` block): pause menu shows **one** button,
  "Open to Nodera", in the LAN slot; sharing a world makes it appear public in the single-player list
  with a live count; the multiplayer screen is Nodera-only with three working tabs; selecting a world
  and "View pieces" renders the grid (green as pieces arrive). Retires L-43 (presentation half).

## Limitations (`LIMITATIONS.md` §B)

- **Advances/retires L-43** (client multiplayer GUI) presentation half — the surface becomes
  Nodera-native and functional; RETIRED on the `runClient` pass with 31d rendering.
- **New L-46 (proposed):** *the piece-map, tracker/rendezvous tabs, and public-world counts are proven
  by view-model tests + compile-clean screens; live per-piece/relay/announce metric feeds into them
  ride the NeoForge live lane (with L-45's `runClient` harness).* Owner: T31. Exit: `runClient` shows
  live green-fill piece progress and live tab metrics.
- **L-45 unchanged** — still the missing automated GUI harness; 31 is the biggest consumer of it.
- If 31b needs a mixin, it is the mod's **first** mixin — record it in `COMPATIBILITY.md` and keep it
  minimal per A-7. Prefer the overlay-renderable approach to keep `nodera.mixins.json` empty.

## Acceptance criteria

1. **One pause button, in the LAN slot.** Vanilla "Open to LAN" removed; "Open to Nodera" occupies
   its bounds; label reflects hosting state; click opens `ShareWorldScreen`. Fallback when the LAN
   button is absent.
2. **Public-world indicator.** A shared world's row in the single-player list shows the public icon +
   live connected-peer count (cached, non-blocking).
3. **Nodera-only multiplayer screen.** Vanilla server-list buttons gone; tabs Worlds/Trackers/
   Rendezvous render; own shared worlds appear in Worlds (local-union + tracker merge); search works.
4. **Piece map.** Selecting a world → "View pieces" → a grid where held pieces are green and other
   states are distinctly coloured, with a legend + aggregates; driven by `PieceMapView` (headless
   test green).
5. `./gradlew check` + `cargo test` green; README progress + `Tested.md` + `LIMITATIONS.md` updated
   in the same commit; GitHub issue `Task 31 — <title>` opened/closed per `.github/ISSUE_SYSTEM.md`.
6. **`runClient` GUI acceptance** deferred with L-45 (documented), same lane as Tasks 26/30.

## Notes for the implementing model

- Keep the split: **pure view models in `diagnostics`** (headless-tested), thin Screen/Widget classes
  in `neoforge-mod`. This is what let Task 26 land its logic on the gate without a GUI env.
- No wire-contract or hashed-encoding changes — this is presentation over existing feeds. `PieceState`
  colours are client-only; they are not consensus state.
- Removing vanilla multiplayer buttons is drastic — do it behind decision A (own screen) so vanilla
  code is untouched and the change is reversible, not by deleting vanilla behaviour.
- One task = one branch = one PR: land 31a (button), 31b (world-list badge), 31c (screen+tabs),
  31d (piece map) as reviewable increments behind the single Task 31 issue; gate green at each.
