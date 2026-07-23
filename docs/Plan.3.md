# Plan.3.md — Limitation Burn-Down Plan (full-register remediation)

> Goal (2026-07-23 directive): remove every §B limitation in
> [`LIMITATIONS.md`](LIMITATIONS.md). This plan is the consolidated remediation program:
> one section per outstanding row, grounded in a code audit of each limitation's actual
> enforcement points. Rows move to [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) as their
> exit tests go green; every removal ships a regression test suite so the limitation
> cannot silently recur.

## Method

1. **Audit** (done, this document): six parallel code audits clustered by owning task
   located, for every row, (a) the code that *enforces* the limitation today, (b) the
   infrastructure its fix builds on, (c) the distance to the exit test, split
   headless vs live-gated.
2. **Execute in dependency order** (§ Execution order below): headless-completable rows
   first, then live-gated halves batched per live series, then the multi-week lanes
   (T13→T14→T15→T16) as their own programs.
3. **Per-removal definition of done**: exit test green + a regression suite named in the
   row + `LIMITATIONS.md` row moved to `LIMITATIONS.fixed.md` + README progress bar
   incremented + related open issues commented (`L-<n> removed — unblocked`) or closed
   (if wholly dependent) — all in the same commit.

## Progress-bar increments

README bar starts at **74%**. Remaining 26 points map onto the 36 outstanding rows:

- Each **RETIRING** row retired: **+0.4%** (10 rows → 4 points).
- Each **OPEN** row retired: **+0.85%** (26 rows → ~22 points).
- Bar is recomputed and rounded to the nearest integer on every removal commit;
  reaching zero outstanding rows sets the bar to 100%.

## §A envelope constraints — not in scope for "removal"

A-1…A-7 are physics/platform facts; per the register they are *hidden*, never removed.
Their hiding mechanisms are owned by rows in this plan (A-1/A-3/A-6 → T16 rows,
A-4 → delivered, A-5 → delivered, A-7 → process). No §A entry blocks a §B removal.

---

## Per-row remediation plans

*(populated from the six audit agents — sections below)*

<!-- AUDIT-SECTIONS -->

### RETIRING lane — L-30, L-31, L-33, L-38, L-39, L-41, L-43, L-45, L-47 — audited 2026-07-23

- **L-30**: `PeerSyncFlow.syncForward` exists but is wired to NO transport (only its test
  calls it). Increment: events-since request/answer pair in `simulationmsg`, serve
  `RegionEventStore` over `PeerTransport`, fresh-peer forward-sync IT over loopback.
  **Headless NOW.**
- **L-31**: code clause MET (both live providers registered; `empty()` only when no lane —
  correct per the row). Remaining = live GUI evidence (boss-bar GREEN / ownedChunks>0)
  riding the e2e harness. Evidence, not code.
- **L-33**: `ChunkLockMap.isChunkEditable` has ZERO live consumers (tests only;
  `WorldMutationApplier.setBlock` unguarded). Increment: editability predicate injected
  into the write-guard path — locked chunk rejects mutation, headless. Renderer half
  GUI-gated.
- **L-38**: `RetentionPolicy` green but NO `RetentionIT` and the countdown never feeds
  the worker's tracker announce. Increment: `RetentionIT` over the real tracker binary +
  announce wiring. **Headless NOW.**
- **L-39**: password never reaches encryption — only a boolean `encrypted` flag crosses
  to the worker; `NoderaHost.reconfigure` is a comment stub; no join prompt; no attempt
  throttling. Increment: worker-side ContentKey derivation on SEED/HOST + throttle
  policy, headless IT; GUI join prompt stays gated.
- **L-41**: no test proves the SAME node's daemon survives its game's kill -9
  (`WorldContinuityIT` proves a different peer survives). Increment: ProcessBuilder IT —
  daemon + fake game process, kill game, daemon still answers `NODERA-ARCHIVE`.
  **Headless NOW.**
- **L-43**: GUI/CI-gated; shares the L-45 harness (fold create-world assertion into the
  e2e script).
- **L-45**: `e2e-live.yml` exists; only the first green CI run is missing (dispatchable).
- **L-47**: no CI job builds the Tauri app or runs the gate both ways. Increment: extend
  CI with app build + `CompanionGateTest` both ways + `WorldContinuityIT` as survival
  proof. **Headless/CI-code NOW.**

---

## Dead-code sweep (goal addendum, audited 2026-07-23)

A whole-repo reference scan (474 main `.java` files; simple-name grep across all main
sources so same-package use counts, cross-checked against Roadmap/PROGRESS/Task specs)
found **no genuinely dead production code**:

- **0 orphans.** Every zero-main-reference class is either the process entrypoint
  (`HeadlessPeerMain`, launched by scripts — a framework false-positive) or a
  **TEST-ONLY seam whose documented consumer has not landed yet** (17 classes), each
  mapped to its owning task/limitation row: `LocalReplicaView`→T16 renderer (#35),
  `EventSyncService`→T9 live wiring, `GenesisRecertification`→L-20 share flow,
  `JoinAttemptThrottle`/`Argon2KeyDerivation`→L-39 join flow, `ChunkLockEditability`→L-33
  mod wiring, `ActivePlayerStream`/`PeerShutdownHook`→L-40 live signals (the shutdown
  hook's worker wiring needs a piece PUSH transfer design — noted for round 2),
  `ArchiveManager`→T21 runtime, `InterferenceProbe`→T5 mixins,
  `PersistedCoordinatorState`→NoderaSavedData, `DelegabilityMonitor`→T11 tickets,
  `ProposalManager`→T6 live loop, `SpotCheckAuditor`→T7/T8 server lane,
  `JointTransferApprover`→transfer host wiring.
- **2 intentional reference implementations, not legacy orphans:** `CommitteeSession`
  (the in-JVM committee harness — the live path reassembles its pieces in
  `WorkerValidationService`) and `EventSourcedWorldStore` (the in-memory `WorldStore`
  double behind 6 test suites; production uses `RocksWorldStore`). Both stay.
- **Consumer implemented this pass:** `PasswordKeyDerivations.production()` — the KDF
  selection point that makes Argon2id the wired production default (PBKDF2 only when
  BouncyCastle is absent), pinned by `PasswordKeyDerivationsTest` including the
  no-silent-downgrade assertion. The remaining seams are consumed by their owning
  round-1/2/3 items above — implementing them out of order would duplicate this plan.

## Execution order

**Round 1 — headless-completable now (this program):**
1. L-49 BANNED-at-JOIN admission gate (also closes L-18's admission hole) + grant gossip.
2. L-50 per-joiner action identities (removes the shared interim signer — L-18's second half).
3. L-50 5b `SnapshotExtractor` (full-section digests).
4. L-30 forward event-sync over the transport + IT.
5. L-38 `RetentionIT` + countdown announce wiring.
6. L-41 daemon-survives-game-kill IT.
7. L-33 edit-guard half (`ChunkLockMap` consumer in the write path).
8. L-39 worker key-derivation + join-attempt throttle.
9. L-47 CI job (app build + gate both ways).
10. L-19 certified dynamic committee sizing.  11. L-20 multi-party genesis.
12. L-6 committed world-time in `RegionExecutionContext`.  13. L-46 badge feed wire.

**Round 2 — evidence runs:** dispatch `e2e-live.yml` (L-45 flips on green; supplies
L-31/L-43 evidence); pearl live drive script (L-50); ownership/pickup series stay the
regression harness.

**Round 3 — lane programs (their own multi-week arcs, in dependency order):**
T13 redstone (L-26 core) → T14 environment (L-1, L-2, L-3, L-5; L-4 lighting; L-6 done in
round 1) → T15 mobs (L-7, L-8, L-9, L-24 — needs L-4) → T16 player lane (L-10…L-15, L-21
SDK → L-25, L-12→L-13; L-16/L-17 live wiring; L-18 completes with per-player signing from
round 1).

### Mod-halves cluster — L-46, L-49, L-50 remainders — audited 2026-07-23

**Headless-completable NOW (execution round 1):**
- **L-49 BANNED-at-JOIN**: model complete (`WorldRole.BANNED`, `WorldPermissions.canJoin`,
  author-immune apply, `WorldPermissionsTest`) but ZERO references in the peer join path —
  `PeerRuntime.onPeerJoin` admits unconditionally. Increment: gate join on
  `canJoin(joiner)` + grant gossip frame; red-first 2-node IT (banned peer refused).
  (Also the L-18 admission hole — same seam.)
- **L-50 5b SnapshotExtractor**: doesn't exist; interim 8-corner-sample digest in
  `WorldGenesisService.regionDigest:157-194`. Increment: deterministic full-section
  extractor, swap the corner loop, determinism + round-trip pins.
- **L-50 per-joiner identities**: `NoderaLanePlanPayload.actionSignerKeyB64` is one
  shared signer; per-`Member.publicKeyB64` seam already exists. Increment: per-member
  action signing, client lane verifies against the member's key; multi-signer unit test.
- **L-46 badge feed**: `SelectWorldScreenAddon.setStatusSupplier` never called — wire to
  `MultiplayerWorldFeed`. (Feeds otherwise live-wired already; the row's "defaults
  empty" premise is stale for 3 of 4 surfaces.)

**GUI/live-gated remainders:** L-46 visual surfaces (LAN slot swap, badge row, tabs,
piece grid — L-45 harness screenshots), L-49 `WorldSelectionListEntryMixin` (new mixin),
L-49 live committee/re-key/seed halves, L-50 pearl live drive (scriptable like
`e2e-pickup.sh`; the routing-policy half is already covered by `PearlLanePolicyTest` —
extend with a materialize-into-vanilla slice headlessly).

### T13/T14 environment cluster — L-1…L-6, L-26 — audited 2026-07-23

**Two structural facts enforce ALL seven rows:** (a) the fixed 9-block palette v2
(`FlatWorldRules.PALETTE:81-91`, fingerprint literal `palette.v2`:111) feeding the single
`DelegabilityPolicy` `UNSUPPORTED_PALETTE` gate (:125) — fluids/fire/gravel/redstone/
observer/daylight are excluded simply by not being in the palette; and (b) the no-op
`RuleSet.tick()` (:58) + the time-less `RegionExecutionContext` (:34-43, no world-time
field). No suppression mixins exist (`nodera.mixins.json` empty); Task.13/14 specs are
complete but entirely unimplemented. Foreign vanilla ticks are today *tolerated* via the
interference guard (`MutationSource.SCHEDULED`), not owned.

Remediation (staged, mostly pure-headless engine additions):
1. **T13 first** (L-26 core): palette v2 redstone entries + `RedstoneRules` signal graph
   + scheduled-tick queue + `rulesVersion` bump; live `LevelTicksMixin` suppression.
2. **L-1**: `RandomTickRules` + deterministic `RandomTickSelector` on the `tick()` hook
   (per-tick DeterministicRandom seed already reserved, engine:139-142); live mixin stops
   vanilla random ticks in owned regions; suppression counter then deleted.
3. **L-2**: `FluidRules` finite spread on the T13 scheduled queue; cross-region via the
   migration lane; palette fluid states.
4. **L-3**: `GravityRules` instant-settle column model + fire aging via RandomTickRules;
   palette gravel+fire.
5. **L-4** (largest): `LightField` sky+block nibbles ADDED TO HASHED SECTION STATE
   (root-affecting) + `SkyLightColumn` heightmap + `BlockLightBfs` fixed visit order;
   halo light carriage. Gates L-8 spawning + crop growth.
6. **L-6**: committed `worldTime` field appended to `RegionExecutionContext` (context is
   root-determining — all members must agree; source = committed time in the plan
   payload); daylight-sensor rule. Small, headless, high-leverage.
7. **L-5**: palette v3 `ObserverRules` + quasi-connectivity after T13; delete
   `COMPATIBILITY.md` §4 note.

**Test suites on removal:** per-rules 3-replica determinism fixtures (jqwik), palette-v3
fingerprint pins, `LightFieldTest` golden nibbles per seed, `WorldTimeContextTest`,
farm-soak suppression-counter-zero IT (L-1 exit), fluid cross-region migration IT.

### T16 player-lane cluster — L-10…L-15, L-25 — audited 2026-07-23

All seven OPEN, owner T16 (un-decomposed; spec `docs/old/Task.16.md` 16b/16c/16d).
The validated root today = `RegionSnapshot{chunks (palette-per-section), entities}` —
no containers, no player state. `GameAction` permits exactly Place/Break/Drop/Pickup
(reserved tags: INTERACT_BLOCK=27 (T13), ATTACK_ENTITY=28 (T15)).

- **L-10** (containers): no `ContainerState` anywhere; palette frozen at 9 MVP blocks
  (`FlatWorldRules:80-91`). Remediation: append container list to `RegionSnapshot`
  (same append pattern as the v1→v2 entity table), `InventoryAction` family, palette ids.
  Mostly headless; live half = NeoForge block-entity extractor. Chains with L-26 v3.
- **L-11** (inventory one-way credit): `InventoryCredit` + `EntityRuleSet.applyPickup` +
  mod-side `queueOrDeliver` outbox are a deliberate stopgap to be REMOVED — player
  inventory becomes a validated root member transferred with the player (generalize
  `MutableRegionState.transferEntity`). Dupe-proof cross-region transfer = exit.
- **L-12** (movement): no movement action/validator exists at all. Signed position-delta
  envelopes per batch window + engine collision/speed/reach rules + committee rollback;
  prerequisite: 16a prediction overlay (LocalReplicaView — landed) live wiring.
- **L-13** (combat/health/XP): absent; gated on L-11 (player-state root member) + L-12
  (position ownership). `@Invariant(10)` extended test = exit.
- **L-14** (portals/dimensions/commands): cross-dimension = generalized region transfer
  (`EntityTransferIntent` plumbing exists; `RegionId` carries dimension); deterministic
  command subset as privileged GameActions. Command parity may stage new rows.
- **L-15** (worldgen): `DelegabilityPolicy.Reason.OUTSIDE_GENERATED_TERRAIN` (:33,:137)
  gated on the mod-reported `terrainGenerated` boolean; no engine worldgen exists.
  Deterministic seed-worldgen (Q32.32 per A-5) is its own subsystem. Deletion site is
  the single Reason.
- **L-25** (async mod writes): `MutationGuard.verdict` is main-thread-only and
  fails-closed via ThreadLocal scopes — off-thread writes classify UNKNOWN and
  BLOCK/CONVERT silently; the missing piece is the legal async API
  (`NoderaApi.submitAction`) + documented error. Gated on L-21's SDK.

**Test suites on removal:** `ContainerStateTest`+container-action engine tests (L-10),
inventory-root transfer dupe tests replacing credit tests (L-11), `MovementRulesTest` +
cheat-rejection IT (L-12), extended `@Invariant(10)` (L-13), portal-transfer +
command-determinism tests (L-14), worldgen golden fixtures per seed (L-15),
`AsyncMutationApiTest` + off-thread guard error test (L-25).

### T16 trust/view cluster — L-16, L-17, L-18, L-19, L-20, L-21 — audited 2026-07-23

- **L-16** (commit latency visible): headless core DONE — `peer/view/LocalReplicaView`
  (predict/committed/render, 7 tests). Remaining: live half only — feed
  `ClientValidationLane`'s applied CommitAnnounces into `view.committed(...)` and bind the
  client renderer to `render()`. Nothing calls the view yet.
- **L-17** (migration reconnect): entirely gated on L-16's live wiring —
  `NoderaContinuity` (standby prefetch + `openFromNetwork` + RehostScreen) is the interim;
  exit = keep rendering from the local replica during committee re-formation, no
  `openWorld` seam. Order: L-16 live → L-17.
- **L-18** (Sybil/collusion): the concrete hole is `PeerRuntime.onPeerJoin` —
  **unconditional admission**, `PeerJoin` carries no signature; plus the shared interim
  action-signer key in `ClientValidationLane`. Detection/penalty machinery green
  (EquivocationDetector, ReliabilityLedger.slash, ByzantineWorkerTest, CommitteeManager
  rotation-with-quorum). Remediation: (1) signed `PeerJoin` (append-only wire evolution) +
  admission gate (identity signature + allowlist/reliability floor), (2) per-player action
  signing replacing the interim key, (3) adversarial-FakePeer admission ITs. Headless-buildable.
- **L-19** (degraded quorum): **closest exit in the cluster.** `MajorityQuorumPolicy.sizedTo`
  + `CommitteeChangeCertificate` + `CommitteeManager.certify` all exist; remaining work =
  route population changes through certified reconfiguration instead of the boolean
  `degraded` flag. Small, headless.
- **L-20** (single-signer genesis): well-scoped — replace `CertifiedWorldGenesis`'s single
  `(author, signature)` with a founding-peer approval list verified like
  `CommitteeChangeCertificate.validApprovals` (same quorum-of-signers pattern). Codec is
  append-only (new tag or versioned body). Headless; founding-set collection UX live.
- **L-21** (RuleSet SDK): most greenfield. Anchors exist — `RuleSet` SPI + hardcoded
  `FlatWorldRules.registryFingerprint`. Needs rule-pack registration API, fingerprint
  aggregation over loaded packs, CI-validated sample pack. Headless SPI first.

**Test suites on removal:** L-16 live render IT (O3 screenless), L-18
`AdmissionControlTest` + adversarial-join IT, L-19 `CertifiedResizeTest`, L-20
`MultiPartyGenesisTest` (quorum verify, forged/insufficient approvals), L-21
`RulePackFingerprintTest` + sample-pack CI job.

### T15 cluster — L-7, L-8, L-9, L-24 (mob lane) — audited 2026-07-23

**State: entirely pre-implementation.** `EntityKind` has only `ITEM`/`GHOST`
(core `state/EntityKind.java:10-15`; `PROJECTILE/TNT/MOB/MINECART` exist only in a comment).
The ghost mechanism the rows describe: `GhostUpdatePolicy` (5-tick vanilla passthrough),
`EntityCaptureBridge.onTickPost` capture, tick suppression only for `validatedItem`
(`onTickPre` cancels vanilla tick — that IS "validated" mechanically). `mobCapture` gate:
`NoderaConfig` `entity.mobCaptureDimensions` empty-by-default; enforcement at
`EntityCaptureBridge` (revoke-on-mob) + `EntityDelegabilityRules.allows`.

**Build-on:** `EntityRuleSet`/`ItemEntityRules` (fixed-point physics template — `move()`,
`tick()`, payload codec), `PersistedEntityState` (FixedVec3 pos/vel + opaque payload for
`MobState.aiMemory`), `DeterministicRandom.seedFor`, `EntityStore.create`, cross-region
transfer lane. Spec: `docs/old/Task.15.md`.

**Remediation:**
- **L-7** (per-species validated AI): add `EntityKind.MOB`, `MobState` (payload codec),
  `MobRules` species dispatch, integer `IntPathfinder`, `GoalSelector`, `Sensors`
  (light read ⇒ **depends L-4 LightField**); `GhostShareMetrics` burn-down gauge (missing —
  build first, it is the exit signal); 3-replica/10k-tick determinism fixtures headless,
  `VisualReplicator` + retirement flip live.
- **L-8** (deterministic spawning): `SpawnCycleRules` (seeded draws via
  `DeterministicRandom(ctx,"spawn",tick)`, cap from `EntityStore.entities()`), predicates
  need engine light ⇒ **gated on L-4**. Envelope test vs vanilla rate, headless.
- **L-9** (projectiles/TNT/rails): `EntityKind.PROJECTILE/TNT/MINECART`; `ProjectileRules`
  (ballistics from the `ItemEntityRules.move` template + integer raycast), `TntRules`
  (seeded blast ray order), `RailRules` (**depends T13 redstone state**). Pearl teleport
  hook already live (`isPearl`/`onPearlTeleport`) — reuse. 3-replica fixtures incl.
  cross-region blast `@Invariant(11)`.
- **L-24**: coupled exit with L-7 — replace the dimension-list gate with per-species
  `SpeciesRetirement` (flip on lease renewal, rollback on divergence alarm).

**Test suites on removal:** `GhostShareMetricsTest`, per-species `MobRulesDeterminismTest`
(jqwik 3 replicas), `SpawnEnvelopeTest`, `ProjectileFixtureTest`/`TntFixtureTest`,
`SpeciesRetirementTest`. Adjacent existing: `PearlLanePolicyTest`, `GhostUpdatePolicyTest`,
`EntityLaneSoakIT`/`EntityLaneSoakMetricsTest` (extend with ghost-share).

---

## Dead-code sweep (goal addendum, audited 2026-07-23)

A whole-repo reference scan (474 main `.java` files; simple-name grep across all main
sources so same-package use counts, cross-checked against Roadmap/PROGRESS/Task specs)
found **no genuinely dead production code**:

- **0 orphans.** Every zero-main-reference class is either the process entrypoint
  (`HeadlessPeerMain`, launched by scripts — a framework false-positive) or a
  **TEST-ONLY seam whose documented consumer has not landed yet** (17 classes), each
  mapped to its owning task/limitation row: `LocalReplicaView`→T16 renderer (#35),
  `EventSyncService`→T9 live wiring, `GenesisRecertification`→L-20 share flow,
  `JoinAttemptThrottle`/`Argon2KeyDerivation`→L-39 join flow, `ChunkLockEditability`→L-33
  mod wiring, `ActivePlayerStream`/`PeerShutdownHook`→L-40 live signals (the shutdown
  hook's worker wiring needs a piece PUSH transfer design — noted for round 2),
  `ArchiveManager`→T21 runtime, `InterferenceProbe`→T5 mixins,
  `PersistedCoordinatorState`→NoderaSavedData, `DelegabilityMonitor`→T11 tickets,
  `ProposalManager`→T6 live loop, `SpotCheckAuditor`→T7/T8 server lane,
  `JointTransferApprover`→transfer host wiring.
- **2 intentional reference implementations, not legacy orphans:** `CommitteeSession`
  (the in-JVM committee harness — the live path reassembles its pieces in
  `WorkerValidationService`) and `EventSourcedWorldStore` (the in-memory `WorldStore`
  double behind 6 test suites; production uses `RocksWorldStore`). Both stay.
- **Consumer implemented this pass:** `PasswordKeyDerivations.production()` — the KDF
  selection point that makes Argon2id the wired production default (PBKDF2 only when
  BouncyCastle is absent), pinned by `PasswordKeyDerivationsTest` including the
  no-silent-downgrade assertion. The remaining seams are consumed by their owning
  round-1/2/3 items above — implementing them out of order would duplicate this plan.

## Execution order

<!-- EXECUTION-ORDER -->

## Issue reconciliation map

Open issues vs the rows that gate them (comment `L-<n> removed — unblocked` on removal;
close the issue only when every gating row is gone and its own acceptance is met):

| Issue | Gating rows | Note |
|---|---|---|
| #5 (Task 5, shadow validation live) | L-45 (CI half), L-50 | Live 3-client soak is the acceptance; headless exits green |
| #6 (Task 6, coordinator live) | L-45, L-50 | Latency box live-only; rest evidence-mapped green |
| #7 (Task 7, MVP gate live) | L-45, L-50 | Headless equivalents all green (evidence map on issue) |
| #8 (Task 8, fallback/router) | L-45 (SoakIT live half) | L-22 already retired; collapse + cross-region ITs landed |
| #9 (Task 9, peer-runtime/storage) | L-30 | PeerSyncFlow-over-transport is the remaining clause |
| #10 (Task 10, gateway/P2P) | L-30, L-45 | Cross-internet soak rides the live lane |
| #11 (Task 11, interference live) | L-49 | Mixins/tickets live half |
| #12 (Task 12, entity lane) | L-50 (5b digests, per-joiner identities, pearls), L-24 | Live series green this sweep |
| #13 (Task 13, redstone) | L-26 | Un-started lane |
| #14 (Task 14, environment) | L-1…L-6, L-26 | Un-started lane |
| #15 (Task 15, mobs) | L-7…L-9, L-24 | Un-started lane |
| #16 (Task 16, player lane) | L-10…L-21, L-25, L-26 | Largest program; L-16/L-17 have the 16a core landed |
| #17 (Task 17, debugger) | none directly | Standing harness; consumes lane scenarios |
| #35 (seamless handover) | L-16, L-17 | LocalReplicaView core landed; O3 screenless upgrade is the exit |
