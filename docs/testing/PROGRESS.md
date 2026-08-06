# Testing — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the testing category. On every
     outcome-changing commit touching the tooling: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (a scenario id, a report path, a command), then reconcile
     ../ROADMAP.md and the root README. Never rewrite an old note — append a new one. -->

**Category:** testing · **Last audit:** 2026-08-05 · Tasks completed: **1 / 1**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Convert the shell suites to Java scenarios; unify the tooling | ✅ COMPLETED | 20 scenarios in `dev.nodera.testkit.scenario`; one `nodera-test` tool over scenarios + benchmarks + structure; worker `--test-mode --role`; `scripts/nodera-test.sh` replaces twenty suite scripts and the batch runner |

---

## 2. Milestone notes (newest first)

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
