# Engine — Testing

<!-- AI-AGENT-INSTRUCTION: Test counts here are AUTHORITATIVE and come from the `./gradlew check`
     XML reports, never from memory or from a grep of @Test. Update the counts and the Last run date
     in the same commit that changes them, and keep them in agreement with the root README module
     table. If a determinism test is disabled or skipped for any reason, say so explicitly here with
     the reason — a silently skipped determinism test is worse than a failing one. -->

**Category:** engine · **Last run:** 2026-08-10 · **808 XML-reported · 0 failing · 0 skipped**

> Counts above are `:core:test` + `:engine:test` + `:testing:test` from the nine-module run recorded
> in commit `44069df` — the JUnit XML of that run is where README's module table was re-measured, and
> this register carries the same three cells rather than a second measurement. Re-measured 2026-08-10:
> `:testing:test` gained the two cases of `ValidatedLoadIsCapturedTest` (46 → 48). The run is dated
> 2026-08-06, after the Plan 11 round 2 reduction (issue #210) deleted the shadow-validation and
> central-coordinator clusters and 21 of their dedicated test files. The whole Java tree comes to
> **2,273 tests / 0 failed** as the sum of README's nine measured module cells; to
> measure it rather than add it up, run `scripts/test-totals.sh --java`. The **2,238** this file used
> to carry predates that re-measurement and never agreed with README.

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `core` | Domain types, canonical encoding, JDK-only crypto, certificates, entity records (type tags hosted through 118; engine-owned tags through 108) | 314 | ✅ |
| `engine` | Deterministic engine + consensus/committee/fallback, and the surviving `shadow` delta applier | 446 | ✅ |
| `testing` | Shared test library (`LoopbackTransport`, `FakeRegion`, fixture IO) | 46 | ✅ |

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
| Headless ITs | The consensus lanes work end to end without Minecraft | `FallbackRoutingIT`, and in `:peer` over the real transport, `WorkerQuorumValidationIT` / `DivergenceCountedIT` |
| Adversarial | Honest members survive a real liar **on the wire** | `ByzantineMeshIT` |
| Soak | Behaviour holds over thousands of ticks with the lane active | `RandomTickRulesTest` (200 ticks), `MobAiRulesTest` (2400), `SpawnRulesTest` (2000) |

`simulation/ForbiddenApiTest` is **enabled, not skipped**: the repository compiles to Java 21
bytecode via `--release 21`, so ArchUnit's bundled ASM parses the classes and the determinism rules
(no wall clocks, no entropy, no IO in `dev.nodera.simulation`) run in CI on every build.

## 2. Landmark tests

| Test | What it proves |
|---|---|
| `VanillaPaletteTest` | Every palette id round-trips through the vanilla state it projects to — the test that fails the day the palette grows past the live capture lane |
| `BlockCaptureRulesTest` (8) | The capture decision without a running game: unsupported blocks refused with their own reason, engine outputs never re-placed, edits outside the height envelope refused, ownership checked first |
| `ByzantineMeshIT` (3) | A real adversarial peer on the mesh: a fabricated root never reaches a certificate; a vote forged in an absent member's name buys no seat (the round times out); an equivocator gets one seat, not two |
| `FallbackRoutingIT` | A spread-out session clears the **> 90% committee-commit** exit criterion |
| `WorkerQuorumValidationIT` (`:peer`) | Three companion-only workers form a committee over the real `PeerTransport` and commit a batch out-of-game — the quorum-commit and failover path that actually ships |
| `DivergenceCountedIT` (`:peer`) | Two nodes disagreeing on the same base is *counted* from both chairs, and agreement leaves the counter alone |
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

### 2.1 What the Plan 11 round-2 reduction took out of this table

Six landmark suites were removed from this file on 2026-08-06 because they no longer exist (issue
#210, commits `24e6f0e` and `0b02aa5`). Four of them drove designs that also went, so their loss is
not a coverage gap; **two are real reductions in what is proven and are recorded here rather than
quietly dropped.**

| Removed suite | Verdict |
|---|---|
| `CoordinatorIT`, `CommitteeMvpIT`, `CrashRecoveryIT`, `LagHandoffIT` | **Not a gap.** They drove the central-coordinator/committee-session design that Task 30 retired and round 2 deleted; no node constructed it. The batch loop that ships, `WorkerValidationService`, is covered by `WorkerQuorumValidationIT`, `ByzantineMeshIT` and `LiveLagHandoffIT` over the real transport. |
| `SpotCheckPolicyTest` | **Not a gap — the feature is gone.** `SpotCheckPolicy` and the whole server-side audit lane were deleted. Custody claims are not spot-checked at a sampled rate; they are checked in full, by the receiver hashing every piece against the manifest. There is no adaptive re-execution rate left to test. |
| `ShadowValidationIT` | **Reduced.** It ran 3 workers × 250 random place/break batches with zero divergence. The surviving headless proof of the same property, `WorkerQuorumValidationIT`, runs the production path over the real transport but only **50** batches, and `DivergenceCountedIT` pins the counter rather than a soak. The property is still proven; the *volume* behind it dropped by 5×. |
| `VanillaCaptureSoakIT` | **Partly lost.** Its two halves separate cleanly. The binding half — every action built from a real `(block key, properties)` pair, modded blocks and inexpressible states refused rather than mis-mapped — is fully held by `VanillaPaletteTest` and `BlockCaptureRulesTest`. The soak half — **a vanilla-shaped edit stream over the whole palette producing zero divergence across three independent workers** — has no replacement in the tree. Nothing today drives multi-replica divergence from the vanilla state space. |

The `VanillaCaptureSoakIT` row is the one to act on if this table is ever short a suite again: it is
the only behaviour in this category with no current owner.

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
