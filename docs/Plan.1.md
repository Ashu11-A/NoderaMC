Plan — Make the Nodera companion fully functional (real worker data, world identity/authorship, P2P 
 permissions + validation)

 Context

 The Task 32 peer worker (nodera-headless) + presence gate landed and run, but the companion app shows
 placeholder data and several player-facing features are stubbed or buggy:

 - The Tauri dashboard renders an empty Metrics struct — the worker exposes only a presence probe, no
 state.
 - Bug: the multiplayer screen's Trackers/Rendezvous tabs show "No trackers configured" / "No
 Rendezvous
 configured" even though ~/.minecraft/config/nodera-client.toml is correct — because
 NoderaMultiplayerScreen.setTrackerSupplier/setRendezvousSupplier have zero callers (confirmed).
 - Shared singleplayer worlds are not shown "like a server" with connected players.
 - A shared world has no persisted identity: worldId is an interim SHA-256(saveName); there is no
 per-world
 file, no author, no persisted "opened to Nodera" flag, no password authority.
 - No P2P permission/operator model and no live world/chunk validation.

 Decisions (from the user): (1) the persistent worker identity is the world author — hosting delegates
 to the worker via control verbs; (2) full validation + permissions implementation; (3) password
 change =
 authority check + local re-key now, network re-encryption propagation later; (4) the singleplayer
 world
 list uses the repo's first mixin for true per-row rendering.

 Outcome: the companion window shows live worker data; the multiplayer tabs read real config/state;
 shared
 worlds carry a signed identity (unique hash + author), appear server-like in the world list, auto
 re-share on
 load, and only the author can change the password; a P2P operator-permission model and
 committee-backed
 world/chunk validation run over the worker's PeerRuntime.

 ---
 Architecture: the worker becomes the network identity + host

 Because author = worker identity, the mod stops standing up its own in-JVM host PeerRuntime and
 delegates
 the control plane to the worker over the loopback control channel. The worker owns: the persistent
 identity
 (PersistentIdentityStore), rendezvous registration, tracker announce, world identities, permissions,
 and
 password authority. The integrated server (the save) remains the source of world content; the
 data-plane
 seeding of that content is the one part kept as a first-increment (see Phase D/G).

 New control verbs on ControlProtocol/ControlServer (line-based request → JSON/line reply), mirrored
 by the
 mod's CompanionClient and the Rust control.rs:

 ┌───────────────────────────────────────────┬────────────────────────────────────────────────────┐
 │                   Verb                    │                      Purpose                       │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-STATE 1                            │ dashboard/HUD metrics snapshot (JSON)              │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-IDENTITY 1                         │ worker NodeId + public key (author identity)       │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-HOST 1 <worldId> <name>            │ start hosting a world (announce+register+seed)     │
 │ <optionsJson>                             │                                                    │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-JOIN 1 <worldId>                   │ resolve + join a world                             │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-STOP 1 <worldId>                   │ stop hosting                                       │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-PASSWORD 1 <worldId> <newPwHash>   │ author-only re-key (worker verifies its id ==      │
 │                                           │ author)                                            │
 ├───────────────────────────────────────────┼────────────────────────────────────────────────────┤
 │ NODERA-STATUS 1 <worldId>                 │ per-world players/health/permissions               │
 └───────────────────────────────────────────┴────────────────────────────────────────────────────┘

 The probe (NODERA-PROBE) stays. ControlProtocol.PROTOCOL_VERSION bumps to 2 (mod + Rust mirror it;
 the gate
 already reports version skew).

 ---
 Phase A — Fix the multiplayer-tab config bug (small, do first)

 Wire the suppliers that are never called. In ClientBootstrap.onClientSetup (already runs at
 FMLClientSetupEvent, config is loaded), set them from config + worker state:

 - NoderaMultiplayerScreen.setTrackerSupplier(...) → build TrackerEndpointStatus rows from
 NoderaConfig.CLIENT_TRACKER_ENDPOINTS.get(); live reachable/worldsIndexed/secondsSinceAck come from
 the worker STATE (Phase B) — until then, rows render as configured (offline/unknown), which already
 fixes
 the "No trackers configured" message.
 - setRendezvousSupplier(...) → same from CLIENT_RENDEZVOUS_ENDPOINTS.
 - setWorldSupplier(...) → the worker's hosted/known worlds + tracker query (own worlds appear).

 Reuse the existing view models TrackerStatusView/RendezvousStatusView/TorrentWorldListView — only the
 data wiring is missing. Files: client/ClientBootstrap.java,
 client/multiplayer/NoderaMultiplayerScreen.java (suppliers already present).

 Phase B — Worker telemetry → dashboard real data (the headline)

 Worker (Java): in HeadlessPeerMain, build a DiagnosticsCollector over the existing
 TrafficMeter+MessageCounters and .register(runtime) (mirror NoderaPeerService), plus a small
 WorkerState provider (maintained pieces/bytes from the content store / ArchiveInventory, peers from
 the
 session view, hosted worlds). Add a StateProvider seam to ControlServer and handle NODERA-STATE by
 writing a compact JSON snapshot (hand-written — no new dependency; fields map 1:1 to the Rust
 Metrics).

 Control protocol: add STATE/IDENTITY constants to peer-runtime ControlProtocol; ControlServer
 gains a StateProvider. Bump PROTOCOL_VERSION → 2.

 Rust app: control.rs — add fetch_state(addr) -> Option<Metrics> (send NODERA-STATE 1, parse JSON via
 serde_json, already a dep). main.rs metrics pump: each second, fetch_state and
 emit("nodera://metrics")
 the real snapshot (replaces the empty snapshot()), and keep daemon_up from the probe. metrics.rs
 struct
 already matches; App.tsx already renders it → real GB/peers/chunks/world appear.

 Files: nodera-headless/HeadlessPeerMain.java, new nodera-headless/.../WorkerState.java,
 peer-runtime/control/{ControlProtocol,ControlServer}.java, rust/nodera-app/src/{control.rs,main.rs}.

 Phase C — Per-world identity + authorship + shared-state persistence

 New signed record WorldIdentity in storage-api (next to GenesisManifest, append-only new TypeTag):
 { Bytes worldId, NodeId authorNodeId, Bytes authorPublicKey, long createdAtEpoch, boolean shared,
 boolean listedOnTracker, boolean encrypted, Bytes manifestRef, Bytes signature }, canonical
 Encodable;
 worldId = SHA-256(genesisRoot ‖ authorPublicKey ‖ salt) (stable, rename-proof, unique). The author
 signs the
 canonical pre-signature bytes with NodeIdentity.sign() (existing).

 - On first "Open to Nodera": the worker (author identity) builds + signs WorldIdentity; the mod
 persists
 it to the save dir at server.getWorldPath(LevelResource.ROOT)/nodera-world.dat (canonical bytes).
 - On world load (ServerStartedEvent in server/ServerBootstrap): read nodera-world.dat; if present and
 shared, auto re-share (auto re-open to Nodera) — so the host always restores the shared status.
 - Replaces the interim NoderaHost.worldId(saveName); worldId now comes from the persisted record.

 Files: new storage-api/WorldIdentity.java (+ tag), common/NoderaHost.java (write/read/re-share),
 server/ServerBootstrap.java (load-time re-share hook), common/NoderaWorldStore.java (new — read/write
 the
 save file), tag registry snapshot test in core/storage-api.

 Phase D — Delegate host/join to the worker (control verbs)

 NoderaHost.activate/deactivate and the join path call the worker through CompanionClient (new HOST/
 JOIN/STOP/IDENTITY verbs) instead of NoderaPeerService.startHost's in-JVM PeerRuntime. The worker
 runs
 the rendezvous register + tracker announce loop (moved from NoderaPeerService), keyed by the real
 worldId.
 CompanionLink already holds the connection.

 Data plane (first increment): the mod extracts region pieces with the existing
 distribution.RegionSnapshotSplitter/PieceManifest and hands the manifest + save path to the worker on
 HOST; the worker seeds. Full genesis-from-world extraction + certified checkpoints remain the
 documented
 live-lane (T9/30c). Keep the mod's in-JVM path behind a fallback flag for environments without a
 worker.

 Files: common/{NoderaHost,NoderaPeerService,CompanionClient}.java,
 peer-runtime/control/ControlServer.java, nodera-headless/HeadlessPeerMain.java.

 Phase E — Password authority (only original author) + local re-key

 WorldIdentity.authorNodeId is the sole password authority. ShareWorldScreen: the password field is
 editable only when the worker identity (NODERA-IDENTITY) equals authorNodeId; otherwise it is
 disabled with
 "only the original host can change the password". On change → NODERA-PASSWORD verb → worker verifies
 self == author, re-derives the ContentKey (existing Task 23 PasswordKeyDerivation), rewrites +
 re-signs
 WorldIdentity (new manifestRef), persists, and marks the world for re-seed. Network ciphertext
 replacement propagation is deferred (rides the live T23 lane) — documented.

 Files: client/share/ShareWorldScreen.java, common/NoderaHost.java,
 peer-runtime/control/ControlServer.java, worker password handler.

 Phase F — Singleplayer world list mixin (server-like per-row display)

 First repo mixin. client/mixin/WorldSelectionListEntryMixin injects into the vanilla world-list entry
 render
 to draw, for any save whose nodera-world.dat marks it shared, a public badge + live connected-player
 count
 (server-like), reusing PublicWorldBadgeView (already formats "● N online"). Player counts come from
 the
 worker STATE/STATUS (Phase B/D). Register in nodera.mixins.json client[]; verify the
 neoforge.mods.toml [[mixins]] config="nodera.mixins.json" entry; add a note to COMPATIBILITY.md (A-7,
 minimal mixin). Retire the approximate SelectWorldScreenAddon overlay for per-row placement.

 Files: new client/mixin/WorldSelectionListEntryMixin.java, resources/nodera.mixins.json,
 resources/META-INF/neoforge.mods.toml (verify), client/worldlist/SelectWorldScreenAddon.java (fold
 in),
 docs/COMPATIBILITY.md.

 Phase G — P2P operator-permissions + world/chunk validation (the large lane)

 Permission model. New signed WorldPermissionGrant (core or storage-api, new tag):
 { Bytes worldId, NodeId subject, Role role, long version, Bytes signature }, Role ∈ {OWNER, OPERATOR,
 MEMBER, BANNED}; the world author is OWNER implicitly. Only the OWNER (or an OPERATOR, per policy)
 signs
 grants; newer version supersedes (revocation). Distribution: grants are author-signed,
 content-addressed,
 announced to the tracker (append a permissions field to the announce family) and gossiped over the
 PeerRuntime; every peer verifies signatures against authorNodeId. Enforcement: on JOIN, the
 host/committee
 checks the joiner's NodeId (BANNED → refuse); in-game, the sharing player is granted MC operator on
 their
 integrated server (server.getPlayerList().op(gameProfile) / permission level 4), and remote joiners
 get op
 iff their grant is OWNER/OPERATOR.

 World/chunk validation + revalidation (wire the existing headless machinery live over the worker).
 - Join-time world validation: verify the WorldIdentity signature (author) + the certified event chain
 via
 storage-eventsourced.EventReplayer → the committed StateRoot; verify each fetched piece by hash
 (distribution.PieceReassembler already does hash-validate-before-accept).
 - Live region validation: wire coordinator (RegionAllocator/RegionPipeline/LeaseManager) +
 committee (CommitteeSession/CommitteeMember/MemberBallot, 2-of-3 quorum) over the worker's
 PeerRuntime transport so committee members re-execute + vote and commit region deltas — the existing
 headless CommitteeMvpIT pipeline, now live.
 - Revalidation: committee.SpotCheckAuditor (existing) periodically samples committed regions and
 re-executes; coordinator.DelegabilityMonitor/ReliabilityScorer drive re-check cadence; a returning
 peer
 revalidates from the certified checkpoint forward. Equivocation → slash (existing consensus
 EquivocationDetector).

 This lane is T7/T11/T16-scale; it is the bulk of the work and will be delivered as its own reviewable
 increments (permission records + tracker/gossip distribution + op-granting first; live committee
 wiring +
 revalidation second). The headless validators already exist and are tested — this wires them to the
 live
 worker mesh.

 Files: new storage-api/WorldPermissionGrant.java (+tag) + core Role; worker permission store +
 gossip;
 protocol/discovery announce family (append permissions); committee/coordinator live adapters in
 nodera-headless; server/ServerBootstrap.java (op-grant on share/join); docs/Task.33.md (new spec for
 the
 permissions+validation program) + LIMITATIONS.md rows.

 ---
 Files to modify (representative, by area)

 - Bug/UI wiring: neoforge-mod/.../client/ClientBootstrap.java,
 .../client/multiplayer/NoderaMultiplayerScreen.java.
 - Worker + control: peer-runtime/.../control/{ControlProtocol,ControlServer}.java,
 nodera-headless/.../HeadlessPeerMain.java (+ new WorkerState.java, permission/host handlers).
 - Rust app: rust/nodera-app/src/{control.rs,main.rs,metrics.rs}, ui/src/App.tsx (already renders).
 - World identity/perms: new storage-api/{WorldIdentity,WorldPermissionGrant}.java (+ append-only
 tags),
 core Role,
 neoforge-mod/.../common/{NoderaHost,NoderaWorldStore,NoderaPeerService,CompanionClient}.java,
 .../server/ServerBootstrap.java, .../client/share/ShareWorldScreen.java.
 - Mixin: new neoforge-mod/.../client/mixin/WorldSelectionListEntryMixin.java,
 resources/nodera.mixins.json, resources/META-INF/neoforge.mods.toml (verify).
 - Validation live wiring: committee/coordinator/consensus (adapters, no contract change),
 storage-eventsourced.EventReplayer, distribution.RegionSnapshotSplitter/PieceReassembler (reuse).
 - Docs/bookkeeping (every outcome-changing commit): README.md, Tested.md, docs/LIMITATIONS.md,
 docs/Roadmap.md, new docs/Task.33.md.
     tags),
     core Role,
     neoforge-mod/.../common/{NoderaHost,NoderaWorldStore,NoderaPeerService,CompanionClient}.java,
     .../server/ServerBootstrap.java, .../client/share/ShareWorldScreen.java.
     - Mixin: new neoforge-mod/.../client/mixin/WorldSelectionListEntryMixin.java,
     resources/nodera.mixins.json, resources/META-INF/neoforge.mods.toml (verify).
     - Validation live wiring: committee/coordinator/consensus (adapters, no contract change),
     storage-eventsourced.EventReplayer, distribution.RegionSnapshotSplitter/PieceReassembler (reuse).
     - Docs/bookkeeping (every outcome-changing commit): README.md, Tested.md, docs/LIMITATIONS.md,
     docs/Roadmap.md, new docs/Task.33.md.

     Verification (end-to-end)

     1. Gate: ./gradlew check + cd rust && cargo test green after each increment; new headless tests —
     WorldIdentity/WorldPermissionGrant canonical round-trip + signature + tamper-reject
     (core/storage-api);
     ControlServer STATE/IDENTITY/PASSWORD-authority tests (peer-runtime); tag-registry snapshot
     updated.
     2. Bug fix: with nodera-client.toml configured, run scripts/dev.sh; the multiplayer
     Trackers/Rendezvous
     tabs list the configured endpoints (not "No … configured"); assert via a headless
     TrackerStatusView/supplier test.
     3. Worker data: run the worker; printf 'NODERA-STATE 1\n' | nc 127.0.0.1 25610 returns a JSON
     snapshot;
     cargo tauri dev (attach mode) shows live chunks/GB/peers/world in the dashboard.
     4. World identity/persistence: "Open to Nodera" writes <save>/nodera-world.dat with a signed
     WorldIdentity; reopening the world auto-re-shares; a second install cannot change the password
     (field
     disabled / NODERA-PASSWORD rejected); the author can.
     5. World list: a shared world shows a per-row public badge + live player count in the singleplayer
     list
     (mixin), matching a server row.
     6. Permissions/validation: the sharer is OP in-game; a BANNED grant refuses join; a signed grant
     is verified
     against the author; a joined peer validates the WorldIdentity + certified chain; committee members
     re-execute + commit a region delta live (the CommitteeMvpIT scenario over the worker mesh);
     SpotCheckAuditor revalidates a sample.
     7. scripts/dev.sh --with-app runs worker + Tauri together; companion.required gate still aborts MC
     without a
     worker.

     Notes / risks

     - Phase G ("full" validation + permissions) is the large, multi-session lane (T7/T11/T16-scale);
     it lands as
     its own increments behind a new docs/Task.33.md, reusing the already-tested headless committee/
     coordinator/consensus/storage-eventsourced modules — the work is live wiring + the permission
     control-plane, not new consensus algorithms.
     - Password network re-encryption propagation is deferred (local re-key + re-sign now) per the
     decision.
     - The first mixin must stay minimal (A-7); document it in COMPATIBILITY.md.
     - Control protocol version bumps to 2; keep the mod, worker, and Rust control.rs constants in
     lockstep (the
     gate already classifies skew).