# Testing — how the test tooling itself is used and proved

<!-- AI-AGENT-INSTRUCTION: Keep the scenario table in agreement with `scripts/nodera-test.sh list`;
     that command is the source of truth, this table is a copy for readers who have not built the
     tool. Counts and last-run dates come from an actual run, never from memory. A scenario that is
     added or renamed updates this file in the same commit. -->

**Category:** testing · **Last run:** 2026-08-10, `./gradlew :testing:test` on branch
`fix/harness-tells-the-truth-#188` · **74 unit tests · 0 failing · 0 skipped** (module `testing`) —
the live scenarios themselves run in the `e2e-live` workflow nightly, and
`scripts/nodera-test.sh list` is the smoke test that all twenty resolve.

The count rose 44 → 74 on 2026-08-10: `LiveStackLivenessTest`, `LogWatcherLagGuardTest`,
`LogWatcherWindowTest` and `SkipGateTest` landed with the harness-truth work, and
`HostWorldSupportTest` moved to `ManagedProcessTokenTest` with the capability it covers.

Whole-tree Java total: **2,299 tests · 0 failed**. That figure is the sum of the nine per-module
counts README's module table carries — core 314, engine 446, transport 189, storage 158, testing 74,
peer 850, endpoint 114, neoforge-mod 134, paper-plugin 20 — which is where a reader should go for
the per-module breakdown. `scripts/test-counts.sh --check java` fails the gate when README and the
measured suites disagree, so those numbers are not typed from memory. To measure it rather than add it up, run
`scripts/test-totals.sh --java`; that command reads JUnit XML rather than a job's exit code, so a
suite that skipped into green cannot contribute to the number.

The **2,423 passed · 12 skipped** figure this file used to carry is the state the Plan 11 round
*found and fixed*, not the state the tree is in. All twelve skips were real-binary suites waiting on
something the `java` job had never built. The job builds them now, and a step named `Nothing skipped
into green` in [`build.yml`](../../.github/workflows/build.yml) fails that job on any non-zero skip
count. Zero is stamped there rather than derived, so a new skip is a decision somebody has to sign
off on: build what the suite waits for, disable it with a reason, or move the number in the same
commit.

```bash
scripts/nodera-test.sh list                    # every scenario, its tags, what a pass proves
scripts/nodera-test.sh list --ids              # bare ids, one per line, for scripts
scripts/nodera-test.sh list --ids --exclude-tag hardware   # what e2e-live builds its matrix from
scripts/nodera-test.sh run                     # the default queue (everything but 'hardware')
scripts/nodera-test.sh run continuity crash    # a selection, in the order given
scripts/nodera-test.sh run --tag server        # everything carrying a tag
scripts/nodera-test.sh bench [--full]          # JMH lanes + the ranked benchmark report
scripts/nodera-test.sh structure [--no-debug]  # dead code + cost findings + the debugger probe
scripts/nodera-test.sh all                     # scenarios, then benchmarks, then structure

  --no-build      use what is already built (fast, and wrong if you just changed code)
  --keep-running  leave the stack up after the run, to poke at it by hand
  --report DIR    where the report goes (default build/reports/nodera)
```

The size ratchet is not a scenario and does not need a stack; it is a second-long Python pass over
git-tracked source, and it runs in `scripts/dev.sh --test` and in the `build` workflow beside
`scripts/version.sh --check`:

```bash
scripts/loc-metrics.py                         # per-language table
scripts/loc-metrics.py --by-module             # per module, crate and package
scripts/loc-metrics.py --check                 # fail if the tree grew past scripts/lib/loc-baseline.json
scripts/loc-metrics.py --baseline              # re-stamp, when a growth is deliberate
scripts/loc-metrics.py --selftest              # 49 cases (22 lexer, 27 gate) — runs before --check
scripts/loc-metrics.py --json                  # per-bucket counts; sum them for a total
scripts/reference-check.py --check              # every exported symbol has a call site elsewhere
scripts/test-counts.sh --check [java|<pkg>]    # README's per-module and per-crate counts are measured
```

`--selftest` runs first everywhere `--check` does. The ratchet is worth exactly as much as the lexer
underneath it, and a regression that made the classifier read comments as code would otherwise
present as a tree that shrank. The programme it serves is [`Plan.11.md`](../plans/Plan.11.md).

`--check` gates the `*.code` limits only. Comment counts are measured, stamped and diffed — moving a
specification out of a comment block is one of the programme's levers and has to be visible — but a
rise in them prints a note and passes. The exclusion is not a softening: a gate that makes deleting
documentation the cheapest way to go green is pointed at the wrong thing, and it demonstrably was.
An agent extracting a collaborator from a god-class hit the comment bucket and trimmed comments to
pay for the new file's header. That time the documentation had moved with the code and nothing was
lost. The next time it would not have been.

**`:testing` buckets as `java.test`, not `java.main`.** Everything in that module is test code; it
compiles into `src/main` only because that is how one Gradle module exposes types to another
module's tests. The classifier reads the exception from `layout.properties`, so relocating the
module cannot silently un-fix it. Before that, extracting a shared fixture out of twenty-five tests
reported as production growth and turned the gate red for doing exactly what its issue asked.

---

## 1a. The in-JVM mesh harness

`scripts/nodera-test.sh` drives the LIVE scenarios. The in-JVM integration tests — the twenty-five
`*IT.java` that boot peers over `LoopbackTransport` without a Minecraft process anywhere — use
`dev.nodera.testkit.peer` instead, and run inside `./gradlew :peer:test`.

| Type | What it is |
|---|---|
| `PeerTestHarness` | one `LoopbackNetwork` plus the teardown of everything built on it; `close()` unwinds in reverse creation order |
| `SpawnedService` | one Rust service binary on an ephemeral port: locate, write a TOML, start, read the bound address off its own ready line, drain, stop. Existed four times before it existed once |
| `EngineFixtures` | the batch/context/execute preamble, `blockAt`, and the uniform-world builders. `executeTicks` was an eight-line body in eighteen files |
| `ValidationNode` | a committee member: identity, transport, optional `PeerRuntime`, `WorkerValidationService` over the real engine, certificate store |
| `WorkerNode` | an always-on worker behind a real `ControlServer`, spoken to through `dev.nodera.testkit.harness.ControlClient` — the product's own client, so no assertion can pass on a state the product cannot read |
| `MeshNode<S>` | a peer carrying one wire-speaking service, with the mutable membership list that service relays to |
| `RegionFixtures` | the snapshot and signed-action values every committee test starts from |
| `Await` | `until` (fails on expiry) and `quietly` (returns, so the test keeps its own failure message) |

**The rule for extending it: where two tests disagree, the difference becomes a named parameter.** A
default that quietly settles a disagreement leaves both tests green and one of them meaningless. The
parameters that exist today because of exactly that are the committee vote timeout, the world seed
and the control-socket timeout.

**And the rule the structural report added: build a collaborator through the constructor that
matches its shape, not through the widest one with `null`s.** They produce the same object, so it
reads as equivalent — but a production type that publishes one overload per embedding loses a
reference for every overload the harness stops calling, and `never_referenced_methods` only ratchets
down. `PeerTestHarness` selects between `WorkerControlHandler`'s three constructors for that reason;
`./gradlew :peer:structureReport` is what enforces it.

Artefacts:

| Path | What it is |
|---|---|
| `build/reports/nodera/TEST-REPORT.md` | scenarios, benchmarks and structure in one document |
| `build/reports/nodera/test-report.json` | the same, machine-readable |
| `build/reports/nodera/LOC-BASELINE.md` | source size by language and by component, from `--baseline` |
| `run/results/<scenario>/<stamp>/` | that run's service logs, client logs, worker state snapshots |
| `run/.e2e-suite.lock` | the exclusive lock live scenarios take — they run one at a time |

---

## 0. The port-plan guard

`HarnessPortPlanTest` (6) and `PortHolderTest` (4) exist because the harness spent a year binding the
**product's** ports: tracker 25600, rendezvous 25601, worker control 25610+i, P2P 25620+i. A
developer running the companion app — which binds 25610 — had every scenario refused at the
preflight, and the live report of 2026-08-07 (0 passed / 17 failed, all `port 25600 is still held
after 60s`) is that, not the stale test stack it was filed as
([#266](https://github.com/Ashu11-A/NoderaMC/issues/266)).

The harness now runs on its own block (26500 by default, probed and announced at startup — see
[`Task.0.md`](Task.0.md) §3.1). A port change alone is a fix a later refactor silently undoes, so the
guard asserts against **the product's own constants** — `PeerNode.DEFAULT_CONTROL_PORT`,
`PeerNode.DEFAULT_P2P_PORT`, `DefaultServices.DEVELOPMENT_TRACKER/RENDEZVOUS` — rather than copies of
them, and covers the whole block rather than the four ports somebody remembered. `PortHolderTest`
pins the *classification* of a held port (installed Nodera / leftover from this checkout / unrelated)
rather than its wording, because those imply three opposite actions and the old message offered one
wrong hint for all three.

```bash
./gradlew :testing:test --tests '*HarnessPortPlanTest*' --tests '*PortHolderTest*'
NODERA_E2E_PORT_BASE=25500 scripts/nodera-test.sh run telemetry   # reproduces the collision
```

---

## 1. The scenarios

Each was a `scripts/e2e-<id>.sh`; the stage names (`S0`, `S1a`, `S2c`, …) are carried over, so a
report from the new tool maps onto an old run line by line.

| Scenario | Tags | What a pass proves |
|---|---|---|
| `telemetry` | headless,telemetry | nothing is collected until consent is granted, and nothing identifying survives it |
| `android-mesh` | android,hardware,live | the peer on an Android phone joins the Linux mesh and receives bytes from it |
| `chunk-continuity` | android,continuity,hardware,live | two launchers and a phone: the creator keeps `/op`, an idle world stays under a per-peer traffic ceiling, and a killed host does not take anybody out of the world |
| `churn` | continuity,live | repeated join/leave churn leaves the world and the peers undamaged |
| `commands` | commands,live | every /nodera command answers correctly for two players in one world |
| `continuity` | continuity,live | a shared world survives the player who was hosting it |
| `crash` | continuity,live | a player's client crashing disrupts nothing for the player still in the world |
| `determinism` | determinism,live,soak | three independent nodes re-executing the same regions never disagree |
| `endpoint` | endpoint,server | nodera-endpoint enables on Paper and keeps its world hosted when the server dies |
| `farlands` | live,ownership | two players 566 km apart each control the chunks they stand in |
| `folia` | folia,server | the endpoint verifies ALIGN-1 on Folia and refuses to enable where it cannot hold |
| `mesh-soak` | live,mesh | validated state keeps flowing over the live mesh under load, and the peers agree |
| `mobile-continuity` | android,continuity,hardware,live | a phone peer holds a live world's content while two desktop players come and go |
| `mobs` | entity,live | the ghost lane captures where a dimension opted in and refuses where it did not |
| `ownership` | live,ownership | each player owns its own regions, and the world survives its host leaving |
| `ownership-follow` | live,ownership | region ownership follows a walking player instead of freezing at its join position |
| `password` | live,password | a password-gated world refuses a joiner without the password and admits one with it |
| `pearl` | entity,live | an ender pearl is captured, flies across regions, and teleports its thrower |
| `pickup` | entity,live | a clean-slate item pickup is delivered to exactly one player, exactly once |
| `plugins` | plugins,server | nodera-endpoint co-exists with the staged plugin corpus and nothing throws |
| `profile` | live,profile,server | the profiler attributes real sampled tick time to Nodera's own classes |
| `rekey` | live,password,rekey | re-keying a world invalidates the old password at the live gate and re-seeds ciphertext |

`scripts/nodera-test.sh list` prints this table from the code; if the two disagree, the code is
right. The `mobile-continuity` row was missing from this copy until 2026-08-10 — twenty-two
scenarios, twenty-one rows — which is the drift this table's instruction header exists to catch.

The nineteen that are **not** tagged `hardware` are exactly what `e2e-live` dispatches, and the
workflow now asks for them (`list --ids --exclude-tag hardware`) instead of keeping its own copy.
Its copy had thirteen: `churn`, `endpoint`, `folia`, `ownership`, `plugins` and `telemetry` were
written, registered, listed, validated against the tool — and never run by any nightly.

## 2. What a scenario may and may not do

- **Every assertion goes through a stage.** `ctx.stage(name, description, body)` records the name,
  what a pass proves, the outcome, the duration and — on failure — the message. That is what makes
  the report a document instead of somebody's stdout.
- **Evidence comes from the product's own surfaces**: a log line, `NODERA-STATE` over the worker's
  control socket, or RCON. There is no test-only accessor, so no scenario can pass on a state the
  product cannot see.
- **A skip is what this machine lacks** — no display, no device, not enough RAM. Anything the build
  produces is built by the runner instead of being waited for. The three server-category shell
  suites used to skip on every machine including CI because nothing built the jar they looked for;
  a skip that is structural rather than circumstantial is a suite that never runs.

  The twelve JUnit skips were the same failure in the unit gate: every one was "a cargo binary or
  the peer distribution was not built", and the `java` job that runs `./gradlew check` was not the
  job that built them. `dev.nodera.testkit.harness.SpawnedService` is the Java half of the fix — it
  locates the binary, and when `nodera.test.requireServiceBinaries` (or
  `NODERA_REQUIRE_SERVICE_BINARIES`) is set it **throws instead of letting the caller assume**, so a
  job that promises to build them cannot silently stop. The workflow half is wired: the `java` job
  now runs, before `./gradlew check`:

  ```yaml
  - run: cargo build --release --bin nodera-tracker --bin nodera-rendezvous --bin nodera-telemetry
  - run: ./gradlew :peer:installDist
  ```

  **Since 2026-08-10 the live lane has the same rule, and it lives in the tool.** `nodera-test run`
  used to exit `anyFailed() ? 1 : 0`, so a matrix in which every leg skipped exited 0 and rendered
  green. Now any skip is a non-zero exit unless `--allow-skips` is passed, and a **structural** skip
  is non-zero with it — there is no property of the machine for an operator to be accepting. The
  kind is on every result in `build/reports/nodera/test-report.json` (`"skipKind"`), and the tool
  prints the id, the kind and the reason of every scenario that did not run.

  **`continuity` cannot be run on a 14 GB developer box, and it now says so instead of failing.**
  Its requirement is `Requirements.liveClients(8)`: two full Minecraft clients, three headless
  workers, a tracker, a rendezvous and the host's own integrated server. On a box reporting ~6 GiB
  available the whole arrangement started and then put the host 1066 ticks behind, which dropped the
  joiner's *vanilla* connection and failed S2c forty minutes in with a message that named the
  validation lane. Eight GiB is the floor under which the 2026-08-04 runs did not survive, so such a
  box now reports a circumstantial skip in one line at the start. Run `continuity` on the reference
  machine or in `e2e-live`; a local `--allow-skips` run is not evidence for it.

  and a `Nothing skipped into green` step fails the job on a non-zero skip count. To run the same
  gate locally, promise the binaries explicitly:

  ```bash
  cargo build --release --bin nodera-tracker --bin nodera-rendezvous --bin nodera-telemetry
  ./gradlew :peer:installDist
  ./gradlew check build -Dnodera.test.requireServiceBinaries=true
  ```

  **That `-D` works only because the build forwards it.** A launcher `-D` sets a property on the
  Gradle *daemon*, and a `Test` task inherits the *daemon's* environment rather than the invoking
  shell's, so neither the property nor the variable reaches a forked test worker on its own.
  `library/java/build-logic/src/main/kotlin/NoderaServiceBinaries.kt` reads both through
  `providers` and re-publishes the value as a system property on every `Test` task; the flag is a
  task input, so flipping it re-runs the suites rather than restoring a cached green.
  `SpawnedServiceRequirementTest` holds the Java half to it.

  The skip guard and the promise flag overlap on purpose. `Nothing skipped into green` catches the
  *outcome* — any skip, from any cause, including one nobody has thought of. The flag catches the
  *cause*, at the suite that skipped, naming the binary and the directory it searched, and it works
  on a developer's machine where no workflow step runs.
- **Timeouts are written in plain seconds** and scaled once, centrally, by
  `NODERA_E2E_TIMEOUT_MULT`. CI runners are slower by a factor nobody can predict from the code, and
  scaling waits individually is how one stage ends up still timing out on the slow machine.

## 3. The other two lanes

**Benchmarks** — four JMH lanes over the real peer classes (discovery, chunk synchronisation, the
wire codec, the always-on worker's per-second cost). The report ranks every measurement, computes
how cost grows against each `@Param`, and diffs against `fixtures/bench/baseline.json`. Details:
[`docs/network/Task.15.md`](../network/Task.15.md).

**Structure** — every module's bytecode read for the reference graph and in-loop cost, plus a JDWP
probe of the real `nodera-headless` worker to see which of that code executes. Counts ratchet
downwards through `fixtures/structure/budget.json`. Details: the same task file.

## 4. Where the live scenarios actually run

| Where | What |
|---|---|
| `e2e-live` workflow (nightly + dispatch) | the live matrix under Xvfb, `NODERA_E2E_TIMEOUT_MULT=3` |
| `benchmarks` workflow (PR · main · weekly) | the benchmark and structure lanes |
| a developer's machine | anything, one at a time — the lock is what makes that safe |

## 5. Proving the tool itself

The tool is code, so it is held to the same standard as the rest of the tree: its classes are
compiled by `./gradlew check`, and `scripts/nodera-test.sh list` is the smoke test that the registry
resolves, the scenarios construct, and the CLI parses. The `e2e-live` plan job both **validates**
every requested scenario name against that command and **derives** its default matrix from it
(`list --ids --exclude-tag hardware`), so a renamed scenario cannot leave CI dispatching something
that no longer exists and a new scenario cannot be left undispatched.

Validating against the tool was never enough on its own: the workflow used to validate a
hand-kept array, which only proved the array was stale in a legal way.
`NoderaTestListTest.theRealRegistryYieldsEveryUnattendedScenarioToTheNightlyMatrix` is the unit
test that holds the derived set equal to `ScenarioRegistry.defaultBatch()`.
