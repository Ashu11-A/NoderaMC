# Minecraft — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: This register is the minecraft category's structural-debt ledger. Source:
     `build/jscpd/jscpd-report.json` (81 clones touch a `neoforge-mod` file; 37 are internal to the
     module) cross-joined with `build/loccount.txt` for line counts, plus a manual pass for god
     classes and dead code. Update it whenever a refactor lands (mark the row DONE and move it to a
     "Completed" note rather than deleting it) and whenever jscpd/loccount are regenerated. It is
     scope-limited to `endpoints/neoforge-mod/` and excludes `build/`. -->

Snapshot date: **2026-07-28**. Sources: jscpd duplication report + loccount line counts + a manual
pass over the largest classes. `% duplicated` = (sum of duplicated line-runs in clones touching the
file ÷ loccount file lines) × 100, to one decimal; `—` means jscpd flagged nothing (a manual candidate).

Paths are relative to this folder (`../../endpoints/neoforge-mod/…`).

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---:|---|---|
| [`…/mod/NoderaClientMod.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/NoderaClientMod.java) | 22 | 36.4 | `NoderaMod.java` (entrypoint boilerplate) | Tiny stub; share one `@Mod` entrypoint helper for the dist split. Low value — defer. |
| [`…/client/create/NoderaCreateOptionsScreen.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/create/NoderaCreateOptionsScreen.java) | 132 | 35.6 | `ShareWorldScreen`, `DeleteWorldScreen`, `JoinPasswordScreen`, `NoderaMultiplayerScreen`, `PieceMapScreen` | Rebuild on `GridLayout` (Task 11); the duplicated `init`/`addRenderableWidget` block becomes one shared options widget. |
| [`…/client/create/CreateWorldNoderaAddon.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/create/CreateWorldNoderaAddon.java) | 52 | 34.6 | `MultiplayerScreenAddon`, `PauseScreenShareAddon`, `TitleScreenAddon` | One `ScreenAddon` base for the six injection sites (button-in-layout + event subscribe). |
| [`…/client/multiplayer/PieceMapScreen.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/PieceMapScreen.java) | 56 | 26.8 | `NoderaCreateOptionsScreen`, `NoderaMultiplayerScreen` | Fold into the shared screen-layout helper above. |
| [`…/common/RegionSeedSpoolTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/RegionSeedSpoolTest.java) | 216 | 26.4 | worker/peer test fixtures | Extract a shared `SeedFixture` into `:testing` (the clone is the fixture, not the logic). |
| [`…/server/entity/VanillaCancelGateTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/lane/VanillaCancelGateTest.java) | 60 | 25.0 | core test fixtures | Shared test setup helper; low risk. |
| [`…/common/WorldGenesisService.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/WorldGenesisService.java) | 195 | 23.6 | `NoderaHost`, `NoderaWorldStore`, `LiveEntityLaneRuntime`, `ServerEntityWorldView`, `WorldPermissionStore` | The genesis/identity encode header is echoed widely; extract a `GenesisIo` helper used by all five. |
| [`…/mod/NoderaMod.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/NoderaMod.java) | 39 | 20.5 | `NoderaClientMod.java` | As `NoderaClientMod` above — one shared entrypoint helper. |
| [`…/mixin/LevelChunkMixin.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/mixin/LevelChunkMixin.java) | 44 | 20.5 | `ServerLevelRandomTickMixin` | Shared mixin preamble (`@Mixin` + region-guard import header) — extract a static helper, keep the three mixins minimal. |
| [`…/client/multiplayer/PanelWidget.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/PanelWidget.java) | 70 | 20.0 | `PieceMapWidget` | **Delete** in favour of `ObjectSelectionList` subclasses (Task 11, MC-GUI-2). |
| [`…/mixin/ServerLevelRandomTickMixin.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/mixin/ServerLevelRandomTickMixin.java) | 45 | 20.0 | `LevelChunkMixin` | As above — shared mixin preamble. |
| [`…/server/shadow/BlockCaptureRulesTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/lane/BlockCaptureRulesTest.java) | 108 | 18.5 | `WorkerPiecesParserTest`, storage fixtures | Shared Minecraft-free rule-test setup. |
| [`…/common/CompanionClientRekeyTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/CompanionClientRekeyTest.java) | 119 | 17.6 | `WorldGenesisService`, core/worker fixtures | Shared identity/stand-in-worker fixture. |
| [`…/client/multiplayer/MultiplayerScreenAddon.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/MultiplayerScreenAddon.java) | 38 | 15.8 | `CreateWorldNoderaAddon` | As the `ScreenAddon` base above. |
| [`…/common/WorldArchiverStreamingTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/WorldArchiverStreamingTest.java) | 51 | 15.7 | `WorldArchiverPackToSpoolTest` | One `WorldArchiverFixture` for the two archive tests. |
| [`…/common/WorldArchiverPackToSpoolTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/WorldArchiverPackToSpoolTest.java) | 52 | 15.4 | `WorldArchiverStreamingTest` | As above. |
| [`…/common/CompanionProtocolContractTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/CompanionProtocolContractTest.java) | 86 | 15.1 | `core/TypeTagsTest` | Shared canonical-encoding test harness. |
| [`…/common/CompanionGateTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/control/CompanionGateTest.java) | 117 | 13.7 | storage/transport fixtures | Shared loopback-socket test base. |
| [`…/common/NoderaWorldStoreTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/world/NoderaWorldStoreTest.java) | 52 | 13.5 | storage fixtures | Shared identity-file test fixture. |
| [`…/common/NoderaNodeAnnouncePayload.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaNodeAnnouncePayload.java) | 45 | 13.3 | `NoderaLanePlanPayload` | Shared `CustomPacketPayload` record header (StreamCodec + TYPE) — a sealed base or helper. |
| [`…/common/WorkerStateParserRendezvousTest.java`](../../endpoints/neoforge-mod/src/test/java/dev/nodera/mod/common/WorkerStateParserRendezvousTest.java) | 152 | 13.2 | peer diagnostics-view fixtures | Shared STATE-JSON test fixture across the view tests. |
| [`…/common/NoderaSessionPayload.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaSessionPayload.java) | 61 | 13.1 | `NoderaLanePlanPayload` | As `NoderaNodeAnnouncePayload` — shared payload header. |
| [`…/common/NoderaLanePlanPayload.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaLanePlanPayload.java) | 112 | 12.5 | `NoderaNodeAnnouncePayload`, `NoderaSessionPayload` | As above. |
| [`…/client/multiplayer/JoinPasswordScreen.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/JoinPasswordScreen.java) | 117 | 12.0 | `NoderaCreateOptionsScreen` | Rebuild on `GridLayout` (Task 11). |
| [`…/client/title/TitleScreenAddon.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/title/TitleScreenAddon.java) | 52 | 11.5 | `CreateWorldNoderaAddon` | As the `ScreenAddon` base; also fixes MC-GUI-1 (duplicate entry point). |
| [`…/common/CompanionClient.java`](../../library/java/endpoint/src/main/java/dev/nodera/endpoint/control/CompanionClient.java) | 568 | 10.4 | self (3 clones: 107-113↔331-338↔354-361, 426-438↔507-519), `peer/ControlHandler`, worker fixtures | Extract the repeated control-verb request/response envelope into one `controlVerb(...)` helper — each new verb today re-clones the scaffolding. |
| [`…/client/multiplayer/PieceMapWidget.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/PieceMapWidget.java) | 137 | 10.2 | `PanelWidget` | Fold into the selection-list replacement (Task 11). |
| [`…/server/entity/MinecraftEntityAdapters.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/MinecraftEntityAdapters.java) | 92 | 9.8 | core `EntityEventsTest` | Shared adapter header; minor. |
| [`…/common/NoderaWorldStore.java`](../../library/java/endpoint/src/main/java/dev/nodera/endpoint/world/NoderaWorldStore.java) | 62 | 9.7 | `WorldGenesisService` | Shared identity-file IO header with `WorldGenesisService`. |
| [`…/server/entity/ObserverOwnershipTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/lane/ObserverOwnershipTest.java) | 99 | 9.1 | engine `CommitteeScoringTest` | Shared ownership-plan test fixture. |
| [`…/common/AnnounceChallengesTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/share/AnnounceChallengesTest.java) | 75 | 8.0 | storage fixtures | Shared challenge test fixture. |
| [`…/common/WorkerPiecesParserTest.java`](../../library/java/endpoint/src/test/java/dev/nodera/endpoint/state/WorkerPiecesParserTest.java) | 103 | 7.8 | `BlockCaptureRulesTest` | Shared worker-reply test fixture. |
| [`…/server/shadow/LiveSnapshotExtractor.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/shadow/LiveSnapshotExtractor.java) | 162 | 7.4 | `BlockWriteGuard`, peer `DistFixtures` | Shared region/section import header with `BlockWriteGuard`. |
| [`…/server/entity/ObserverLaneRuntime.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/ObserverLaneRuntime.java) | 109 | 7.3 | `EntityCaptureBridge` | Shared lane-runtime install header; minor. |
| [`…/client/multiplayer/NoderaJoinFlow.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/NoderaJoinFlow.java) | 233 | 7.3 | `NoderaContinuity`, self (171-180↔210-219) | Extract the duplicated route-resolve/error-screen block (the self-clone) into one `joinScreenForResult(...)`. |
| [`…/common/WorkerStateParser.java`](../../library/java/endpoint/src/main/java/dev/nodera/endpoint/state/WorkerStateParser.java) | 306 | 6.9 | self (62-82↔154-174, 21 lines) | Two near-identical JSON parse blocks — extract a `parseSection(node, fields)` helper. |
| [`…/common/NoderaPeerService.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaPeerService.java) | 946 | 6.7 | self, `peer/TrackerClient`, worker `HeadlessPeerMain`/`WorkerControlHandler` | **God class.** Decompose into `HostPeerService` / `ClientPeerService` / `TransportComposition`; the host↔client peer lifecycle is the seam MC-JOIN-2/3 concentrate on. |
| [`…/client/entity/ClientValidationLane.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/entity/ClientValidationLane.java) | 181 | 6.6 | core/peer fixtures | Shared client-lane test header; minor. |
| [`…/client/share/PauseScreenShareAddon.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/share/PauseScreenShareAddon.java) | 93 | 6.5 | `CreateWorldNoderaAddon` | As the `ScreenAddon` base. |
| [`…/client/multiplayer/NoderaMultiplayerScreen.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/NoderaMultiplayerScreen.java) | 233 | 6.0 | `NoderaCreateOptionsScreen`, `PieceMapScreen` | Rebuild on `HeaderAndFooterLayout` + `TabNavigationBar` (Task 11, MC-GUI-3). |
| [`…/debug/command/SelfTest.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/debug/command/SelfTest.java) | 391 | 5.9 | `NoderaHost`, `peer/WorkerEvent` | Extract the report-emission struct shared with `WorkerEvent`; the command tree walker otherwise stays. |
| [`…/client/share/ShareWorldScreen.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/share/ShareWorldScreen.java) | 188 | 5.9 | `NoderaCreateOptionsScreen` | Rebuild on `GridLayout` (Task 11). |
| [`…/server/entity/ServerEntityWorldView.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/ServerEntityWorldView.java) | 366 | 5.5 | `WorldGenesisService`, engine `ContainerRules`/`MobAiRulesTest` | Shared adapter header; the projection/apply logic stays cohesive. |
| [`…/server/entity/LiveEntityLaneRuntime.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/LiveEntityLaneRuntime.java) | 587 | 5.5 | self (253-260↔323-330, 260-265↔330-335), `WorldGenesisService` | Two self-clones in the lane's commit path — extract the duplicated submit/verify block. |
| [`…/server/shadow/BlockWriteGuard.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/shadow/BlockWriteGuard.java) | 112 | 5.4 | `LiveSnapshotExtractor` | Shared import header; minor. |
| [`…/common/ModTelemetry.java`](../../library/java/endpoint/src/main/java/dev/nodera/endpoint/telemetry/ModTelemetry.java) | 287 | 4.4 | worker `WorkerTelemetryServiceTest` | Test-side clone only; no production duplication. |
| [`…/common/NoderaConfig.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaConfig.java) | 290 | 2.8 | self (141-148↔205-211) | Two duplicated config-validation blocks (rendezvous vs tracker route parse) — already split on purpose, but the 8-line run could share a validator. |
| [`…/server/entity/EntityCaptureBridge.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/EntityCaptureBridge.java) | 392 | 2.0 | `ObserverLaneRuntime` | Shared lane header; cohesive otherwise. |
| [`…/client/multiplayer/NoderaContinuity.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/NoderaContinuity.java) | 450 | 1.6 | `NoderaJoinFlow` | One small join block shared with the flow; cohesive otherwise. |
| [`…/common/NoderaHost.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/NoderaHost.java) | 1307 | 1.5 | `WorldGenesisService`, `SelfTest` | **God class — highest structural priority.** Mixes host lifecycle, identity, join-gate arming, entity-lane bootstrap, resident-seat dispatch, presence refresh, worker notify, and game-server publish. Split into `HostIdentityService`, `HostGameServer`, `HostEntityLaneBootstrap`, `HostPresence`. Hosts MC-JOIN-3 (server-thread activation). |
| [`…/debug/command/NoderaCommand.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/debug/command/NoderaCommand.java) | 628 | — | (not jscpd-flagged) | **Long command tree.** Not duplicated, but one file holds every `/nodera` subcommand executor. Split per area (`share`, `op`, `debug`, `telemetry`) once Task 11's GUI work lands. |
| [`…/common/WorldArchiver.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/WorldArchiver.java) | 326 | — | (not jscpd-flagged) | Cohesive; no action beyond keeping the pack/stream/seed paths separate as it grows. |
| [`…/server/shadow/BlockCaptureBridge.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/shadow/BlockCaptureBridge.java) | 271 | — | (not jscpd-flagged) | Cohesive; the `Sink`/`DISABLED` seam is the contract, keep it. |
| [`…/common/ModNetworking.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/ModNetworking.java) | 268 | — | (not jscpd-flagged) | Cohesive payload-registrar; hosts MC-JOIN-2 (move `onServerSessionInfo` peer bring-up off the render thread as part of Task 10). |
| [`…/server/ServerBootstrap.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/ServerBootstrap.java) | 293 | — | (not jscpd-flagged) | Cohesive; no action. |
| [`…/client/multiplayer/MultiplayerWorldFeed.java`](../../endpoints/neoforge-mod/src/main/java/dev/nodera/mod/client/multiplayer/MultiplayerWorldFeed.java) | 250 | — | (not jscpd-flagged) | Cohesive merge point; hosts MC-JOIN-1 (replace the hardcoded `HEALTHY`/`10_000` with the readiness predicate as part of Task 10). |

## Sequencing

Ordered so each step unblocks or pays for the next, and so refactors that retire an open limitation
land first.

1. **`NoderaHost.java` (1307 lines) — split the host-lane god class.** The single largest file and the
   one that concentrates the most live-lane risk (it hosts MC-JOIN-3, the server-thread activation).
   Splitting into `HostIdentityService` / `HostGameServer` / `HostEntityLaneBootstrap` / `HostPresence`
   makes the thread-context boundaries the file already documents in Javadoc into actual class
   boundaries. Do this first: every later host-lane change is safer against smaller units.
2. **`CompanionClient.java` (568, 10.4%, three self-clones) — extract the control-verb envelope.** The
   highest duplication in production code. The clones at `107-113↔331-338↔354-361` and `426-438↔507-519`
   are the verb request/response scaffolding copy-pasted per verb; a single `controlVerb(...)` helper
   removes them and stops every future verb regenerating the clone.
3. **`NoderaPeerService.java` (946 lines) — decompose host vs client peer lifecycle.** The second
   largest file and the seam `NoderaHost` calls into. Splitting `HostPeerService` / `ClientPeerService`
   / `TransportComposition` is the natural follow-on to step 1 and is where the MC-JOIN-2
   render-thread bring-up gets moved off-thread cleanly.
4. **Screen-layout duplication cluster (Task 11 alignment).** `NoderaCreateOptionsScreen` (35.6%),
   `PanelWidget` (20%, to be deleted), `PieceMapWidget`/`PieceMapScreen`, and the six `*Addon`
   injection sites share the layout/inject boilerplate. Folding them onto vanilla layout objects is
   *already* Task 11's remit — doing it retires MC-GUI-1/2/3/4 directly, so the refactor pays twice.
5. **Payload + mixin header boilerplate — a shared base, last.** `NoderaLanePlanPayload` /
   `NoderaNodeAnnouncePayload` / `NoderaSessionPayload` (12–13% each) and the two mixin preambles (20%
   each) are mechanical clones. Extract the shared header *after* the structural splits so the base
   lands once and the noise that hides real duplication is gone.

## Notes

- **No long mixin chains.** The module ships exactly three mixins (`LevelChunkMixin`,
  `LevelTicksMixin`, `ServerLevelRandomTickMixin`) by charter design; the 20% duplication between the
  two is the preamble header only, not a chain.
- **No dead code identified** in this pass. The manual review found god classes (above) but no
  unreachable/unreferenced production classes — every `src/main` file is wired through `NoderaMod` /
  `NoderaClientMod` / `ServerBootstrap` / `ClientBootstrap`.
- Test-file clones (the bulk of the 10–25% rows) are fixture echoes across modules; they move when a
  shared fixture lands in `:testing`, not by editing the tests in isolation.
