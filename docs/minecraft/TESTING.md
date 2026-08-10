# Minecraft — Testing

<!-- AI-AGENT-INSTRUCTION: This file documents the SCRIPTED LIVE ACCEPTANCE SUITES as well as the
     module's unit tests. Update it whenever a script gains or changes a stage. Counts come from the
     `./gradlew check` XML reports, never from memory, and the module row below is held by
     `scripts/test-counts.sh --check java`. An `rg -c '@Test'` sweep is NOT the same number: it
     counted 216 across 39 files on 2026-07-30 while JUnit reported 134, because a
     `@ParameterizedTest` row and a `@Nested` class each count once in the annotation sweep and
     once per executed case in the report. Quote the report. RUN THE LIVE SUITES whenever a change touches the
     host, join, lane, or continuity surfaces — the headless gate cannot see configuration-gated
     lifecycle paths, and most defects in this category were only catchable live. -->

**Category:** minecraft · **Last run:** 2026-08-10 · **134 unit tests · 0 failing** (module
`neoforge-mod`), plus the live acceptance scenarios (now `dev.nodera.testkit.scenario`, run with `scripts/nodera-test.sh`)

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

The module is marked 🚧 in the root table because its scope is incomplete
([`Task.2.md`](Task.2.md)), not because anything fails.

---

# Part 1 — The scripted live suites

All live suites drive **real NeoForge clients** — each with its **own peer worker** — against the
standalone tracker and rendezvous binaries: the full decentralized stack on one machine. They need a
GUI session, roughly 6 GB of free RAM, and the Rust toolchain. Headless rehearsals of the same flows
run on the ordinary gate.

## 1.1 Running

`scripts/nodera-test.sh run` is the entry point for a local batch. It builds once, then runs the requested
suites **strictly one at a time** — on one machine they share a port block and the run directories, so
concurrency is made structurally impossible by an exclusive lock that standalone runs honour too.

In CI the suites do not share a machine at all: `e2e-live` runs **one suite per runner** as a matrix,
so the wall clock is the slowest suite rather than the sum, and one suite's leftovers can never reach
another.

```bash
scripts/nodera-test.sh run                    # every suite, canonical order (~45 min)
scripts/nodera-test.sh run commands crash     # just these
scripts/nodera-test.sh run --no-build churn   # skip the up-front build

scripts/nodera-test.sh run continuity               # also BAKES the shared world the others reuse
scripts/nodera-test.sh run ownership
scripts/nodera-test.sh run churn     --no-build [--cycles 5]
scripts/nodera-test.sh run pickup    --no-build
scripts/nodera-test.sh run mobs      --no-build
scripts/nodera-test.sh run pearl     --no-build
scripts/nodera-test.sh run mesh-soak
scripts/nodera-test.sh run determinism   # SOAK_SECONDS=7200 for issue #5's acceptance run
scripts/nodera-test.sh run password  --no-build
scripts/nodera-test.sh run rekey     --no-build
scripts/nodera-test.sh run commands  --no-build
scripts/nodera-test.sh run farlands  --no-build
scripts/nodera-test.sh run crash     --no-build
scripts/nodera-test.sh run ownership-follow

scripts/dev.sh --play --no-build        # not a test: two interactive clients
scripts/dev.sh --play --with-app        # …plus a companion window per player

scripts/dev.sh --lan  --with-app        # two UNMODIFIED clients, for the Open-to-LAN lane
```

**`--lan` is the only mode that runs Minecraft without the mod**, which is the whole point: the LAN
lane's claim is that two people can play together with nothing installed in the game, and a harness
that used the NeoForge dev client would be testing the opposite. It launches real vanilla clients
through `scripts/lib/vanilla-client.py`, reusing whatever libraries and assets an installed launcher
already has and downloading only what is missing (~25 MB on a first run). It never writes into
`~/.minecraft`.

One asymmetry is deliberate and is what makes a single-machine run mean anything: **player 2's worker
runs with LAN detection off** (`NODERA_LAN_WATCH=0`). Both workers would otherwise hear the same
multicast beacon, and player 2 could reach the world locally without the network being involved at
all. With it off, the only route from player 2 to player 1 is the tracker and the tunnel — so if they
meet, the lane worked.

**The shared world bakes itself.** The ownership, churn, and crash suites reuse a genuinely shared
save (signed world identity, certified genesis); the launcher bakes it on first use, so any suite can
run first — or alone on its own machine, which is what CI does.

## 1.2 The suites

| Suite | What it proves | Duration |
|---|---|---|
| `continuity` | The world survives its host: share → join → archive on the network → host killed → player B recovers and re-hosts | ~8 min |
| `ownership` | Per-player field-of-view region ownership, the cross-owner drive, and host leave with network re-join | ~10 min |
| `churn` | Join/leave churn ×5 with random dwell; a log audit proves no error accumulation | ~12 min |
| `pickup` | A clean-slate validated pickup delivers **exactly once** — no vanish, no dupe | ~6 min |
| `mobs` | The ghost-mob lane. G1: regions report holding ghost mobs where capture is enabled and are not revoked. G2a: a **zombie** is captured in a dimension that opted nothing in, on the per-species default alone (L-24). G2b: a **creeper** — a species the engine does not own — refuses its region, names the reason, and announces the refusal to the mesh even when the observing node holds no seat (L-60) | ~9 min |
| `pearl` | The pearl drive: the teleport lands the thrower where the lane says it did (a hard assertion — the event is not ownership-dependent); the ghost-capture half reports SKIPPED naming **L-60** on a node that owns no regions | ~7 min |
| `mesh-soak` | **L-30's exit test.** The live mesh under sustained load: 180 s of *captured* edits (`/nodera debug drive`; a `fill` is a direct world write that fires no capture event and reaches the committee through nothing at all), mobs and movement. S2 the walking player's client lane re-executed; **S2b two headless workers each report a non-zero `committee_commits`** — the counter no amount of arriving state can produce, because it is only reachable after this worker cast a ballot on a proposal it re-executed; S3 every region root held at the same version by more than one peer is identical | ~9 min |
| `determinism` | **The Phase-1 exit gate** (issue #5): THREE clients on one server, ≥4 regions, sustained random play, and `validation.divergences` read from every node at the end — zero, or the suite names the disagreeing nodes. Records the bandwidth/interference numbers to `phase1-numbers.json`, now including the in-game halves: the block-capture ledger by outcome and the live extraction's palette-exclusion, dense-section and drift-from-committed counts. Default 15 min; the acceptance run is `SOAK_SECONDS=7200` | ~20 min (2 h+ for the acceptance run) |
| `password` | The live-join password gate: a joiner **with no password is refused at the game server** (no player, no world), and the same client carrying the password joins normally | ~7 min |
| `rekey` | The author changes the world password: the worker seeds and announces the re-keyed manifest, **no refresh of a password-protected world is ever plaintext**, the **old** password is refused at the game server, and the new one joins | ~9 min |
| `commands` | Two players × every `/nodera` command with response validation, the live extraction drive (four unsupported blocks placed, the extractor's palette-exclusion count must move by exactly four), plus the in-game self-test tree walk and benchmark | ~9 min |
| `farlands` | Two players ~566 km apart: positions verified and per-player chunk control interrogated | ~8 min |
| `crash` | One client **SIGKILLed** mid-session: the survivor sees no disruption, no continuity arm, **no migration screen**; the crashed player rejoins | ~7 min |
| `ownership-follow` | Ownership follows the player: after a multi-thousand-block teleport the session re-plans and the client re-derives its owned region set | ~5 min |
| `profile` | **Where the tick went.** R1: the dedicated server under a live two-player entity-lane drive is profiled and `nodera` must appear as an attributed source with non-zero self time. R2: the same against Paper, whose spark is **bundled** (no jar installed), asserting the capture is non-empty and that spark enumerated `NoderaEndpoint`. R3 (Folia) currently **SKIPS with a named reason** — Folia refuses to enable its bundled spark and the community jar targets a newer Folia | ~12 min |

### 1.2.1 The profiling overlay

Any suite can be profiled, and by default none is:

```bash
NODERA_SPARK=1 scripts/nodera-test.sh run mobs                       # steady-load capture
NODERA_SPARK=1 NODERA_SPARK_TICKS_OVER=100 scripts/nodera-test.sh run mesh-soak   # lag spikes only
scripts/nodera-test.sh run profile                                   # the dedicated suite; always profiles
```

With `NODERA_SPARK` unset nothing is downloaded, staged, configured or launched differently — the
overlay is `scripts/lib/spark.sh`, and every function in it returns immediately when the flag is
off. Captures are **never uploaded**: every stop passes `--save-to-file`, and the results land in
`$RESULTS_DIR/spark/` alongside a `.sources.txt` per-source summary produced by
`scripts/lib/sparkprofile.py`.

Read one with `python3 scripts/lib/sparkprofile.py <profile> --thread '^Server thread$'`, or by
dragging the `.sparkprofile` onto `https://spark.lucko.me/` (parsed in the browser, uploaded
nowhere). Full documentation: [`spark/`](spark/); the runbook is
[13 — Reading a Profile](spark/13-reading-a-profile.md).

**Two traps, both verified live rather than inferred.** spark's replies never come back over RCON
(it answers asynchronously, after the exchange has closed), and a capture taken with **no
`--thread` flag** contained zero threads on Paper 1.21.1-133 — twice, under load. The overlay
always passes `--thread *` and locates captures by file mtime; do not "simplify" either.

## 1.3 How clients are launched — no GUI automation anywhere

The scripts use Minecraft's **quick play** arguments. A host client boots straight into the shared
world; joiner clients boot straight into the session; a rejoin variant returns player A as a network
client. Distinct usernames prevent the duplicate-login kick, staged worlds pin the game port, disable
Mojang session auth (dev accounts), and enable entity-lane auto-activation. The dedicated-server
suites drive gameplay over **RCON** — teleports, inventory reads, and per-player execution.

Automating clicks against a game UI would make every screen change a test failure. Assertions come
from logs and worker control sockets instead: stable, greppable, and meaningful.

## 1.4 The shared launcher and the standard topology

No suite starts a service itself. One launcher brings the stack up — lock, build, port preflight,
tracker, rendezvous, workers, control-socket probe — so every suite runs the same topology, set in
exactly one place:

> **2 players · 1 tracker · 1 rendezvous · 3 headless peers**

Three peers is the **quorum floor**: below three session members the health derivation reports
degraded, so a thinner run measures a degraded system rather than failing honestly. Two peers are the
players' companion workers; the third is a spare standalone worker with no client attached — swarm and
archive ballast plus a standby gateway.

The companion peers are **real session members**: on host start the mod hands its companion the game's
P2P route, the peer dials it, and membership gossip publishes every member's key. A joined peer
therefore counts toward quorum, is handed committee seats, and can inherit the gateway when the
hosting game exits. The hosting JVM deliberately does not *also* start a client peer — one JVM, one
node — which used to cap a player-hosted two-player world at two members and permanently degraded.

Every parameter is a variable with a default, so pinning one is just setting it:

```bash
NODERA_SPARE_PEERS=0 scripts/nodera-test.sh run crash          # thin the swarm on purpose
NODERA_E2E_TIMEOUT_MULT=3 scripts/nodera-test.sh run pickup    # slow machine / CI
scripts/nodera-test.sh run --spare-peers 2 --players 2   # retopologise a whole batch
```

## 1.5 The stale-bake trap

The baked shared world stores a **certified genesis pinned to the rules version of the day it was
baked**. Bump that constant and the current engine correctly refuses the old genesis — but the symptom
is that the entity lane never boots and every ownership assertion in the bake-reusing suites fails
with no obvious cause. Those suites now bail out immediately naming this. The fix is a re-bake:

```bash
rm -rf endpoints/neoforge-mod/run-host/saves/NoderaE2E && scripts/nodera-test.sh run continuity
```

## 1.6 The first-run trap: a game dir with no `options.txt`

A client game dir that has never been launched has no `options.txt`, so `onboardAccessibility`
defaults to **true** and `Minecraft.addInitialScreens` puts the accessibility onboarding screen
**in front of quick play** — quick play is that screen's *continue* callback, so it never runs. The
client boots correctly, ticks its loop, and sits there forever; nothing in its log says why.

This is invisible on a developer machine, whose `run-*/` dirs kept an `options.txt` from the first
manual launch, and fatal on a fresh CI checkout — it is exactly what the first three `e2e-live` jobs
hit, each burning its full timeout to report "never joined". `ClientStallReporter` named the screen
(`screen: AccessibilityOnboardingScreen`, 60 times per run), which is what turned a mystery into a
one-line fix.

The launcher now seeds `options.txt` for every client game dir it configures, and only when the file
is absent — a real `options.txt` belongs to the player and Minecraft rewrites it on exit:

| Key | Why |
|---|---|
| `onboardAccessibility:false` | The blocker above; must be false before the first frame |
| `skipMultiplayerWarning:true` | The third-party-server notice otherwise sits in front of the multiplayer screen |
| `pauseOnLostFocus:false` | Under Xvfb there is no window manager, so a client may never be "active"; a paused singleplayer client stops ticking and would never share its world |

The waits for a join are guarded to match: a quick-play client parked on a screen quick play never
reaches (onboarding, title, ban notice) fails immediately, naming the screen, instead of burning
thirty minutes. `DisconnectedScreen` is deliberately **not** guarded — the continuity and crash
suites pass through it by design.

## 1.7 Reading the artifacts

| File | What to look for |
|---|---|
| `client-host.log` / `client-join*.log` / `server.log` | `Nodera: sharing world`, `game server open for joiners`, `entity lane live … member node(s)`, `client validation lane active`, drive and region lines, `SELFTEST complete:`, `Nodera continuity: host connection lost` → `restored to saves/` |
| `worker-*.log` | `Now hosting world`, `Seeding world archive vN — P piece(s)`, `Fetched world archive` |
| `tracker.log` / `rendezvous.log` | Announce acks; signed-record registrations |
| `commands-*.log` / `interrogation.log` | Per-player command → response transcripts |
| `nodera-selftest/selftest-*.{json,md}` | The in-game suite's per-command status, benchmark, and response report |
| Control sockets (25610–25612) | Live JSON: maintained pieces, connected worlds, validation counters |
| `spark/*.sparkprofile` + `spark/*.sources.txt` | Profiled runs only. The per-source table: self time per owning mod/plugin, and our hottest frames named. `spark/SUMMARY.txt` on `e2e-profile` carries both the all-threads and tick-thread views of every capture |

---

# Part 2 — Module unit tests

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `neoforge-mod` | Host and GUI surfaces, entity-lane adapters, continuity halves, permission/identity/re-key lanes, the live-join password gate **and its lockout** (`JoinGateThrottleIsWiredTest`, `JoinerIdentityTest` — a reconnect no longer buys a fresh password guess), crash-resilience degrade, the vanilla-cancel contract, the piece-map lane, the stall reporter, the in-game self-test drive, and the forward event-sync call sites (`EventSyncIsWiredTest`) | 134 | 🚧 |

Landmark unit tests:

| Test | What it proves |
|---|---|
| `ShareOptionsTest` | The share value type: a password implies encryption, defaults are correct, copies are immutable, replication must be positive, and the password never appears in `toString` |
| `CompanionGateTest` | Against a **real** loopback server socket: present ⇒ start, absent ⇒ an actionable abort, skew ⇒ the correct classification |
| `VanillaCancelGateTest` | The rule "cancel vanilla only where the commit is synchronous and local" is pinned **Minecraft-free**, so it is testable rather than merely observed in a session |
| `NoderaPeerServiceIsBindFailureTest` | The cause-chain bind-failure classifier that decides retry-on-ephemeral versus degrade |
| `WorldArchiverStreamingTest` | Continuous archive streaming cadence, the bounded final flush, and the seeded-version freshness marker |
| `WorkerPiecesParserTest` / `PieceMapFeedTest` | The worker's piece reply becomes the grid — the seam that had never been called by anything |
| `ClientStallReporterTest` | A client outside a world for ten seconds logs the active screen's class, repeating while it does not change — the fact every opaque live CI failure was missing |
| `HostJoinGateTest` | The live-join password gate's whole admission rule, Minecraft-free: right password in, wrong password out, no password out, per-connection nonces, single use, expiry, a bounded challenge store, and a **sealed** gate that refuses everyone rather than silently opening the world |
| `NoderaWorldStoreTest` | The per-world signed identity file, written atomically |
| `SparkProfileBridgeTest` | The client profiling bridge's schedule, Minecraft-free: inert without its property, garbage input disables rather than throws, the warm-up keeps world loading out of the capture, and it fires **exactly once** across a re-login |

## Conventions

- **Headless first, always.** Value types, feeds, parsers, and rules are tested Minecraft-free on the
  ordinary gate; screens are compile-clean against the pinned NeoForge.
- **Dist-classload guard.** A test proves no client class loads on a dedicated server.
- **Run the live suites when you touch host, join, lane, or continuity surfaces.** Configuration-gated
  lifecycle paths are invisible to the headless gate, and nearly every defect this category has found
  came from a live run.
- **The harness is the multiplier.** Once the suites run in CI under a headless display
  ([`Task.1.md`](Task.1.md)), the deferred acceptance of tasks 2–6 and of several other categories
  runs as one batch.
