# Testing Task 1 — One tool: shell suites become Java scenarios

<!-- AI-AGENT-INSTRUCTION: Three rules are load-bearing and must survive every edit.
     (1) An assertion goes through a surface the PRODUCT exposes (a log line, the worker control
     socket, RCON) — never a test-only accessor, or a suite can pass on a state the product cannot
     see. (2) A skip is circumstantial (no display, no device) and never structural (nothing builds
     the jar the scenario looks for); a structural skip is a scenario that never runs. (3) Worker
     test mode is a COMMAND-LINE flag and nothing else — it opens a remote-control surface, so no
     config file, environment variable or peer may enable it. Keep this header accurate. -->

**Status:** ✅ DONE — twenty scenarios, one harness, one report, one command; the live matrix is
carried over stage for stage and awaits its first full green nightly (row T-1)
**Category:** testing · **Owns:** T-1, T-2, T-3, T-4, T-6, T-7 · **Last audit:** 2026-08-10
**Depends on:** [worker 1](../peer/Task.1.md), [minecraft 5](../minecraft/Task.5.md),
[server 1](../server/Task.1.md), [network 15](../network/Task.15.md)
**Consumed by:** every category with a live acceptance claim

---

## Goal

One command answers "is this commit any good": the acceptance scenarios, the benchmarks and the
structural report all run from `scripts/nodera-test.sh`, all write into one report, and all mean the
same thing by a non-zero exit. The twenty shell suites become Java classes sharing one harness, and
the workers in a run know which player they are.

## Status detail

### What replaced what

| Was | Is |
|---|---|
| `scripts/e2e-<id>.sh` × 20 | `dev.nodera.testkit.scenario.<Id>Scenario` × 20 |
| `scripts/run-tests.sh` | `scripts/nodera-test.sh run [ids…]` |
| `scripts/lib/e2e-main.sh` (for tests) | `dev.nodera.testkit.harness.*` |
| `scripts/lib/e2e-server.sh` | `ServerEndpointSupport` |
| `scripts/lib/vanilla-bot.py` | `ServerVanillaBot` (Minecraft protocol, no Minecraft) |
| `rcon_once()` (inline Python) | `dev.nodera.testkit.mc.RconClient` |
| `control_verb()` (`/dev/tcp`) | `dev.nodera.testkit.harness.ControlClient` |
| stage numbers on stdout | `StageResult` → `TEST-REPORT.md` + `test-report.json` |

`scripts/lib/` itself stays: `scripts/dev.sh --play` uses it for the hands-on two-player playground,
which is a development convenience and not a test (row T-3 tracks that duplication).

### What the harness now owns once

- **The topology and port plan** — 2 players · 1 tracker · 1 rendezvous · 3 peers, with the quorum
  floor explained where it is decided rather than in twenty copies of a comment.
- **Process supervision** — including killing a Gradle launcher's Minecraft *grandchild*. A
  `destroy()` on the launcher left that JVM holding the game port, and the next scenario failed a
  port check with no hint that the previous one was still alive.
- **Log waiting, in its three real shapes** — a new occurrence strictly after a mark (a needle that
  was already there proves nothing); a bail-out guard (five more minutes of waiting after a known
  fatal line produces a worse message, not a better one); and the blocked-screen guard that turns a
  thirty-minute "never joined" into "the client is parked on TitleScreen".
- **The run lock, port preflight, and artefact collection.**

### Roles: a cross-node assertion can name its subject

Workers are started `nodera-headless --test-mode --role player1`. The role comes back over
`NODERA-TEST ROLE` and appears in the state document, so a scenario writes `stack.worker(PLAYER_TWO)`
and gets the right node whatever the port plan says. Before this, such an assertion identified its
subjects by which control port had started first — and a port number is not an identity.

`NODERA-TEST DRIVE <action>` publishes an action for the attached game to execute, on the same event
stream the mod already consumes. The consumer in the mod is not written yet (row T-2), so scenarios
currently drive players through RCON; the role plumbing is what a driven player will use.

### Why test mode is a flag and nothing else

It opens a remote-control surface. A production node must not be able to grow one because a test
needed it, so it is enabled only by an argument on a process a human deliberately started — not by a
config file another program can write, not by an inherited environment variable, and not by a peer.
Without the flag the worker answers `NODERA-ERR unsupported` to every test verb: the same answer it
gives to a verb that does not exist.

## Dependencies

- **worker 1** — `HeadlessPeerMain`, the control verbs, and now the test-mode flag.
- **minecraft 5** — the dev client/server run tasks the scenarios launch.
- **server 1** — the Paper/Folia endpoint plugin the server-tagged scenarios test against.
- **network 15** — the benchmark and structural lanes this tool drives as subcommands.

## Deliverables

| # | Deliverable | Where |
|---|---|---|
| 1 | Live harness (topology, processes, logs, control, lock, artefacts) | `library/java/testing/.../testkit/harness/` |
| 2 | Scenario SPI, context, results, registry, runner | `library/java/testing/.../testkit/suite/` |
| 3 | Twenty acceptance scenarios | `library/java/testing/.../testkit/scenario/` |
| 4 | RCON client + Minecraft-protocol bot | `library/java/testing/.../testkit/mc/` |
| 5 | One report over scenarios + benchmarks + structure | `library/java/testing/.../testkit/report/RunReport.java` |
| 6 | The `nodera-test` CLI | `library/java/testing/.../testkit/cli/NoderaTestMain.java` |
| 7 | Worker test mode + role | `peer/.../headless/TestMode.java`, `HeadlessPeerMain` |
| 8 | The `NODERA-TEST` verb | `dev.nodera.peer.control.{ControlProtocol,ControlServer,ControlHandler}` |
| 9 | The single entry script | `scripts/nodera-test.sh` |
| 10 | CI rewired to the tool | `.github/workflows/e2e-live.yml` |

## Design

### Why the logic moved into Java at all

The suites shared a launcher and nothing else. Each re-implemented its own waiting, its own JSON
poking with `grep`, its own idea of what "the world is shared" looks like in a log, and its own exit
conventions. A change to an evidence line fixed thirteen suites and quietly broke seven; a failure
was a stage number with no structure any report could read; and an assertion made against a silent
process was indistinguishable from one made against a wrong answer.

### Why scenarios are discovered, not listed

`ScenarioRegistry` uses `ServiceLoader`. The shell runner carried an `ALL_SUITES=(…)` array, and a
suite added without touching it existed as a file nobody ran. Discovery makes "written" and
"runnable" the same state.

### Why each scenario gets a fresh stack

Scenarios kill hosts, corrupt archives and re-key worlds on purpose. Sharing one stack across a
queue lets the third scenario inherit the second's damage and report it as its own — which is how a
shell batch produced failures nobody could reproduce alone.

### Why the queue does not stop at the first failure

A batch that stops tells you about one lane and nothing about the other nineteen, and the first red
lane is often the flakiest rather than the most important. Every scenario runs; the exit status is
the worst outcome in the queue.

## Files

```
library/java/testing/build.gradle.kts                      # application plugin, :peer dependency
library/java/testing/src/main/java/dev/nodera/testkit/
    harness/  TestPaths Topology PlayerRole ManagedProcess LogWatcher ControlClient
              LiveStack HarnessException
    suite/    Scenario ScenarioContext ScenarioRegistry ScenarioRunner ScenarioResult
              StageResult Requirements SkipSignal
    scenario/ <one class per scenario> + HostWorldSupport + Server*Support
    mc/       RconClient ServerVanillaBot
    report/   RunReport
    cli/      NoderaTestMain
peer/src/main/java/dev/nodera/headless/TestMode.java
scripts/nodera-test.sh
.github/workflows/e2e-live.yml
```

## Testing

```bash
scripts/nodera-test.sh list                     # the registry resolves and every scenario constructs
scripts/nodera-test.sh run telemetry            # the headless scenario, no display needed
scripts/nodera-test.sh run --tag live           # the live matrix, one at a time
./gradlew check                                 # unchanged in scope: the tool compiles with the tree
```

The `e2e-live` workflow validates every dispatched scenario name against `nodera-test list` rather
than against a list kept in the workflow, so a renamed scenario cannot leave CI dispatching
something that no longer exists.

## Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| 1 | Every `scripts/e2e-*.sh` exists as a Java scenario with its stages carried over | `dev.nodera.testkit.scenario`, 20 classes |
| 2 | A run produces a report of results and failures, not stdout | `build/reports/nodera/TEST-REPORT.md` + `test-report.json` |
| 3 | Workers take a CLI flag for debug/test mode and a player role | `nodera-headless --test-mode --role player2`; `NODERA-TEST ROLE` |
| 4 | Roles address nodes in assertions | `stack.worker(PlayerRole.PLAYER_TWO)` |
| 5 | Benchmarking and reporting are part of the same tool | `nodera-test bench` / `structure` / `all` |
| 6 | One script runs the queue or a selection | `scripts/nodera-test.sh run [ids…] [--tag t]` |
| 7 | The individual suite scripts are gone | `ls scripts/e2e-*.sh` → nothing |
| 8 | The tooling is documented | this category |

## The harness tells the truth about itself (2026-08-10)

Three rules landed together, because until they held, no live run's evidence meant anything.

**A run in which nothing ran is not green.** `nodera-test run` exited `anyFailed() ? 1 : 0`, and
`SKIPPED` is not `failed` — so a live matrix in which every leg skipped exited 0 and rendered as a
passing build. The unit gate had carried the opposite rule for months (`build.yml`, "Nothing skipped
into green"); the lane that produces the expensive evidence had no equivalent. It is now in the
TOOL, not in a workflow step, because a workflow can forget it and a second workflow never had it.
Any skip is red unless the operator passes `--allow-skips`, and a **structural** skip — an artefact
the harness itself builds is missing, so the scenario could not have run anywhere — is red either
way. `Requirements.unmet` returns a classified `Skip`, `ScenarioResult` carries the kind, and
`test-report.json` publishes it. Issue [#256](https://github.com/Ashu11-A/NoderaMC/issues/256).

**Every process the stack starts is proven answering before a scenario's first stage.** The rule
existed for the trackers and the rendezvous and stopped there. It now covers the dedicated server
(its game port **and** an RCON round trip — a server that binds and does not answer RCON is not
ready), a Minecraft client (a game JVM carrying that run's own `<run>RunProgramArgs` token, which
only exists once Gradle has configured, compiled and forked), and the companion launcher. Every
failure names the process, its exit status where it has one, and quotes its log tail. Row T-5,
retired.

**A capacity failure is not a lane bug.** `LogWatcher.awaitWithLagGuard` reads vanilla's own
`Can't keep up! … N ticks behind` from the host's log when a wait expires, and above 200 ticks says
"the host server fell N ticks behind … the machine, not the lane". The threshold is not zero on
purpose: a guard that fired on a chunk-generation hiccup would convert every real lane bug into
"blame the machine", which is the same wrong answer pointing the other way. Row T-6.

## Limitations

Rows T-1 (a full green nightly across the converted matrix), T-2 (the mod does not consume
`test.drive` yet), T-3 (`scripts/lib/` still serves `dev.sh --play`), T-4 (RETIRING — the five named
capabilities are in the harness; the tree-wide grep is not empty because five markers it also caught
became T-7), T-6 (RETIRING — the lag guard is in and unit-proved; no live demonstration yet) and
T-7 (the harness has no slot for a process it did not design one for) are open in
[`LIMITATIONS.md`](LIMITATIONS.md), each with its exit test. T-5 is retired in
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
