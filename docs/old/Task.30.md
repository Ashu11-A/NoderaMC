# Task 30 — Decentralization: Retire the Dedicated Server, Player-Hosted Worlds, "Share" Button (Phase 5→6, mod + client)

**Phase:** 5→6 (completes the Plan §6 "demote the server" step) · **Depends on:** Tasks 1, 4, 9,
10, 19, 20, 21, 23, 26, 28, 29 (the peer runtime, genesis, transport, torrent core, tracker,
rendezvous, and the GUI shell) · **Modules:** `neoforge-mod` (common + client + a new host lane),
`transport-rendezvous` (wire it live), `distribution` (make it a mod dependency), `scripts/`,
`docs/`.

## Implementation status (2026-07-20 — first increment landed, gate green)

`./gradlew check` + `cargo test` green. Landed this pass:

- **30a (done):** the `Dist.DEDICATED_SERVER` gate is gone (`NoderaMod`); `dedicated/` package renamed
  to `server/`; `ServerBootstrap` registers on both dists; an integrated server no longer
  auto-broadcasts (waits for "Share"), a dedicated server auto-hosts iff `host.autoShare` (new
  config). `NoderaPeerService` is role-driven: `startHost`/`hostRoute`/`isHosting`/`stopHosting`
  (was dist-driven `startBootstrap`/`bootstrapRoute`/`stopServer`).
- **30b (done, compile + GUI-deferred):** `client/share/` — `PauseScreenShareAddon` (button on
  `PauseScreen` only when `hasSingleplayerServer()`), `ShareWorldScreen` (password + delegation +
  visibility + stop-sharing), `common/ShareOptions` (Minecraft-free value type), `common/NoderaHost`
  (server-thread activate/reconfigure/deactivate). No network payload needed — the host is the local
  integrated server (same JVM), driven via `MinecraftServer.execute`.
- **30e (partial):** `RENDEZVOUS_ENDPOINTS` is now consumed (no longer dead config). Live
  `RendezvousPeerTransport` composition + password→`WorldKeyMaterial` encryption still need the world
  namespace / genesis (below).
- **30f (done):** `scripts/dev.sh` no longer installs/runs a Minecraft server — it builds + runs
  tracker + rendezvous only, with `--install-mod` to drop the jar into a real client. README masthead
  + run section, `AGENTS.md`, `docs/Prompt.base.md` reframed for the player-hosted model.
- **Tests:** `ShareOptionsTest` (8, headless) — password→encryption, defaults, immutable copy-with,
  password never in `toString`.

Still deferred (the live lane, blocked on Task 9/19/23 production + a GUI env, exactly as the rest of
the mod's Minecraft-facing production is): **30c** genesis-from-existing-world extraction +
self-cert; the **30d** actual re-manifest on password change; **30e** live `RendezvousPeerTransport`
+ per-piece encryption; the `runClient` GUI acceptance pass (needs the missing `neoforge { runs {} }`
block). `NoderaHost.activate` marks each of these with a precise seam comment. Not yet done as
bookkeeping: README progress %/`Tested.md`/`LIMITATIONS.md` (L-45) updates + the GitHub `Task 30`
issue — these ride the commit.

## Goal

Finish the project's central architectural promise — *"the server is special in capacity and
availability, not in authority"* (`docs/Context/Readme.md:2772`; Plan Invariants 1–2,
`docs/Plan.md:438-439`) — by making the **dedicated NeoForge server optional and non-central**, so a
world lives on the player who hosts it and on the peers who join, with only the standalone
infrastructure services (`nodera-tracker`, `nodera-rendezvous`) as **untrusted** helpers.

Concretely:

1. **No required central server.** A normal Minecraft client — playing a singleplayer/LAN world —
   runs the same `PeerRuntime` and can take the bootstrap/host role. The dedicated-server process
   stops being the *only* way to introduce a world to the network; it becomes just one more
   `FULL_ARCHIVE` peer someone may choose to run. Remove the tooling that treats "launch a NeoForge
   dedicated server" as the entry point (`scripts/dev.sh` server install/run; the `Dist.DEDICATED_SERVER`
   gate on the peer stack).

2. **Direct player-to-player.** Two players reach each other through `transport-socket` (LAN /
   reachable) **or** `transport-rendezvous` (NAT hole-punch / E2E relay, Task 29 — currently
   config-only, wired live here); the tracker (Task 28) locates worlds; no dedicated server sits in
   the data path.

3. **A "Share" button in the pause menu** — the analogue of vanilla "Open to LAN" — that broadcasts
   the player's **existing, already-created** world to the Nodera network *after* creation, sets or
   changes the world **password**, and exposes the host options (region delegation, replication,
   visibility). This closes the gap that Task 26 only covers *create-time* torrent hosting.

4. **Client-originated genesis.** The world's `GenesisManifest` is created and self-certified from
   the *player's* integrated server (reusing the Task 9 "genesis from current world" primitive),
   signed by the hosting player's `NodeIdentity`. L-20's single-signer property is unchanged — only
   the signer moves from the dedicated server to the host player (multi-party genesis stays T16).

## Context — where the code stands today (audited 2026-07-20)

- **The whole peer/diagnostics/HUD/command stack is gated behind a dedicated server.**
  `NoderaMod.java:31` — `if (dist == Dist.DEDICATED_SERVER) ServerBootstrap.register();`. On a
  client hosting a singleplayer/LAN world, `dev.nodera.mod.dedicated.ServerBootstrap` is never
  registered, so `ServerStartedEvent` never calls `NoderaPeerService.startBootstrap(...)`
  (`ServerBootstrap.java:50-55`), no `PeerRuntime` is created, and `/nodera` is never registered. An
  **integrated server runs none of Nodera.**
- **Bootstrap-vs-peer is decided by dist, not by role.** `NoderaPeerService.startBootstrap`
  hard-codes `PeerRuntime.bootstrap(...)` (`:113`); the client path hard-codes `PeerRuntime.peer(...)`
  (`:214`). The route is handed to joining players over the vanilla play channel
  (`NoderaSessionPayload`, server→client only — `ServerBootstrap.java:65-69`,
  `ModNetworking.java:36`). A self-hosting player is *both* server and client and has no external
  peer to hand the route to.
- **The client GUI is a data-starved shell, not a working surface** (verified — see
  "Visual-implementation status" below). Widgets compile and lay out, but the world list is never
  fed (`MultiplayerScreenAddon.setWorldSupplier` has zero callers, `:30`), and the create-world
  torrent toggle + password are **collected and discarded** — `CreateTorrentWorldOption` is a local
  variable in `onScreenInit`, and `password()` / `torrentHostingEnabled()` have zero external
  callers (`MultiplayerScreenAddon.java:45-51`).
- **No pause-menu / Open-to-LAN / integrated-server hook exists.** A grep for
  `PauseScreen|ShareToLanScreen|IntegratedServer|singleplayer` across `neoforge-mod/src` finds only
  a javadoc mention. The "Share" button is greenfield; the only reusable convention is the
  `ScreenEvent.Init.Post` + `event.addListener(...)` pattern in `MultiplayerScreenAddon.java:35-52`.
- **The rendezvous transport and the encryption layer are unreachable from the mod.**
  `NoderaConfig.RENDEZVOUS_ENDPOINTS` / `CLIENT_RENDEZVOUS_ENDPOINTS` are declared but never
  `.get()`-consumed; `transport-rendezvous` is not even a Gradle dependency of `neoforge-mod`
  (`build.gradle.kts:5-16`). `:distribution` (the Task 23 `WorldKeyMaterial` / `EncryptedRegion`
  seam) is likewise absent from the classpath — a collected password has no compiled path into
  encryption.
- **`scripts/dev.sh` is a dedicated-server launcher.** It installs a NeoForge **dedicated** server
  (`setup_server`, `dev.sh:156-209`), accepts the Mojang EULA, and runs it in the foreground next to
  the two Rust services (`start_stack`, `dev.sh:252-281`). Removing the central server means this
  script's Minecraft half goes away; the Rust-only path (`--no-mc`) becomes the default.
- **No `runClient` / `runServer` Gradle tasks are configured.** The convention plugin
  (`java/build-logic/src/main/kotlin/nodera.neoforge-mod.gradle.kts`) applies ModDevGradle and pins
  `version = "21.1.77"` but has **no `neoforge { runs { ... } }` block**, so there is no way to
  launch a real client/server from Gradle today (blocks the standing "GUI-deferred" acceptance).

### The architecture already contains the pieces (this is *finishing* the design, not fighting it)

- **A0:** *"Every player runs the Nodera mod and joins the network as a peer"* (`Plan.md:40-45`).
- **Shared runtime:** *"Every installation runs the same logical `PeerRuntime`. Capabilities
  determine which roles it can perform"* (`Readme.md:1556`); *"The role set is descriptive. It must
  not give the server additional voting power"* (`:1592`).
- **Demote the server:** Plan Phase 5 — *"same `PeerRuntime` on every installation, capabilities
  decide roles… Server keeps **one** committee vote — no exclusive key, no override"*
  (`Plan.md:325-330`).
- **Integrated-server-as-host is already the Task 26 create-time design:** *"The creating client's
  integrated server becomes the world's host peer: `FULL_ARCHIVE` (physical backup) +
  bootstrap/tracker roles + a one-vote validator — 'player-hosted' means exactly this"*
  (`Task.26.md:76-78`). Task 30 makes that reachable **after** creation, from the pause menu, for
  worlds that already exist.
- **Client-originated genesis reuses Task 9:** *"first boot with no `GenesisManifest` ⇒ create from
  current world (extract + checkpoint all delegable regions) and self-certify genesis (documented
  trust root)"* (`Task.9.md:131-133`). Task 30 relocates that trigger from
  `dedicated/FullPeerBootstrap` to the host player's integrated server.

## The architectural shift, precisely

| Concern | Today (server-central) | After Task 30 (player-hosted) |
|---|---|---|
| Who runs the first `PeerRuntime` | dedicated server, gated by `Dist.DEDICATED_SERVER` | whoever hosts the world — integrated server of the sharing player, **or** a dedicated server if someone runs one (now just a well-provisioned peer) |
| Role selection | `bootstrap` vs `peer` chosen by dist (`NoderaPeerService.java:113/214`) | chosen by **role**: the host peer bootstraps; joiners peer. Config/runtime-driven, dist-agnostic |
| Genesis signer | dedicated server `NodeIdentity` (`FullPeerBootstrap`) | hosting player's `NodeIdentity` (same self-cert; L-20 unchanged) |
| How a world enters the network | dedicated-server first boot, or Task 26 create-world | **also** the pause-menu "Share" action on an existing world |
| Reachability | LAN / port-forward to the server | `transport-socket` direct **or** `transport-rendezvous` (punch / E2E relay), tracker-located |
| Server-fallback lane (cross-region / non-delegable, Task 8) | the dedicated server's last unique duty (`Plan.md:140-145`) | owned by the world's `FULL_ARCHIVE` host peer while online; if it is offline and no peer holds the role, those regions **pause, never fork** (Plan A-3, `Task.10.md:135`) — documented, not silently dropped |
| Dev/test entry | `scripts/dev.sh --accept-eula` runs a dedicated MC server | `scripts/dev.sh` runs infra only (tracker + rendezvous); the real client at `~/.minecraft/` runs the mod (see testing) |

## Folder structure (additions / changes)

```
neoforge-mod/src/main/java/dev/nodera/mod/
├── common/
│   ├── NoderaHost.java            # NEW: dist-agnostic host lifecycle — "start a host PeerRuntime for
│   │                             #   THIS integrated/dedicated server". Wraps the role decision that
│   │                             #   NoderaPeerService.startBootstrap currently hard-codes.
│   └── NoderaPeerService.java     # CHANGE: role-driven bootstrap/peer; expose a host-activate entry
├── server/                        # RENAME of dedicated/ → server/ (works for integrated + dedicated)
│   └── ServerBootstrap.java       # CHANGE: registered for BOTH dists; ServerStartedEvent starts the
│                                 #   host only when this world is shared (or a dedicated server always
│                                 #   hosts, per config). Singleplayer never auto-shares.
├── client/
│   ├── ClientBootstrap.java       # CHANGE: also register the pause-menu Share hook
│   └── share/                     # NEW package, Dist.CLIENT (mirrors client/multiplayer)
│       ├── package-info.java
│       ├── PauseScreenShareAddon.java   # ScreenEvent.Init.Post on PauseScreen → adds the "Share" button
│       ├── ShareWorldScreen.java        # the "share/options" screen (like ShareToLanScreen)
│       ├── ShareOptions.java            # value type: password, delegate-regions, replication, visibility
│       └── ShareRequest.java            # client→integrated-server request to activate/reconfigure sharing
└── resources/assets/nodera/lang/en_us.json   # nodera.share.* keys

distribution/ + transport-rendezvous/          # add as neoforge-mod Gradle deps (build.gradle.kts)

scripts/
└── dev.sh                         # CHANGE: drop the Minecraft dedicated-server install/run; run the
                                   #   tracker + rendezvous only. Add a note pointing runtime testing at
                                   #   the real client (~/.minecraft/) with the built mod jar.
```

## Implementation details

### 30a — Dist-agnostic host lifecycle (remove the dedicated-server assumption)

1. **Un-gate the peer stack.** Register `ServerBootstrap` on *both* dists (drop the
   `dist == Dist.DEDICATED_SERVER` guard, `NoderaMod.java:31`). `ServerStartedEvent` fires for the
   integrated server too, so the host lane now has a lifecycle on singleplayer/LAN.
2. **Do not auto-broadcast singleplayer.** Starting the integrated server must **not** put a private
   world on the network. `ServerStartedEvent` only *prepares* the host lane; the world is broadcast
   **only** when the player presses "Share" (30b) or when a dedicated server is configured to always
   host (`nodera.host.autoShare = true`, default true only for dedicated).
3. **Make role selection role-driven, not dist-driven.** Refactor `NoderaPeerService` so the choice
   between `PeerRuntime.bootstrap(...)` (`:113`) and `PeerRuntime.peer(...)` (`:214`) is a function
   of a `HostRole` (HOST vs JOINER), not `Dist`. The host peer advertises `FULL_ARCHIVE` +
   bootstrap/tracker/one-vote-validator capabilities (Task 26 semantics); a joiner dials in.
4. **Route advertisement without a dedicated server.** For a joiner over vanilla multiplayer,
   `NoderaSessionPayload` still delivers the host route (unchanged). For discovery *without* a prior
   vanilla connection (the torrent-world list → click-to-join), the join path comes from the tracker
   (Task 28 `TrackerClient` query → host route → rendezvous/socket dial), not the play channel.

### 30b — The "Share" button (pause menu, like "Open to LAN")

1. **Hook `PauseScreen`.** Add `PauseScreenShareAddon.onScreenInit(ScreenEvent.Init.Post)` and
   register it in `ClientBootstrap.register` beside `MultiplayerScreenAddon::onScreenInit`
   (`ClientBootstrap.java:36`). Gate on `event.getScreen() instanceof PauseScreen`, and only add the
   button when the client is hosting a local integrated server (`Minecraft.getInstance().hasSingleplayerServer()`),
   mirroring where vanilla shows "Open to LAN". Button label switches on state: **"Share to Nodera"**
   when not yet shared, **"Nodera Sharing…"** / **"Sharing options"** once active.
2. **`ShareWorldScreen`** (the analogue of `ShareToLanScreen`) collects `ShareOptions`:
   - **Password** — set on first share; **change** on an already-shared world (see 30d for the
     re-manifest consequence, surfaced in the UI as *"changing the password re-encrypts and re-seeds
     the world; other players must re-enter it"*).
   - **Region delegation** — allow/deny committee delegation of this world's regions.
   - **Replication / visibility** — replication factor hint; list-on-tracker vs invite-only
     (invite-only = tracker registration suppressed, join by `InvitationCodec` only, Task 20).
   - **Stop sharing** — tear the world's host lane down (keep the local save; deregister from the
     tracker).
3. **`ShareRequest` → integrated server.** Pressing "Share" sends a `ShareRequest` from the client to
   its own integrated server (a mod network payload, added to `ModNetworking`), which invokes
   `NoderaHost.activate(level, ShareOptions)`. This runs on the server thread and:
   - creates/loads the `GenesisManifest` from the current world (30c),
   - builds the `PieceManifest` (Task 19) with encryption iff a password is set (Task 23),
   - starts/upgrades the host `PeerRuntime` (30a) to `FULL_ARCHIVE`,
   - announces the world (name + genesis hash) to the tracker (Task 28 `TrackerClient`).
4. **Fold the Task 26 create-world path into the same host lane.** `CreateTorrentWorldOption`'s
   currently-discarded `password()`/`torrentHostingEnabled()` become a `ShareOptions` produced at
   create-time and handed to `NoderaHost.activate` on the new world's first `ServerStartedEvent` —
   so "share at create" and "share later" run identical code. (This retires L-43's create-world half
   and the Task 26 deferral simultaneously.)

### 30c — Client-originated genesis

1. Relocate the Task 9 genesis-from-current-world primitive so it is callable from the integrated
   server's host lane, not only `dedicated/FullPeerBootstrap`. On `NoderaHost.activate`, if the
   world has no `GenesisManifest`, extract + checkpoint all delegable regions and **self-certify
   genesis with the hosting player's `NodeIdentity`**.
2. The manifest is name-free (`Task.20.md:99`); the display name is the tracker directory entry
   registered in 30b. Downstream state still requires quorum certificates
   (`Plan.md:440` invariant 3) — genesis is the one self-signed exemption, exactly as before.
3. **L-20 is unchanged** (single-signer trust root, owner still T16 multi-party re-cert). Task 30
   only moves the signer identity; it does not weaken or strengthen the trust root. State this
   explicitly so no one reads "player signs genesis" as a new risk.

### 30d — Set / change password on an already-shared world

Because content addressing is over **ciphertext** and encryption is convergent (`Task.23.md:20-21`,
`:55-66`), the content key is a pure function of the password. Therefore changing (or first setting)
the password on a live shared world is a **full re-manifest**, and the spec must say so:

1. Re-derive the content key from the new password (Argon2id, Task 23).
2. Re-encrypt every piece → new piece hashes → new `manifestRoot` → new `ContentId`s.
3. Emit a new `PieceManifest`, re-announce it to the tracker, re-seed; old ciphertext on other
   seeders is invalidated and re-fetched.
4. Every joiner must supply the **new** password; there is no wrapped-key rotation and no escrow
   (`Task.23.md:106`). The UI must warn before committing the change.

Turning encryption **on** for a previously-plaintext shared world is the same operation
(plaintext-slice → ciphertext-slice). Turning it **off** likewise re-manifests to plaintext.

### 30e — Wire the rendezvous transport + encryption live

1. Add `:distribution` and `:transport-rendezvous` to `neoforge-mod/build.gradle.kts` (and to the
   fat-jar `noderaBundled` list, `build.gradle.kts:22-24`, so their classes ship in the mod jar).
2. Consume the already-declared `NoderaConfig.RENDEZVOUS_ENDPOINTS` / `CLIENT_RENDEZVOUS_ENDPOINTS`:
   when non-empty, compose `RendezvousPeerTransport(identity, endpoints, networkId, genesisHash,
   capabilities, directTransport=SocketPeerTransport)` behind the same `PeerTransport` seam
   (`TransportSelector` prefers direct > punched > relayed). LAN stays `SocketPeerTransport`.
3. Feed the collected password into `WorldKeyMaterial` / `EncryptedRegion` (Task 23) in the host
   lane. Password/key material is never serialized (`Task.23.md:69`).

### 30f — Retire the dedicated-server launcher

1. `scripts/dev.sh`: remove `setup_server` and the Minecraft half of `start_stack`
   (`dev.sh:156-209`, `:269-280`); keep only the tracker + rendezvous build/run. `--no-mc` becomes
   the implicit behaviour; drop `--accept-eula` (no Mojang server to accept for). Keep
   `--build-only` (CI still collects the mod jar + two binaries).
2. `README.md` "Run the local stack": rewrite to *"build the mod jar, drop it in your NeoForge
   1.21.1 client's `mods/`, run the two infra services, host a world and press Share."* Update the
   masthead/Plan references that describe the server as the coordinator to the demoted-peer
   end-state (they already anticipate it — `Plan.md:5-8`, `:325-330`).
3. Keep the *option* of a dedicated server as a plain `FULL_ARCHIVE` peer (a always-on seeder), but
   it is no longer the entry point and no longer required by any test.

## Visual-implementation status (answer to "is the mod's visual layer functional for testing?")

**No — today it is a compile-only shell, not a functional surface.** Findings:

- The multiplayer torrent list renders **always empty** (`setWorldSupplier` never called;
  `MultiplayerScreenAddon.java:24,30`).
- The create-world torrent toggle + password are **discarded** (no caller of
  `CreateTorrentWorldOption.password()`; `MultiplayerScreenAddon.java:45-51`).
- All HUD surfaces (tab list, boss bars, action bar) are **server-packet-driven** and only exist on
  a dedicated server's `DiagnosticsService` — **nothing renders in singleplayer** today.
- There is **no client GUI overlay class**, **no pause-menu hook**, and **no mixins**
  (`nodera.mixins.json` arrays empty).
- **No `runClient` Gradle run** exists, so the surface has never been rendered in a real client.

Task 30's 30b/30e make the surface *functional* (fed by the tracker, the password wired, the host
lane live) — a precondition for any live testing.

## Testing strategy

### Headless (JUnit, the gate — no Minecraft), do these first

Everything except the actual Screen rendering can be proven Minecraft-free, matching Tasks 19/23/26:

1. `NoderaHostIT` — `activate(existingWorld, ShareOptions)` produces a `GenesisManifest`
   self-signed by the host identity, a `PieceManifest` (encrypted iff password set), a tracker
   announce with the display name, and a host `PeerRuntime` advertising `FULL_ARCHIVE`. Reuses the
   Task 9 genesis primitive over an in-memory world view.
2. `PasswordRekeyIT` — set → change password re-manifests: new `manifestRoot`, old joiner key fails,
   new joiner key decrypts to the engine `StateRoot` (extends `EncryptedDistributionIT`).
3. `ShareOptionsCodecTest` — `ShareRequest`/`ShareOptions` round-trip through the mod network payload
   (no secret leaks; password not serialized to disk).
4. `RoleSelectionTest` — `NoderaPeerService` chooses bootstrap vs peer by `HostRole`, not `Dist`
   (a dist-guard test proves no client class loads on a dedicated server, per `Task.26.md` #2).
5. Dist-classload guard test for the new `client/share` package (never loaded on a dedicated server).

### Real-Minecraft-client acceptance (see feasibility below)

- Configure a `neoforge { runs { register("client") } }` block in the convention plugin so a real
  client can be launched from Gradle (currently missing — the blocker).
- **`runClient` GUI pass:** host a singleplayer world, press **Share**, set a password; a second
  `runClient` instance queries the tracker, sees the world in the multiplayer list, joins over
  socket/rendezvous, and the two peers form a mesh (the `SessionContinuityIT` scenario, now live).
- Retires L-43 (GUI) and advances L-30/L-31 (real committee/ownership panels once 6/12 land).

## Feasibility of testing against the real client at `~/.minecraft/`

**Feasible, and partially set up already.** Audited 2026-07-20:

- `~/.minecraft/` exists with a working launcher (`launcher_profiles.json`, `sklauncher-fx.jar`), a
  bundled `runtime/` (its own Java), a `saves/New World`, and **NeoForge already installed**:
  `versions/neoforge-21.1.238` (plus newer 26.x lines and Fabric). `~/.minecraft/mods/` already
  contains a `neoforge-mod.jar` — the built Nodera mod has already been dropped in.
- **Version caveat:** the project pins **NeoForge 21.1.77** (`gradle.properties`, `dev.sh:50`); the
  installed client is **21.1.238**. Both are the same MC 1.21.1 / NeoForge 21.1.x line and are very
  likely load-compatible, but the mismatch must be reconciled — either bump the project pin to
  21.1.238 (a single dedicated version-bump commit, Task 0 §3 discipline) or install 21.1.77 into the
  client. Do this before trusting any acceptance result.
- **Java caveat:** the host JDK is 25; NeoForge 21.1 targets Java 21. The launcher ships its own
  runtime, so the *client* is fine; only the Gradle `runClient` path needs a Java 21 toolchain
  (already handled by `NODERA_JAVA` in `dev.sh` and the moddev toolchain).

**Two viable real-client test modes:**

1. **Manual / launcher-driven (works now):** `scripts/dev.sh --build-only` builds the jar; copy it
   to `~/.minecraft/mods/neoforge-mod.jar`; launch the `neoforge-21.1.x` profile; host **saves/New
   World**, press Share. This is a genuine end-to-end test but is manual (no assertion harness).
2. **Gradle `runClient` (needs the missing `runs` block, 30/Testing):** reproducible from CI-adjacent
   tooling, but still a GUI env — automatable only with a headless display (Xvfb) + screenshot/log
   assertions, which the repo does not yet have.

**Recommended:** keep the *logic* proven headlessly (the JUnit ITs above — the real gate), and treat
the real client at `~/.minecraft/` as a **manual smoke lane** (mode 1) plus a `runClient` config
(mode 2) for the GUI acceptance that `LIMITATIONS.md` already defers. A fully automated
real-client-vs-real-client test (two GUIs, Xvfb, screenshot diffing) is possible but is its own
tooling task — out of scope for Task 30's gate, notable as a follow-up (candidate L-45).

## Limitations (`LIMITATIONS.md` §B)

- **Advances L-43** (client multiplayer GUI): the list is fed and the create/share password is
  wired live — RETIRING → RETIRED once the `runClient` pass lands.
- **New L-45 (proposed):** *no automated real-client GUI acceptance harness (Xvfb + assertions); the
  real `~/.minecraft/` client is a manual smoke lane.* Owner: a future tooling task. Exit: headless
  `runClient` under Xvfb drives the Share flow and asserts the tracker list + mesh formation.
- **Updates the A0 framing:** the dedicated server is no longer assumed to exist; Plan Phase 5's
  "demote the server" is realised. `Readme.md` §"Responsibilities of the base server" is reframed as
  "responsibilities of a host peer," most of which any player peer can hold.
- **L-20 unchanged** — genesis stays a single-signer trust root (now the host player); T16 owns
  multi-party re-certification.
- **Server-fallback lane (A-3 / Plan §3.11):** with no dedicated server, non-delegable / cross-region
  actions are owned by the world's `FULL_ARCHIVE` host peer while online; if none holds it, those
  regions **pause, never fork**. Document; do not silently drop.

## Acceptance criteria

1. **The peer stack runs without a dedicated server.** Un-gated wiring; the integrated server starts
   a host `PeerRuntime` on `ServerStartedEvent`; a dist-guard test proves no `client/*` class loads
   on a dedicated server. Role (not dist) selects bootstrap vs peer (`RoleSelectionTest`).
2. **Existing-world sharing works headlessly.** `NoderaHostIT`: an already-created world → genesis
   self-signed by the host identity → `PieceManifest` (encrypted iff password) → tracker announce →
   `FULL_ARCHIVE` host runtime. `PasswordRekeyIT`: set/change password re-manifests correctly and
   old keys fail closed.
3. **The Share button exists and is coherent.** `PauseScreenShareAddon` adds the button on
   `PauseScreen` only when hosting a local world; `ShareWorldScreen` collects password + options;
   compiles against NeoForge 21.1.x; `nodera.mixins.json` stays valid/empty.
4. **Rendezvous + encryption are live, not config-only.** `:transport-rendezvous` + `:distribution`
   are mod dependencies and bundled; `RENDEZVOUS_ENDPOINTS` are consumed; the collected password
   reaches `WorldKeyMaterial`.
5. **The dedicated-server launcher is retired.** `scripts/dev.sh` runs infra only; `README.md`'s
   run-the-stack section is rewritten for the player-hosted model; the version pin vs the installed
   client is reconciled.
6. **`runClient` acceptance (GUI env, deferred like Tasks 1/18/26):** host → Share → a second client
   sees + joins the world through the tracker + rendezvous/socket; the two peers mesh. L-43 → RETIRED
   on this pass.
7. `./gradlew check` **and** `cd rust && cargo test` green; README progress + `Tested.md` +
   `LIMITATIONS.md` updated in the same commit (the standing discipline); a GitHub issue
   `Task 30 — <title>` opened and closed per `.github/ISSUE_SYSTEM.md`.

## Notes for the implementing model

- This is **finishing Phase 5**, not a redesign. Every quoted invariant already anticipates a
  demoted server; do not introduce a new authority to replace the old one.
- Keep determinism sacred and the frozen contracts frozen — Task 30 adds no wire tags to the hashed
  region encoding; `ShareRequest`/`ShareOptions` are mod-local control messages, not consensus state.
- One task = one branch = one PR. The rip-out of the server launcher (`dev.sh`, the dist gate) and
  the Share feature are large; land 30a/30f (decentralization skeleton) and 30b–30e (Share + wiring)
  as reviewable increments behind the single Task 30 issue, gate green at each.
