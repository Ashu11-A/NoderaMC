<!-- AI-AGENT-INSTRUCTION: This is a PROGRAMME PLAN — a historical record of how a multi-task effort
     was scoped and executed. It is NOT the current specification of anything. Paths and task numbers
     inside it predate the 2026-07-25 documentation reorganization: the current specs are
     docs/<category>/Task.<n>.md, indexed by docs/ROADMAP.md. Do not "fix" the historical references
     below; read them through the current structure. Do not treat this file as a status source. -->

> **Historical programme plan.** Current specifications live in `docs/<category>/Task.<n>.md`;
> the index is [`../ROADMAP.md`](../ROADMAP.md) and the documentation format is
> [`../README.md`](../README.md). Task numbers and paths below are from before the 2026-07-25
> reorganization and are preserved as written.

# Plan.3.md — Limitation Burn-Down Plan (full-register remediation)

> Goal (2026-07-23 directive): remove every §B limitation in
> each category's `LIMITATIONS.md` (indexed in [`../ROADMAP.md`](../ROADMAP.md) §6). This plan is the consolidated remediation program:
> one section per outstanding row, grounded in a code audit of each limitation's actual
> enforcement points. Rows move to that category's `LIMITATIONS.fixed.md` as their
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
   **Sequencing discovery (2026-07-23, increment 3 planning):** the signal graph is
   BLOCKED on a state-model densification — `ChunkColumnState` is uniform-per-section
   (one palette id per 16³ section), so per-block redstone components cannot be
   represented. Increment 3 is therefore *dense sections* (per-block ids within a
   section, next `ChunkColumnState` body version, byte-stable uniform fast-path so
   existing roots keep their bytes), and the signal graph becomes increment 4.
   Increments 1 (hashed scheduled state, @Invariant(10)) and 2 (NeighborUpdateOrder)
   are landed. **Densification scope (measured):** it is not only `ChunkColumnState` —
   `BlockMutation.expectedPreviousStateId` guards, `SnapshotDeltaApplier`'s two-pass CAS
   (reads `palette[section]` as "current"), `MutableRegionState`'s working palettes,
   `InMemoryWorldView`'s column model, and the drift exceptions are ALL section-granular
   today. The dense increment must move the mutation guard to per-block semantics in one
   coordinated change with a single canonical column-mutation point (a
   `ChunkColumnState.withBlock` style API all three mutators delegate to), an
   all-uniform-dense-section re-sparsification rule so historical hashes stay
   byte-identical, and jqwik equivalence fixtures (uniform vs dense-all-same must be
   the same canonical bytes). Own session-scale arc; do not start it as a side edit.
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

### T15 cluster — L-7, L-8, L-9, L-24 (mob lane) — audited 2026-07-23, cores landed 2026-07-24

**State: ALL FOUR CORE MECHANISMS LANDED (headless).** `EntityKind` grew to
`ITEM/GHOST/TNT/PROJECTILE/MINECART` (wire bytes for `ITEM`/`GHOST` unchanged — kind is a u8,
decode accepts new ordinals, `EntityLaneTypesTest` round-trips all kinds; new kinds pass the
delegability gate like `ITEM`). The ghost mechanism the rows describe stays as the fallback:
`GhostUpdatePolicy` (5-tick vanilla passthrough), `EntityCaptureBridge.onTickPost` capture,
`mobCapture` gate (`entity.mobCaptureDimensions` empty-by-default; `EntityDelegabilityRules.allows`).

**Landed this lane (each first-run green, full gate 232 suites / 0 failures):**
- **L-8 spawn** (`SpawnRules`, f7cef87): `SPAWN_INTERVAL_TICKS=20`, `MOB_CAP=8`, standing-cells
  draw, `LightField<8` gate, `GHOST` zombies typeId 54, `NetworkEntityId.allocate` spawn-seq
  domain, 6000-tick despawn.
- **L-7 mob AI** (`MobAiRules`, d9a731b): `AI_INTERVAL_TICKS=10`; idle 3/8 or one-block step
  (FIXED decision count ⇒ rng stays aligned); walkable-only; despawn horizon.
- **L-9 TNT** (`TntRules`, ecb03ef): `EntityKind.TNT`; `despawnTick`=detonate; blast-local rng
  seeded `(domain,id,tick,pos)`; per-cell destroy `P=1−distSq/R²` over `[-R,R]³`; chain ignition.
- **L-9 projectile** (`ProjectileRules`, b4faac2): `EntityKind.PROJECTILE`; fixed-order Q32.32
  ballistics (gravity-before-drag pinned); opaque-dest ⇒ stick; border transfer; lifetime despawn.
- **L-9 minecart** (`RailRules`, 84f96e5): `EntityKind.MINECART` + palette `RAIL(74)`/`POWERED_RAIL(75)`;
  axis-aligned kinematics follow rail by neighbour connectivity (no rail-shape states);
  `POWERED_RAIL` pins `MAX_SPEED`; plain rail `FRICTION`; dead-end stop; border transfer.
`EntityRuleSet.tick` order: Item→Redstone→RandomTick→Spawn→MobAi→Tnt→Projectile→Rail.
`COMPATIBILITY.md` §8 records the parity envelopes per species.

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

## Dead-code sweep — re-audit (2026-07-26)

The same whole-repo scan, re-run after the live block lane landed. Two things changed since
2026-07-23, and one of them was a real hole rather than a pending consumer.

**Now consumed (were test-only):**

- `InterferenceProbe` — the T5 mixin lane landed, so the probe is reachable live through
  `/nodera debug extract`, and it gained an exact block-level count because the section-level one
  could not distinguish one mined block from four thousand burned ones.
- `ReliabilityLedger` — **this was the hole.** The ledger has existed since Task 6 and the live lane
  wrote to it in exactly one place: a private `handoffReliability` instance used only for the lag
  penalty. Committee outcomes — who re-executed a batch and reached the committed world, and who did
  not — were recorded **nowhere**, so a node that consistently computed the wrong world kept a
  spotless reputation as long as it answered quickly. `CommitteeScoring` (engine, 6 tests) now folds
  every committed round into the ledger, and the two ledgers are one: agreement, disagreement and
  the handoff penalty all land on the same score. Absence is deliberately *not* evidence — silence
  is indistinguishable from an unreachable peer, and punishing it would make reputation a proxy for
  network luck.

**Still test-only, with the reason each one waits (14):**

| Class | Verdict | Why it is not wired |
|---|---|---|
| `CommitteeSession`, `ProposalManager`, `ClientProposal` | **legacy by supersession** — keep | The in-JVM Phase 2/3 committee harness. The decentralized lane reassembles the same pieces (`VoteCollector`, `MajorityQuorumPolicy`, per-region epochs) inside `WorkerValidationService`, where the members are separate processes. The originals stay as the readable reference the distributed version is checked against. |
| `SpotCheckAuditor` | **unimplemented, premise not yet true** | Its purpose is to let a trusted party *stop* re-executing every batch and audit a sample instead. In the live lane every validator already re-executes every batch it votes on, so the sampler has nothing to save. It becomes reachable when a member holds a region it does not vote on — the resident-seat and external-delta paths — and that is where it should be wired, not before. |
| `DelegabilityMonitor` | **unimplemented, real gap** | Hysteresis around revoke/restore. Live revocation today is one-way (`refuseRegion`), so a region that becomes delegable again is never restored automatically. Owner: minecraft task 2 deliverable 6. |
| `PersistedCoordinatorState` | **WIRED 2026-07-26** | `DurableCoordinatorState` gives it a file beside the action/credit/vote journals; the live session attaches on open, checkpoints every 30 s (a crash never reaches `close()`), and flushes on close. A corrupt file costs the node its memory of who behaved — recoverable by observing again — never its world, so damage is reported and replaced rather than thrown. |
| `JointTransferApprover` | waiting on host wiring | Dual-committee transfer approval; the transfer path drives approvals inline today. |
| `WorldGenRules` | waiting on its consumer | Deterministic terrain (L-15's retirement evidence); the live lane still starts regions from the all-AIR genesis, so nothing calls it yet. |
| `ActivePlayerStream`, `PeerShutdownHook`, `ChunkLockEditability`, `JoinAttemptThrottle`, `ArchiveManager`, `EventSyncService`, `GenesisApprovalFlow` | unchanged from the 2026-07-23 sweep | Each maps to its owning round-1/2/3 item there. |
| `EventSourcedWorldStore` | **intentional double** — keep | The in-memory `WorldStore` behind six suites; production uses `RocksWorldStore`. |

`HeadlessPeerMain` and `LevelChunkMixin` are scanner false positives: the first is the worker's
`mainClass`, the second is referenced from `nodera.mixins.json`.

## Round 4 — every remaining row, audited (2026-07-26)

Thirty rows remain across six categories. This section is the verdict on **each one**: not what it
says, but what would actually have to happen for its exit test to go green, and therefore whether it
can be worked on now or is waiting for something else. Four rows retired the day this audit was
written (**L-25**, **L-63**, **L-79**, **L-78**), and all four were in the same class: an exit test
that needed no live run and no unbuilt subsystem, sitting behind a row whose prose made it sound
larger than it was. That class is now empty, which is the useful finding.

**The three blockers, in order of how much they hold:**

| Blocker | Rows waiting on it |
|---|---|
| **A live run with a node that actually holds regions** — the scripted suites run a dedicated server that the field-of-view planner leaves owning nothing | engine L-1, L-2, L-7, L-24, L-50 · minecraft L-50, L-60, L-80 · network L-30 |

**Correction (2026-07-26):** this table first read as though live evidence were unobtainable without
a person at a keyboard. It is not. `e2e-live` is a `workflow_dispatch` workflow that boots real
NeoForge clients under Xvfb, one suite per runner, and **any of its suites can be dispatched against
a branch** (`gh workflow run e2e-live.yml --ref <branch> -f suites=mobs,pearl`). The constraint on
these rows is therefore CI minutes and reading the result, not access. Rows whose exits name a
scripted stage should be driven that way rather than described as blocked.
| **A GUI environment where a person looks at the screen** | minecraft L-43, L-46, L-49 · app L-47 |
| **A subsystem nobody has written yet** (the client prediction overlay; the endpoint's validated lane) | engine L-12, L-16, L-17 · network L-33 · server L-62, L-64…L-70 |

### Per-row verdicts

| Row | Verdict | What it is actually waiting for |
|---|---|---|
| app L-47 | **infra, not code** | A CI job with two machines (or two network namespaces) that installs the app, hosts a world, closes the game, and joins from the other side. Everything it drives exists. |
| app L-56 | **decidable now, but it is a product decision** | The exit offers two doors: give the transport per-world attribution and a peer-advertised cap, or delete both controls **and migrate their saved values**. The second is an afternoon; the first is a protocol change. Neither should be picked by an implementer alone, which is why this row stays open rather than being quietly resolved the cheap way. |
| engine L-1 | **one live clause left** | Crops and the suppression mixin landed 2026-07-26. The farm soak needs a node that owns the farm's region — the same seat problem as minecraft L-80. |
| engine L-2 | **needs a producer, a consumer, and a trust decision + live** | Fluid interactions landed 2026-07-26. Two corrections in one day, both worth keeping. This row first said cross-region spread "can start today" as engine work; then, after reading `BorderSignal` (which carries no state and rides the execution result rather than the root), that it needed a protocol addition. **Both were wrong.** `HaloUpdate` (tag 56) already IS the edge-state message — "after `region` commits `version`, its coordinator sends the region's EDGE COLUMNS to the committees of every neighbor whose halo overlaps them" — fully codec'd in both directions, with **no producer and no consumer outside the codec**, and the read side (`RegionWorldView.getBlock` beyond the covered chunks) still the documented MVP stub. So it is a producer, a consumer, and the halo-version staleness assertion the javadoc already specifies. The genuinely open question is trust, and it is now narrowed to one decision. Verifying a slice against the sender's committed root is **not on the table**: `StateRoot` is `SHA-256` over the whole canonical `RegionSnapshot`, a flat hash with no per-column commitment, so partial verification needs the entire snapshot — the thing edge-only delivery exists to avoid. Options: (a) add a Merkle commitment over columns — correct, but a state-format change and a versioned migration; (b) hold a full replica of the neighbour — defeats the purpose; (c) **recommended**, have the neighbour's committee sign the slice, so the receiver verifies a quorum attestation against membership it already knows. (c) proves the region's own committee vouches for the columns, which is the authority that owns them, and it is cheap precisely because `HaloUpdate` has no producer or consumer yet — its shape can change at no compatibility cost. Simulation messages are Java-only, so no Rust codec mirror is involved. |
| engine L-7 | **species work** | Ghost AI is deterministic; retiring the row per species means targeting, pathfinding and combat for each shipped species. Large, but not blocked. |
| engine L-12 | **rides L-16** | The engine half (`MovePlayerAction`, per-axis legality, border transfer) is done. What remains is mod-side capture plus the prediction/rollback overlay — capture alone would make a rejected move a visible rubber-band, so shipping it before L-16 would be worse than not shipping it. |
| engine L-16, L-17 | **the biggest remaining engine lane** | Client prediction with rollback, and a local-replica view so migration does not reconnect. Both are new subsystems, both are unblocked, and both are where the "feels like vanilla" claim is finally paid for. |
| engine L-24, L-50 · minecraft L-50, L-60 | **live suites only** | Every headless half is green; each exit names a stage in `e2e-mobs.sh` / the gameplay drives. |
| minecraft L-43, L-46, L-49 | **someone must look at a screen** | All feeds are wired; the exits are about what a player sees. `e2e-live` runs under Xvfb, so these retire by adding assertions to a suite plus one human pass. |
| minecraft L-80 | **RETIRING** | The observer mechanism landed 2026-07-26; the live run remains, and it will surface the actor-key question that is issue #45's work. |
| network L-30 | **live** | Committee validation and certified event sync over one `PeerTransport` in a sustained session. |
| network L-33 | **needs the client piece pipeline** | The edit half is done; the render half is a client subsystem. |
| network L-76 | **needs a population** | The emitter, the plane and the dashboards exist; the exit is a dashboard answering a real question from real reports, and no deployment has opted in yet. Nothing to build. |
| server L-61 | **RETIRED 2026-07-26** | The jar builds and all three suites pass — Paper enable, the Folia ALIGN-1 pass **and** its refusal at exponent 2, and corpus co-existence. The eight rows behind it now sit behind **task 2** (the endpoint hosting a peer) rather than behind the plugin existing. |
| server L-62 | **needs the custody model first** | `CustodyAuditIT` cannot be written before something advertises custody, and re-checked on 2026-07-26 this is still literally true: `EndpointConfig.Custody` is read from the yaml and **only logged** (`NoderaEndpointPlugin` line 67) — no announce, no capability, nothing on the wire for an auditor to catch out. It unblocks with server task 2 (the endpoint hosting a peer). Two notes for whoever takes it: the mechanism is now available, because a tracker announce already carries `ManifestHolding(manifestRoot, pieceBitmap)` per manifest — including, since worker L-41, per-region manifests — so an audit is "ask for a piece this node claims in its own bitmap and hash-check the answer", which the content plane already serves. And `SpotCheckAuditor` is **not** that audit: it re-executes a sampled batch (a compute check), while custody is a holding check; conflating them would leave the row unproven. |
| worker L-41 | **RETIRED 2026-07-26** | The remaining clause was two things: an announce heartbeat that describes what this node holds *now*, and validated-lane region pieces seeded beside the whole-save archive. Both landed — `NODERA-SEED-REGION`, a per-lane manifest ladder, both lanes on one announce, and the mod-side `RegionSeedSpool` — with `SeedRegionVerbIT` proving the clause itself: nothing connected, both lanes still held and still advertised. The evidence deliberately stops short of a real Minecraft client; the pushing side is proven by its control-channel behaviour. |

### Full register census + live-evidence audit (2026-07-26, end of session)

Every register read, every open row checked against the day's live runs rather than against memory.
**25 open across five categories; 56 retired; three categories now completely clear** (worker,
tracker, rendezvous — worker emptied today).

| Category | Open | Retired |
|---|---|---|
| engine | 7 (L-1, L-2, L-7, L-12, L-16, L-17, L-50) | 19 |
| minecraft | 5 (L-43, L-46, L-49, L-50, L-80) | 6 |
| network | 3 (L-30, L-33, L-76) | 19 |
| server | 8 (L-62, L-64…L-70) | 4 |
| app | 2 (L-47, L-56) | 2 |

**Did today's runs retire anything? No — and the checks are worth recording, because three rows
looked close enough to be worth testing rather than assuming.**

- **minecraft L-80** — exit: "a player's place and break reach the owning node's committee and
  commit; `/nodera debug capture` reports captured edits rather than `REGION_NOT_DELEGATED`". The
  driven soak produced literally `"block_capture": {"REGION_NOT_DELEGATED": 1}`. The clause names the
  exact string the run emitted, and it is the failing one. Not met, now with live evidence instead of
  inference.
- **network L-30** — the transport half is fixed (`0 → 64` worker-held replicas) and the row is
  rewritten around what replaced it: one resident, because a joiner's companion never joins the
  session. Still open, but for a stated reason rather than an inherited one.
- **engine L-17** — stays RETIRING. `GatewayHandover` and its bind landed and the live `ownership`
  suite passes, but B still crosses a `DisconnectedScreen`, which is the clause.

**What every remaining row is actually waiting on, in four buckets:**

1. **A design decision** — L-30 (what authorises a joiner's worker to join its world's session),
   L-66 (the Folia pin), server L-62 (custody must be advertised before it can be audited).
2. **A GUI pass** — minecraft L-43/L-46/L-49, engine L-16's renderer bind, network L-33's render
   half. No headless run can produce this evidence.
3. **A population or a platform** — network L-76 needs opted-in users; server L-64/L-65/L-67/L-69
   need real Folia and real plugins.
4. **A longer or differently-shaped live run** — engine L-1 (farm soak), L-7 (per-species retirement),
   L-2/L-12/L-50 (live evidence for lanes that are headlessly complete).

Nothing in buckets 2-4 is reachable by writing more code, which is why the honest count of rows this
session could have retired is zero — and why the session's value sits in the four defects fixed and
the five suites found asserting less than they claimed.

---

### Dead-code sweep, round 5 (2026-07-26)

Every `.java` file under a `src/main` tree whose simple name appears in **no other main file** was
listed, then classified by reading it. The count of test-only references is the interesting column:
a class with six of them is a core somebody drives, while a class with one is usually a unit test
keeping its own subject alive.

| Class | Verdict |
|---|---|
| `HeadlessPeerMain`, `NoderaEndpointPlugin`, `LevelChunkMixin`, `ServerLevelRandomTickMixin` | **Not dead** — entry points reached by a manifest, a `plugin.yml` or `nodera.mixins.json`, never by an import. A name-based sweep will always list these; they are the reason it needs a human read. |
| `CommitteeSession`, `EventSourcedWorldStore` | **Reference cores, driven by ITs.** Legitimate but worth knowing: the live path is `WorkerValidationService`, so these are exercised only by the tests that pin the design. |
| `ProposalManager` | **Legacy.** Superseded by per-replica state — `WorkerValidationService` holds `pendingProposal` on the replica itself, so the coordinator-side map has no caller and should not gain one. |
| `DelegabilityPolicy` + `DelegabilityMonitor` | **UNIMPLEMENTED, not legacy — corrected on the same day it was written.** The first read called this a legacy chain superseded by the `RegionRefusal` path, on the strength of `EntityDelegabilityRules` calling the `ENTITY_PRESENT` gate "legacy". Comparing the two enums says otherwise: `RegionRefusal.Reason` has exactly **one** value, `NON_DELEGABLE_ENTITY`, while `DelegabilityPolicy.Reason` enumerates the full rule set — `UNSUPPORTED_PALETTE`, `CHUNKS_NOT_LOADED` and the rest, each unit-tested in `DelegabilityPolicyTest`. So the live lane refuses a region for one reason out of many, and a region with an unsupported palette or unloaded chunks is **not** refused live at all. `DelegabilityMonitor` is the hysteresis wrapper that stops such a decision oscillating, and it is unwired for the same reason. This is a genuine gap in engine task 7 / #11, and the wire cost is small: `RegionRefusal.Reason` is ordinal-coded, so adding reasons is additive. |
| `SpotCheckAuditor` | **Unwired on purpose**, recorded above: every validator already re-executes every batch it votes on. Its first real use would be server L-62's custody audit — and that is a *holding* check, not the compute check this class performs, so it is the wrong tool for that row too. |
| `TenantBoundary`, `ChunkLockEditability`, `ActivePlayerStream`, `ArchiveManager`, `EventSyncService`, `GenesisApprovalFlow`, `JoinAttemptThrottle`, `PeerShutdownHook`, `WorldGenRules`, `JointTransferApprover` | **Unread this round.** Each has exactly one test-only reference and needs the same per-class read before anything is wired or deleted. Listed here so the next sweep starts from a name list rather than from zero. |

**The rule this sweep keeps proving:** an unreferenced class is a question, not a defect. Round 4
found three genuine holes this way (`ReliabilityLedger`, `PersistedCoordinatorState`,
`LocalReplicaView.predict`) and this round found a fifth in `RegionHalo` — and one chain,
`ProposalManager`, where wiring would have been the mistake.

**What wiring the delegability gap actually requires, in order.** Attempted directly and stopped at
a hazard worth writing down. `RegionRefusal.Reason` says "ordinals are wire values; append only",
which is true of the enum and *not* sufficient for interop: `MessageCodec` decodes a refusal through
`RegionRefusal.reasonOf`, which **throws** on an unknown code — deliberately, so "an unknown refusal
is never silently treated as a known one". So a node that appends a reason and sends it to a peer
that predates the addition throws inside that peer's decode path. The safe order is therefore:

1. Decide how an unknown refusal reason should be represented — fail-closed-but-contained, rather
   than either throwing in decode or silently mapping to a known reason. This is a **design
   decision**, not a mechanical change: the current throw is intentional.
2. Append the shareable subset of `DelegabilityPolicy.Reason` (`UNSUPPORTED_PALETTE`,
   `CHUNKS_NOT_LOADED`, `FAKE_PLAYER_ACTIVE`, `INTERFERENCE_RATE_HIGH`, `NO_PLAYER_PRESENT` — each
   observable by a node that owns none of the region, which is the message's whole premise).
3. Map policy verdict → refusal in `engine` or `peer`, never on `RegionRefusal` itself: `transport`
   must not depend on the engine (Task 0 §7 layering).
4. Evaluate the policy where the observer already refuses, and wrap it in `DelegabilityMonitor` so a
   flapping condition does not thrash a region between lanes.

**Steps 1–3 landed** (see the commit that follows this note). Step 1 turned out not to be the open
design question it looked like: representing an unknown reason as an explicit `UNKNOWN` that is
never encoded and never acted on is *stricter* than the throw it replaces, not laxer — the invariant
was "an unknown refusal is never silently treated as a known one", and an explicit non-reason
satisfies it while a decode exception merely fails louder in the wrong place. **Step 4 landed** as `RegionDelegabilityGate` (peer, 8 tests): the monitor's hysteresis driving real
refusals, announcing only on the `REVOKE` edge, only for verdicts a recipient can re-check, and
picking the announced reason in the rule set's declaration order so two nodes seeing the same dirty
region announce the same thing. Minecraft-free by construction — the live world supplies
`DelegabilityPolicy.Inputs` and the gate decides — so the remaining work is the **mod-side adapter
that fills those inputs from the running world**, which is minecraft task 2's territory (#67) and
needs a live run to mean anything.

**And a second rule, learned by nearly getting it wrong here:** "unreferenced plus a javadoc calling
something legacy" is not evidence of legacy. `DelegabilityPolicy` was classified as superseded and
then reclassified an hour later, because the class that *is* superseding it covers one of its
reasons out of many. Compare the enums, not the prose.

---

### The determinism soak does not yet exercise the validated lane (2026-07-26)

Found by reading the suite's own numbers rather than its exit code, after restoring its executable
bit (`e1a893e`) let it run for the first time. It reported **`PASS D3: zero divergences over 900s of
three-client play`**, and that result is close to vacuous:

```
state-peer1.json: divergences=0 commits=0 votes_cast=0 regions=0    (all four peers)
"rounds_driven": 197,
"block_capture": {},
"blocks_outside_palette": 1248240
```

Three facts together:

1. **The peers hold no regions and cast no votes.** Zero divergence among nodes that validated
   nothing is not evidence of determinism; it is evidence of idleness. Same root as `mesh-soak`'s
   S3 skip and network L-30 — the seats live on the client lanes.
2. **The edits never reach the capture path.** The soak drives play with
   `execute at <player> run setblock ~dx ~ ~dz minecraft:stone`, and `BlockCaptureBridge` listens to
   `BlockEvent.EntityPlaceEvent` / `BlockEvent.BreakEvent` — *entity*-driven events. An RCON
   `setblock` is a direct world write and fires neither, which is why `block_capture` is `{}` after
   197 rounds. The empty ledger is correct behaviour, not a defect.
3. **The world is overwhelmingly outside the validated palette** (1.2 M blocks), so most of what the
   extractor sees could not be consensus state anyway.

**Consequence, and the reason this is worth writing down before spending the budget:** issue #67's
clause 1 — a zero-unexplained-divergence soak — **cannot be satisfied by this suite as written**, at
any duration. Dispatching the documented `SOAK_SECONDS=7200` acceptance run would cost two hours of
CI and prove exactly what the 900 s run proved: that an idle lane does not diverge.

What the suite needs before that spend is worthwhile:

- **Player-driven edits**, so the capture path actually runs — the drive must place and break as a
  player, not via `setblock`. The `pickup`/`mobs` drives already do player-driven actions; the
  mechanism exists.
- **Peers that hold regions**, so there are two inspectable replicas whose roots can disagree. That
  is the same prerequisite L-30 has been waiting on, which makes it one fix serving two rows.

---

### Issue closure sweep (2026-07-26)

Six issues closed, and the reason they were open was **bookkeeping, not scope**. The 2026-07-25
documentation reorganization moved the *live* acceptance of six already-complete headless tasks into
one place — minecraft task 2 — because they were all waiting on the same thing: real Minecraft
clients under CI, not more code. The issues kept their pre-reorg acceptance lists, so each still
carried a live clause that had formally moved elsewhere.

| Issue | Spec | Spec status | Live clause now in |
|---|---|---|---|
| #5 | engine 3 | ✅ COMPLETED (headless) | #67 |
| #6 | engine 4 | ✅ COMPLETED (headless) | #67 |
| #7 | engine 5 | ✅ COMPLETED (headless) | #67 |
| #8 | engine 6 | ✅ COMPLETED (headless) | #67 |
| #9 | network 3 | ✅ COMPLETED | #67 |
| #11 | engine 7 | ✅ COMPLETED (headless) | #67 |

**#67 was created first, deliberately.** minecraft task 2 owned all six live halves and had no issue
of its own, so closing the six without it would have taken that acceptance off GitHub entirely.

Each closure was checked rather than assumed: every deliverable row ✅ with no 🚧/⏳/❌, no open
limitation row owned by that task, and — for #7 — the named tests re-run rather than trusted from
the 2026-07-23 evidence map.

---

### Issue-by-issue read (2026-07-26)

Every open issue was read in full — title, body and comments — and commented with its own status.
The result is worth stating plainly, because it is not what a burn-down usually looks like:

**No open issue is closeable today, and each for a stated reason.** #5, #6, #7, #8, #9, #10, #11,
#12, #13, #14, #15 and #35 all carry at least one acceptance clause that is inherently live — a
multi-client soak, a screen a person looks at, a species retired — or that waits on a subsystem
nobody has written. #17 is labelled a **standing task** and is not meant to close at all; it takes
scenario intake from every other lane, and this session added three suites to it.

Closing any of them on headless evidence alone would be the failure mode this register exists to
prevent: a green tick standing in for a claim nobody has checked against a running game.

### What this means for sequencing

The rows that can be worked today, with nobody waiting on anybody: **engine L-2's cross-region
clause**, **engine L-16/L-17** (the largest and most valuable), **engine L-7's species work**, and
**server L-61** (which unlocks eight rows behind it). Everything else needs either a live run whose
seat problem minecraft L-80 has just addressed, a person at a screen, or a product decision.

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
