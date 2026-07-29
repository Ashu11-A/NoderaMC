# Engine — Testing

<!-- AI-AGENT-INSTRUCTION: Test counts here are AUTHORITATIVE and come from the `./gradlew check`
     XML reports, never from memory or from a grep of @Test. Update the counts and the Last run date
     in the same commit that changes them, and keep them in agreement with the root README module
     table. If a determinism test is disabled or skipped for any reason, say so explicitly here with
     the reason — a silently skipped determinism test is worse than a failing one. -->

**Category:** engine · **Last run:** 2026-07-28 · **785 `@Test`/XML-reported · 0 failing · 0 skipped**

> Counts above are reconciled after the 2026-07-28 L-51 + L-52 merges: core 263 (260 `@Test` + 3
> jqwik `@Property`), engine 508, testing 14; 785 total, 0 failed/skipped.

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `core` | Domain types, canonical encoding, JDK-only crypto, certificates, entity records (type tags hosted through 118; engine-owned tags through 108) | 260 + 3 `@Property` | ✅ |
| `engine` | Deterministic engine + consensus/shadow/coordinator/committee/fallback | 508 | ✅ |
| `testing` | Shared test library (`LoopbackTransport`, `FakeRegion`, fixture IO) | 14 | ✅ |

Run with:

```bash
./gradlew :core:test :engine:test :testing:test        # this category
./gradlew check                                        # the full Java gate
cd rust && cargo test                                  # the Rust gate (required alongside)
```

### A fast `java` job is not a skipped one

`org.gradle.caching=true` is set repo-wide and CI's `gradle/actions/setup-gradle` persists the
Gradle home between runs, so the `java` job's `./gradlew check build` is cached end to end. A run
that touches only workflows or docs legitimately finishes in **~40 seconds** with every `:*:test`
task logged `FROM-CACHE`; a run that touches production code re-executes exactly the affected
modules. Both shapes have been observed in CI, e.g. run `30409106421` (workflow-only change: 8 of 9
test tasks `FROM-CACHE`, 43s) versus `30409846415` (peer/relay change: `:core`/`:transport`/
`:testing` cached, the six downstream modules executed, 2m 2s). Verified directly: with a warm
cache, editing one production method in `:core` makes `:core:test` execute rather than resolve from
cache, and the build goes red. Gradle's cache key covers the test task's runtime classpath, so a
changed production class invalidates its own module's tests **and** every downstream module's.

Because wall-clock time therefore proves nothing either way, the gate is judged on what it
reported. `scripts/java-test-report.sh` runs after `check build` in CI and requires that **every**
module with `src/test/java` produced JUnit XML — those reports are task outputs, so Gradle restores
them on a cache hit and the counts are real whether the suite ran now or was replayed. It writes the
per-module table into the job summary (2 070 tests across 9 modules as of 2026-07-28), so a module
whose suite silently stops running turns the job red instead of merely making it faster.

---

## 1. The testing strategy

**Determinism first.** Every rule carries a property test before it carries a feature test. The
question is never "does this look right?" but "do three replicas agree, byte for byte, forever?"

| Layer | What it proves | Examples |
|---|---|---|
| Property tests (jqwik) | Same input ⇒ same root; permuted input ⇒ different root; order independence where claimed | `DeterminismPropertyTest`, `PressurePlateStickyPistonTest` (reversed-order root property) |
| Golden fixtures | Canonical encodings are byte-stable across languages and versions | `fixtures/wire/*.bin`, `MessageCodecTypeTagTest` |
| Negative determinism | Dropping state from the hash **must** be detectable | `ScheduledStateRootTest`, `CombatStateRootTest`, `ContainerStateRootTest` |
| ArchUnit | The determinism ban is mechanical, not cultural | `simulation/ForbiddenApiTest` |
| Headless ITs | The consensus lanes work end to end without Minecraft | `ShadowValidationIT`, `CoordinatorIT`, `CommitteeMvpIT`, `FallbackRoutingIT` |
| Adversarial | Honest members survive a real liar **on the wire** | `ByzantineMeshIT`, `ByzantineWorkerTest` |
| Soak | Behaviour holds over thousands of ticks with the lane active | `RandomTickRulesTest` (200 ticks), `MobAiRulesTest` (2400), `SpawnRulesTest` (2000) |

`simulation/ForbiddenApiTest` is **enabled, not skipped**: the repository compiles to Java 21
bytecode via `--release 21`, so ArchUnit's bundled ASM parses the classes and the determinism rules
(no wall clocks, no entropy, no IO in `dev.nodera.simulation`) run in CI on every build.

## 2. Landmark tests

| Test | What it proves |
|---|---|
| `ShadowValidationIT` | 3 workers × 250 random place/break batches with **zero divergence**; a lying worker is caught and re-snapshotted |
| `VanillaCaptureSoakIT` | The same soak driven from **vanilla block states**: every action is built from a `(block key, properties)` pair through `VanillaPalette`, so the run proves the *binding* as well as the engine — modded blocks and vertical piston states ride the same stream and never reach the lane |
| `VanillaPaletteTest` | Every palette id round-trips through the vanilla state it projects to — the test that fails the day the palette grows past the live capture lane |
| `CoordinatorIT` | Commit-on-match; forced mismatch rejected with the world uncorrupted; stale-epoch drop; primary-death reassignment under a bumped epoch |
| `CommitteeMvpIT` | 2-of-3 quorum commit, then primary failover under epoch+1 with continuation |
| `ByzantineMeshIT` (3) | A real adversarial peer on the mesh: a fabricated root never reaches a certificate; a vote forged in an absent member's name buys no seat (the round times out); an equivocator gets one seat, not two |
| `FallbackRoutingIT` | A spread-out session clears the **> 90% committee-commit** exit criterion |
| `SpotCheckPolicyTest` | 120k committed versions hold proven committees at ≤ 2% re-execution; unproven committees keep the ~25% floor |
| `PackRuleExecutionTest` (5) | A pack's block reaches consensus state through the pack's own code, replica-identical across independently-built registries; base blocks behave byte-identically with a pack installed |
| `AsyncMutationApiTest` (2) | 4-thread × 50-action per-submitter FIFO drain; an off-thread direct write gets the documented error while applier-scope and undelegated writes stay legal |
| `PlayerInventoryTest` (3) | Root-inventory pickup; the stopgap credit path; `portalHandOffCarriesTheInventoryExactlyOnce` — the dupe-proof cross-region clause |
| `CommandSubsetTest` (8) | Engine-side authority, no minting of unplaceable states, volume-bounded canonical-order fills, `/time set` refused |
| `RandomTickRulesTest` | 200-tick grass/dirt checkerboard soak with identical roots on 3 replicas **while the lane actively spreads** |
| `FluidRulesTest` (6) | Level-per-hop, finite reach, source-break drain, fall-before-spread, mint protection, border signal — all through the full engine path |
| `CrossRegionFluidTest` (9) | Complete uniform face scan, bounded ownership-facing dense scan with diagonal rejection, winning-fluid cadence, exact dense off-corner tick on two replicas, identical roots |
| `MobAiRulesTest` (3) | 2400-tick soak: every ghost ends on a walkable cell with replica-identical roots; the despawn horizon fires exactly on time |
| `SpawnRulesTest` | 2000-tick dark-shelter soak: population ≤ cap, every spawn inside the shelter, replica-identical roots; a lit platform spawns zero |
| `PressurePlateStickyPistonTest` (7) | The full engine path for both components, including the order-independence root property |
| `FixedPointTest` / `ChunkKeyTest` / `EntityLaneTypesTest` | L-52: full-range signed Q32.32 arithmetic matches `BigInteger`; packed signed chunk coordinates round-trip; entity motion copies remain byte-identical to direct canonical construction |

## 3. Conventions

- **Every test that touches hashed state asserts a root**, not just a field. A test that checks a
  block changed but not that the root changed would pass while the replicas diverged.
- **Soaks assert three replicas**, not one run twice. Reproducibility within a process is a much
  weaker claim than agreement between independently constructed replicas.
- **A found divergence becomes a committed replay fixture** in the same commit that fixes it.
- **Red first.** A regression test is written to fail against the unfixed build, then the fix makes
  it pass. Several live-lane defects were only caught because the test failed first.
- `@Invariant(n)` tags map tests to the plan's numbered invariants; a parity task is not done until
  its invariants have green tests.

## 4. Live evidence

Live acceptance for this category runs through the mod's scripted suites — see
[`../minecraft/TESTING.md`](../minecraft/TESTING.md). The engine-relevant ones are
`e2e-ownership.sh` (per-player FOV region ownership and the cross-owner drive), `e2e-pickup.sh`
(clean-slate validated pickup delivered exactly once), and `e2e-churn.sh` (join/leave churn with a
log audit proving no error accumulation).
