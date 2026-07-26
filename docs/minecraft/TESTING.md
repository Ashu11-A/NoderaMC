# Minecraft — Testing

<!-- AI-AGENT-INSTRUCTION: This file documents the SCRIPTED LIVE ACCEPTANCE SUITES as well as the
     module's unit tests. Update it whenever a script gains or changes a stage. Counts come from the
     `./gradlew check` XML reports, never from memory. RUN THE LIVE SUITES whenever a change touches
     the host, join, lane, or continuity surfaces — the headless gate cannot see configuration-gated
     lifecycle paths, and most defects in this category were only catchable live. -->

**Category:** minecraft · **Last run:** 2026-07-25 · **105 unit tests · 0 failing** (module
`neoforge-mod`), plus **14 scripted live suites**

The module is marked 🚧 in the root table because its scope is incomplete
([`Task.2.md`](Task.2.md)), not because anything fails.

---

# Part 1 — The scripted live suites

All live suites drive **real NeoForge clients** — each with its **own peer worker** — against the
standalone tracker and rendezvous binaries: the full decentralized stack on one machine. They need a
GUI session, roughly 6 GB of free RAM, and the Rust toolchain. Headless rehearsals of the same flows
run on the ordinary gate.

## 1.1 Running

`scripts/run-tests.sh` is the entry point for a local batch. It builds once, then runs the requested
suites **strictly one at a time** — on one machine they share a port block and the run directories, so
concurrency is made structurally impossible by an exclusive lock that standalone runs honour too.

In CI the suites do not share a machine at all: `e2e-live` runs **one suite per runner** as a matrix,
so the wall clock is the slowest suite rather than the sum, and one suite's leftovers can never reach
another.

```bash
scripts/run-tests.sh                    # every suite, canonical order (~45 min)
scripts/run-tests.sh commands crash     # just these
scripts/run-tests.sh --no-build churn   # skip the up-front build

scripts/e2e-continuity.sh               # also BAKES the shared world the others reuse
scripts/e2e-ownership.sh --no-build
scripts/e2e-churn.sh     --no-build [--cycles 5]
scripts/e2e-pickup.sh    --no-build
scripts/e2e-mobs.sh      --no-build
scripts/e2e-pearl.sh     --no-build
scripts/e2e-mesh-soak.sh --no-build
scripts/e2e-determinism.sh --no-build   # SOAK_SECONDS=7200 for issue #5's acceptance run
scripts/e2e-password.sh  --no-build
scripts/e2e-rekey.sh     --no-build
scripts/e2e-commands.sh  --no-build
scripts/e2e-farlands.sh  --no-build
scripts/e2e-crash.sh     --no-build
scripts/e2e-ownership-follow.sh --no-build

scripts/dev.sh --play --no-build        # not a test: two interactive clients
scripts/dev.sh --play --with-app        # …plus a companion window per player
```

**The shared world bakes itself.** The ownership, churn, and crash suites reuse a genuinely shared
save (signed world identity, certified genesis); the launcher bakes it on first use, so any suite can
run first — or alone on its own machine, which is what CI does.

## 1.2 The suites

| Suite | What it proves | Duration |
|---|---|---|
| `e2e-continuity.sh` | The world survives its host: share → join → archive on the network → host killed → player B recovers and re-hosts | ~8 min |
| `e2e-ownership.sh` | Per-player field-of-view region ownership, the cross-owner drive, and host leave with network re-join | ~10 min |
| `e2e-churn.sh` | Join/leave churn ×5 with random dwell; a log audit proves no error accumulation | ~12 min |
| `e2e-pickup.sh` | A clean-slate validated pickup delivers **exactly once** — no vanish, no dupe | ~6 min |
| `e2e-mobs.sh` | The ghost-mob lane. G1: regions report holding ghost mobs where capture is enabled and are not revoked. G2a: a **zombie** is captured in a dimension that opted nothing in, on the per-species default alone (L-24). G2b: a **creeper** — a species the engine does not own — refuses its region, names the reason, and announces the refusal to the mesh even when the observing node holds no seat (L-60) | ~9 min |
| `e2e-pearl.sh` | The pearl drive: the teleport lands the thrower where the lane says it did (a hard assertion — the event is not ownership-dependent); the ghost-capture half reports SKIPPED naming **L-60** on a node that owns no regions | ~7 min |
| `e2e-mesh-soak.sh` | The live mesh under sustained load: the walking player's lane re-executes and votes for its regions through 180 s of edits, mobs and movement. The root-agreement half asserts only when a **worker** holds a replica — on today's topology none does, and the stage says so naming L-30/L-60 | ~9 min |
| `e2e-determinism.sh` | **The Phase-1 exit gate** (issue #5): THREE clients on one server, ≥4 regions, sustained random play, and `validation.divergences` read from every node at the end — zero, or the suite names the disagreeing nodes. Records the bandwidth/interference numbers to `phase1-numbers.json`. Default 15 min; the acceptance run is `SOAK_SECONDS=7200` | ~20 min (2 h+ for the acceptance run) |
| `e2e-password.sh` | The live-join password gate: a joiner **with no password is refused at the game server** (no player, no world), and the same client carrying the password joins normally | ~7 min |
| `e2e-rekey.sh` | The author changes the world password: the worker seeds and announces the re-keyed manifest, **no refresh of a password-protected world is ever plaintext**, the **old** password is refused at the game server, and the new one joins | ~9 min |
| `e2e-commands.sh` | Two players × every `/nodera` command with response validation, plus the in-game self-test tree walk and benchmark | ~8 min |
| `e2e-farlands.sh` | Two players ~566 km apart: positions verified and per-player chunk control interrogated | ~8 min |
| `e2e-crash.sh` | One client **SIGKILLed** mid-session: the survivor sees no disruption, no continuity arm, **no migration screen**; the crashed player rejoins | ~7 min |
| `e2e-ownership-follow.sh` | Ownership follows the player: after a multi-thousand-block teleport the session re-plans and the client re-derives its owned region set | ~5 min |

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
NODERA_SPARE_PEERS=0 scripts/e2e-crash.sh          # thin the swarm on purpose
NODERA_E2E_TIMEOUT_MULT=3 scripts/e2e-pickup.sh    # slow machine / CI
scripts/run-tests.sh --spare-peers 2 --players 2   # retopologise a whole batch
```

## 1.5 The stale-bake trap

The baked shared world stores a **certified genesis pinned to the rules version of the day it was
baked**. Bump that constant and the current engine correctly refuses the old genesis — but the symptom
is that the entity lane never boots and every ownership assertion in the bake-reusing suites fails
with no obvious cause. Those suites now bail out immediately naming this. The fix is a re-bake:

```bash
rm -rf java/neoforge-mod/run-host/saves/NoderaE2E && scripts/e2e-continuity.sh
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

---

# Part 2 — Module unit tests

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `neoforge-mod` | Host and GUI surfaces, entity-lane adapters, continuity halves, permission/identity/re-key lanes, the live-join password gate, crash-resilience degrade, the vanilla-cancel contract, the piece-map lane, the stall reporter, and the in-game self-test drive | 97 | 🚧 |

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
