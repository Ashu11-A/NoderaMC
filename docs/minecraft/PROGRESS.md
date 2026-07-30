# Minecraft — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the minecraft category. On every
     outcome-changing commit touching this category: update the §1 row, append a dated §2 milestone
     note naming the EVIDENCE (test name or live observation), then reconcile ../ROADMAP.md §2 and
     the root README bar. Live observations count as evidence here ONLY when they name the log line
     or artifact that showed them. Never rewrite an old note. -->

**Category:** minecraft · **Last audit:** 2026-07-28 · Tasks completed: **5 / 11**

Tests and live suites: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) ·
retired gaps: [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Skeleton, build, run harness | ✅ DONE | Dev runs + 15 scripted suites, green under Xvfb in CI (`e2e-live`) — L-45 retired 2026-07-25 |
| [2](Task.2.md) | Live validation lane | 🚧 IN PROGRESS | Entity lane and ownership run live; apply half + capture defaults remain (L-50, L-80 RETIRING) — unblocked, Task 1's harness is green |
| [3](Task.3.md) | Diagnostics HUD + commands | ✅ COMPLETED | Live providers replaced the placeholders (L-31 retired) |
| [4](Task.4.md) | Multiplayer + share GUI | 🚧 IN PROGRESS | Built and feed-wired; live presentation pass remains (L-43, L-46) |
| [5](Task.5.md) | Decentralized host lane | 🚧 IN PROGRESS | Genesis, continuity, re-key, and the live-join password gate landed (L-51/L-52/L-59 retired); live rendezvous + per-piece encryption at share remain |
| [6](Task.6.md) | World identity + permissions | 🚧 IN PROGRESS | Identity, grants, and ban enforcement landed; world-list mixin remains (L-49) |
| [7](Task.7.md) | Companion presence gate | ✅ COMPLETED | Defaults on; verified both ways in CI |
| [8](Task.8.md) | In-game telemetry + consent mirror | ✅ COMPLETED (headless) | `ModTelemetryTest` (8) against a loopback worker; live pass pending |
| [9](Task.9.md) | Profiling lane — the spark profiler | ✅ COMPLETED | `e2e-profile` R1 green: `nodera` attributed in a live dedicated-server capture |
| [10](Task.10.md) | A world is shown only when it can be played | 🚧 IN PROGRESS | Readiness gate, thread discipline on join/host, capture defaults (MC-JOIN-1…6; MC-JOIN-4 RETIRING) |
| [11](Task.11.md) | The mod's GUI, rebuilt on the vanilla layout API | ⬜ NOT STARTED | Duplicate entry points, overflowing panels, the footer drawn into the body (MC-GUI-1…5) |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — Documentation sweep: status reconciliation across the category

A category-wide audit (`docs/full-sweep`) reconciled every task header against the current evidence
and the retired-limitation register. Net status changes:

- **Task 1 → ✅ COMPLETED.** Its one open deliverable was the CI harness under a headless display;
  **L-45 is RETIRED** (the `e2e-live` workflow is green under Xvfb —
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)), so the exit is met.
- **Task 2 → 🚧 IN PROGRESS** (was ⏳ BLOCKED). Its only blocker was repeatable live evidence, which
  Task 1's now-green harness supplies. The capture/apply lane and its live scenarios remain (L-50,
  L-80 RETIRING).
- **Task 5** keeps 🚧 IN PROGRESS but now **owns no open limitation**: **L-51**, **L-52**, and **L-59**
  are RETIRED (the re-key live exit, the live-join password gate, and the plaintext-publishing hole —
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)). Remaining work is deliverable 10 (live rendezvous
  composition + per-piece encryption at share).
- **Task 10 → 🚧 IN PROGRESS** (the PROGRESS row previously read NOT STARTED, contradicting
  [`Task.10.md`](Task.10.md); reconciled). Its six MC-JOIN rows stay open; MC-JOIN-4 is RETIRING with
  `ClientJoinPasswordsGateMarkerTest` (3) green headlessly.

Module unit-test count re-verified by grep: **155 `@Test`** in `endpoints/neoforge-mod/src/test`
(`rg -c '@Test'` summed across the 27 test files — was reported as 118/97); live suites re-counted at
**15** (was 14; `e2e-profile` was omitted from the header). No limitation retired on this pass: L-43,
L-46, L-49, L-50, and L-80 all have **live** exit tests that cannot be verified green from a static
read, so they stay open/retiring.

A new [`REFACTORING.md`](REFACTORING.md) registers the jscpd duplication (81 clones touch a
`neoforge-mod` file; 37 internal) and the god-class candidates (`NoderaHost` 1307, `NoderaPeerService`
946, `NoderaCommand` 628) for scheduled structural work. The mod keeps its minimal **three-mixin** set
(`LevelChunkMixin`, `LevelTicksMixin`, `ServerLevelRandomTickMixin`) — no mixin-chain concern.

### 2026-07-27 — The endless "Migrating world…" screen, root-caused from a live run

Reported repeatedly and reproduced from `run/logs/play`. The chain, with the log lines that prove
each link:

1. The host shares a **password-gated** world — `'Asd' is password-gated — joiners must supply the
   world password`.
2. The joiner connects and is refused during configuration — `JoinerDev lost connection:
   Disconnected`, four seconds after `Nodera join: connecting to 'Asd'`.
3. That refusal opens a `DisconnectedScreen`, which is indistinguishable from the one a host crash
   opens. `NoderaContinuity.onScreenOpening` runs on `ScreenEvent.Opening`, **before**
   `JoinPasswordScreen.onScreenInit` runs on `Init.Post`, and replaced the whole screen — so the
   prompt that would have asked for the password was thrown away before it could attach.
4. `RehostScreen` then held the player for the full fetch timeout with no button (added only once a
   failure existed), no Esc (`shouldCloseOnEsc` agreed with it), and a hardcoded string: the
   `status` field the fetch thread moves through starting → fetching → unpacking → opening was
   **written by four call sites and read by none**.
5. It ended in `the worker could not fetch the archive (no seeder online?)` — while the seeder was
   online and had just seeded v3 at 40 pieces. The archive is encrypted; the joiner had no
   password, because of step 3.

Fixed: continuity stands down when the disconnect belongs to the gate; the marker is set on **every**
challenge rather than only unanswerable ones, because a wrong password produces a proof and was the
case the narrow marker missed; it is cleared on `ClientPlayerNetworkEvent.LoggingIn` so a genuine
host loss later in the session still recovers; the screen always has a working Cancel and Esc,
renders the step it is on, and honours the cancel rather than dragging the player into a world they
left; and the failure message no longer asserts a cause it has not checked. `rehosting` is now
cleared by `disarm()` — it latched, so a second disconnect in one session was ignored.

Evidence: `ClientJoinPasswordsGateMarkerTest` (3, green). The gate decision was split into a
Minecraft-free overload to make it testable at all — the packet record implements a Minecraft
interface, so a test that named it could not even be discovered.

### 2026-07-27 — What the mod's screens and its join flow actually do

Two audits, one functional and one visual, opened [`Task.10.md`](Task.10.md) and
[`Task.11.md`](Task.11.md).

Functional: the capture lane is **off by default** (`entity.laneAutoActivate=false`), and switching
it on is not enough because the first non-zombie entity revokes the region permanently. Client and
server derive different committee membership, because the plan payload carries no resident list.
Nothing anywhere expresses readiness — `MultiplayerWorldFeed` hardcodes `HEALTHY` for the node's own
worlds even with an empty `mc_route`, and the join button gates on "a row is selected" — which is
how a player reaches a loading screen that never resolves. Peer bring-up runs on the client render
thread and host activation on the server main thread, each capable of ~90 s of freeze with
unreachable relays.

Visual: the mod uses **zero** vanilla layout objects. `PanelWidget` never measures text against its
own width and calls no scissor, so a tracker row (≈234 px with the `tcp://` scheme shown) overflows
a 224 px column at GUI width 480; the status footer is drawn at an absolute `height-66` into a body
whose bottom is `height-64`, after every widget. "Multiplayer" and "Nodera Network" open the same
screen from two registration sites. Twenty-nine further layout defects are itemised in Task 11.

### 2026-07-26 — The mod no longer carries the worker, and no longer imports its control plane

`dev.nodera.headless` left `:peer` for a new `:worker` module, so the mod's fat jar stopped shipping
`HeadlessPeerMain` and every service behind it: `dev/nodera/headless` classes in `neoforge-mod.jar`
went 60 → **0**. The mod keeps the peer *library* — the in-game host, validation and entity lanes are
built on it — but the always-on worker is an application, and an application inside a mod jar can
only ever be the wrong copy of itself.

`CompanionProtocol` also stopped delegating to `dev.nodera.peer.control.ControlProtocol` and now
holds literals, with `CompanionProtocolContractTest` (test scope) comparing the two. A shared
compile-time constant cannot make two *separately installed* artifacts agree — a player updates the
app on Tuesday and the mod on Friday — so what it really bought was a drift alarm, and that belongs
in a test that fails loudly rather than in a coupling that fails silently in the field.

The presence gate (`companion.required`) is already `true` on both client and server: the mod refuses
to launch without a running worker, which is now also how an unmodified player's LAN world reaches
the network ([worker 7](../peer/Task.7.md)).

### 2026-07-26 — The tick has a breakdown now: the spark profiler lane (task 9)

"The entity lane is expensive" was an opinion. The mod is a fat jar of the whole core running
inside Minecraft's tick loop and the endpoint runs inside somebody else's, so nothing we could
add to our own code separated our frames from the game's.

Landed: the upstream source vendored at `docs/minecraft/upstream/spark` (`f181ccf`), a 13-part
study in [`spark/`](spark/), `scripts/lib/spark.sh` (opt-in via `NODERA_SPARK=1`),
`scripts/lib/sparkprofile.py` (an offline per-source reader, pure stdlib), `scripts/e2e-profile.sh`,
and `SparkProfileBridge` for the player-hosted world RCON cannot reach.

**Evidence:** `scripts/e2e-profile.sh` R1 — `PASS R1: 'nodera' is an attributed source in the
dedicated-server profile`, from a live two-player entity-lane drive on the NeoForge dedicated
server. Artifacts in `run/results/e2e-profile/<stamp>/spark/`.

Six things were checked against a running server rather than assumed, and two of them were not
what the documentation implied:

- **spark's replies never come back over RCON.** Every `/spark …` returns an empty string — spark
  answers asynchronously, after the RCON exchange has closed. The harness therefore locates a
  capture by file mtime against a marker, not by parsing output.
- **The default thread dumper produced a capture containing ZERO threads**, twice, under load, on
  Paper 1.21.1-133. `--thread *` is mandatory; a silently empty artifact is indistinguishable from
  fast code, which is also why the suite asserts on presence rather than only collecting.
- Paper **bundles** spark (`me/lucko/spark-paper` resolves into `libraries/` on boot), so it needs
  no jar. **Folia bundles it and refuses to enable it** — with `spark.enabled: true` and
  `-Dpaper.preferSparkPlugin=false` both set — and the community `spark-folia` build dies against
  our pinned 1.21.4 with `NoClassDefFoundError: ca/spottedleaf/moonrise/…/TickData`. Folia
  profiling is unavailable; the stage skips and names why.
- FML **does** scan `<gameDir>/mods/` in an MDG dev run, and a production Modrinth jar loads
  unremapped there.

**The first finding.** On the server tick thread `nodera` is 20 ms of self time out of 69 s
sampled, with a 4 ms subtree; across all threads its subtree is 138,948 ms. The entity lane's work
is genuinely off-tick, as designed — and the hottest Nodera frames *on* the tick are
`RegionDriveDebug.trackRegions` and `TabListRenderer.header`, both diagnostics.

Also repaired on the way past: `.gitmodules` did not exist, so the `folia` and `MultiPaper`
gitlinks under `docs/minecraft/upstream/` had no submodule mapping — a fresh clone got two empty
directories and `git submodule update` failed. Both now resolve; the recorded SHAs were unchanged.

### 2026-07-26 — `e2e-mobs` is green — L-60 and L-24 RETIRED

Nine dispatched live runs. The row said "remaining: the live run", and the live run had never been
read; when it was, it was red for reasons that had nothing to do with what the row described.

What it actually took, in the order the evidence forced:

1. **The close/lock cascade.** A re-plan threw on the way down, so `close()` never reached
   `store.close()`, RocksDB kept its file lock, the next bootstrap failed, and the lane never came
   back. Each close step is contained now and the store closes in a `finally`.
2. **A non-volatile field.** `EntityCaptureBridge.runtime` is written on the boot thread and read on
   the server thread.
3. **A diagnostic that could see the invisible case.** Every line in the bridge was conditional, so a
   DISABLED runtime and a quiet world produced identical logs. `LANE: … observed (… runtime=…)` is
   unconditional, and it is what finally named the fault.
4. **The root cause**: a node with no seats never opened a lane at all — so the one process that can
   see the world had nothing running. `ObserverLaneRuntime` + `ObserverRefusals` give it the single
   power a seatless node is entitled to: refusing what nobody there can validate, and saying so.
   Opening a full session instead was tried and stalled the server for **720 s** per boundary
   crossing; the observer holds no store, no journals and no replicas.
5. **Both assertions were wrong.** The nether is full of species the engine does not own, so the
   region is legitimately refused before any summon lands. G2b was asserting that a creeper won a
   race against the nether's own mobs; G2a was asserting silence where the correct behaviour is
   noisy. They assert the rule now.

L-24 retires on the same clause: G2a proves the species default, G2b proves the refusal.

### 2026-07-26 — The live suites were dispatched, and one of them says the register was wrong

`e2e-live` is a `workflow_dispatch` workflow: any subset of the real-client suites can be run against
a branch. I had been describing these rows as needing evidence I could not produce. They needed a
dispatch. Six suites were run against this branch.

**Green:** `pickup`, `pearl`, `commands`, `ownership-follow`, `continuity`. `commands` includes the
new **K2b** extraction stage in CI for the first time, with real numbers — four unsupported blocks
placed, and the extractor's palette-exclusion count moved `1240376 → 1240380`, exactly four. The live
`LiveSnapshotExtractor` reads a real world correctly.

**Red, and informative:** `mobs` fails at **G2b**. A creeper is summoned and no region is refused.
The artifact says more than the assertion could: ghost capture demonstrably works — five
`GHOST: … now holds ghost mobs (first: minecraft:chicken/pig/cow/squid/salmon)` lines — and in the
nether **not one `entity lane revoked` line appears in the entire run**, not for the creeper and not
for the ambient piglins and ghasts that are equally non-delegable. The revocation path does not fire
in the nether at all, which is upstream of the announce half L-60 describes.

**It fails on `main` too** (runs 30177362113 and 30176735874), so it is not a regression from the
observer work. It is a row whose exit was assumed rather than read: L-60 said "remaining: the live
run", and the live run had not been looked at.

**`pearl` still SKIPS its ghost half naming L-60** — "this server's lane owns no regions, so nothing
on it captures the pearl as a ghost". That is precise and worth acting on: the observer path built
this session (`ObserverOwnership` + `forwardTo`) covers **block** capture only. `EntityCaptureBridge`
still gates ghost capture on `runtime.delegated(region)`, so the entity lane has the same fault line
the block lane just had. That is the next piece of work, and it is now named by a run rather than by
a guess.

### 2026-07-26 — A node that owns nothing can still route what it sees (L-80 → RETIRING)

Every dedicated-server log has the line "no regions fall to this node", and it is correct: the
field-of-view planner gives regions to the players' nodes. The consequence was that the one process
which actually sees block edits could capture them and had nowhere to send them — `forwardToPrimary`
starts from a local replica's lease, and an observer has no replica.

The missing lookup was never missing. The observer is the node that **computes** the plan and
broadcasts it; it simply threw it away afterwards. `ObserverOwnership` keeps it, and a capture in an
unheld region is signed and forwarded through the new `forwardTo(primary, envelope)`, which starts
from the plan rather than from a replica.

The observer is a **courier, not an authority**: the receiving primary re-verifies the actor
signature, the admission rule and the batch before proposing anything, so a stale index entry costs
a dropped forward, never a wrong world. `ObserverOwnershipTest` (5) pins the index — including the
one that would fail silently: re-publishing **replaces** the plan rather than merging into it,
because a player walking away has to take their regions out of it.

What remains is the live run, and the question it will surface: the primary must admit the
observer's signature for that actor, which is issue #45's membership work. The row moves OPEN →
RETIRING rather than retiring outright, because its exit test is a live assertion and this is the
mechanism, not the evidence.

### 2026-07-26 — The third mixin, and the last one the charter plans

There is no event for "the game is about to random-tick this chunk". NeoForge fires per-block events
*after* vanilla has chosen the cells and consumed randomness from the level RNG, which is exactly
what must not happen: the engine owns grass, fire and crops in a delegated region, and letting
vanilla roll for them too produces a world neither side predicted — the committed root says one
thing and the player's screen shows another.

`ServerLevelRandomTickMixin` cancels `ServerLevel.tickChunk` at HEAD for chunks in a delegated
region. The **whole chunk** is skipped rather than filtered per block, because a per-block filter
would still have drawn from the level RNG for the blocks it rejected — and the draws are the thing
being suppressed.

It reuses the existing suppression registry (a delegated region suppresses both kinds of vanilla
tick) but counts into its own counter: scheduled ticks are the redstone lane's assert-zero, random
ticks are the farm lane's, and a soak that read one number would be reading the wrong one.

Evidence that it applies: `runServer` reaches `Done (0.735s)!` with `required: true` and no mixin
diagnostic — a mismatched injection point aborts class load long before the world finishes loading.

That is three mixins: the write choke point, scheduled ticks, and random ticks. `COMPATIBILITY.md`
now states the whole set, and that Nodera ships no others.

### 2026-07-25 — The choke point is live, and the guard it feeds finally has a caller

There is no event for "a block changed". NeoForge fires events for the *causes* it knows — a player
placing, a piston moving, a fluid spreading — and a foreign write is by definition the write whose
cause nobody enumerated: a mod calling `setBlockState`, a fake player, a late worldgen feature, an
async executor. All of them funnel through `LevelChunk.setBlockState` and nothing else does, so the
second mixin in the repository guards that funnel. Guarding the causes instead would mean guarding
an open set, which is the same as not guarding.

The mixin holds no judgement: `BlockWriteGuard` translates chunk and `BlockState` into region and
palette ids, and `MutationGuard` — unit-tested in the engine, with the applier scope, the CONVERT
default, and the STRICT mode already built — decides. On a server validating nothing the whole
choke point is one field read per block write.

The entry point is chosen **by thread**, and that is the design decision worth reading twice. A
main-thread write with no phase marker is ordinary vanilla plumbing; rejecting those would have made
the guard a crash generator. A write arriving from another thread is exactly the case L-25's
documented rejection exists for, so it gets `verdictChecked` and the `AsyncWriteException` naming
`AsyncActionGate.submit` — rethrown, never degraded into a warning, because a mod that cannot see
the error cannot fix it.

That gives **L-25** the one thing it lacked: a call site. Both halves had existed for a while, and
the row's own note said so — "a guard nothing calls rejects nothing in practice". RETIRED, with the
evidence in [`../engine/LIMITATIONS.fixed.md`](../engine/LIMITATIONS.fixed.md).

The lane installs the guard on `install()` and removes it on `close()`, and the block applier's own
writes run inside `MutationGuard.applierScope` — without that, every commit this node applied would
have been re-certified back to itself as foreign.

**Live evidence that the mixin applies**: `./gradlew :neoforge-mod:runServer` reaches
`[minecraft/DedicatedServer]: Done (0.748s)!` with `required: true` and `defaultRequire: 1` in
`nodera.mixins.json` and no mixin diagnostic in the log — a mismatched injection point aborts class
load long before the world finishes loading, so reaching Done *is* the proof. (The run then exits on
`CompanionUnavailableException`, which is the companion gate doing its job with no worker running —
unrelated to the mixin.)

### 2026-07-25 — The world reads back, and a commit becomes a block a player can see

Capture without apply is half a lane, and a probe with nothing to read is half a measurement. Both
halves landed.

`LiveSnapshotExtractor` turns real chunk sections into a real `RegionSnapshot`. A section whose 4096
states all map to one palette id — most of a world — stays a single id; anything else arrives as a
dense array, and `ChunkColumnState` canonicalises both so two nodes reading the same world encode
the same bytes. Blocks the palette cannot express are extracted as air **and counted**: the
validated lane holds the palette's world, so a region full of modded machinery must report a high
excluded count rather than a confident wrong root. Chunks that are not resident are counted too and
never force-generated — loading a region synchronously on the server thread is the stall the lane
exists to avoid.

The apply half is one line in `ServerEntityWorldView.setBlock` and a projection beside it:
a committed block mutation is staged exactly like an item projection and runs after the canonical
scope commits, so the world is never written from a mutation that then aborts. An id the running
game cannot express is skipped with a log line — a wrong block is a divergence, a missing one is
visible interference.

`InterferenceProbe` gained the count it always wanted. Its section comparison could not tell one
mined block from four thousand burned ones, because a dense section's palette entry is pinned to
zero; it now descends into dense sections and reports **exact block differences** alongside the
coarse number, which every historical measurement is expressed in. `/nodera debug extract` prints
the whole thing for the caller's own region: extraction time, chunks resident, dense sections,
blocks outside the palette, and — when this node's lane holds the region — how far the world has
drifted from committed state, in blocks.

Evidence: `InterferenceProbeTest` grew the two cases that matter — one block mined inside a section
is exactly 1, and a dense section whose contents equal its uniform counterpart is **not**
interference, so the extractor's shape can never decide what counts as drift.

### 2026-07-25 — A real block edit becomes a consensus action (issue #5's capture half)

The determinism gate has always been the project's hard exit, and it has always been proven over
inputs the engine invented for itself. What was missing was the sentence in the middle: *this
`BlockState` is that palette id*. Three pieces landed together.

`VanillaPalette` (engine, Minecraft-free) is the binding table — every one of the palette's ~100 ids
against the vanilla block key and the properties that carry consensus meaning, in both directions.
It lives in the engine on purpose: the table is exactly as load-bearing as the palette itself (a
wrong row does not crash, it diverges), so it belongs where the ordinary gate can read it. The test
that matters is `VanillaPaletteTest.everyPaletteEntryRoundTripsThroughItsVanillaState` — a palette
that grows a new id without a binding fails it the same day, instead of the live lane quietly
excluding the new block for a month. `PaletteMapper` (mod) is what is left over once the table is
elsewhere: read the registry key, read the properties, ask the engine — and back again for the
applier, answering empty rather than guessing when the running game does not know a bound state.

`BlockCaptureBridge` subscribes to `EntityPlaceEvent` and `BreakEvent` at LOWEST priority — we
capture what the world agreed to, not what someone proposed — and hands the action to the same
signed submit path the entity lane uses. **Vanilla is never cancelled**: this is the
`VanillaCancelGate` contract (issues #33/#44) read across to blocks, and it is why capture needs no
gate in front of the player's own edit. Every judgement about *whether* to capture is in
`BlockCaptureRules`, Minecraft-free and pinned by 8 tests: a modded block, a state the palette cannot
express, a network-computed state offered as a *placement* (placing powered wire would mint 15 power
out of a client packet), an edit outside the height envelope, and a fake player's edit are each
refused with their own reason. `/nodera debug capture` prints the resulting ledger, because
"nothing was captured" and "everything was captured" look identical from inside the game.

Evidence: `VanillaCaptureSoakIT` re-runs the three-worker shadow soak with every action built from a
vanilla `(key, properties)` pair — 250 batches, more than fifteen distinct palette ids exercised,
modded and vertical-piston states mixed into the same stream — and asserts zero divergence with all
three replicas byte-identical to the reference chain. That closes the audit gap named on issue #5:
the soak now exercises the real vanilla palette rather than `STONE` on flat rules.

What this does **not** do is captured as **L-80**: the capture gate is `delegated(region)`, so on a
dedicated server — where the field-of-view planner gives every region to the players' nodes — the
one process that sees block events holds no seat. Same fault line as L-60, same fix shape.

### 2026-07-25 — The game's events reach the worker, and nothing else does

`ModTelemetry` + `/nodera telemetry` landed, tested against a **stand-in worker on loopback**
rather than a mock — the property under test is a wire property, and a mock would have proved the
mock.

The two tests to read first are the ones about what does *not* survive:
`aShareEventCarriesNoWorldIdentity` and `anErrorReportCarriesAFingerprintRatherThanAMessage`. The
second builds an event out of an exception whose message contains a home directory, a world name,
and the word "corrupt", and asserts that none of the three appears in what the worker receives — the
event carries a 16-hex fingerprint of the stack instead.

A design note for later: the mod's join flow now labels its outcome (`direct` / `relayed` /
`world_gone`) as a **declared enum**, never a message. A free-text reason is the field that
eventually contains an address, and the registry makes that unrepresentable rather than merely
discouraged.

### 2026-07-25 — An eighth task: the events only the game can see

A share, a join and the path it took, a rehost, a divergence, a feature someone actually used —
these are observable only inside the game, and [Task 8](Task.8.md) routes them to the worker.

Two constraints are written into the task file as refusable rules. **The mod opens no telemetry
connection of its own**: it hands events to the worker over the control protocol it already speaks,
so there is exactly one consent check and no events are lost when the game closes. And **nothing on a
tick path does I/O** — recording is a bounded enqueue, and the socket write happens on the mod's own
executor.

One detail that will matter when it is implemented: a join's failure reason is an *enum*, never a
message. A free-text reason eventually contains a world name or an address, which is precisely what
the ingest registry makes unrepresentable ([`../plans/Plan.6.md`](../plans/Plan.6.md) D5).

### 2026-07-25 — Mesh population and boundary independence (three live-play defects)

Three defects reported from real play shared one root: *a region's committee was whatever players
happened to be standing in it, and nothing on the live lane ever noticed when its primary stopped
keeping up.*

1. **Workers hold committee seats through a player's disconnect.** The resident lane now tops every
   committee up from the standing worker pool and hands each resident its seats.
2. **A laggy player no longer wedges its regions for everyone.** The handoff lane existed and was
   tested but had **no live call site** — a client thousands of ticks behind stayed primary of every
   region its view covered.
3. **Region status stopped reading as a permanent "unsigned".** Certified / pending / **solo** now
   distinguishes a co-signed head from a population shortfall no amount of waiting fixes.

Two latent faults surfaced en route and were fixed: a commit announcement for a **revoked** replica
threw an illegal pipeline transition out of the peer state thread (killing it), and a newer-epoch
assignment re-activated a **live** replica from the genesis snapshot, rewinding it to v0 and failing
every later check.

### 2026-07-24 — Live-play bug bundle

Four defects from real sessions, each with a fix that generalised:

- **Host exit hung on "Saving World"** — the final archive seed was synchronous. Replaced by
  continuous streaming with a bounded final flush, a freshness guard, and exit progress.
- **Item drop/pickup desync** — vanilla was being cancelled against an *unconfirmed* commit. The rule
  is now: cancel vanilla only on the synchronous local-primary path, pinned Minecraft-free by a
  dedicated gate class so it is testable without a game.
- **A P2P bind failure crashed the integrated server.** NeoForge's event bus does **not** isolate
  listener exceptions, so code reachable from server events must self-catch. Three layers of
  containment plus an ephemeral-port retry.
- **Command and telemetry surfaces** — `/tps`, operator aliases, and correct host targeting.

### 2026-07-23 — The world stops dying with its host

The acceptance was blunt: *the host disconnects — the world must not die with them.* A share now packs
the save into the canonical archive, the worker seeds it to the swarm, and a client that loses its
host swaps the vanilla disconnect screen for a recovery flow: fetch, unpack, re-open, auto-re-share.
**The joiner becomes the world's next host with the same world id.**

The scripted continuity series passed **all seven stages** on a live display: share → tracker and
rendezvous listing → second client in-world → an 11.2 MB archive on the network → host killed → the
joiner recovered, re-opened, and re-hosted the world **in 3 seconds**.

En route it caught four real defects that no headless test could have: dead client-ready and publish
wiring (auto-re-shared worlds were never joinable), Mojang session auth versus dev accounts, and a
piece-plane stall where bounded seeders silently dropped over-budget requests and the downloader never
re-issued them.

Same period, the **no-host ownership** model landed: every joining client announces its own peer node,
the session broadcasts the deterministic plan inputs, and every member derives the identical
field-of-view ownership plan — *each player's client re-executes and votes on its own region set*. An
action captured on a non-owner is forwarded to the owning player's node, so the capture point is a
courier with no authority. Live evidence: the entity lane live on 13 regions across 2 member nodes,
with the joiner's own validation lane active on 13 regions.

### 2026-07-22 — The run harness and the join path

The `runs { }` block landed with the NeoForge pin reconciled to the version the real client runs, the
project modules joined to the mod definition (module isolation otherwise hides them at dev-run time),
and runtime libraries aligned with Minecraft's strict pins. `runServer` boots to `Done` with the host
lane self-activating; `runClient` reaches the title screen.

The join path became real rather than decorative: a tracker directory query lists worlds this node has
never seen, a route query resolves a row to a live endpoint, "Open to Nodera" actually publishes the
integrated server and hands the endpoint and live player count to the worker, and the redesigned
two-tab multiplayer screen joins through the vanilla connect screen.

The entity lane was proven live the same week: *entity lane live on 12 region(s)* with the mesh formed
and zero errors, then an RCON-driven drive showing 239 validated and ghost entities across 12
delegated regions.

### 2026-07-21 — Identity, authorship, and the companion gate

The worker mints a signed world identity that the mod persists per world; a shared world re-shares
automatically on load; only the author can change the password; and the sharing player becomes
operator. The presence gate defaults **on**, so Minecraft refuses to start without the peer worker —
with an actionable message and version-skew classification rather than a stack trace.

### 2026-07-20 — Decentralization: the dedicated server stops being required

The dist gate was removed, so a player's integrated server runs the same host lane and shares a world
from a pause-menu button. The host service became role-driven rather than dist-driven, and the
development script stopped installing or running a Minecraft server at all.
