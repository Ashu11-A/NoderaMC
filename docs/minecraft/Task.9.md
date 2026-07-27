# Minecraft Task 9 — Profiling Lane: the spark Profiler

<!-- AI-AGENT-INSTRUCTION: This task owns the PROFILING overlay. Three rules that must be refused
     if violated: (1) profiling is OPT-IN — with NODERA_SPARK unset, nothing is downloaded, staged,
     configured or launched differently; (2) NO CAPTURE IS EVER UPLOADED — every stop passes
     --save-to-file, and no code path may call bare `stop`, `upload` or `open`; (3) "spark" here
     always means lucko's profiler, never Apache Spark (docker/telemetry/spark/). Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** minecraft · **Owns:** — · **Last audit:** 2026-07-26
**Depends on:** [minecraft 1](Task.1.md) (the dev runs and the suite harness)
**Consumed by:** — (a diagnostic lane; nothing depends on it)

---

## Goal

Make it possible to answer **"which Nodera class burned the tick"** with evidence, from an
automated test run and without uploading anything. Two of the project's three platforms are
covered; the third, Folia, turned out not to be reachable at all on the build we pin, and the
suite says so rather than pretending otherwise.

Before this task the question was unanswerable. The mod ships as a fat jar of the whole core
running inside Minecraft's tick loop, and the Paper plugin runs inside somebody else's; no timer we
could add to our own code separates our frames from the game's. Every claim about Nodera's cost was
an assertion nobody could check.

## Status detail

Landed and verified live:

- **The upstream source is vendored** at `docs/minecraft/upstream/spark` as a pinned submodule
  (`f181ccf`). Adding it was also the occasion to write the missing `.gitmodules` and repair the
  `folia` and `MultiPaper` gitlinks, which had been committed with no submodule mapping at all — a
  fresh clone got two empty directories and `git submodule update` failed outright. The recorded
  SHAs were not changed.
- **A 13-part study** in [`spark/`](spark/) covering the engines, the commands, the data format, the
  configuration chain, and — the document the rest exists to support —
  [how per-mod attribution is actually computed and where it lies to you](spark/05-source-attribution.md).
- **The harness overlay**, `scripts/lib/spark.sh`: fetch and checksum the pinned NeoForge jar, stage
  it into the dev-run game directories, configure the profiler Paper already bundles,
  drive captures over RCON, and sweep the results into the run's artifacts. Every function is a
  no-op unless `NODERA_SPARK=1`.
- **An offline reader**, `scripts/lib/sparkprofile.py`: a pure-standard-library protobuf decoder
  that turns a `.sparkprofile` into a per-source self-time table with our hottest frames named. It
  exists because a suite cannot assert against a web page and CI has nobody to drag a file onto one.
- **A dedicated suite**, `scripts/e2e-profile.sh`, registered in `scripts/run-tests.sh` and the
  `e2e-live` matrix.
- **A client bridge**, `SparkProfileBridge`, so the player-hosted integrated server — which RCON
  cannot reach, and where most of our server-side logic actually runs — is profilable too.

## What was verified rather than assumed

Every one of these was checked against a running server, because each had a plausible wrong answer:

| Question | Answer |
|---|---|
| Does FML scan `<gameDir>/mods/` in an MDG dev run? | **Yes.** `spark 1.10.124 (spark)` appears in the dev server's mod list. |
| Does a production Modrinth jar load unremapped in the dev runtime? | **Yes** — NeoForge 21.x is Mojang-named in both dev and production. |
| Does Paper really bundle spark? | **Yes.** Booting `run/servers/paper.jar` resolves `me/lucko/spark-paper` into `libraries/` and creates `plugins/spark/`. No jar is installed. |
| Does Folia? | **It bundles it and refuses to enable it** — with `spark.enabled: true` and `-Dpaper.preferSparkPlugin=false` both set. The community `spark-folia` jar enables but dies on the first command against our pinned 1.21.4 (`NoClassDefFoundError: ca/spottedleaf/moonrise/…/TickData`). Folia profiling is unavailable; R3 skips and says so. |
| Does spark's output come back over RCON? | **No.** Every `/spark …` over RCON returns an empty string — spark answers asynchronously, after the RCON exchange has closed. |
| Does the default thread dumper work? | **No.** Twice, under load, on Paper 1.21.1-133, a capture with no `--thread` flag contained **zero threads**. `--thread *` is mandatory. |
| Does attribution actually name our code? | **Yes**, on both platforms — `Burner → dev.burner.BurnerPlugin.burn` on Paper, and `nodera` under a live two-player entity-lane drive on NeoForge. |

The last two are why the suite asserts on presence rather than merely collecting data: a capture
that silently contains nothing looks exactly like a capture of very fast code.

## The first real finding

From the R1 capture — the dedicated server under a two-player entity-lane drive:

- On the **server tick thread**, `nodera` accounts for 20 ms of self time out of 69 s sampled.
- Across **all threads**, `nodera`'s subtree is 138,948 ms — nearly four orders of magnitude larger.
- The hottest Nodera frames on the tick are `RegionDriveDebug.trackRegions` and
  `TabListRenderer.header` — **both diagnostics**, not the lane.

So the entity lane's work is genuinely off-tick, as designed, and our most expensive tick-thread
code in that drive is the debug tab list. That is a conclusion nobody would have guessed, and it is
exactly the kind this lane exists to produce. See
[13 — Reading a Profile](spark/13-reading-a-profile.md) for the full worked example, including what
the numbers do *not* say.

## Dependencies

- [minecraft 1](Task.1.md) — the MDG dev runs the profiler is staged into, and the suite harness it
  hooks into. No new dependency was added to any module: Nodera compiles no spark code and links
  against no spark artifact.

## Deliverables

| # | Deliverable | Status |
|---|---|---|
| 1 | `docs/minecraft/upstream/spark` pinned, `.gitmodules` written, folia/MultiPaper gitlinks repaired | ✅ |
| 2 | `docs/minecraft/spark/` — README + 13 topic documents | ✅ |
| 3 | `scripts/lib/spark.sh` — fetch, stage, drive, collect; opt-in via `NODERA_SPARK=1` | ✅ |
| 4 | `scripts/lib/sparkprofile.py` — offline per-source reader with `--assert-source` | ✅ |
| 5 | `scripts/e2e-profile.sh` — R0–R3, registered in `run-tests.sh` and `e2e-live` | ✅ (R3 skips: Folia unprofilable) |
| 6 | `SparkProfileBridge` + `SparkProfileBridgeTest` — the client/integrated-server path | ✅ |
| 7 | `-XX:+EnableDynamicAgentLoading` on every dev run, so attribution does not silently degrade | ✅ |

## Non-goals

- **No performance gate.** The suite asserts that our code *appears* in the profile; it does not
  assert a threshold. A millisecond budget on a shared CI runner would be a flake generator, not a
  performance test. Regressions are found by comparing two runs of the same suite
  ([13](spark/13-reading-a-profile.md)).
- **No uploads.** Not to `spark.lucko.me`, not to bytebin, not via the live viewer. A capture
  carries our class names, the server configuration and the host's CPU and OS strings.
- **No always-on profiling.** Sampling perturbs timing, and the timing-sensitive suites
  (`mesh-soak`, `determinism`, `crash`) must stay byte-identical by default.

## Known limits

- **NeoForge attributes by JPMS module name**, so the entire mod is one bucket called `nodera`;
  per-subsystem detail has to come from the frame names inside it. Mixin-injected code is attributed
  to the host class and therefore to nobody. Only Fabric solves this, and we are not on Fabric.
- **Folia cannot be profiled on the pinned build.** It bundles spark and refuses to enable it, and
  the community `spark-folia` jar targets a newer Folia. R3 skips with the reason printed. Staging a
  matching jar at `$NODERA_SPARK_FOLIA_JAR` turns the stage on; nothing else needs changing.
- **R3 has never completed a live capture**, so the claim that profiling introduces no Folia
  thread-context violation is expected but unobserved.
- **The `neoforge` module shows up as a source** — spark's filter excludes a module named `forge`,
  and NeoForge's is named `neoforge`. Harmless, but it is not a mod anyone installed.

---

Status ledger: [`PROGRESS.md`](PROGRESS.md) · tests and live suites: [`TESTING.md`](TESTING.md) ·
open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · charter: [`Task.0.md`](Task.0.md).
