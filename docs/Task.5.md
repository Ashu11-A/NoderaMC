# Task 5 — NeoForge Minecraft (Java) Module (module: `neoforge-mod` + `transport-neoforge`)

> **Module-unification note (issue #30, 2026-07-21):** the fine-grained Gradle modules this file
> mentions were merged into the seven unified modules — `core` · `engine` · `transport` ·
> `storage` · `peer` · `testing` · `neoforge-mod` — with **packages unchanged**. Read old module
> names as packages inside the new modules (mapping: [`Task.0.md`](Task.0.md) §5).

**Module:** everything that touches Minecraft/NeoForge types — the mod itself ·
**Depends on:** Task 1 (the validation stack it wires live), Task 2 (the network stack it
wires live), Task 3 (3b tracker feed), Task 4 (4b transport), Task 6 (the worker it requires) ·
**Consumed by:** players. This module delivers the **live halves** of Tasks 1 and 2.

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting. The recurring blocker is
**L-45**: no `neoforge { runs {} }` block exists, so `runClient`/`runServer` cannot launch from
Gradle and every GUI acceptance is compile-clean + headless-view-model only.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 5a | Mod skeleton, build conventions, dist discipline, `runClient`/`runServer` harness (L-45) | 🚧 (skeleton/jar ✅; `runs` block + GUI-env acceptance missing) | — |
| 5b | Live validation lane: capture events/mixins, `ServerLevel` applier, chunk tickets, fake-player detection, live shadow→coordinator→committee→fallback wiring, `ChunkLockMap` consumers, live commit/content/lifecycle adapters | ⏳ | 5a (env); consumes 1c–1g, 2b–2j seams |
| 5c | HUD + command tree: `/nodera` + `/noderac`, tab list, boss bars, zone alerts | ✅ compile+headless (live surface pass ⏳ 5a; L-31 placeholders wait on 5b) | 5a |
| 5d | Multiplayer + share GUI: "Open to Nodera", tabbed `NoderaMultiplayerScreen` (Worlds/Trackers/Rendezvous), piece map, world-list badge, create/share password flow, live tracker feed | 🚧 (view models + screens ✅ compile; `runClient` pass + live feeds ⏳; L-43/L-46) | 5a, 3b, 6b |
| 5e | Decentralized host lane: role-driven hosting, "Share" activation, genesis-from-existing-world (30c), password-change re-manifest (30d), live rendezvous+encryption composition (30e) | 🚧 (30a/30b/30f ✅; 30c/30d ⏳; 30e partial) | 2c (genesis primitive), 2h, 4b |
| 5f | World identity + permissions, mod half: `nodera-world.dat` persistence, auto-re-share, author-only password UI, world-list mixin, grant gossip + `BANNED` join enforcement | 🚧 (store/authority/op-grant ✅; mixin + gossip/enforcement ⏳; L-49) | 5a (mixin verify), 6b/6c |
| 5g | Companion presence gate: probe the worker, abort with actionable error, version-skew classification | ✅ (`companion.required` defaults ON) | — |

## Goal

One mod jar, identical on client and (optional) dedicated server, that turns the proven
headless stacks into the playable product: capture player actions into the validated lane,
apply committed deltas through the single `WorldMutationApplier`, render the Nodera-native GUI
("Open to Nodera" in the LAN slot, a Nodera-only tabbed multiplayer screen with live tracker/
rendezvous status and a torrent piece map, public-world badges with live player counts), host
worlds from the pause menu with optional password encryption, persist signed per-world identity
and the P2P permission model, surface live diagnostics on native Minecraft surfaces, and
**require** the always-on peer worker (Task 6) so a player's node outlives the game process.
No dedicated server is required anywhere (A0 + the demoted-server invariants).

## Context (last audit: 2026-07-21)

- The mod compiles and assembles against NeoForge **21.1.77** with `nodera.mixins.json` still
  empty (the 5f world-list mixin will be the repo's first — A-7 discipline). The real test
  client at `~/.minecraft` runs NeoForge **21.1.238** — reconcile the pin before trusting live
  acceptance (Task 0 §6).
- Landed (legacy Tasks 1/18/26/30/31/32/33 mod halves): role-driven `NoderaPeerService`
  (`startHost`/`hostRoute`/`isHosting`/`stopHosting` — no `Dist.DEDICATED_SERVER` gate;
  `dedicated/` renamed `server/`), `client/share` (`PauseScreenShareAddon` — "Open to Nodera"
  takes the vanilla LAN slot, `ShareWorldScreen`, Minecraft-free `ShareOptions`),
  `client/multiplayer` (tabbed `NoderaMultiplayerScreen`, `PieceMapWidget`/`PieceMapScreen`,
  `TorrentWorldListWidget`, `WorldSearchBox`, `TrackerDataSource`, `MultiplayerStatusFeed` —
  the "No trackers/rendezvous configured" bug is fixed), `client/worldlist`
  (`SelectWorldScreenAddon` screen-level summary), `debug/` (the full `/nodera` + `/noderac`
  command tree, tab/boss-bar/action-bar renderers over the 2k view models),
  `common/{NoderaHost,NoderaWorldStore,CompanionGate,CompanionClient,CompanionLink}`, and the
  worker gate defaulting ON.
- The deferred live lane is precisely enumerated in the legacy specs' status sections:
  [`old/Task.30.md`](old/Task.30.md) §Implementation status, [`old/Task.31.md`](old/Task.31.md),
  [`old/Task.33.md`](old/Task.33.md) §Still the live lane.
- `scripts/dev.sh` runs infra only (tracker + rendezvous + worker; `--install-mod` drops the
  jar into `~/.minecraft/mods`); the dedicated-server launcher is retired.

## Folder structure (monorepo default)

```
java/neoforge-mod/src/main/java/dev/nodera/mod/
├── NoderaMod.java / NoderaClientMod.java      both-dist + client entrypoints
├── common/     NoderaConfig, ModNetworking, ModAttachments, McAdapters, NoderaPeerService,
│               NoderaHost, NoderaWorldStore, CompanionGate/Client/Link, CompanionProtocol
├── server/     ServerBootstrap (both dists), shadow/ + coordinator/ + commit/ + fallback/ +
│               interference/ live adapters (5b — seams defined by Tasks 1/2)
├── client/     ClientBootstrap, share/, multiplayer/, worldlist/, worker/ (5b), debug HUD prefs
├── debug/      Palette, ComponentRenderer, TabListRenderer, BossBarManager, ActionBarNotifier,
│               ZoneWatcher, DiagnosticsService, command/{CommandTree,NoderaCommand,NoderaClientCommand}
├── mixin/      (5b/5f) LevelChunkMixin choke point, tick suppression, WorldSelectionListEntryMixin
└── resources/  META-INF/neoforge.mods.toml, nodera.mixins.json, assets/nodera/lang/en_us.json

java/neoforge-mod (relay lane; former transport-neoforge deleted)/   NoderaPayload (single payload type), PayloadBridge, StreamReassembler,
                           NeoForgeRelayTransport (relay impl — 5b; permanent fallback lane)
java/build-logic/          nodera.neoforge-mod.gradle.kts (ModDevGradle; needs the runs {} block — 5a)
```

## Related files

- Entrypoints/config: `java/neoforge-mod/src/main/java/dev/nodera/mod/{NoderaMod,NoderaClientMod}.java`, `common/NoderaConfig.java`
- Host lane: `common/{NoderaPeerService,NoderaHost,NoderaWorldStore}.java`, `client/share/*.java`
- GUI: `client/multiplayer/*.java`, `client/worldlist/*.java` (+ view models in
  `java/peer` — owned by 2k)
- HUD/commands: `debug/**` (renderers over `TelemetrySnapshot`)
- Gate: `common/{CompanionGate,CompanionClient,CompanionLink,CompanionProtocol}.java`
- Build: `java/build-logic/src/main/kotlin/nodera.neoforge-mod.gradle.kts`, `scripts/dev.sh`
- Legacy specs (class-level): [`old/Task.1.md`](old/Task.1.md), [`old/Task.5.md`](old/Task.5.md)–
  [`old/Task.8.md`](old/Task.8.md) + [`old/Task.11.md`](old/Task.11.md) (live halves),
  [`old/Task.18.md`](old/Task.18.md), [`old/Task.26.md`](old/Task.26.md),
  [`old/Task.30.md`](old/Task.30.md), [`old/Task.31.md`](old/Task.31.md),
  [`old/Task.32.md`](old/Task.32.md) (gate), [`old/Task.33.md`](old/Task.33.md)

## Implementation details (phases)

- **5a — Skeleton + build + run harness.** 🚧 Full spec: [`old/Task.1.md`](old/Task.1.md).
  Landed: convention plugins, version catalog, both entrypoints, dist isolation, empty
  registered mixin config, payload registrar, CI jar. Remaining: the
  `neoforge { runs { register("client") / register("server") } }` block (the L-45 blocker), the
  21.1.77 ↔ 21.1.238 pin reconciliation (one dedicated commit), then a headless-display
  (Xvfb) `runClient` harness that drives Share → second client joins, asserting listing + mesh
  (the L-45 exit). Deps: none. Related: `build-logic`, `.github/workflows`.
- **5b — The live validation lane.** ⏳ The single biggest remaining lane: wire the proven
  headless stacks to a real `ServerLevel`. Sub-deliverables, each consuming a green headless
  phase — capture (events-first, mixins-second: `BlockEvent` at the documented priorities,
  `EventPriority.LOW` capture-and-cancel contract per `COMPATIBILITY.md`) feeding 1c/1d;
  `SnapshotExtractor`/`PaletteMapper`; the real `WorldMutationApplier` adapter (1d) on the
  server main thread; the three 1g mixins (`LevelChunkMixin` single choke point, random/
  scheduled-tick suppression) + `ChunkTicketService` + `FakePlayerDetector`; live committee
  runs (1e 3-client MVP gate, 1f soak with lane metrics); renderer/applier consulting 2d's
  `ChunkLockMap`; the 2i/2j live commit/content/lifecycle adapters; `NeoForgeRelayTransport`
  (2a's relay impl — the permanent fallback lane); live forward sync (2c). Deps: **5a**;
  consumes 1c–1g, 2a–2j. Related: `server/**`, `mixin/**`, `transport-neoforge/**`; specs:
  [`old/Task.5.md`](old/Task.5.md)–[`old/Task.8.md`](old/Task.8.md),
  [`old/Task.11.md`](old/Task.11.md) (mod sections).
- **5c — HUD + command tree.** ✅ compile+headless. Full spec:
  [`old/Task.18.md`](old/Task.18.md). One pipeline: capture → `TelemetrySnapshot` (2k) → view
  model → surface; declarative `CommandTree`; colour = policy via the exhaustive `Palette`.
  Remaining: the manual on-server surface pass (screenshots, TPS budget) with 5a; the
  region/entity panels fill when 5b populates their providers (L-31). Deps: 2k, 5a.
- **5d — Multiplayer + share GUI.** 🚧 Full specs: [`old/Task.26.md`](old/Task.26.md),
  [`old/Task.31.md`](old/Task.31.md). Landed compile-clean + view-model-tested: the Nodera-only
  tabbed multiplayer screen (Worlds/Trackers/Rendezvous, live-reachability
  `MultiplayerStatusFeed`), piece-map grid, "Open to Nodera" in the LAN slot, search, create-
  world toggle + independent password field. Remaining: the live tracker feed on a timer (3b's
  announce scheduling), the worker-fed world supplier (6b `STATE`), join flow
  (tracker-resolve → rendezvous/socket dial), the `runClient` GUI pass (L-43/L-46 exits).
  Deps: 5a, 3b, 4b, 6b.
- **5e — Decentralized host lane.** 🚧 Full spec: [`old/Task.30.md`](old/Task.30.md). Landed:
  30a (dist-agnostic role-driven host), 30b (Share screen; create-time and share-later run one
  code path), 30f (infra-only dev script). Remaining: 30c genesis-from-existing-world +
  self-cert by the hosting identity (relocates 2c's genesis primitive; L-20 unchanged —
  signer moves, trust root doesn't); 30d password-change **full re-manifest** (convergent
  encryption ⇒ new key = new ciphertext = new `manifestRoot`; UI must warn); 30e live
  `RendezvousPeerTransport` composition + password → `WorldKeyMaterial` per-piece encryption.
  With Task 6 linked, hosting delegates to the worker (6c) instead of the in-JVM peer.
  Deps: 2c, 2h, 4b; 6c for delegation.
- **5f — World identity + permissions (mod half).** 🚧 Full spec:
  [`old/Task.33.md`](old/Task.33.md). Landed: signed `WorldIdentity` minted by the worker
  (`WORLDID` verb) persisted as `nodera-world.dat` (atomic `NoderaWorldStore`), auto-re-share
  on load, author-only password UI, host op-grant, `WorldRole`/`WorldPermissionGrant`/
  `WorldPermissions` evaluator (types owned by 2c's `storage-api`). Remaining: the
  `WorldSelectionListEntryMixin` per-row public badge + live player count (repo's first mixin —
  minimal, documented in `COMPATIBILITY.md`), grant announce/gossip over tracker + mesh,
  `BANNED` enforced at `JOIN`, remote-joiner op-grant, password network propagation (rides 2h
  live). Deps: 6b (counts), 6c (gossip), 5a (mixin verify). L-49.
- **5g — Companion presence gate.** ✅ Full spec: [`old/Task.32.md`](old/Task.32.md) §32c.
  `CompanionGate.requireRunning()` probes `127.0.0.1:25610` at client setup; absent ⇒ NeoForge
  aborts with the actionable install-URL error; version skew classified ("update the app" vs
  "update the mod"); `CompanionLink` holds the verified connection; `companion.required`
  defaults ON (`false` opt-out in `nodera-client.toml`). Deps: 6a.

## Testing strategy

- **Headless first, always**: view models in `diagnostics` (2k), value types
  (`ShareOptionsTest`, `NoderaWorldStoreTest`, `CompanionGateTest` with a real loopback
  `ServerSocket`), dist-classload guard tests (no `client/*` class loads on a dedicated
  server). These ride the normal gate.
- **Compile-clean against the pinned NeoForge** is the acceptance floor for every screen;
  `nodera.mixins.json` stays valid.
- **The 5a harness is the multiplier**: once `runClient` exists (Xvfb + log/screenshot
  assertions), the deferred GUI acceptances of 5c/5d/5e/5f run as one batch (the "GUI-deferred
  pool" — do them together).
- Live-lane ITs (5b) reuse the standing debugger scenarios: 3-client quorum, failover,
  byzantine, cross-region, interference soak on a normal world — now against real servers.
- Manual smoke lane: `scripts/dev.sh` + the real `~/.minecraft` client (`--install-mod`),
  documented per run.

## Limitations

Owned rows in [`LIMITATIONS.md`](LIMITATIONS.md): **L-45** (no automated real-client GUI
harness — the 5a exit), **L-31** (HUD placeholder panels until 5b), **L-43** RETIRING
(multiplayer GUI live pass), **L-46** (piece-map/tabs/badge live feeds), **L-49** (Task 33 live
halves: mixin, committee-over-worker, grant gossip, password propagation, worker seeding),
**L-47/L-48** shared with Tasks 6/7. §A A-7 (mixin minimalism) binds 5b/5f. The mod-compat
contract is `COMPATIBILITY.md` (normative; event ordering, fake players, palette exclusion,
tickets, async-write rules). Prior art for the capture/suppression design:
[`minecraft/folia/`](minecraft/folia/) (thread-context guards),
[`minecraft/MultiPaper/`](minecraft/MultiPaper/) (ownership takeover).

## Acceptance criteria

1. 5a: `runClient`/`runServer` launch from Gradle; version pin reconciled; the Xvfb harness
   drives Share → a second client sees + joins through tracker + rendezvous/socket (L-45 exit).
2. 5b: the legacy live acceptance of old Tasks 5–8/11 on real servers — zero unexplained
   divergence soak, MVP-gate 3-client scenario, >90% committee-commit soak with guard counters
   stable, tickets/pearl tests, `ExternalDelta` conversion live.
3. 5c: manual surface pass green (tab/boss/action-bar with correct colours, no TPS
   regression); L-31 exits when 5b populates ownership.
4. 5d: multiplayer page lists live worlds with counts/health/countdown; piece map fills green
   as pieces arrive; join flow works end-to-end (L-43/L-46 exits).
5. 5e: `NoderaHostIT`-class proofs for genesis-from-world + password re-key (old keys fail
   closed); a shared world survives its host closing Minecraft (with 6c).
6. 5f: per-row badge + live count via the mixin; a BANNED peer refused at join; only the author
   changes the password (L-49 exit).
7. Both toolchains green; README/Tested + this status table updated every outcome-changing
   commit; issues per `.github/ISSUE_SYSTEM.md`.

## Notes for the implementing model

- **This module wires; it never re-implements.** Every behaviour has a green headless twin in
  Tasks 1/2 — 5b is adapters over existing seams (`MutableWorldView`, `CommitListener`,
  capture sinks, providers). If a seam is missing, add it in the owning task, headless-tested,
  then consume it here.
- Dist discipline is non-negotiable: `net.minecraft.client.*` only under
  `dev.nodera.mod.client`, guarded by `Dist.CLIENT`; a dedicated server must never classload
  it (guard test exists — keep it green).
- Mixins are a last resort: events first; every mixin carries a "why an event was not enough"
  header and a `COMPATIBILITY.md` note (A-7). `LevelChunkMixin` is the only write choke point.
- The gate (5g) must fail closed with an actionable message — never a stack trace, never a
  silent no-network degrade.
- Passwords: never serialized, never in `toString`, never sent beyond the loopback trust
  boundary; a password change is a full re-manifest — surface the warning in the UI.
- One task = one branch = one PR; land phases as reviewable increments behind their GitHub
  issues, gate green at each.
