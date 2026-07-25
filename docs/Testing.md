# Testing.md — live test procedures + module test status

<!-- AI-AGENT-INSTRUCTION: This file is the SINGLE testing document (it merged the former
     docs/Testing.Live.md and the repo-root Tested.md on 2026-07-24).
     Part 1 documents the SCRIPTED live acceptance suites: how to run each one, what each stage
     asserts, and how to read the logs/artifacts. Update it whenever a script gains/changes a
     stage. Part 2 is the module test-status ledger: update it on EVERY commit that changes test
     outcomes, together with the README module table, so the two never drift. -->

# Part 1 — the live two-player test procedures

All live suites drive **real NeoForge clients** (each with its **own peer worker**) against the
standalone `nodera-tracker` + `nodera-rendezvous` services — the full decentralized stack on one
machine. They need a GUI session (the clients render on your display), ~6 GB free RAM, and the
Rust toolchain. The headless rehearsals (`WorldContinuityIT`, `ActionForwardIT`,
`WorkerQuorumValidationIT`) cover the same flows Minecraft-free and run in `./gradlew check`.

## Running — one suite at a time, via the runner

**`scripts/run-tests.sh` is the entry point.** It builds once, then executes the requested suites
**strictly one at a time** — the suites share the port block 25599–25622 and the Minecraft run
dirs, so concurrency is structurally impossible: the runner holds an exclusive `flock`
(`run/.e2e-suite.lock`) that every standalone suite honours too (a second stack refuses to start).

```bash
scripts/run-tests.sh                    # every suite, canonical order (~45 min)
scripts/run-tests.sh commands crash     # just these suites
scripts/run-tests.sh --no-build churn   # skip the up-front build

# suites can still run standalone (each takes the same lock + checks its ports):
scripts/e2e-continuity.sh               # also BAKES the shared world others reuse
scripts/e2e-ownership.sh --no-build
scripts/e2e-churn.sh     --no-build [--cycles 5]
scripts/e2e-pickup.sh    --no-build
scripts/e2e-commands.sh  --no-build
scripts/e2e-farlands.sh  --no-build
scripts/e2e-crash.sh     --no-build
scripts/e2e-ownership-follow.sh --no-build
scripts/dev.sh --play    --no-build     # not a test: two interactive clients
scripts/dev.sh --play --with-app        # …plus one Tauri companion window per player
```

Canonical order matters once: `e2e-continuity.sh` bakes
`java/neoforge-mod/run-host/saves/NoderaE2E` (a genuinely shared world with a signed
`nodera-world.dat` + certified genesis); `e2e-ownership.sh`, `e2e-churn.sh`, and `e2e-crash.sh`
reuse it. **Run these live suites whenever a change touches the mod's host/join/lane/continuity
surfaces** — the headless gate cannot see NeoForge-config-gated lifecycle paths, and most of the
defects listed at the end of this part were only catchable live.

**Stale-bake trap (re-bake after a rules bump).** The baked `NoderaE2E` world stores a *certified
genesis* pinned to the `FlatWorldRules.RULES_VERSION` of the day it was baked. Bump that constant
and the current engine refuses the old genesis — `Nodera: entity lane bootstrap failed:
IllegalArgumentException: rulesVersion N does not match FlatWorldRules.RULES_VERSION M` — so the
entity lane never boots and every ownership assertion in the bake-reusing suites (`ownership`,
`churn`, `crash`) fails with no obvious cause. Those suites now bail out immediately naming this;
the fix is a re-bake:

```bash
rm -rf java/neoforge-mod/run-host/saves/NoderaE2E && scripts/e2e-continuity.sh
```

| Suite | What it proves | Duration |
|---|---|---|
| `e2e-continuity.sh` | World survives the host: share → join → archive on the network → host killed → player B recovers + re-hosts | ~8 min |
| `e2e-ownership.sh` | **Test 1** — per-player FOV region ownership + the cross-owner drive + host leave/network re-join | ~10 min |
| `e2e-churn.sh` | **Test 2** — join/leave churn ×5 with random dwell; log audit proves no error accumulation | ~12 min |
| `e2e-pickup.sh` | Clean-slate validated pickup delivers exactly once (no vanish, no dupe) | ~6 min |
| `e2e-commands.sh` | **Two players × every `/nodera` command** with response validation, plus the in-game `/nodera selftest` tree-walk + benchmark | ~8 min |
| `e2e-farlands.sh` | Two players **~566 km apart**: positions verified, per-player chunk-control interrogated (`nodera zone` + `REGION:` owner evidence) | ~8 min |
| `e2e-crash.sh` | One client **SIGKILLed mid-session**: the survivor sees no disruption, no continuity arm, **no migration screen**; the crashed player rejoins | ~7 min |
| `e2e-ownership-follow.sh` | **Ownership follows the player**: after a multi-thousand-block teleport the session re-plans and the player's client re-derives its owned region set at the new position | ~5 min |
| `dev.sh --play` | Not a test — same launcher + same topology, two **interactive** clients for hands-on play (workers bind `0.0.0.0` so a LAN peer can reach them). `--with-app` adds one Tauri companion window per player, each attached to that player's own worker; `--apps N` / `--spare-peers N` retopologise it. Absorbed the former `play-two.sh` | manual |

**How Minecraft is launched** — no GUI automation anywhere: the scripts use quick play.
`runClientHost` boots straight into the shared world (`--quickPlaySingleplayer NoderaE2E`; the
Task 33 auto-re-share puts it on the network), `runClientJoin`/`runClientJoinTwo` boot straight
into the session (`--quickPlayMultiplayer 127.0.0.1:25599`, usernames JoinerDev/JoinerTwo),
`runClientRejoin` is player A returning as a network client. Distinct usernames prevent the
duplicate-login kick. Staged worlds pin `host.gamePort=25599`, disable Mojang session auth
(`host.onlineAuth=false` — offline dev accounts), and enable `entity.laneAutoActivate`. The
dedicated-server suites (`pickup`, `commands`, `farlands`, `ownership-follow`) drive gameplay over
**RCON** (port 25575) — teleports, inventory reads, and `execute as <player> run …` execution.

### The shared launcher and the standard topology

No suite starts a service itself. `scripts/lib/e2e-main.sh` is **the** launcher: a suite sources
it, names itself, and calls `nodera_stack_up` (lock → build → port preflight → tracker →
rendezvous → workers → control-socket probe). Every suite therefore runs the same topology, set in
exactly one place:

> **2 players · 1 tracker · 1 rendezvous · 3 headless peers**

The three peers are the quorum floor — `DiagnosticsCollector.deriveHealth` reports `DEGRADED`
("below quorum (3)") under three session members, so a thinner run measures a degraded system.
Peer 1 and peer 2 are the players' companion workers (control 25610/25611, p2p 25620/25621);
peer 3 is a **spare standalone worker** with no client attached (25612/25622).

The companion peers are **real members** of the world's session (issue #45). On host start the mod
hands its companion the game's P2P route over `NODERA-MESH`; `PeerRuntime.joinSession` dials it and
membership gossip publishes every member's Ed25519 key. A joined peer therefore counts toward
`deriveHealth`'s quorum, is handed committee seats via `RegionAssigned`, and can inherit the gateway
when the hosting game exits.

Membership is per-JVM plus the peers that joined:

| shape | members |
|---|---|
| dedicated server + 2 joiners + companions | 5–6 |
| host client + 1 joiner + companions | 4–5 |

The hosting JVM deliberately does not *also* start a client peer
(`ModNetworking.handleSessionOnClient` — "already in the mesh as the server peer"): one JVM, one
node. That used to cap a player-hosted two-player world at **2 members — permanently DEGRADED** no
matter how many peers were running (observed live 2026-07-24). Peer membership is what now carries
such a world over the quorum floor without needing a third human.

> ⚠️ The **spare** peer has no client attached, so nothing meshes it on its own — it is swarm and
> archive ballast plus a standby gateway, and becomes a session member only when some world's mod
> meshes it. Only the suites' own companions auto-join.

Every parameter is a variable a function loads into memory, with `${VAR:-default}`, so pinning one
is just setting it before the run:

```bash
NODERA_SPARE_PEERS=0 scripts/e2e-crash.sh          # thin the swarm on purpose
NODERA_E2E_TIMEOUT_MULT=3 scripts/e2e-pickup.sh    # slow machine / CI
scripts/run-tests.sh --spare-peers 2 --players 2   # retopologise a whole batch
```

Two composite stages cover the two player shapes: `nodera_hosted_two_players` (player A hosts from
its client, player B joins — the suites that kill a hosting player) and
`nodera_dedicated_two_players` (dedicated server + JoinerDev + JoinerTwo — the RCON suites).

## Test 1 — region ownership (`e2e-ownership.sh`)

Stages (each `PASS`/`FAIL` line names its stage):

- **O1** — player A shares; player B joins through the tracker; the plan spans both nodes
  (`entity lane live on N region(s) across 2 member node(s)` in `client-host.log`) and player B's
  client runs its own validation lane (`client validation lane active on N region(s)` in
  `client-join.log`).
- **O2** — the **drive** (`debug.regionDrive=true`): the server teleports each player to a region
  its own node owns, then sends player B into player A's region. Evidence lines in
  `client-host.log`:
  ```
  DRIVE step 1: HostDev → its own region Region[...]; JoinerDev → its own region Region[...]
  DRIVE step 2: JoinerDev → HostDev's region Region[...] (cross-owner visit)
  REGION: JoinerDev left Region[...] → entered Region[...] (owner: HostDev)
  ```
  The `REGION:` tracker runs always (not only during tests) — every boundary crossing logs the
  entered region's owning player when the plan knows it.
- **O3** — player A killed; player B recovers the world from the network and re-hosts it.
  *Boundary note:* today this is a **visible, brief recovery** (fetch → reopen → re-share, a few
  seconds). The goal's "no loading screen at all" is the Task 16 local-replica view — the client
  simulating its own regions so a departed peer changes nothing on screen; the stage asserts what
  the current architecture guarantees: **the session and world survive**.
- **O4** — player A re-joins the same world **over the network** (now hosted by player B), and
  ownership re-plans across the pair again.

## Test 2 — churn (`e2e-churn.sh`)

- **C1** — player A hosts.
- **C2 ×5** — player B joins (`JoinerDev joined the game`), dwells 10–30 s (random), is killed
  abruptly (worst-case disconnect), and the leave registers. Each cycle re-plans ownership; the
  survivors absorb the departed node's regions.
- **C3** — log audit over `client-host.log` + both worker logs: any `ERROR`/`FATAL` line fails
  the test, minus a curated allowlist of benign disconnect noise (`Lost connection`,
  `Connection reset`, …). WARN lines are allowed — the lane logs expected degradations
  (revokes, stale plans) at WARN by design.

## Commands suite (`e2e-commands.sh`) + the in-game `/nodera selftest`

- **K0/K1** — clean-slate dedicated server (RCON on) + three workers + JoinerDev + JoinerTwo
  joined, entity lane live.
- **K2** — **each player executes every read-surface `/nodera` command** (session, status, peers,
  net, net `<type>`, regions, zone, entities, health, server, worlds, share status,
  whois `<other>`, all four hud toggles, debug sample-rate/verbose/relay, and `/tps`), driven via
  RCON `execute as <player> at <player> run …`. Every response is captured to a per-player
  transcript and validated against a per-command expectation (e.g. `nodera session` must contain
  `epoch`, `nodera share status` must say `Sharing: yes`).
- **K3** — `/nodera selftest`: the mod itself **walks the live Brigadier tree** under
  `nodera`/`tps`, synthesizes arguments, executes every reachable leaf **as each connected
  player** against a capturing command source, times each read-only command over 3 iterations
  (the command benchmark), and persists JSON + Markdown reports under
  `<world>/nodera-selftest/selftest-<stamp>.{json,md}` with a single greppable
  `SELFTEST complete: ok=… syntaxErr=… exception=… report=…` summary line. The stage requires
  `syntaxErr=0 exception=0`, then re-runs as `/nodera selftest full` (adds the `op`/`deop` grant
  lane; `share`/`share stop` are never executed — they would flip the host lane under the
  joiners).
- **K4** — everything is collected under `run/results/e2e-commands/<stamp>/`: per-player command
  transcripts, the selftest reports, and the logs of **every** component (tracker, rendezvous,
  all three workers, the dedicated server, and both Minecraft clients).

## Farlands suite (`e2e-farlands.sh`)

- **F0** — same staging as the commands suite (two joined players, lane live).
- **F1** — the players are teleported **~566 km apart** (`(200000, 200, 200000)` vs
  `(-200000, 200, -200000)`); their real positions are read back over RCON
  (`data get entity <p> Pos`) and asserted within ±16 blocks.
- **F2** — per-player chunk-control interrogation: `nodera zone` (the region at the player's
  feet + its `OwnershipState`), `nodera regions`, `nodera entities` — each response transcribed;
  the two players must report **distinct regions**. A +64-block nudge teleport then crosses a
  region boundary so the always-on `REGION:` tracker logs the entered region's **owner by name**;
  the assertion is that each far-apart player owns the region it stands in (its own FOV disc).
- **F3** — artifacts: the interrogation transcript, all three workers' `NODERA-STATE` JSON
  snapshots, and every service/client log land under `run/results/e2e-farlands/<stamp>/`.

## Ownership-follow suite (`e2e-ownership-follow.sh`)

Pins the FOV model's central promise — **the regions you own follow your view**.

- **W0/W1** — clean-slate dedicated server + one joining player; the player's own client lane
  reports a non-empty owned region set (`client validation lane active on N region(s)`).
- **W2** — the player is teleported thousands of blocks away, crossing dozens of region
  boundaries. A **new** plan must be computed and broadcast, and the client must re-derive its
  region set at the new position. Both assertions use `wait_log_after` so they can only be
  satisfied by log lines written *after* the teleport.
- **W3/W4** — the teleport landed, and the re-plan swaps produced no errors and no tick-loop crash.

**Whose ownership is being read matters.** Ownership belongs to *players*, not to the session
server: the server's node holds no `PlayerView`, so on a dedicated server it correctly owns zero
regions once players announce their nodes, and `/nodera regions` — which answers for the server
node — reads empty. That is the no-host model working, not a blind panel. A player's real
ownership is its client lane, which is what this suite asserts on.

Two defects this suite caught, both invisible to the headless gate:

1. **Ownership never followed the player.** `NoderaHost.planKey` hashed only `(player → node)`
   pairs, so the plan froze at the positions held when the last player joined; walking away left
   you reading `FOREIGN`/`UNASSIGNED` for the ground under your feet. Region coordinates are now
   part of the key and a once-a-second tick re-plans on boundary crossings (5 s cooldown so a
   player straddling a boundary cannot churn the lane).
2. **A cooldown that suppressed every re-plan.** The first version seeded the last-re-plan tick
   to `Long.MIN_VALUE`; `tick - Long.MIN_VALUE` overflows negative, so the cooldown test was
   always true and no movement re-plan ever fired. The live run caught it — the fix compiled,
   passed the headless gate, and did nothing at all. It is now seeded one cooldown in the past.

## Crash suite (`e2e-crash.sh`)

- **X0** — player A hosts the baked `NoderaE2E` world; player B joins (continuity topology).
- **X1** — player B's client JVM is **SIGKILLed** mid-session: a real crash — no disconnect
  packet, no shutdown hooks, the TCP peer just goes silent.
- **X2** — the survivor must be **undisturbed**: the leave registers, ownership re-plans across
  the remaining member set, and the stage FAILS if the survivor arms continuity recovery
  (`Nodera continuity: host connection lost`), shows the migration screen
  (`nodera.continuity.migrating`), crashes (`Exception in server tick loop`), or logs any
  non-allowlisted `ERROR`/`FATAL` in a 20 s observation window.
- **X3** — the world is still live: player B rejoins the same session successfully.
- *Boundary:* the inverse case — the **host** crashes — is the continuity recovery
  (`e2e-continuity.sh` S4–S5, `e2e-ownership.sh` O3). There the joiner's brief "Migrating
  world…" beat is today's accepted seam; removing it is exactly Task 16's local-replica view.

## Reading the logs + artifacts

Live logs land under `run/logs/<suite>/`; the newer suites additionally copy **everything**
(suite logs, tracker/rendezvous logs, per-player worker logs, each Minecraft client's
`latest.log`, command transcripts, selftest reports, worker STATE snapshots) into
`run/results/<suite>/<stamp>/` for offline analysis. The batch runner tees each suite's output to
`run/results/runner/<stamp>/<suite>.out` next to `summary.txt`.

| File | What to look for |
|---|---|
| `client-host.log` / `client-join*.log` / `server.log` | `Nodera: sharing world`, `game server open for joiners`, `entity lane live … member node(s)`, `client validation lane active`, `DRIVE`/`REGION:` lines, `SELFTEST complete:`, `Nodera continuity: host connection lost` → `restored to saves/` |
| `worker-*.log` | `Now hosting world`, `Seeding world archive vN — P piece(s)`, `Fetched world archive` |
| `tracker.log` / `rendezvous.log` | announce acks, signed-record registrations |
| `commands-*.log` / `interrogation.log` | per-player command → response transcripts (commands/farlands suites) |
| `nodera-selftest/selftest-*.{json,md}` | the in-game suite's per-command status/benchmark/response report |
| in-game (manual runs) | `/nodera regions`, `/nodera entities`, `/nodera selftest`; the `NODERA-STATE` control verb (port 25610/25611/25612) returns live JSON: `maintained_pieces`, `connected_worlds`, `validation` counters |

Quick greps:

```bash
grep -a "REGION: \|DRIVE " run/logs/e2e-ownership/client-host.log   # ownership evidence
grep -a "validation lane active" run/logs/e2e-ownership/client-join.log
grep -aE "ERROR|FATAL" run/logs/e2e-churn/*.log                     # should be empty/benign
grep -a "SELFTEST complete" run/logs/e2e-commands/server.log        # the in-game suite verdict
```

## Defects these tests caught (all fixed 2026-07-23 — why the suite exists)

1. `setClientPlayerReady`/`tickGamePublish` had no callers — auto-re-shared worlds were listed
   but never joinable.
2. The published integrated server enforced Mojang session auth — offline dev accounts were
   refused (`host.onlineAuth`, secure by default).
3. Piece-plane stall — bounded seeders silently drop over-budget requests and the downloader
   never re-issued (`PieceDownloader.retryPending` + bulk bounds for the archive lane).
4. **Host self-recovery cascade** — the hosting JVM armed its own continuity: any local-connection
   hiccup made the host "recover" the world it was still hosting, reopening it and kicking every
   joiner in a loop. Guards: a hosting JVM never arms recovery, never starts a second in-JVM
   client peer, never runs a duplicate client validation lane.
5. **Player-position loss** — the world archive excluded `playerdata/`, so a rehosted world reset
   every returning player to spawn with an empty inventory. Player state is world state:
   `playerdata/`, `advancements/`, `stats/` now travel.
6. The drive re-ran on every rejoin (teleporting players who just came back) — it now runs once
   per server session; the replan path de-dupes on the member set so announce storms cannot churn
   the lane through close/reopen cycles.

## Known boundaries (tracked in `LIMITATIONS.md`)

- Player B's survival of a **host** exit is a **recovery**, not yet seamless. Since 2026-07-23 the
  seam is minimized: the joiner's worker **prefetches the world archive while the session is
  healthy** (hot standby — no download phase on loss) and the transition screen is a quiet
  vanilla-style "Migrating world…" beat, so what remains visible is the world-open itself
  (~1–3 s). Removing that last seam is exactly Task 16's local-replica view — each client
  simulating its own regions so a departed peer changes nothing on screen (design prior art:
  the ownership-takeover model in `docs/minecraft/MultiPaper/`). A **non-host** crash is already
  seamless for the survivors — that is what `e2e-crash.sh` pins.
- The validated event families are the entity/item lane; blocks/fluids/mobs/etc. join per
  Tasks 13–16.
- Action envelopes are signed by one interim session key (declared in the plan payload);
  per-player signing is staged.

---

# Part 2 — module test status

**Tests:** `1,382 passing · 0 failing · 0 skipped` (+**150 Rust workspace**, **56 `nodera-app`**;
**1,588 total**). Per module (authoritative `./gradlew check` XML): core 233 · engine 430 ·
transport 93 · storage 97 · testing 14 · peer 432 · neoforge-mod 83.

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

> **Task-numbering note (2026-07-21):** the task numbers cited throughout this file ("Task 19",
> "Task 33", …) are the **legacy** per-increment task numbering. Those specs now live verbatim in
> [`old/`](old/); the current module-task set is `Task.0.md` … `Task.7.md`,
> with the legacy→new mapping in [`Task.0.md`](Task.0.md) §4. The history below is kept
> as-is — it is the test-growth record.
>
> **Test growth + resync (→ 1,288 Java; issues #39 + #37):** the per-module table is recomputed from
> the authoritative `./gradlew check` XML reports. It had drifted below the real source counts (recent
> permission/identity/re-key lanes landed without a Tested bump); the corrected totals are
> core 228 · engine 430 · transport 86 · storage 97 · testing 14 · peer 368 · neoforge-mod 65.
> Issue #39 adds the P2P-port-bind crash-resilience fix: `transport` (+2)
> `SocketPeerTransportBindTest` pins that a bind on a busy port throws `TransportException` (cause
> `BindException`) and leaves no listener, while a port-`0` bind still succeeds — the invariants the
> host peer's elastic retry relies on; `neoforge-mod` (+6) `NoderaPeerServiceIsBindFailureTest` pins
> the cause-chain bind-failure classifier (bare / direct-socket / rendezvous-wrapped / message-form)
> that decides retry-on-ephemeral vs degrade. Issue #37 adds the password re-key pipeline: `peer`
> (+2) `RekeyVerbIT` proves the `NODERA-REKEY` crypto+identity round trip (re-encrypt under a fresh
> Argon2id salt, new `manifestRoot`, re-signed identity verifies, wrong-author rejected, old password
> no longer decrypts) and `neoforge-mod` (+4) `CompanionClientRekeyTest` (+3) + `WorldArchiverPackToSpoolTest`
> (+1). The `startHost` orchestration and the `reconfigure` Share-screen path are NeoForge-config-gated
> and validated by the live `runClient` exit (like every other `NoderaHost` lifecycle method), not
> headless units.

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, canonical encoding, JDK-only crypto, transition-bound authority/vote/joint-transfer certificates, Task 12 entity snapshots/deltas/mutations/credits/transfer records (tags through 102), and the product-identity constants (`CLIENT_AGENT`) peers publish | 233 | 0 | 0 | ✅ | 2026-07-24 |
| `engine` | unified deterministic engine + consensus/shadow/coordinator/committee/fallback stack; Task 12 fixed-point items, throttled ghosts, transactional entity/credit CAS, delegability/playerless isolation, transfer recovery, pearl policy, and soak metrics | 430 | 0 | 0 | ✅ | 2026-07-24 |
| `transport` | unified wire/carrier API; port-range bind retry + a live max-connections cap; transfer prepare/accept/commit + tracker routes + the continuity lane's `WorldManifestQuery`/`Answer` + the no-host `ActionForward` extend append-only message tags through 53; Java/Rust tag mirror stays green; issue #39 pins the socket bind-failure + ephemeral-retry invariants; issue #41 (L-53) `SocketPeerTransportAuthTest` pins the authenticated challenge-response handshake (key-proven interop, legacy/forged/replay refusal) | 93 | 0 | 0 | ✅ | 2026-07-25 |
| `storage` | unified event-sourced/RocksDB/client storage; Task 12 adds atomic paired event append, joint transfer certificates, durable stage records, reopen validation, forced-kill WAL recovery; Task 30c adds the host-signed `CertifiedWorldGenesis` (tag 103); issue #36/33 add the signed identity/permission stores | 97 | 0 | 0 | ✅ | 2026-07-24 |
| `testing` | shared test library (`LoopbackTransport`, `FakeRegion`, fixture IO) | 14 | 0 | 0 | ✅ | 2026-07-24 |
| `peer` | unified distribution/runtime/diagnostics/headless worker plus authenticated validation, disjoint-committee transfer routing, process-kill replay, durable vote/action/inventory-credit journals, entity-lane bootstrap planning, dirty-shutdown compensation, and the world-continuity lane (`WorldArchive` codec + worker `WorldArchiveService` seeding/manifest-serving/swarm-fetch + `SEED`/`ARCHIVE`/`GRANT`/`REKEY` control verbs; `WorldContinuityIT` proves host-death survival over the real tracker + rendezvous binaries; `RekeyVerbIT` proves the password re-key crypto+identity round trip), plus the no-host ownership lane (`ActionForward` routing, forwarded-quorum `ActionForwardIT`), the discovery plane (`PeerDiscoveryService` tracker+rendezvous sweeps feeding `PeerRuntime.announceTo`; scheme-aware `TrackerClient.Endpoint` with a real UDP datagram path + TCP fallback — `TrackerEndpointTest`, `TrackerClientUdpTest`), per-peer throughput attribution (`PeerTrafficMeter`), and network-wide replication (`WorldReplicationService` adopts worlds this node is deterministically placed for; `WorldHostingService.seed`), and the **live configuration surface** (`NODERA-CONFIG` verb + `ContentTransferService` volatile serve bounds / `transfersPaused` gate / download pacing, `WorldReplicationService.reconfigure`, one shared `TrackerClient` with a mutable endpoint list; `ConfigVerbIT` proves a pushed setting actually changes worker behaviour, and pins the cross-language key contract against the companion app's golden string), and the **mesh-population + boundary-independence lane** (issues #45/#46/#47): `ResidentQuorumIT` proves the #45 exit — with two standing workers on the mesh a player's logout leaves the region committee at `QUORUM_MVP_SIZE` and it keeps committing with a certificate co-signed by a peer that has no Minecraft process (the no-resident counterfactual is asserted beside it); `LiveLagHandoffIT` gives the Task 25 handoff lane its first live call site (`forwardLagTickBps` = age of the oldest forwarded action the primary has not answered → `tickLagHandoff` → `LagHandoffPolicy` → `CommitteeFailover.promoteOnLag`, initiated only by the deterministic successor, with cooldown and no-rewind re-seating); `RegionCertificationTest` pins the honest CERTIFIED/PENDING/**SOLO** region status (a committee of one is a population shortfall, never a stalled commit); `TrafficDirectionSplitTest` pins that up/down rates never share a field | 432 | 0 | 0 | 🚧 | 2026-07-25 |
| `neoforge-mod` | host/GUI/control surfaces plus Task 12 persistent attachments, capture bridge, canonical projection, persistent host identity, lifecycle-owned live entity session, self-bootstrapping activation, the continuity halves (`WorldArchiver` share/stop seeding + `packToSpool` re-key blob, `NoderaContinuity` disconnect-rehost, server-dist companion gate, world identity on the session payload), issue #36/33/37 permission/identity/re-key lanes (`OperatorBridge`, `/nodera op|deop`, `CompanionClient.rekey`), the #39 crash-resilience degrade (bind failure never crashes the integrated server), the #43 continuity hardening (`WorldArchiver` continuous streaming cadence + bounded final flush + seeded-version freshness marker — `WorldArchiverStreamingTest`), the `/nodera selftest` in-game command test+benchmark drive (tree walk, per-player capturing execution, persisted JSON/MD reports), the piece-map lane (`WorkerPiecesParser` reads the worker's `NODERA-PIECES` reply, `PieceMapFeed` turns it into the grid and finally installs the `setPieceMapSource` seam that nothing had ever called — `WorkerPiecesParserTest`, `PieceMapFeedTest`), and the #44 vanilla-cancel contract (`VanillaCancelGate` — Minecraft-free, so the rule "cancel vanilla only where the commit is synchronous" is pinned headlessly instead of only observed in a two-client session; `VanillaCancelGateTest`) | 83 | 0 | 0 | 🚧 | 2026-07-25 |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **1361** | **0** | **0** | ✅ | 2026-07-25 |

Line coverage (JaCoCo XML/HTML under `java/<m>/build/reports/jacoco/`): core 82.21% · engine
87.18% · transport 85.32% · storage 79.96% · peer 82.17% · testing 90.91% · neoforge-mod 8.79%.
Minecraft-free modules aggregate to ~83.7%; repository-wide coverage is ~73.3%, not 100%.

Rust workspace (`cd rust && cargo test`) — a separate, equally-required gate (Task 27):

| Crate | Responsibility | Tests | Failures | Status | Last run |
|---|---|---:|---:|:---:|---|
| `nodera-codec` | byte-exact canonical encoding port, Ed25519 verify, frozen mirror through type tag 102/message tag 48, framing, and fixture conformance | 35 | 0 | ✅ | 2026-07-24 |
| `nodera-tracker` | standalone tracker service: signed announce lifecycle + TTL expiry, per-world registry + isolation, sampling, health/countdown, quotas, **TCP + UDP wire** (one datagram per request, shared registry, anti-reflection amplification cap, silent drop of undecodable datagrams) | 60 | 0 | ✅ | 2026-07-24 |
| `nodera-rendezvous` | signed registration/discovery, HMAC relay reservations, metered circuits, punch coordination, and TCP wire | 55 | 0 | ✅ | 2026-07-24 |
| `nodera-app` | companion app (workspace-excluded; `cd rust/nodera-app && cargo test`): piece-bitmap decode matching Java's `BitSet` byte order, bounded/short/undecodable bitmaps, additive-field tolerance, log ring, system sampling; plus the configuration lane — `worker_env` spawn pairs, `Settings → WorkerConfig` golden JSON, `power::should_transfer` truth table, control-socket error surfacing + read timeout, and the `ENFORCEMENT` coverage/live-only-if-confirmed invariants | 56 | 0 | ✅ | 2026-07-25 |

> `simulation/ForbiddenApiTest` is now **re-enabled** (0 skipped): the repo compiles to Java 21
> bytecode (v65) via `--release 21`, so ArchUnit 1.3's bundled ASM parses the classes again. The
> ArchUnit determinism rules (no wall clocks / entropy / IO in `dev.nodera.simulation`) run in CI
> once more, alongside `simulation/DeterminismPropertyTest`.
>
> **Live dev-run evidence (2026-07-22, not a test-count change):** the Task 5a `runs` block landed
> (`runClient`/`runServer`/`runClientJoin` from Gradle, NeoForge pin reconciled 21.1.77 → 21.1.238,
> Nodera project modules joined to the mod definition so FML's module classloader sees
> `dev.nodera.*`, fastutil/slf4j aligned to Minecraft's strict pins, our third-party runtime libs
> — rocksdb/caffeine/zstd/roaring/bouncycastle — on MDG's `additionalRuntimeClasspath`).
> `runServer` boots to `Done (2.97s)` with zero errors and the host lane self-activating (persistent
> identity, host peer on :25566, tracker announce); `runClient` reaches the title screen with the
> companion gate's not-enforced warn path; the scripted `runClientJoin` (`--quickPlayMultiplayer`)
> joins the shared world — `Dev joined the game`, the client's peer dials the host (`[host] peer
> joined`), and with `entity.laneAutoActivate=true` the **Task 12 lane goes live on a real server:
> `entity lane live on 12 region(s) around Dev`**, zero errors (one known one-time activation tick
> stall — L-50). The tracker/rendezvous-path join + gameplay assertions remain L-45/L-50's last exits.
>
> **Live scripted gameplay assertion (2026-07-22, RCON-driven, no GUI input):** on a running
> dedicated server + quick-play joining client with `entity.laneAutoActivate=true` and
> `mobCaptureDimensions=["minecraft:overworld"]`, the Task 12 lane ran end-to-end: activation
> **swept** pre-loaded entities into the lane (a live finding — pre-activation chunk joins were
> never captured), ghost mobs streamed ~10k throttled captures, the solo committee committed
> per-region deltas continuously (v1→v4+ observed), an RCON `summon` shifted the live totals, and
> `/nodera entities` (now fed by `LiveEntityControlProvider` — the entities half of L-31) reported
> **239 entities across 12 delegated regions**. Also confirmed live: with `mobCapture` off, every
> mob-holding region gracefully revokes (acceptance #3) — now logged. Drop→pickup-exactly-once and
> pearl scripted drives remain (L-50).
>
> **Test growth (1298 → 1345; Rust 144 → 154 +10 app) is the discovery/telemetry audit remediation
> (2026-07-24).** An end-to-end audit of the tracker/rendezvous/companion chain found four things
> built but not connected, and each fix is pinned by tests:
>
> * **The rendezvous transport could never choose the direct path.** It advertised only a relay
>   candidate, so `hasDirectCandidate` was false for every peer, `Path.DIRECT` was never in the
>   available set, and 100% of its traffic crossed the relay — the inversion `rendezvous.md` §12.2
>   warns about. It now publishes a host candidate from the direct transport's listen route
>   (`PeerTransport.listenRoute()` joined the seam), dials the peer's best direct candidate, and
>   renews its registration lease at half the TTL instead of silently expiring at five minutes. The
>   joiner is wrapped in the same transport as the host, so relay fallback is no longer one-sided.
> * **Trackers were never asked for peers.** `TrackerClient.query` fed only the archive lane;
>   membership came exclusively from one bootstrap route. `PeerDiscoveryService` now sweeps every
>   tracker and rendezvous for every world and announces each routable peer through the new
>   `PeerRuntime.announceTo` — merged, never arbitrated (`trackers.md` §16/§17).
> * **A shared world's bytes lived only on the machine that shared it.** `WorldReplicationService`
>   runs the Task-21 placement policy over the tracker directory and adopts the worlds this node is
>   deterministically placed for, under a byte budget; `WorldHostingService.seed` advertises them.
>   `NODERA-JOIN` stopped being a no-op that reported success.
> * **The piece map had no data source.** `NoderaMultiplayerScreen.setPieceMapSource` had never been
>   called, so "View pieces" always opened an empty grid. New `NODERA-PIECES` control verb →
>   `WorkerPiecesParser` → `PieceMapFeed`, plus grid scrolling and a "peers sharing" count
>   (`PieceMapView.holders`) distinct from complete seeders.
>
> Also: tracker endpoints are scheme-aware (`tcp://` / `udp://`, bare = TCP) with a real UDP
> datagram surface on both sides, bounded against reflection amplification; `PeerJoin`/`PeerEntry`
> carry a `clientVersion` (membership layout only — the Rust-visible discovery layout stays
> byte-frozen); `PeerTrafficMeter` replaces the STATE peer rows' hardcoded zeros. Two long-standing
> flakes in `SocketPeerTransportAuthTest` were fixed for real (a TCP-reset race on the refusal path,
> and a two-queue handoff where the frame was polled without a timeout).
>
> **Tailwind migration + the configuration lane (2026-07-25).** Two changes, three parallel agents.
>
> The companion app's 1177-line hand-written stylesheet is gone: `ui/src/styles.css` is now 136
> lines of design tokens and document-level base rules, and every component carries its own
> Tailwind v4 utilities. The theming mechanism is unchanged — `@theme inline` re-exports the
> existing custom properties **by reference**, so `bg-surface` compiles to `var(--surface)` and the
> `[data-theme]` attribute still repaints the whole tree. No `dark:` variant is written anywhere and
> `theme.ts` needed no edit. Three constructs were removed rather than translated: the rail's
> `::before` indicator, the switch's `input:checked + ::after` knob, and `.stat.up .stat-value` are
> now ordinary elements driven by React state. Gates: zero class selectors left in the stylesheet,
> and zero occurrences of the `active` class — which previously carried **four different visual
> treatments** disambiguated only by parent selector.
>
> **Settings now actually reach the worker.** Before this, `save_settings` wrote a JSON file and
> applied exactly one thing (auto-start), and `daemon.rs` spawned the worker with *no environment at
> all* — so a user's tracker list had never once reached the JVM. Now: env-at-spawn for the
> identity-shaped values, a new additive `NODERA-CONFIG` verb (base64 payload, because
> `ControlServer` splits on whitespace) for everything adjustable at runtime, and a worker restart
> the app offers **only when it owns the process**. `ContentTransferService` gained volatile serve
> bounds and a `transfersPaused` gate — the single seam every live knob and the battery rules route
> through.
>
> The badge is now computed, not asserted: a control reads "live" **only** if the connected worker
> named its key in the `applied` list of its own reply. An older worker therefore degrades to
> "worker too old" by itself, and the app has no way to claim enforcement that was not confirmed.
> The two structurally impossible controls (`max_connections_per_world`,
> `unlimited_connections_only`) stay in the UI with a *distinct* muted "not supported" badge and the
> worker's own written reason — deliberately not the amber "not enforced yet", which would imply
> they are coming.
>
> ⚠️ **A real cross-agent defect this caught.** The Java and Rust halves were written in parallel
> against the same spec and picked different key names — `network.tracker_endpoints` vs
> `network.default_trackers`, `storage.archive_dir` vs `storage.peer_worlds_dir`,
> `network.serve_max_inflight` vs `network.max_upload_slots_per_world`. Nothing crashed: the keys
> came back under `rejected` as "unknown setting", so three controls would have looked saved and
> done nothing. Java was corrected to the app's settings-document names (that identity is what lets
> the badge match a reply to a control with no translation table), and
> `ConfigVerbIT.everyKeyTheCompanionAppSendsIsOneThisWorkerRecognises` now feeds the app's real
> golden string through the real worker so the two cannot drift silently again.
>
> **Verified live (2026-07-25):** a real `nodera-headless` worker on control 25640, driven with the
> companion's exact default payload — `applied` named 6 keys, `restart_required` named
> `network.port_range` + `storage.peer_worlds_dir`, and the only two rejections were the two
> permanent ones with their documented reasons; zero "unknown setting". Pushing
> `transfers_paused:true` flipped `NODERA-STATE`'s `transfers_paused`, and an upload cap of 65536
> read back as 65536. The app rendered against that worker showing the restart banner in its
> **attach-mode form** (no button — the app must not kill a worker it did not start).

> **PROVEN LIVE (2026-07-24, `scripts/run-tests.sh` — all 8 suites PASS).** Batch
> `run/results/runner/20260724-211542`: continuity · ownership · ownership-follow · churn · pickup ·
> commands · farlands · crash, every one green, clean teardown (no leaked process, no held port).
> The new lanes are visible in the collected worker `STATE` snapshots rather than merely not
> breaking anything — `run/results/e2e-ownership/20260724-211633/state-peer{1,2}.json`:
>
> ```
> peer1  client=NoderaMC 0.1.0  total_chunks=94  avail‰=1000
>   peer route=127.0.0.1:25621 path=direct client=NoderaMC 0.1.0
>        up/s=475 down/s=46  totUp=17837451 totDown=4230
>   world pieces=94/94 bytes=24548067 checksum=e0d88b94c8fcc313 seeding=false
> peer2  client=NoderaMC 0.1.0  total_chunks=68  avail‰=1000
>   peer route=127.0.0.1:25620 path=direct client=NoderaMC 0.1.0
>        up/s=35  down/s=371 totUp=4230     totDown=17837451
> ```
>
> That single pair of rows exercises most of the increment at once: the `clientVersion` field
> crossed a real `PeerJoin`/`MembershipUpdate` between two processes; `route` and `path` are real
> where they used to be `""` and a hardcoded `"direct"`; the rates are live; and the per-peer totals
> **mirror exactly** across the two independent workers (17 837 451 / 4 230 each way), which is the
> assertion that per-peer accounting is attributing bytes to the right peer rather than merely
> counting them. `server.log` also shows `Nodera rendezvous: host registered with [127.0.0.1:25601]`
> — the host candidate lane — and the piece/world metadata (`piece_count`, `pieces_held`,
> `checksum`, `added_at`/`updated_at`) is populated from the real archive.
>
> ⚠️ **Pre-existing, unchanged: the dedicated-server suites' companion never meshes.** In
> `farlands` all three workers report `peers=[] is_gateway=true` — each is its own session of one,
> so the mod's `NODERA-MESH` handoff is not landing on the dedicated-server path (the host-client
> suites, e.g. `ownership`, mesh correctly — hence the peer rows above). Byte-identical to the
> pre-change run `run/results/e2e-farlands/20260724-164414`, so it is **not** a regression from this
> work; it is an open gap in the dedicated-server lane worth its own issue.
>
> ⚠️ **Known environment flake — `SocketPeerTransportAuthTest` under maximum parallelism.** On a
> busy machine `./gradlew check --rerun-tasks` (parallel, all workers) can fail this class with
> `auth handshake timed out`: the transport's 10-second handshake latch is a *production* timeout,
> and every module's tests competing for cores can starve two Ed25519 handshakes past it. This
> reproduces on a clean `HEAD` worktree with none of the above changes, so it is the gate's CPU
> budget, not a regression — deliberately **not** "fixed" by weakening the production timeout.
> `./gradlew check --rerun-tasks --no-parallel --max-workers=2` is green end to end
> (1345 / 0 failures / 0 skipped).
>
> **Test growth (1014 → 1015; tag 53) is the no-host ownership lane (2026-07-23, PASSED LIVE):**
> every connected player now owns + validates its own FOV region set. The client announces its
> peer node (`nodera:node`), the session broadcasts the deterministic plan inputs (`nodera:plan`),
> every member derives the identical plan and activates its primary+validator regions, and the
> joiner's client runs its own `WorkerValidationService` over its peer transport. Actions captured
> on a non-owner forward to the owning player's node (`ActionForward`, tag 53) — only the owner
> proposes; `ActionForwardIT` proves the forwarded quorum commit over the transport. Live (e2e
> run 9, stage S2c): host `entity lane live on 13 region(s) across 2 member node(s)`; joiner
> `client validation lane active on 13 region(s) — this player re-executes and votes for its own
> region set`. Ownership re-plans on join/leave; stale plan broadcasts are ignored client-side.
>
> **Test growth (1000 → 1014 Java; message tags extended to 52) is the world-continuity lane
> (2026-07-23)** — the acceptance "the host disconnects and the world must not die with them".
> `transport` (+2): `WorldManifestQuery`/`WorldManifestAnswer` round-trips + frozen-registry
> updates (tags 51/52, Rust mirror in the same change). `peer` (+10): `WorldArchiveTest` (the
> canonical save-folder archive — byte-exact round trip, insertion-order determinism,
> traversal-proof unpack, save filter, piece-manifest binding) and **`WorldContinuityIT`** — the
> headless rehearsal of the full scripted series over the REAL `nodera-tracker` +
> `nodera-rendezvous` binaries and real-TCP worker nodes driven by real control-socket verbs:
> host worker HOSTs + SEEDs the world archive → tracker lists it with manifest seeders +
> rendezvous returns the host record → the joiner's worker fetches the archive over the P2P piece
> plane byte-exactly (`NODERA-ARCHIVE`) → the host's game endpoint drops → the host's **entire
> worker dies** → the joiner still reproduces the complete world and the unpacked save re-opens.
> The mod halves (share/stop seeding via `WorldArchiver`, `NoderaContinuity` rehost-on-disconnect
> + network-first hostless join, the server-dist companion gate) are proven by the staged live
> acceptance: **`scripts/e2e-continuity.sh` passed all seven stages on 2026-07-23** — real host
> client auto-re-shares on quick-play, real joiner client in-world (two players, two workers,
> tracker + rendezvous), 11.2 MB world archive seeded at share + final flush, host client killed,
> and the joiner **recovered, re-opened, and re-hosted the world in 3 seconds** (fetch from the
> peer network → `saves/` → auto-re-share → game port re-published). The series also caught and
> fixed four live defects: dead `setClientPlayerReady`/`tickGamePublish` wiring (auto-re-shared
> worlds were listed but never joinable), Mojang session auth refusing dev accounts
> (`host.onlineAuth`, default secure), and the piece-plane silent-drop stall
> (`PieceDownloader.retryPending` + bulk serve bounds on the archive lane).
>
> Test growth (997 → 1000 Java) is the **lease-sized quorum fix**: `WorkerValidationService`
> hardcoded the 2-of-3 MVP profile everywhere, so any committee smaller than three — including the
> solo host the decentralized FOV plan produces — timed out and revoked on every batch (latent:
> idle smokes never submitted a batch). Quorum is now a strict majority of the LEASE's committee
> (`MajorityQuorumPolicy.sizedTo`/`requiredForMajority`; transfers use true per-side majorities).
> New: solo-committee commit proof (peer), sizedTo/majority table + solo-accept commit (engine).
>
> Test growth (989 → 997 Java; Rust remains 144, tag mirror extended to 103) is **Task 30c —
> genesis-from-existing-world** (+8, `storage`): `CertifiedWorldGenesisTest` proves the host-signed
> record (certify/verify, deterministic input-order-independent root, root commits to region
> content and to world parameters alone when empty, encode/decode round-trip, tampered-signature
> and foreign-key rejection, null rejection). Live: first share extracts FOV/spawn coarse region
> digests, certifies with the host identity, persists `nodera-genesis.dat` (restart reuse proven:
> identical mtime, zero re-certification); the entity lane now opens against the certified
> manifest (`entity lane live … (genesis caa1f320…)`), and worldId derives from the certified root
> with pre-30c derivation-matching continuity. Also hardened the flaky
> `VoteCollectorTest.isTimedOutUsesWallClock` (born captured before construction — raced under
> CPU load).
>
> Test growth (979 → 989 Java; Rust remains 144) is the **Task 12 live-activation bootstrap +
> reconciliation increment** (+10, all `peer`): `EntityLaneBootstrapTest` (9) — deterministic
> seed-derived genesis manifest pinned to `FlatWorldRules`, byte-stable all-AIR initial snapshots
> matching the canonical `FakeRegion` fixture, epoch-1 leases from the FOV ownership plan
> (closest-node-primary committees, map-order-independence), argument validation — and
> `DurableActionJournalTest.abortPendingCompensatesOnlyReservedEntriesAndKeepsSequencesConsumed`:
> a dirty shutdown's RESERVED actions are compensated at reopen while their sequence numbers stay
> consumed and re-reservation with a different payload still fails. `NoderaHost` gained the
> config-gated (`entity.laneAutoActivate`, default off) `activateEntityLaneFromWorld` wiring;
> `LiveEntityLaneSession.open` now compensates instead of failing closed. Certified genesis
> (Task 9/30c), per-joiner identities, and `runClient` evidence remain open under L-50.
>
> Test growth (944 → 979 Java; Rust remains 144) is the **Task 12 durability/live-composition
> increment** (+35): jqwik 3-replica item fall/merge/despawn, ghost throttling + zombie-door combined
> external delta, loaded-but-playerless isolation, pearl route policy, disjoint six-worker source/target
> committees, Rocks paired-log/stage reopen, a forcibly killed PREPARED process recovering to one
> entity and one paired history (`@Invariant(11)`), durable vote/action/inventory-credit journals, and
> a 1,200-tick mixed item/ghost soak measuring **23,040 B/mob/min and 0 resync bps**. NeoForge
> attachments/capture/projection/session composition compile; Task 5b genesis/region production and
> automated `runClient` pickup/zombie/pearl evidence remain open under L-50.
>
> Test growth (185 → 199) is the adversarial-review remediation: added `CanonicalReaderBoundsTest`
> (allocation-DoS bound), `TypeTagsTest` (tag registry snapshot), `MajorityQuorumPolicy` liveness
> regressions, `RegionCommittee` equals-order, and `ChunkedStreams`/`StreamChunk` validation.
>
> Test growth (199 → 211) is the **P2P session-continuity beta**: `SocketPeerTransport` round-trip +
> disconnect detection (4, `transport-socket`), `GatewayElection` determinism (6) plus the
> loopback failover cycle (1) and the **real-TCP** `SessionContinuityIT` (1, `peer-runtime`), and the
> five appended membership tags in `MessageCodecTypeTagTest`. `SessionContinuityIT` is the executable
> stand-in for the deliverable's manual scenario — two headless player peers stay meshed over a
> direct socket after the bootstrap peer is killed and re-elect the same successor gateway.
>
> Test growth (211 → 243) is **Task 18 — in-game observability & diagnostics HUD**: a new
> Minecraft-free `diagnostics` module (30 tests — `TrafficMeter`/`RateWindow`/`MessageCounters`,
> `ZoneClassifier` with negative coords, `ViewBuilder` Panel/Row/Cell + Semantic, `DiagnosticsCollector`
> rate + health derivation), a `MessageCodec.typeName`/`KNOWN_TAGS` registry snapshot (+1 `protocol`),
> the `MeteredPeerTransport` decorator + per-type `PeerRuntime` counters + the loopback
> `DiagnosticsIT` (+1 `peer-runtime` — asserts real tx/rx bytes+frames, `SessionKeepAlive` in the
> per-type breakdown, and correct member/gateway/epoch). The `Palette` Semantic→colour totality is
> enforced at compile time by the exhaustive enum `switch`, not a runtime test.
>
> **Test growth (746 → 773 Java) is Task 33 — live worker data + world identity/authorship + P2P
> permissions** (+27). `core` (+5): `WorldRole` + tags 92/93 (registry snapshot). `storage-api` (+15):
> `WorldIdentityTest` (author-signed, derived-unique `worldId`, tamper-reject, author-only re-sign),
> `WorldPermissionGrantTest` (signed/versioned grant round-trip + tamper), `WorldPermissionsTest` (the
> authenticated evaluator — author=OWNER, granter-authority check, newer-supersedes, author-can't-be-
> demoted, wrong-world reject). `peer-runtime` (+1): `ControlServerTest` STATE/IDENTITY/HOST dispatch
> over the v2 protocol. `neoforge-mod` (+4): `NoderaWorldStoreTest` (the per-world `nodera-world.dat`
> round-trip / shared flag / corrupt-safe read). Verified live outside the gate: the worker answers
> `NODERA-STATE` (real bytes/peers/worlds JSON), `NODERA-IDENTITY`, `NODERA-HOST`, and mints a signed
> `WorldIdentity` via `NODERA-WORLDID`. Also fixes the multiplayer-tab bug ("No trackers/rendezvous
> configured" despite correct config — the suppliers were never wired; `MultiplayerStatusFeed` now
> feeds them from the config with live TCP reachability). The live lane (world-list mixin, committee
> validation over the worker mesh, grant gossip, password network propagation, worker seeding) is
> documented in `docs/old/Task.33.md` (now Task 5f/6b).
>
> Test growth (744 → 746 Java) is **Task 32 — the peer worker + control endpoint** (+2). The new
> `nodera-headless` module is the always-on peer worker (`HeadlessPeerMain`): a Minecraft-free `main`
> that boots a `PeerRuntime` over a real socket, holds a persistent identity, and serves the loopback
> `ControlServer` the mod probes. `peer-runtime` gains `control/ControlProtocol` (single source of
> truth for the wire, mirrored by the mod's `CompanionProtocol` and the Rust app) + `ControlServer`
> with `ControlServerTest` (+2 — probe → `NODERA-OK`, and a bad connection never takes the server
> down). Verified end-to-end outside the gate: the worker installDist boots, becomes gateway, and
> answers `NODERA-PROBE 1` with `NODERA-OK 1 0.1.0-SNAPSHOT`. `scripts/dev.sh` now builds + runs the
> worker (control-probe health check) alongside tracker + rendezvous, with `--with-app` launching the
> Tauri companion in attach mode; the mod's `companion.required` gate now defaults **ON** (Minecraft
> refuses to launch if the worker is not answering). Worker↔mod host/join delegation (control verbs)
> + the worker's live telemetry pump remain the live lane.
>
> Test growth (712 → 744 Java) is **Task 31 GUI redesign + Task 32 companion app** (+32). `diagnostics`
> (+24): `PublicWorldBadgeViewTest` (31b — shared-world "● N online" badge, screen summary, no badge
> when unshared), `PieceMapViewTest` (31d — the torrent-chunk grid model: index-ordered cells, held
> permille/counts, held=green/locked=critical policy, aggregates line), `TrackerStatusViewTest` +
> `RendezvousStatusViewTest` (31c — per-endpoint online/registered rows, path labels, pure-integer
> byte/ack formatting). `neoforge-mod` (+8): `CompanionGateTest` (Task 32 — the presence gate: absent
> ⇒ actionable error naming the install URL + throws when enforced, a throwing probe reads as absent,
> matching protocol proceeds, older daemon ⇒ "update the app" / newer ⇒ "update the mod", `host:port`
> parsing, and a real loopback `ServerSocket` answering `NODERA-PROBE` ⇒ RUNNING). The GUI screens/
> widgets themselves (`NoderaMultiplayerScreen` tabs, `PieceMapWidget`, `SelectWorldScreenAddon`,
> "Open to Nodera") compile against NeoForge 21.1.77, no mixin; the `runClient` GUI pass is deferred
> (L-45/L-46). The Tauri `rust/nodera-app` scaffold (backend + React dashboard + tray + autostart) is
> EXCLUDED from the `cargo test` gate (Tauri native deps); its headless-peer jar + live metrics ride
> the live lane (L-47). Companion enforcement (`companion.required`) defaults OFF until the app ships.
>
> Test growth (704 → 712 Java) is **Task 30 — decentralization (first increment)**: `neoforge-mod`
> `ShareOptionsTest` (+8) pins the pause-menu "Share" value type — a non-blank password turns on Task
> 23 content encryption while an empty one is plaintext, the dedicated/player defaults, immutable
> copy-with helpers, positive-replication validation, equality over every field, and the discipline
> that the password never appears in `toString`. The increment also removes the `Dist.DEDICATED_SERVER`
> gate (an integrated server now runs the same host lane, waiting for "Share"), renames `dedicated/`→
> `server/`, makes `NoderaPeerService` role-driven (`startHost`/`hostRoute`/`isHosting`/`stopHosting`),
> adds the `client/share` `PauseScreenShareAddon`/`ShareWorldScreen` + `common/NoderaHost`, and
> retires the dedicated-server launcher in `scripts/dev.sh` — all compile-clean against NeoForge
> 21.1.77. Genesis-from-existing-world, the real re-manifest on password change, live
> `RendezvousPeerTransport`/encryption, and the `runClient` GUI pass remain the deferred live lane.
>
> Test growth (688 → 704 Java, 82 → 144 Rust) is **Task 29 — the rendezvous + relay service**: the
> Java `transport-rendezvous` module (+16 — `TransportSelector` path policy, `EndToEndCipher`
> X25519+AES-GCM loopback + identity-binding + tamper-reject, `CandidateDialer`,
> `HolePunchCoordinator`, and `RendezvousRelayIT` bridging two relay-only peers through the **real
> `nodera-rendezvous` binary**), the Rust `nodera-rendezvous` crate (+55), and the extended
> `nodera-codec` rendezvous family (+7, byte-exact vs. Java golden fixtures). L-23 + L-27 RETIRED.
>
> Prior growth (670 → 688) was **Task 12a — the entity-lane core foundation** (+18, `core`).
> `FixedVec3Test` pins Q32.32 fixed-point arithmetic (ONE = 2³², pure-integer add/subtract,
> negative block-part recovery, canonical round-trip, and same-bits-same-encode determinism — the
> property that lets entity position live in the root). `EntityLaneTypesTest` pins
> `NetworkEntityId.allocate` as a pure collision-free function (same region/version/seq ⇒ identical
> id on every replica, never a random UUID; distinct seq/region ⇒ distinct id) plus
> `PersistedEntityState` round-trip over both `EntityKind`s and the despawn/tick boundary.
> `EntityActionsTest` round-trips `DropItemAction`/`PickupItemAction` through the polymorphic
> `GameAction.decode` dispatch (no tag collision across all four permits) and rejects bad inputs.
> `EntityEventsTest` round-trips the three entity-lifecycle `RegionEvent`s through
> `RegionEvent.decodeEvent` alongside `BlockChangedEvent`. The simulation half (`EntityStore` in the
> region root, item physics, `mobCapture`, 12c transfer) and the NeoForge capture bridge remain
> deferred; the MVP `FlatWorldRules` rejects item actions as `UNSUPPORTED_ACTION`.
>
> Test growth (652 → 670) is **Task 9 — committee-change certification + capability-weighted
> gateway election** (+18). `core` (+6): `CommitteeChangeCertificateTest` pins the canonical
> round-trip, the exactly-one-epoch rule, and approval verification against the OLD committee's
> keys — the property that stops any single party (server included) rotating members: quorum of
> old-committee approvals verifies, an outsider's valid signature does not count, a member entry
> carrying someone else's signature fails, and a duplicated approver counts once. `peer-runtime`
> (+12): `CommitteeManagerTest` — a change needs the old committee's quorum (3-of-4) of distinct
> old-member approvals, a lost primary is replaced under a bumped epoch with the population intact,
> a too-small population degrades loudly to a 2-of-3 committee, three survivors cannot staff a
> second loss, a stale link fails chain verification, and a proposal must step exactly one epoch;
> `GatewayElectionTest` (+4) — `capabilityWeight` is bounded pure-integer math (cores + GiB memory
> + inverse latency + reliability, clamped), the most-capable peer wins on every epoch regardless
> of order (rendezvous only spreads duty among equal-weight peers), equal-weight peers still rotate
> across epochs, and a bootstrap peer still outranks a more capable player while alive. Live manager
> wiring + new-peer forward sync remain deferred.
>
> Test growth (646 → 652) is **Task 26 — the multiplayer view model** (+6, `diagnostics`).
> `TorrentWorldListViewTest` is acceptance #1: a tracker entry renders name/players/chunks/
> reliability/health cells with the countdown cell present only while the 24 h clock runs;
> `WorldHealth` maps to the three NEW dedicated `Semantic` world-health values (a lost-data world
> colours red via `WORLD_DEGRADED`, dead worlds gray — never the session `DEGRADED`, whose yellow
> the Task 18 HUD keeps); search filters case-insensitively with blank-matches-all; panel order is
> deterministic regardless of input order; reliability formatting is clamped pure-integer math.
> The mod half (`client/multiplayer` screens + `Palette` world-health rows + `TrackerDataSource`)
> compiles against NeoForge 21.1.77 with `nodera.mixins.json` still empty; the `runClient` GUI
> pass, live tracker feed, and create-world pipeline remain deferred, so L-43 is RETIRING.
>
> Test growth (633 → 646) is **Task 9 — the RocksDB archival tier** (+13). The new
> `storage-rocksdb` module (+10): `RocksWorldStoreTest` proves seam parity with the in-memory
> event-sourced store ACROSS close/reopen — genesis persisted and a foreign genesis rejected
> ("different world"), the event log's recovered head feeding append validation (gap and
> broken-chain appends still throw after reopen, the true-head append lands), per-region log
> isolation, checkpoint strictly-greater ordering with latest/at/all surviving reopen, and
> content-addressed certificate idempotency. `FsContentStoreTest` pins dedup, count recovery, and
> the acceptance-#6 corrupt-blob rejection (a tampered file throws on read, never returns bytes).
> `RocksCrashRecoveryIT` is the crash-consistency proof: a child JVM appending chained events in a
> tight storm is `destroyForcibly`'d, and the reopened store recovers a clean prefix — contiguous
> ids from 0, unbroken `prevRoot→resultingRoot` chain, head equal to the tail event — and accepts
> the next chained append. `storage-api` (+3) pins the canonical round-trips of the new
> `ContentId`/`Checkpoint`/`GenesisManifest` encodings (appended TypeTags 81–83, incl. the
> lastEventId = -1 genesis sentinel and negative world seeds). Live forward sync and
> committee-change certificates remain deferred.
>
> Test growth (605 → 633) is **Task 11 — world-interference control, the headless half** (+28).
> `core` (+4) adds `ServerAuthorityCertificate` (reserved tag 54): signed-portion strict-prefix,
> round-trip over every reason ordinal, a real Ed25519 verify with tamper rejection, and the
> version-advance guard. `protocol` (+1) appends `ExternalDelta` as wire tag 32 (registry pins
> updated; clients apply it to their replica WITHOUT voting after certificate verification) and
> proves its field round-trip. `coordinator` (+23): `MutationGuardTest` pins the choke-point
> classification (vanilla lane untouched, applier scope PASS with the sanity counter, STRICT
> block, CONVERT record with the innermost source marker); `InterferenceBufferTest` pins
> coalescing (first-prev/last-new per position — a converted delta can never carry two CAS guards
> for one block; a write restoring the committed state vanishes); `InterferenceStatsTest` pins the
> tick-driven window (no wall clock); `InterferenceCommitterTest` is the acceptance flow — a
> scripted foreign write CONVERTs into a certified `EXTERNAL_MUTATION` delta whose applied form
> re-extracts to the live world root on a replica, STRICT leaves the world bit-identical, and
> interference during VOTING is held then committed after the decision so a replica applies batch
> + external delta with zero CAS aborts (interference `STALE_BASE` impossible by construction);
> `DelegabilityPolicyTest` (+4) evaluates the full Task-11 reason set incl. the strictly-above
> `INTERFERENCE_REVOKE_RATE` threshold; `DelegabilityMonitorTest` proves immediate revoke, restore
> only after a full clean `DELEGABILITY_COOLDOWN_TICKS`, exactly-one-revoke under oscillation (no
> flapping), storm-revoke/quiet-restore, and the neighbor-ring demotion that makes the piston
> boundary-bleed setup impossible. The three mixins, `ChunkTicketService`, `FakePlayerDetector`,
> and live acceptance remain in the NeoForge lane.
>
> Test growth (576 → 605) is **Task 25 — tick-lag / TPS handoff** (+29). `protocol` (+8)
> upgrades `SessionKeepAlive` tag 23 to canonical per-region v2 progress while decoding v1 as empty.
> `diagnostics` (+10) adds integer-EMA validator/region `TickSkewMeter` and injected-time
> commit-throughput `TpsMeter`. `peer-runtime` (+5) adds `TickSync`, assignment-gated advisory
> heartbeat progress, explicit locally certified network references, and loopback propagation.
> `coordinator` (+5) adds strict-greater sustained-skew `LagHandoffPolicy`, assignment resets,
> cooldown, and a one-shot below-floor reliability penalty. `committee` (+1) adds guarded stale-safe
> failover and `LagHandoffIT`: only the lagging region moves, epoch bumps once, its new primary commits,
> the neighbouring state remains unchanged, and the certified event replays to the committed root.
> Live commit feeds, policy scheduling, HUD exposure, and NeoForge runtime construction remain
> deferred, so L-42 is RETIRING.
>
> Test growth (556 → 576) is **Task 24 — active-player stream + crash safety** (+20).
> `distribution` (+10) adds `ActivePlayerStream` (latest-per-region coalescing, cross-manifest hash
> reuse, physical receipt/activation acknowledgements, explicit bandwidth windows and oversize-piece
> progress) plus deadline-bound `EmergencyFlush`; their ITs prove ten near-current versions across
> five physical holders, bounded convergence/retry, verified replacement storage, replication-first
> priority, and one shared timeout against an unreachable target. `peer-runtime` (+6) fixes
> rendezvous placement to select highest scores, makes archive repair record only after destination
> storage succeeds, and adds `PeerShutdownHook` ordering/idempotency/deadline proofs. `committee`
> (+3) adds vote-before-sign `VotePersistence` and `CrashRecoveryIT`: a forcibly destroyed primary
> runs no shutdown hook, surviving quorum stores retain the root/certificate, real physical repair
> restores snapshot ×5, and certified replay/restart reaches the committed root. `storage-client`
> (+1) proves eviction callbacks run outside the store monitor. Live NeoForge commit/content/lifecycle
> adapters remain deferred, so L-40 is RETIRING; separate-OS-sidecar L-41 stays OPEN.
>
> Test growth (523 → 556) is **Task 23 — per-world content encryption** (+33). `core` (+14)
> adds JDK-only `ContentKey`, `PasswordKeyDerivation`, bounded PBKDF2-HMAC-SHA256, and AES-GCM-256
> with domain-separated deterministic 96-bit nonces; tests cover convergence, 4,096 cross-manifest
> nonce tuples, wrong-key/tamper rejection, input/cost bounds, and append-only type tag 80.
> `distribution` (+19) adds pinned BouncyCastle Argon2id (correct UTF-8 including surrogate pairs,
> temporary-secret clearing, bounded 16–256 MiB / 2–10 passes / 1–16 lanes), `EncryptedPiece`, and
> `EncryptedRegion`. `EncryptedDistributionIT` obtains ciphertext from three keyless partial seeders,
> derives the join key from plaintext manifest KDF metadata, rejects wrong password/tamper, and
> recovers bytes that decode to the engine snapshot and hash to its plaintext `StateRoot`. Manifests
> intentionally reveal geometry/KDF metadata; password loss has no escrow/recovery. Live opt-in
> create/join wiring and attempt throttling remain in the NeoForge lane, so L-39 is RETIRING.
>
> Test growth (499 → 523) is **Task 22 — multi-factor reliability + client quotas + 24-h retention**
> (+24). `coordinator` `ReliabilityScorerTest` (+8): the blend is pure integer arithmetic (same
> inputs ⇒ same score, pinned to 7850 bps for a known factor set under default weights), slash-to-0
> on a correctness of 0, the 9500-bps assignment floor, and the no-single-factor-dominates property
> (a high-correctness/poor-connectivity node scores below its inverse under a connectivity-heavy
> config, and the reverse under a correctness-heavy one); offline decay converges to the 0.5 target;
> factors round-trip canonically. New `storage-client` module `BoundedClientWorldStoreTest` (+8):
> the store honours the byte budget, evicts oldest-cold-first, NEVER evicts a pinned assigned-region
> current blob, refuses (QuotaException) when only pinned content remains, signals repair via the
> eviction listener, and is idempotent on duplicate bytes. `peer-runtime/archival`
> `RetentionPolicyTest` (+8): zero seeders start a visible countdown, a returning seeder cancels it,
> expiry drops the world, and coordination adopts the earliest announced deadline so the network
> counts down in lockstep. Mod-side wiring (feeding PeerLink/heartbeat/seed-share into the scorer,
> the client runtime over the bounded store, the tracker surfacing the retention countdown) is
> deferred with the NeoForge lane.

> Test growth (473 → 499) is **Task 21 — archive placement, replication, repair** (+26, all
> `peer-runtime/archival`). `RendezvousArchivePolicyTest` is the placement property (acceptance #1):
> `expectedHolders` is a pure function agreed on by every peer regardless of input order, holds R
> distinct partial peers, the `FULL_ARCHIVE` host is always in the set but does NOT count toward R
> (so losing it still leaves R replicas — acceptance #4), and checkpoint/genesis replicate to
> everyone. `SeedFloorPolicyTest` pins the floor `min(25%, R/N)` and cap `max(5%, 2·R/N)` at the
> N=20 and N=200 crossover points, proves the floor is always below the cap, and that the host is
> exempt even at 100% (acceptance #3). `ArchiveAuditTaskTest` pins the expected-vs-inventory diff:
> a promoted peer (ranked into the expected set after a higher-ranked peer died) becomes a target
> missing every piece, a satisfied holder is never a target. `ArchiveRepairIT` is acceptance #2 —
> kill holders of a ×5 manifest, the audit detects the loss, repair re-replicates back to the
> factor within budget, and every repaired piece still verifies against the manifest (no data
> loss); plus the bounded/progressive path (one piece per tick converges) and the corrupt-seeder
> path (a rejecting verifier records nothing). `ArchiveManagerTest` pins the per-peer reconcile
> (never evicts assigned-region current state; evicts over-cap unassigned content; host never
> evicts). Mod-side repair coordinator and live churn soak deferred with the NeoForge lane.

> Test growth (413 → 473) is **Task 20 — tracker, peer directory, archive inventory,
> multi-bootstrap** (+60: +49 `peer-runtime`, +11 `core`). The `peer-runtime/discovery` package:
> `PeerDirectoryTest` (per-world liveness with deterministic staleness, id-sorted online lists,
> least-recently-seen eviction), `ArchiveInventoryTest` (piece-level holdings merge, the
> later-advertisement-replaces-earlier-claim semantics, empty-bitmap-as-retraction, the 100k-
> manifest bound), `TrackerServiceTest` (peers+seeders+counts from live state, the safety-critical
> health rule that zero-seeders-inside-the-retention-window is DEGRADED-not-DEAD, reliability
> clamping to basis points), `CachedPeerStoreTest` + `InvitationCodecTest` + `BootstrapClientTest`
> (most-recently-seen ordering, atomic persistence round-trip, signature forgery rejection, 3-
> mechanism join with freshness-ordered de-duplication), `PersistentIdentityStoreTest` (load-or-
> generate keeps the same `NodeId` across restarts, restored identity still signs+verifies).
> `TrackerIT` is acceptance #1 — a 5-peer mesh across 2 worlds answers a per-world query with
> exactly that world's peers and their held manifests, counts/reliability matching live state.
> `MultiBootstrapIT` is acceptance #2 — the original bootstrap offline, a new client reaches the
> mesh via each of the three mechanisms in isolation. In `core`: `WorldHealthTest` (frozen
> ordinals, round-trip, bad-ordinal/tag rejection), `PersistedNodeIdentityTest` (generate→restore
> same id+keys, restored identity signs+verifies, `toString` redacts the private key),
> `NodeCapabilitiesTest` roles round-trip canonicalisation. Mod-side tracker wiring and live-mesh
> acceptance are deferred with the NeoForge lane.

> Test growth (364 → 413) is **Task 19 — the torrent distribution data plane** (+49, new
> Minecraft-free `distribution` module). `PieceManifestTest` pins the canonical round-trip and the
> derived `manifestRoot`, and proves the root commits piece *position and length* — swapping two
> pieces' hashes while keeping the layout changes the root, so a reordered manifest is detectable;
> the `encrypted`/`keyMaterial` slots reserved for Task 23 round-trip today, so shipping encryption
> needs no version bump. `PieceSplitterTest` pins the record-boundary rule (a cut never lands
> mid-record, an over-target record stays whole) and the load-bearing invariant that the sliced blob
> is **byte-for-byte** `RegionSnapshot.encode` — which is why `manifestRoot`'s sibling `regionRoot`
> equals the committee's `StateRoot` with no extra agreement. `PieceSelectorTest` is the determinism
> property (acceptance #5): two selectors given the same `(manifest, holderSet)` emit the identical
> order regardless of holder-map iteration order, ties break by `StableHash` rather than index (so
> concurrent fetchers do not all serialise on piece 0 of one seeder), and holder choice is a
> reproducible rendezvous that spreads pieces across seeders. `PieceReassemblerTest` and
> `ChunkLockMapTest` pin the safety defaults: a rejected piece leaves the reassembler bit-identical,
> right-bytes-wrong-index fails, a corrupt *local cache* is refused exactly like corrupt network
> bytes, an untracked region reads as **locked** (fail-closed), and a superseding manifest re-locks
> rather than showing stale sections. `PieceDownloaderTest` covers the swarm state machine —
> in-flight bound, racing holders with the loser's duplicate dropped (not counted as a rejection),
> retry-away-from-the-liar, lost-holder re-selection, and piece-level resume that never re-requests
> what is already on disk. `DistributionIT` is the acceptance proof: a region split into 13 pieces is
> reassembled over a real loopback transport from 3 seeders **each holding under 40%**, and the
> assembled blob hashes to the root the *engine* computed — a swarm data plane that required no new
> trust from the consensus layer. It also proves corrupt-seeder rejection never unlocks a chunk,
> resume-after-disconnect, and the seeder's bandwidth bound. Mod-side consumption of `ChunkLockMap`
> (renderer / `WorldMutationApplier`) is deferred with the NeoForge lane.

> Test growth (348 → 364) is **Task 9 — Phase 5 event-sourced storage** (+16): `storage-api` filled out
> (+4 — `ContentId`/`Compression`/`GenesisManifest` value types + the `WorldStore` seam and its
> content/event/checkpoint/certificate interfaces; replaces the placeholder smoke test) and a new
> in-memory `storage-eventsourced` impl (+13 — content-addressed dedup + integrity, append-only event
> logs that reject non-monotonic ids and broken `prevRoot→resultingRoot` chains, checkpoint version
> ordering, content-addressed certificates). `EventReplayerTest` proves the certified-chain walk: a
> fully-certified chain replays to the final root, an uncertified suffix stops replay at the last
> certified root (forward-only sync, Invariant 8), a chain break or a certificate that contradicts its
> event is a hard error (tampered log), and the store's own append validation is the primary
> Invariant-3 gate. `PeerSyncFlowTest` proves a new peer syncs from genesis with no checkpoint, a
> returning peer resumes forward from a checkpoint, and an uncertified network tail never advances
> the peer. The RocksDB archival tier and live multi-seeder fetch remain deferred.
>
> Test growth (338 → 348) is **Task 8 — Phase 4 server-fallback + cross-region router** (+10, new
> Minecraft-free `fallback` module): `CrossRegionRouterTest` (a cross-region action always falls back
> even when the region is delegated; unassigned/disputed/collapsed regions route to the server lane;
> a healthy delegated region goes to the committee), `FallbackExecutorTest` (the server lane commits
> an unassigned batch to the engine's own root), `SoakMetricsTest` (ratio + per-reason counts, the
> strict &gt;90% threshold), and `FallbackRoutingIT` — a spread-out session (190 committee / 10
> server) clears the Phase 4 exit criterion, and an unassigned batch still commits correctly on the
> server lane. Real vanilla cross-region execution and the live synthetic-client soak remain deferred.
>
> Test growth (326 → 338) is **Task 7 — Phase 3 committee validation, the MVP gate** (+12, new
> Minecraft-free `committee` module): `CommitteeSessionTest` (honest 2-of-3 quorum commits and the
> committed world re-extracts to the engine's own root), `ByzantineWorkerTest` (a lone lying
> validator lands in its own root group and is excluded + penalised; a lying primary is out-voted by
> the honest validators; two colluding liars DO commit a wrong root — the case only the spot-check
> auditor catches; an equivocating voter is slashed to zero), `SpotCheckAuditorTest` (deterministic
> selective sampling; audit agrees on an honest commit and disputes a colluded wrong root),
> `CommitteeFailoverTest` (primary loss promotes a validator under a bumped epoch; no-survivors
> revokes), and `CommitteeMvpIT` — the MVP milestone: quorum commit, then primary disconnect →
> validator promoted under epoch+1 → the surviving committee keeps committing. NeoForge wiring + the
> live 3-client acceptance remain deferred (Phase 0 pattern).
>
> Test growth (277 → 326) is **Task 6 — Phase 2 coordinator** (+49): a new Minecraft-free
> `coordinator` module (48 tests) plus a `shadow-validation` hardening (+1). The coordinator suite:
> `ReliabilityLedgerTest` (EMA maths, slash, floor, canonical persistence round-trip),
> `RendezvousPlacementTest` (deterministic score, higher-tier-wins, within-tier spread),
> `RegionAllocatorTest` (distinct committee, too-few→empty, reassignment excludes the failed node,
> reliability floor, primary load cap), `DelegabilityPolicyTest` (palette/chunks/quorum/guard
> reasons), `LeaseManagerTest` (epoch 0 → bump on reassign, renew keeps epoch, revoke bumps,
> stale-epoch, expiry, restore-never-reuses-epoch), `HeartbeatMonitorTest` (loss after timeout,
> deterministic order), `RegionPipelineTest` (happy path + illegal transitions + mismatch/timeout/
> stale routing + revoke-from-any), `WorldMutationApplierTest` (commit re-extracts to the engine
> root; a bad guard in the MIDDLE applies nothing — atomicity), `ServerVerifierTest` (MATCH/MISMATCH,
> staleness), `PersistedCoordinatorStateTest` (epochs+reliability round-trip, byte-stable encoding),
> and `CoordinatorIT` — the full delegate→propose→verify→commit pipeline over the `InMemoryWorldView`
> seam, plus forced-mismatch reject (world provably uncorrupted), stale-epoch drop, and primary-death
> reassignment under a bumped epoch. Two `core` TypeTags appended (RELIABILITY_LEDGER, COORDINATOR_STATE)
> with the golden snapshot updated; `VerificationOutcome` + `RegionPlacementPolicy` added. The
> NeoForge event capture/cancel, `ServerLevel`-backed applier, and live 2-client acceptance remain
> deferred (Phase 0 pattern).
>
> Test growth (253 → 277) is **Task 5 — Phase 1 shadow validation** (+24, new Minecraft-free
> `shadow-validation` module): `WorkerRuntimeTest` (INACTIVE/ACTIVE/STOPPED lifecycle, off-thread
> determinism, two-runtime root equality), `SnapshotDeltaApplierTest` (applied delta re-hashes to the
> engine's own root — the delta-transports-the-transition invariant — plus CAS drift + version-mismatch
> guards), `ReplicaStoreTest` (LRU eviction bound), `ShadowWorkerTest` (Computed vs Resync on
> missing/stale replica), `ServerRecomputeTest` (a deliberately flaky engine trips the intra-JVM
> `NondeterminismException` self-check), `DivergenceTracker`/`InterferenceProbe` tests, and the
> headless `ShadowValidationIT` — 3 worker runtimes × 250 random place/break batches with **zero
> divergence**, and a lying worker (corrupted root) caught + its region re-snapshotted. This is the
> executable stand-in for Task 5's manual multi-client soak; the NeoForge capture mixins, live
> `runClient` soak, and bandwidth/interference numbers remain deferred (Phase 0 pattern).
>
> Test growth (243 → 253) is the **Task 18 adversarial-review remediation**: a 6-dimension
> find→verify review workflow (23 agents, 17 confirmed findings, 0 refuted) surfaced a blocker —
> `ViewBuilder.formatBytes`/`formatRate` threw `StringIndexOutOfBoundsException` for any byte value
> in [1024, 2047] (the tab/boss-bar/net HUD hot path) — plus the net bar being unreachable, a stale
> tab list after `/nodera hud tab off`, and dead code. All 17 were fixed; new tests
> (`MeteredPeerTransportTest`, `ViewBuilder` `formatRate`/`serverPanel`/populated `regions`+`entities`,
> `ZoneClassifier`↔`RegionBounds` consistency, deterministic per-type TX in `DiagnosticsIT`) close
> the coverage gaps that hid the blocker.

<!-- AI-AGENT-INSTRUCTION: When a module goes red, flip its emoji to ❌, open a `bug` issue, and do
     NOT commit the regression. When fixed, flip back to ✅ and close the issue with `fixes #N`. -->
