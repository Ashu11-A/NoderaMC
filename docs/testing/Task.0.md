# Testing — Category charter

<!-- AI-AGENT-INSTRUCTION: This category owns the TOOLING, not the coverage. A claim about what is
     tested belongs in the owning category's TESTING.md; what belongs here is the harness, the
     scenario SPI, the benchmark lanes, the structural report, and the one command that drives all
     three. Two rules are load-bearing: (1) a skip must be circumstantial (this machine lacks a
     display) and never structural (nothing builds the jar the scenario looks for) — a structural
     skip is a scenario that never runs; (2) an assertion must go through the same interface the
     product exposes (the control socket, RCON, a log line), never a test-only accessor, or the
     suite can pass on a state the product cannot see. -->

**Category:** testing · **Owns:** the test tooling · **Last audit:** 2026-07-29
**Code:** `library/java/testing` (`dev.nodera.testkit.*`), `scripts/nodera-test.sh`, `scripts/bench-report.py`,
`peer/src/jmh`, `peer/src/test/java/dev/nodera/structure`

---

## 1. What this category is

One tool answers the three questions worth asking about a commit:

| Question | Lane | Command |
|---|---|---|
| Does it work, end to end, with real Minecraft? | acceptance scenarios | `scripts/nodera-test.sh run` |
| Is it getting slower? | JMH benchmark lanes | `scripts/nodera-test.sh bench` |
| How much of it ships and does nothing? | structural report | `scripts/nodera-test.sh structure` |

All three write into one report (`build/reports/nodera/TEST-REPORT.md`), because a change that
passes every suite while doubling a benchmark and adding two dead classes used to look entirely
clean — three artefacts, three formats, and nobody read all three.

## 2. Architecture

```
scripts/nodera-test.sh          builds and execs the tool; the only shell left in the test lane
  └── nodera-test (library/java/testing, application plugin)
        ├── dev.nodera.testkit.cli        the subcommands: list · run · bench · structure · all
        ├── dev.nodera.testkit.suite      Scenario SPI, context, stage results, runner, registry
        ├── dev.nodera.testkit.scenario   one class per acceptance scenario
        ├── dev.nodera.testkit.harness    topology + port plan, process supervision, log waiting,
        │                                 the worker control client, the run lock, artefacts
        ├── dev.nodera.testkit.mc         RCON, and a Minecraft-protocol bot with no Minecraft in it
        └── dev.nodera.testkit.report     scenarios + benchmarks + structure, in one document
```

The in-JVM helpers that were always here (`LoopbackTransport`, `FakeRegion`, `FixtureWriter`) are
unchanged and still consumed by every module's unit tests. This category absorbed the live harness
rather than replacing them.

## 3. The standard topology

Every scenario gets the same shape unless it asks for another:

```
2 players · 1 tracker · 1 rendezvous · 3 headless peers

  peer 1  player 1's companion worker   control 26610 · p2p 26620   role PLAYER_ONE
  peer 2  player 2's companion worker   control 26611 · p2p 26621   role PLAYER_TWO
  peer 3  spare standalone headless     control 26612 · p2p 26622   role SPARE
```

Three peers is the quorum floor: `DiagnosticsCollector.deriveHealth` reports DEGRADED below three
session members, so a thinner run measures a degraded system and calls the result normal. The spare
peer has no client; it holds the swarm above that floor and keeps seeding when a player's worker
dies with its game — which the continuity and crash scenarios arrange on purpose.

### 3.1 The harness's port block is not the product's

Every port above is `portBase + offset`. The base defaults to **26500** — the product's block plus a
thousand — and the offsets are the historical ones, so `PRODUCTION_PORT_BASE` (25500) `+ offset`
gives the numbers every older log, script and doc names:

| Offset | What | Harness default | Product default |
|---|---|---|---|
| `+75` | RCON of the staged dedicated server | 26575 | 25575 |
| `+99` | game | 26599 | — |
| `+100` | tracker 1 (`+140+i` for extras) | 26600 | 25600 |
| `+101` | rendezvous 1 (`+150+i` for extras) | 26601 | 25601 |
| `+110+i` | worker `i` control | 26610 | 25610 |
| `+120+i` | worker `i` P2P | 26620 | 25620 |

Until 2026-08-10 the two right-hand columns were **one column**, and the consequence was that nobody
with Nodera installed could run the suite: the companion app holds 25610, the preflight refused
every scenario, and the more complete somebody's install the less of the test matrix they could
execute. The live report of 2026-08-07 — 0 passed, 17 failed, every failure `port 25600 is still
held after 60s` — is that collision, filed at the time as a stale test stack
([#266](https://github.com/Ashu11-A/NoderaMC/issues/266)).

`Topology.chosenBase()` probes the whole block at startup, steps 200 upward if anything at all is
listening in it, and prints the block it settled on as the run's first line, so a failing run can be
traced to the ports it actually used:

```
nodera-test: port block 26500 (free) — tracker 26600, rendezvous 26601, worker control 26610+i, …
```

`NODERA_E2E_PORT_BASE` pins the base when a run must be reproducible port for port. And when a port
really is held, the preflight names the process and says which kind of problem it is — an installed
Nodera to quit, a leftover from this checkout to kill, or something unrelated — because
`port 25610 is still held` sent every reader hunting a stale test stack that did not exist.

`scripts/dev.sh` and `scripts/lib/e2e-main.sh` deliberately stay on the product's own numbers: that
stack is a playground meant to behave exactly like an install, and the harness moving out of its way
is what lets the two run side by side.

## 4. Roles: workers know which player they are

Workers in a run are started as `nodera-headless --test-mode --role player1`. The role is reported
back over `NODERA-TEST ROLE` and in the state document, so a scenario says `stack.worker(PLAYER_TWO)`
and gets the right node however the ports are numbered.

Before roles existed, a cross-node assertion identified its subjects by which control port had been
started first. A port number is not an identity: renumber the plan or start the spare first, and
every such assertion silently swapped its subjects while continuing to pass.

Test mode is a **command-line flag and nothing else**. It opens a remote-control surface
(`NODERA-TEST DRIVE` publishes an action for the attached game to execute), so it must be something
a human deliberately started — not a config file another program can write, not an inherited
environment variable, and not something a peer can ask for. A worker without the flag answers
`NODERA-ERR unsupported` to every test verb, the same answer it gives to a verb that does not exist.

A driven action travels the production path: the worker publishes it on the same event stream the
mod already consumes over `NODERA-EVENTS`. A test-only side channel into the game would prove that
the side channel works.

## 5. Task index

| Task | Title | Status |
|---|---|---|
| [1](Task.1.md) | Convert the shell suites to Java scenarios; unify the tooling | ✅ COMPLETED |

Status ledger: [`PROGRESS.md`](PROGRESS.md) · how to run it: [`TESTING.md`](TESTING.md) · open gaps:
[`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).

## 6. What this category does not own

- **Coverage claims.** "The entity lane is tested" belongs in `docs/engine/TESTING.md`; this
  category owns the machinery that makes such a claim checkable.
- **The unit-test gate.** `./gradlew check` is unchanged in scope and runtime: acceptance
  scenarios, benchmarks and the structural report are all outside it, because none is a correctness
  gate and all three are minutes long.
- **`scripts/dev.sh`.** The hands-on developer stack still uses `scripts/lib/e2e-main.sh` to launch
  a two-player playground. That is a development convenience, not a test, and it stays shell.
