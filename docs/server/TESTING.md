# Server — Testing

<!-- AI-AGENT-INSTRUCTION: This file documents the SCRIPTED LIVE SUITES and the module's unit tests.
     Counts come from the `./gradlew check` XML reports, never from memory. Two rules are specific to
     this category and non-negotiable: (1) NO GUI AUTOMATION — every assertion comes from a log line
     or a control socket, exactly as in the minecraft suites; (2) a stage that cannot assert reports
     SKIPPED naming the LIMITATIONS.md row that blocks it, and the suite exits 0 — a nightly run must
     read as "not built yet", never as "broken". Update this file whenever a script gains a stage. -->

**Category:** server · **Last run:** 2026-08-10 · **32 unit tests · 0 failing** (module
`paper-plugin`: `EndpointConfigTest` 9 · `EndpointPlatformTest` 5 · `EndpointPeerLinkTest` 6 ·
`ProjectionPinnerTest` 12), plus `InterferenceThroughputTest` (4) in `:engine`, which is this
category's headless twin of `folia` F5,
plus **3 scripted live suites, green for the built subset**: `e2e-endpoint.sh` against a real Paper
1.21.1 (including **E4**: the server JVM is SIGKILLed and the world stays hosted by its worker — L-71's
exit), `e2e-folia.sh` against a real Folia (ALIGN-1 passes at the platform default and **refuses** at
grid-exponent 2), and `e2e-plugins.sh` for co-existence with a staged corpus. The register previously
said all three suites were committed when only the harness library was; they exist now
([L-61](LIMITATIONS.md) retired 2026-07-26).

> **Correction, 2026-08-10 — the mixed-client stages are ABSENT, not skipped.** This file used to say
> "the mixed-client headline stages (P2–P8, F2–F6, WorldEdit certification) report `SKIPPED` naming
> the open row that blocks each". They did not, because they did not exist: the shell → Java
> conversion carried over `EndpointScenario` **E0–E4**, `FoliaScenario` **F0–F2** and
> `PluginsScenario` **C0–C2** and nothing else. Five limitation rows (L-65, L-67, L-68, L-69, L-70)
> named P- and F- stages that existed in no form, so none of those rows had a runnable exit test. A
> row whose exit test was never written is not "blocked", it is **unmeasurable**, and this file said
> the opposite. **P6** and **F5** were written on 2026-08-10 (#180, #177) and are the first two of
> them to exist; §1.3 and §1.4 now say, per stage, which exist and which do not.

> **P6 and F5 fail, on purpose, and that is the deliverable.** Both begin by asking whether any
> region on this endpoint is delegated to its validated lane
> (`ServerEndpointSupport.requireDelegatedRegion`), and nothing on the endpoint path delegates one
> yet — server tasks [2](Task.2.md) and [3](Task.3.md). They **fail** rather than skipping: a
> delegated region is an artefact the harness's own topology is meant to produce, so a skip here
> would be structural, and a structural skip has been red under every flag since PR #259. Failing is
> the same colour and says considerably more. **Consequence to expect:** the `endpoint` and `folia`
> legs of `e2e-live` are red until server tasks 2/3 land, and that is a truthful signal rather than a
> regression to hunt.
>
> **Both run LAST in their scenario, each on a server of its own**, and that placement is load
> bearing: the runner stops a scenario at its first failing stage, so a stage expected to fail must
> never sit upstream of one expected to pass. P6 was written before E4 to begin with, which silently
> stopped **L-71's retired exit test** from running at all — a retirement that quietly stops being
> checked is exactly the failure this register exists to prevent.

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

ALIGN-1's own arithmetic is tested in `core`, not here: `RegionAlignmentTest` walks every region in a
±64 grid across grid exponents 3–6, because whether two threads can write one authority unit is not a
thing to check by eye.

```bash
# Each suite needs its platform jar staged; without one it SKIPS (exit 0), never fails.
NODERA_PAPER_JAR=/path/to/paper-1.21.1.jar scripts/nodera-test.sh run endpoint
NODERA_FOLIA_JAR=/path/to/folia.jar        scripts/nodera-test.sh run folia    --no-build
NODERA_PLUGIN_CORPUS_DIR=/path/to/jars     scripts/nodera-test.sh run plugins  --no-build
```

Folia has no 1.21.1 build (it went 1.21.x → 1.21.4+), so `e2e-folia.sh` runs the newest available.
That is sound for plugin enable and ALIGN-1, which are platform properties; it is why the
mixed-client suites stay blocked on [L-66](LIMITATIONS.md).

---

# Part 1 — The scripted live suites

The three suites extend the same launcher every other live suite uses
(`scripts/lib/e2e-main.sh`) with a Paper/Folia half
(`dev.nodera.testkit.scenario.ServerEndpointSupport`; the former `scripts/lib/e2e-server.sh` was
deleted in the shell → Java conversion and this file went on citing it). No suite starts a
service itself: the topology, the port plan, the lock, and the log audit are set in exactly one place.

## 1.1 Running

```bash
scripts/nodera-test.sh run endpoint              # the mixed-client drive — the headline
scripts/nodera-test.sh run folia                 # parallel Folia regions, ALIGN-1 live, thread-context clean
scripts/nodera-test.sh run plugins               # the plugin-compatibility corpus

scripts/nodera-test.sh run endpoint   # skip the up-front build
scripts/nodera-test.sh run endpoint folia  # through the batch runner (holds the shared lock)
```

Requirements: the Rust toolchain, a GUI session (or Xvfb) for the **modded** client, ~6 GB free RAM,
and a pinned Paper/Folia jar. The suites download nothing: `ServerEndpointSupport` resolves a jar
from `NODERA_PAPER_JAR` / `NODERA_FOLIA_JAR`, or from `run/servers/`, and reports `SKIPPED` if neither
is present — so a local run and a CI run execute the same code path.

## 1.2 The suites

| Suite | What it proves | Duration |
|---|---|---|
| `e2e-endpoint.sh` | **An unmodified client and a modded client in the same world** — one joining directly at the endpoint's game port with nothing installed, one joining through the NoderaMC network — with an item passing between them **exactly once**. *E0–E4 (enable, ALIGN-1 not-applicable, external worker link, SIGKILL survival) green; **P6 fails** at its delegation gate; P2–P5, P7 and P8 are not written* | ~16 min — P6's hold window is five of them |
| `e2e-folia.sh` | Two Folia regions ticking and committing **concurrently**; ALIGN-1 holding live; **zero** thread-context violations across the whole run. *F0–F2 (ALIGN-1 and the refusal) green; **F5 fails** at its delegation gate; F3 and F4 are not written* | ~16 min — F5's soak is five of them |
| `e2e-plugins.sh` | The pinned plugin corpus loads; a WorldEdit `//set` in a delegated region is **certified**, not suppressed and not silently reverted; the log audit is clean. *Co-existence stage green now; WorldEdit-certification stage skips* | ~8 min |

### 1.2.1 Profiling a server suite

Paper 1.21+ **bundles the spark profiler** — verified in this repository: booting
`run/servers/paper.jar` resolves `me/lucko/spark-paper` into `<stage>/libraries/` and creates
`plugins/spark/`. So on Paper there is **no plugin jar to install**; the harness writes
`plugins/spark/config.json` and drives the profiler over the existing RCON helper.

**Folia cannot currently be profiled.** It bundles spark and then refuses to enable it, and the
community `spark-folia` build targets a newer Folia than our pinned 1.21.4. The profiling stage
skips and names the reason — details in
[12 — Nodera on Paper & Folia](../minecraft/spark/12-nodera-paper-folia.md).

```bash
NODERA_SPARK=1 scripts/nodera-test.sh run endpoint    # profile this suite
scripts/nodera-test.sh run profile                    # stages R2 (Paper) and R3 (Folia)
```

Attribution on Bukkit is **exact** — spark reflects the plugin out of its classloader, so our
source name is `NoderaEndpoint` and the shaded `core` classes attribute to it as well.

Two platform facts that shaped the integration, both observed rather than inferred: spark's replies
**never come back over RCON** (it answers asynchronously, after the exchange has closed), and a
capture taken with no `--thread` flag contained **zero threads** on Paper 1.21.1-133. On Folia
`--thread *` is doubly required — there is no main thread, and profiling one region thread would
profile a thread that does almost nothing.

Full documentation: [`../minecraft/spark/`](../minecraft/spark/), and in particular
[12 — Nodera on Paper & Folia](../minecraft/spark/12-nodera-paper-folia.md).

## 1.3 `e2e-endpoint.sh` — stages

| Stage | Assertion | Blocked by |
|---|---|---|
| P0 | Standard topology + a **Paper** server with `nodera-endpoint` installed | [L-61](LIMITATIONS.md) *(retired — green)* |
| P1 | The endpoint boots its peer, publishes/adopts the world, announces it, reports `custody: FULL` | [server 2](Task.2.md) in-process peer |
| P2 | The **modded** client joins **through the network** (tracker-resolved, not a hardcoded address) and is in membership **with its own key** | ⬜ **not written** · [server 2](Task.2.md) / [7](Task.7.md) |
| P3 | The **unmodified** client joins **directly at the game port**, with nothing installed | ⬜ **not written** · [server 6](Task.6.md) |
| P4 | Both in one world: the modded client is a member; the vanilla player is a **tenant** with a stable id and is **not** a member | ⬜ **not written** · [server 6](Task.6.md) |
| P5 | **The drive:** the tenant drops an item, the modded player picks it up, credited **exactly once** — no vanish, no dupe, across client classes | ⬜ **not written** · [server 5](Task.5.md) / [6](Task.6.md) |
| P6 | A validated item neither despawns nor drifts more than **1.0 block** over a **five-minute** hold window (vanilla's own 6000-tick despawn age — a shorter window cannot tell a pinned item from an unpinned one), and picking it up credits **exactly once**, counted with `/clear <player> <item> 0`. Runs **last**, on a server of its own | 🚧 **written 2026-08-10; fails at its first assertion** — nothing delegates a region ([2](Task.2.md)/[3](Task.3.md)). This is [L-69](LIMITATIONS.md)'s exit |
| P7 | A wrong world password never reaches the world; no plaintext in any log | ⬜ **not written** · [L-68](LIMITATIONS.md) |
| P8 | A fingerprint-mismatched client is refused, with the offending mod named | ⬜ **not written** · [L-70](LIMITATIONS.md) |

## 1.4 `e2e-folia.sh` — stages

| Stage | Assertion | Blocked by |
|---|---|---|
| F0/F1 | The stack on a **Folia** server; ALIGN-1 preflight passes at `grid-exponent = 4` | ✅ *(green)* |
| F2 | **The refusal**: the same jar at `grid-exponent = 2`, where a Nodera region straddles two Folia sections, must refuse to enable and name the setting, the value and the fix | ✅ *(green — this is the stage that turns the preflight into a check)* |
| F3 | Both regions commit **concurrently** — two certificates with overlapping wall-clock windows, which a single-threaded server cannot produce | ⬜ **not written** · [L-61](LIMITATIONS.md) headline scope |
| F4 | A `world.save()` on one region appears as a new archive version on another peer | ⬜ **not written** · [L-61](LIMITATIONS.md) headline scope |
| F5 | A **delegated** region under sustained redstone load commits for five minutes with the **resync rate ≤ 100 bps** (`EntityLaneSoakMetrics.MAX_RESYNC_RATE_BPS`) and **no region revoked for interference rate** (> `NoderaConstants.INTERFERENCE_REVOKE_RATE` = 60 per 1200-tick window) | 🚧 **written 2026-08-10; fails at its first assertion** — nothing delegates a region ([2](Task.2.md)/[3](Task.3.md)), the clause needs a decision ([Task.5.md](Task.5.md) §Design), and `NODERA-STATE` reports no resync count. This is [L-67](LIMITATIONS.md)'s exit |
| F6 | **Zero** thread-context violations in any log: no `May not access .* off-thread`, no `ensureTickThread`, no `Cannot recursively operate in the regioniser` | 🚧 asserted **inside F5** via `assertNoThreadContextViolations`, not as a stage of its own · [L-61](LIMITATIONS.md) headline scope |

> **The two numbers F5 is stated in did not exist anywhere before 2026-08-10.** "The resync count
> under threshold" and "revoked for interference rate" named two thresholds and stated neither, which
> makes an exit test unanswerable. Both are derived and recorded in [`Task.5.md`](Task.5.md) §Design,
> and asserted headlessly by `InterferenceThroughputTest`. The derivation also produced the finding
> that the clause's two halves cannot both hold at the current bound — one two-tick repeater clock is
> ten times the revocation rate on a platform that cannot cancel a scheduled tick.

F6 is a grep, not a judgement call — which is the point. On Folia an uncaught exception on a tick
thread **halts the scheduler and stops the entire server**, so a thread-context violation is both the
likeliest failure mode and the loudest one.

## 1.5 `e2e-plugins.sh` — the corpus

Pinned by version so the corpus is reproducible. On Folia it narrows to the Folia-ready members and
the suite **names the ones it dropped**, rather than quietly testing less.

| Plugin | Exercises |
|---|---|
| WorldEdit / FAWE | bulk foreign writes — the hardest PC-3 case |
| EssentialsX | commands, teleports, inventories |
| LuckPerms | permission resolution on every gate |
| Vault | the services manager, alongside `NoderaEndpointAPI` |
| PlaceholderAPI | async expansion resolution |
| CoreProtect | block logging at `MONITOR` — a direct PC-2 collision test |
| ViaVersion | protocol translation in front of the endpoint |

## 1.6 How the clients are driven — no GUI automation anywhere

The **modded** client is a real NeoForge client launched through Gradle quick play, exactly as the
`minecraft` suites launch theirs.

The **unmodified** client is `dev.nodera.testkit.scenario.ServerVanillaBot` (the former
`scripts/lib/vanilla-bot.py`, deleted in the shell → Java conversion): a Minecraft-protocol client
that performs
the handshake, offline-mode login, and play-state exchange, and can move, chat, run a command, and
drop an item. It is deliberately **not** a second launched game —

- a vanilla client needs a Mojang launcher, an asset index, and an account, none of which belong in CI;
- two Minecraft clients plus a Paper server plus three workers on one runner is a memory problem, not
  a test;
- and it buys nothing, because every assertion here comes from logs and control sockets. Nothing is
  read off a screen.

It is in fact *more* faithful to the thing under test: it proves the endpoint is reachable **by
anything that speaks the protocol**, with no NeoForge anywhere in the process.

`NODERA_VANILLA_CLIENT=<command>` swaps in a real vanilla client for anyone who has one staged; the
suite drives it identically.

## 1.7 The skip contract

A stage that asserts something no node in the topology can yet do reports `SKIPPED` naming the row
that blocks it and the suite exits **0**:

```
[endpoint] SKIPPED: … (server task <n> — L-NN). Nothing was asserted.
```

A nightly run must read as "not built yet", never as "broken". This is the same discipline
`e2e-mobs.sh` already uses for L-60. As the open rows retire, the skips become assertions in the same
commit as the capability.

---

# Part 2 — Unit and integration tests

The module exists: 8 main classes + 4 test classes = **32 unit tests, 0 failing**. Counts come from
the `./gradlew check` XML reports and are a **separate** module total (`:paper-plugin`), not a subset
of another module's.

## 2.1 The testing strategy

| Level | What it proves |
|---|---|
| Minecraft-free unit (`:paper-plugin`) | platform detection, configuration parsing/validation, the worker control-socket exchange, the background retry link — none of it needs a server |
| `core` unit | ALIGN-1 arithmetic (`RegionAlignmentTest`), multi-view ownership planning, min-distance ranking, determinism under N views |
| ArchUnit over `:paper-plugin` *(planned, lands with [3](Task.3.md)–[5](Task.5.md))* | No `BukkitScheduler` reference; no throwing override of a Bukkit API; every listener and task body self-catches |
| Adapter parity *(planned)* | Bukkit and NeoForge adapters produce **byte-identical** `PersistedEntityState` for the same entity — the anti-drift test |
| Plugin ITs on a real Paper server *(planned)* | Enable/disable, event-mirror fidelity, chunk gating, foreign-write certification |
| Scripted live suites | The three above |

## 2.2 Landmark tests

| Test | What it proves | Owner | State |
|---|---|---|---|
| `EndpointPlatformTest` | Folia is detected by the regionised scheduler class (not a name); legacy Paper config counts; a Folia fork shipping Paper config stays Folia; unknown is never guessed | [1](Task.1.md) | ✅ (5) |
| `EndpointConfigTest` | The harness's staged file parses exactly; omissions take defaults; contradictions (not omissions) refused with every reason; a hash inside a quoted password survives comment stripping | [1](Task.1.md)/[2](Task.2.md) | ✅ (9) |
| `EndpointPeerLinkTest` | A real stand-in worker links; only state changes are logged; a wrong answer is not a link; nonsense addresses are refused | [2](Task.2.md) | ✅ (6) |
| `RegionAlignmentTest` (in `core`) | ALIGN-1 across `gridExponent` 0…6, both signs of each axis, every boundary case | [1](Task.1.md) | ✅ |
| `RegionGridAlignmentTest` | *(planned name; folded into `RegionAlignmentTest` in `core`)* | [1](Task.1.md) | ✅ |
| `SchedulerDispatchTest` | Each `NoderaScheduler` method routes to the expected target; `BukkitScheduler` unreferenced | [1](Task.1.md) | ⬜ deferred |
| `EndpointControlHandlerTest` | Every `ControlProtocol` v2 verb answers from live state; unknown verb → `NODERA-ERR unknown verb` | [2](Task.2.md) | ⬜ |
| `ViewOwnershipPlannerTest` (extended) | A node with N views; min distance; two peers plan a 20-tenant endpoint identically; custody breaks ties **only** | [3](Task.3.md) | ✅ (L-63 retire) |
| `CustodyAuditIT` | *(deleted 2026-08-06 with `CustodyAudit` — the spot-check lane had no production entry point; see the note below)* | [3](Task.3.md) | ⬜ withdrawn |
| `CustodyDigestTest` | *(deleted 2026-08-06 with `CustodyDigest` — same lane)* | [3](Task.3.md) | ⬜ withdrawn |
| `EndpointConfigTest` (custody clauses) | The configured claim parses to `CustodyClass.FULL` and an omitted one defaults to `VIEW` — the only custody assertions left in the tree | [3](Task.3.md) | ✅ |
| `BukkitWorldViewTest` | `MutableWorldView` conformance against the same contract `InMemoryWorldView` satisfies | [4](Task.4.md) | ⬜ |
| `CrossFoliaRegionCommitIT` | A joint-transfer prepare/commit across two Folia region threads is atomic; a one-sided failure commits neither | [4](Task.4.md) | ⬜ |
| `ProjectionPinnerTest` | A pinned validated item survives 72,000 simulated ticks — an hour, twelve vanilla despawn ages; drift stays inside 1.0 block against a fake that keeps moving after every velocity write, while a settled item is **never** teleported (the client jitter a per-tick position write would be); a foreign plugin clearing the pickup delay is overwritten on the next tick; ten takers credit **once**; a declining lane leaves the item claimable rather than burning the claim; a **re-entrant** claim from inside the lane's own credit is refused; a throwing projection drops one item and nothing else | [5](Task.5.md) | ✅ (12) |
| `InterferenceThroughputTest` *(in `:engine`)* | `folia` F5's headless twin, and where L-67's two thresholds are pinned: the certificate rate follows the commit cadence and not the write volume (sixty-four times the load, the same number of certificates); one ordinary two-tick redstone clock is 600 foreign writes a window against a bound of 60, and `DelegabilityPolicy` revokes for it; the same clock on the modded host costs zero because `LevelTicksMixin` cancels at the source | [5](Task.5.md) | ✅ (4) |
| `BukkitEntityAdaptersTest` | Bukkit and NeoForge adapters emit byte-identical `PersistedEntityState` — the anti-drift test | [5](Task.5.md) | ⬜ |
| `CaptureFailureContainmentTest` | A throwing runtime drops one entity's capture; the region and the server survive | [5](Task.5.md) | ⬜ |
| `TenantIdTest` | Determinism across restarts, distinctness across endpoints, never collides with a real `NodeId` | [6](Task.6.md) | ⬜ |
| `TenantLimboTest` | No world interaction before release; a wrong password throttles then disconnects | [6](Task.6.md) | ⬜ |
| `EndpointHandshakeTest` | The `nodera:v1` flow; replayed challenge refused; a refusal always names a reason | [7](Task.7.md) | ⬜ |
| `ForeignWriteBridgeTest` | A foreign write is certified; a raced one is reverted **and** fires `NoderaRegionDeniedEvent` | [8](Task.8.md) | ⬜ |

> **Why the two custody rows are marked withdrawn (2026-08-06, issue #210).** They carried a ✅ —
> a claim of proof — against suites that no longer exist. `CustodyDigest` and `CustodyAudit` were
> deleted in commit `0b02aa5` as part of the archival audit triangle: a closed loop with no
> production entry point, so the spot-check they implemented never ran outside its own tests.
>
> **This is a genuine reduction in what is checked, and marking it is the point.** A node
> advertising `custody: FULL` is no longer sampled and can no longer be downgraded to `VIEW`. What
> remains is weaker but real, and it is what `CustodyClass`'s javadoc now says: every piece a node
> serves is hash-verified by the receiver against the manifest, and since the "no negative on the
> piece wire" fix a peer asked for pieces it does not hold answers with a `ContentAvailability`
> stating what it actually has. So a node claiming `FULL` it cannot back fails to answer and is
> passed over — discovered at fetch time, by the fetcher, rather than proactively by an auditor.
> Nothing detects a silently-degraded replica *before* somebody needs it.
>
> The Merkle-digest design itself is not disproven; it was unreachable code. **L-62 reopened on
> 2026-08-06** for exactly this reason — its exit test went with the classes — and now lives in
> [`LIMITATIONS.md`](LIMITATIONS.md) §B, with the retirement evidence preserved under "Withdrawn
> retirement" in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). See also the Task 3 status note in
> [`Task.3.md`](Task.3.md).

## 2.3 Conventions

- **Assertions come from logs and control sockets.** Automating clicks against a game UI would make
  every screen change a test failure.
- **A stage that cannot assert reports `SKIPPED` naming its limitation row**, and the suite exits 0.
- **Adapter parity is a test, not a review.** The single biggest risk in a second platform is two
  implementations that drift; `BukkitEntityAdaptersTest` is what stops it.
- **Thread-context violations are a grep, and the grep is an assertion.** On Folia they stop the
  server, so they must never be tolerated as noise.
- **Run these suites whenever a change touches custody, capture, world I/O, or admission.** The
  headless gate cannot see configuration-gated lifecycle paths — the lesson the `minecraft` category
  learned the expensive way.
