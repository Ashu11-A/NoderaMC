# Testing — how the test tooling itself is used and proved

<!-- AI-AGENT-INSTRUCTION: Keep the scenario table in agreement with `scripts/nodera-test.sh list`;
     that command is the source of truth, this table is a copy for readers who have not built the
     tool. Counts and last-run dates come from an actual run, never from memory. A scenario that is
     added or renamed updates this file in the same commit. -->

**Category:** testing · **Last run:** 2026-07-29 · **25 unit tests · 0 failing** (module `testing`,
from the `./gradlew check` XML reports) — the live scenarios themselves run in the `e2e-live`
workflow nightly, and `scripts/nodera-test.sh list` is the smoke test that all twenty resolve.

```bash
scripts/nodera-test.sh list                    # every scenario, its tags, what a pass proves
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
scripts/loc-metrics.py --selftest              # 18 lexer fixtures — runs before --check in the gate
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

Artefacts:

| Path | What it is |
|---|---|
| `build/reports/nodera/TEST-REPORT.md` | scenarios, benchmarks and structure in one document |
| `build/reports/nodera/test-report.json` | the same, machine-readable |
| `build/reports/nodera/LOC-BASELINE.md` | source size by language and by component, from `--baseline` |
| `run/results/<scenario>/<stamp>/` | that run's service logs, client logs, worker state snapshots |
| `run/.e2e-suite.lock` | the exclusive lock live scenarios take — they run one at a time |

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
right.

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
resolves, the scenarios construct, and the CLI parses. The `e2e-live` plan job validates every
requested scenario name against that command rather than against a list kept in the workflow, so a
renamed scenario cannot leave CI dispatching something that no longer exists.
