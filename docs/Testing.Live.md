# Testing.Live.md — the live two-player test procedures

<!-- AI-AGENT-INSTRUCTION: This file documents the SCRIPTED live acceptance series for the
     decentralized (no-host) system: how to run each test, what each stage asserts, and how to
     read the logs. Update it whenever a script gains/changes a stage. The headless rehearsals
     (WorldContinuityIT, ActionForwardIT, WorkerQuorumValidationIT) cover the same flows
     Minecraft-free and run in `./gradlew check`. -->

All live tests drive **two real NeoForge clients** (each with its **own peer worker**) against the
standalone `nodera-tracker` + `nodera-rendezvous` services — the full decentralized stack on one
machine. They need a GUI session (the clients render on your display), ~6 GB free RAM, and the
Rust toolchain.

| Script | What it proves | Duration |
|---|---|---|
| `scripts/e2e-continuity.sh` | World survives the host: share → join → archive on the network → host killed → player B recovers + re-hosts | ~8 min |
| `scripts/e2e-ownership.sh` | **Test 1** — per-player region ownership: each player validates its own FOV regions; the drive teleports players across ownership boundaries with an enter/leave evidence log; player A leaves and **re-joins over the network** | ~10 min |
| `scripts/e2e-churn.sh` | **Test 2** — join/leave churn ×5 with random dwell; log audit proves no errors accumulate | ~12 min |
| `scripts/play-two.sh` | Not a test — launches the full stack + two interactive clients for hands-on play (you drive the GUI yourself) | manual |

## Running

```bash
# one-time (or after code changes): full build happens inside the scripts
scripts/e2e-continuity.sh          # also BAKES the shared world the other tests reuse
scripts/e2e-ownership.sh --no-build
scripts/e2e-churn.sh    --no-build [--cycles 5]
scripts/play-two.sh     --no-build # two windows: HostDev + JoinerDev
```

Order matters once: `e2e-continuity.sh` bakes `java/neoforge-mod/run-host/saves/NoderaE2E`
(a genuinely shared world with a signed `nodera-world.dat` + certified genesis); the other tests
reuse it. Every script checks its ports (25599–25601, 25610/25611, 25620/25621) and refuses to
run over another stack (`scripts/dev.sh` uses the same ports — stop it first).

**How Minecraft is launched** — no GUI automation anywhere: the scripts use quick play.
`runClientHost` boots straight into the shared world (`--quickPlaySingleplayer NoderaE2E`; the
Task 33 auto-re-share puts it on the network), `runClientJoin` boots straight into the session
(`--quickPlayMultiplayer 127.0.0.1:25599`), `runClientRejoin` is player A returning as a network
client. Distinct `--username`s (HostDev/JoinerDev) prevent the duplicate-login kick. The staged
world's `serverconfig/nodera-server.toml` pins `host.gamePort=25599`, disables Mojang session auth
(`host.onlineAuth=false` — offline dev accounts), and enables `entity.laneAutoActivate`.

## Test 1 — region ownership (`e2e-ownership.sh`)

Stages (each `PASS`/`FAIL` line names its stage):

- **O1** — player A shares; player B joins through the tracker; the plan spans both nodes
  (`entity lane live on N region(s) across 2 member node(s)` in `client-host.log`) and player B's
  client runs its own validation lane (`client validation lane active on N region(s)` in
  `client-joiner.log`).
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

## Reading the logs

Everything lands under `run/logs/<test>/`:

| File | What to look for |
|---|---|
| `client-host.log` / `client-joiner.log` | `Nodera: sharing world`, `game server open for joiners`, `entity lane live … member node(s)`, `client validation lane active`, `DRIVE`/`REGION:` lines, `Nodera continuity: host connection lost` → `restored to saves/` |
| `worker-host.log` / `worker-joiner.log` | `Now hosting world`, `Seeding world archive vN — P piece(s)`, `Fetched world archive` |
| `tracker.log` / `rendezvous.log` | announce acks, signed-record registrations |
| in-game (manual runs) | `/nodera regions` and `/nodera entities` panels; the `NODERA-STATE` control verb (port 25610/25611) returns live JSON: `maintained_pieces`, `connected_worlds`, `validation` counters |

Quick greps:

```bash
grep -a "REGION: \|DRIVE " run/logs/e2e-ownership/client-host.log   # ownership evidence
grep -a "validation lane active" run/logs/e2e-ownership/client-joiner.log
grep -aE "ERROR|FATAL" run/logs/e2e-churn/*.log                     # should be empty/benign
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

- Player B's survival of a host exit is a **recovery**, not yet seamless. Since 2026-07-23 the
  seam is minimized: the joiner's worker **prefetches the world archive while the session is
  healthy** (hot standby — no download phase on loss) and the transition screen is a quiet
  vanilla-style "Migrating world…" beat, so what remains visible is the world-open itself
  (~1–3 s). Removing that last seam is exactly Task 16's local-replica view — each client
  simulating its own regions so a departed peer changes nothing on screen (design prior art:
  the ownership-takeover model in `docs/minecraft/MultiPaper/`).
- The validated event families are the entity/item lane; blocks/fluids/mobs/etc. join per
  Tasks 13–16.
- Action envelopes are signed by one interim session key (declared in the plan payload);
  per-player signing is staged.
