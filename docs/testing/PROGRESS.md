# Testing — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the testing category. On every
     outcome-changing commit touching the tooling: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (a scenario id, a report path, a command), then reconcile
     ../ROADMAP.md and the root README. Never rewrite an old note — append a new one. -->

**Category:** testing · **Last audit:** 2026-08-10 · Tasks completed: **1 / 1**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Convert the shell suites to Java scenarios; unify the tooling | ✅ COMPLETED | 20 scenarios in `dev.nodera.testkit.scenario`; one `nodera-test` tool over scenarios + benchmarks + structure; worker `--test-mode --role`; `scripts/nodera-test.sh` replaces twenty suite scripts and the batch runner |

---

## 2. Milestone notes (newest first)

### 2026-08-10 — The harness stopped binding the product's ports, so a developer can run it at all

`Topology` handed every scenario the numbers a shipped Nodera binds — tracker 25600, rendezvous
25601, worker control 25610+i, P2P 25620+i. On any machine with the companion app running, the
preflight refused every scenario, so **the more complete somebody's install the less of the matrix
they could execute**. The live report of 2026-08-07 (0 passed / 17 failed, every failure `port 25600
is still held after 60s`) is that collision and not the stale test stack it was filed as.

Every port is now `portBase + offset`; the base defaults to 26500, `Topology.chosenBase()` probes the
whole block at startup and steps 200 up if anything is listening in it, and the block it settled on
is the run's first line. `NODERA_E2E_PORT_BASE` pins it. `PortHolder` reads `/proc/net/tcp{,6}` and
`/proc/<pid>/fd` so a genuinely held port names its holder and says whether it is an installed
Nodera, a leftover from this checkout, or something unrelated — three different actions where the
old message offered one wrong hint. `TelemetryScenario`'s own three literals moved onto
`Topology.scenarioPort(i)`, inside the block the probe checks.

Evidence, both runs made **with the user's installed companion app still holding 25610**:

- failing-before — `NODERA_E2E_PORT_BASE=25500 scripts/nodera-test.sh run telemetry` →
  `0 passed, 1 failed`, `port 25610 is still held after 60s by pid 742938 → … (an INSTALLED Nodera
  (/usr/lib/Nodera/resources/nodera-headless/lib/peer-headless.jar), not a leftover test stack …)`,
  exit 1;
- passing-after — `scripts/nodera-test.sh run telemetry` → `nodera-test: port block 26500 (free)`,
  T0–T6 all PASS, `1 passed, 0 failed, 0 skipped`, exit 0
  (`build/reports/nodera-i266-after/TEST-REPORT.md`).

`HarnessPortPlanTest` is what keeps it fixed: it reads `PeerNode.DEFAULT_CONTROL_PORT`,
`PeerNode.DEFAULT_P2P_PORT` and `DefaultServices.DEVELOPMENT_*` rather than copies, so the two tables
cannot converge again by either side moving. Also fixed here: `Requirements` truncated free RAM to
whole GiB, so 4.99 GiB read as 4 and skipped a scenario asking for 5 — and a skip is red since
[#259](https://github.com/Ashu11-A/NoderaMC/pull/259). ([#266](https://github.com/Ashu11-A/NoderaMC/issues/266))

### 2026-08-05 — Twenty-five integration tests stopped building their own mesh (Plan 11 phase 3)

`dev.nodera.testkit.peer` is now the one in-JVM mesh an integration test stands up:
`PeerTestHarness` owns a `LoopbackNetwork` and the teardown of everything on it, and hands out three
node shapes — `ValidationNode` (a committee member over the real engine), `WorkerNode` (an always-on
worker behind its real control endpoint) and `MeshNode` (a peer carrying one gossip service).
`RegionFixtures` holds the snapshot and action values every committee test starts from, and `Await`
is the one place waiting is written. This is the `ControlSocketHarness` / `LoopbackMeshHarness` pair
that [`../peer/REFACTORING.md`](../peer/REFACTORING.md) §2 sequences first; the control half reuses
the existing `dev.nodera.testkit.harness.ControlClient` rather than adding a fourth implementation of
the control wire.

**Evidence.** `scripts/test-totals.sh --java` reports `2423 passed · 0 failed · 12 skipped` before
and after — the same numbers, which is the phase's stated gate, and the skip count is flat too.
`scripts/loc-metrics.py --diff` reports `java.test.code −768` net (1,484 lines of duplicated setup
removed from the twenty-five ITs, against 716 for the harness that replaced it) and
`java.main.code ±0` — this phase changed no production code. `./gradlew :peer:structureReport` is
within budget on every counter.

**What was deliberately NOT flattened.** Three per-suite differences became named parameters rather
than defaults, because a shared fixture that settles one of them leaves both tests green and one of
them meaningless: the committee vote timeout (700 ms for the collapse suite, whose central assertion
is that a proposal *fails* to reach quorum; 2 s for the Byzantine suite; 5 s elsewhere), the world
seed (the halo suite runs on its own, so the fluid automaton floods what it floods), and the control
timeout (60 s for the re-key verb, which derives an Argon2id key on the request thread).

**Two defects found by the extraction.** `library/rust/nodera-codec/tests/mutation.rs` read its wire
tag as `u16::from_be_bytes([golden[0], golden[1]])` — the first two bytes of the `NDR2` magic, which
is no tag — so every fixture fell through to the discovery decoder and the rendezvous and service
frames were decode-failed and silently skipped. Pulling `tag_of` into `tests/common/mod.rs` put the
two files' readings side by side; the correct read passes.

And the structural report caught the consolidation itself. `WorkerControlHandler` publishes one
constructor per embedding — no config plane, a config plane, a config plane plus a key store — and
each declines the verbs it has no seam for. Building every harness worker through the widest
overload with `null`s produces the same object, so it looks equivalent; it is not, because it left
the eleven-argument constructor (whose only caller in the tree was the configuration suite)
referenced by nothing at all, and `never_referenced_methods` rose 136 → 137 against a
ratchet-down-only budget. The harness now selects the constructor by the SHAPE of the worker it is
building, which is what each suite did before it moved here. A shared fixture erases an API's only
exercise as easily as it erases duplication.

### 2026-08-05 — The gate can now say how big the tree is

`scripts/loc-metrics.py --selftest && --check` joined the gate, in `scripts/dev.sh --test` and in the
`build` workflow beside `scripts/version.sh --check`. It is not a test of the product; it is a test
of a claim about the product, and it exists because the reduction programme in
[`../plans/Plan.11.md`](../plans/Plan.11.md) has no evidence without one agreed way to count.

**Lexed, not grepped.** `scripts/lib/loc_classify.py` is a state machine per language, because the
three mistakes a regex makes here are all present in this tree: `"https://example"` is not a
comment, Rust's `'a` is not an unterminated character literal, and the `/*` inside a TypeScript
regex literal must not swallow the rest of the file. 18 fixtures in `--selftest` pin all three plus
Java text blocks, nested Rust block comments and multi-line raw strings and templates. `--selftest`
runs before `--check` everywhere, because a classifier that started reading comments as code would
otherwise report a tree that shrank.

**Ratcheted like `budget.json`.** `scripts/lib/loc-baseline.json` holds ten limits that may only go
down. Growth is not forbidden — it has to be re-stamped with `--baseline` in the same commit, which
turns "the tree got bigger" into a line somebody reviews. Generated files are measured and then
excluded from the limits: `nodera-codec`'s `kinds.rs` grows whenever a wire kind is appended, and a
size gate that fails on that is a gate against adding messages.

**Evidence:** baseline stamped at `9959df1` — 1,420 tracked files, 246,102 lines, 164,552 code,
58,372 comment. Both directions proved: a one-line probe file staged into `:core` failed the check
with `java.main.code: 69241 → 69242`, and removing it passed. Report at
`build/reports/nodera/LOC-BASELINE.md`.

### 2026-07-29 — The test lane stopped being twenty shell scripts (task 1)

Twenty `scripts/e2e-*.sh` suites and their batch runner became twenty `Scenario` classes and one
tool. What changed is not the coverage — the stages, the evidence strings and the timeouts were
carried over deliberately — but what a run can say about itself.

**The harness is shared now.** `dev.nodera.testkit.harness` owns the topology and port plan, process
supervision (including killing a Gradle launcher's Minecraft grandchild, which a `destroy()` on the
launcher never did), log waiting with its three shapes (a new occurrence after a mark, a bail-out
guard, and the blocked-screen guard for a client parked where quick play never goes), the worker
control client, the run lock and artefact collection. Each suite used to carry its own version of
all of that, so a change to one evidence line fixed thirteen and quietly broke seven.

**Assertions produce a report, not stdout.** Every assertion runs inside `ctx.stage(name, what, body)`,
so a run yields stage names, durations and causes that `build/reports/nodera/TEST-REPORT.md` renders
— alongside the benchmark and structure lanes, which used to be separate artefacts nobody read
together.

**Workers know which player they are.** `nodera-headless --test-mode --role player1` is new: the role
is reported over `NODERA-TEST` and in the state document, so a cross-node assertion names its
subject instead of identifying it by which control port started first. Test mode is a command-line
flag and nothing else, and a worker without it has no test surface at all.

**One command, one queue.** `scripts/nodera-test.sh run | bench | structure | all` replaces
`scripts/run-tests.sh` and the twenty suite scripts. `scripts/lib/` stays: `scripts/dev.sh --play`
uses it to launch a hands-on two-player playground, which is a development convenience rather than a
test.
