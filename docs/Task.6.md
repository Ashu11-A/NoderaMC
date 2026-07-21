# Task 6 — Peer Worker (module: `nodera-headless` + `peer-runtime/control`)

> **Module-unification note (issue #30, 2026-07-21):** the fine-grained Gradle modules this file
> mentions were merged into the seven unified modules — `core` · `engine` · `transport` ·
> `storage` · `peer` · `testing` · `neoforge-mod` — with **packages unchanged**. Read old module
> names as packages inside the new modules (mapping: [`Task.0.md`](Task.0.md) §5).

**Module:** the required always-on headless Java peer — a player's node when Minecraft is
closed ·
**Depends on:** Task 2 (it *is* the `PeerRuntime` run as a process), Task 3/4 (services it
dials), Task 1 (validation stack for 6d), Task 5 (5e genesis production for 6c) ·
**Consumed by:** Task 5 (5g gate probes it; 5d/5f read it), Task 7 (the app supervises it)

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 6a | Worker boots + presence endpoint: `HeadlessPeerMain` runs a `PeerRuntime` with persistent identity; loopback `ControlServer` answers `NODERA-PROBE` | ✅ (verified live: boots, becomes gateway, answers probe) | — |
| 6b | Control protocol v2 + live telemetry: `STATE`/`IDENTITY`/`HOST`/`JOIN`/`STOP`/`PASSWORD`/`STATUS`/`WORLDID` verbs; `WorldIdentity` minting; real bytes/peers/worlds JSON | ✅ (verified live) | — |
| 6c | Host/join delegation + worker seeding: hosting rides the worker (announce loop, rendezvous registration, region-piece seeding), grant gossip | 🚧 (HOST records the world; seeding + gossip pending) | 5e (30c genesis/extraction), 2d (splitter reuse) |
| 6d | Out-of-game committee validation: the worker re-executes regions + casts votes (the bundled Java peer — Option B) | ✅ headless (issue #30 goal pass, 2026-07-21: `dev.nodera.peer.validation.WorkerValidationService` + `WorkerQuorumValidationIT` — 3 companion-only workers quorum-commit over the transport via the `simulationmsg` family, fail over to epoch+1, run the fallback lane; certificates persisted per member; `STATE` reports live `validation` counters; L-48 RETIRED) | live half: 5b (real-world region feed) |

## Goal

Decouple "being a Nodera node" from "running Minecraft." The worker is a Minecraft-free `main`
that boots the full Java `PeerRuntime` — persistent identity, membership, gateway candidacy,
tracker announce, rendezvous registration, distribution seeding, and (6d) committee
validation — as a long-lived OS process, and serves the **loopback control endpoint**
(`127.0.0.1:25610`) that the mod requires (5g gate), queries (5d/5f), and delegates hosting to
(5e→6c). This is what makes player-hosted worlds survive the host closing their game, and it is
the project's answer to the L-41 "separate OS sidecar" requirement: the worker is a different
process by construction, so a Minecraft crash cannot take the node down.

## Context (last audit: 2026-07-21)

- Landed (legacy Tasks 32/33): the `nodera-headless` Gradle module (runnable `application`
  installDist, run by `scripts/dev.sh` with a control-probe health check), the
  `peer-runtime/control` package — `ControlProtocol` (the single source of truth for the
  line-based wire, mirrored by the mod's `CompanionProtocol` and the Rust app's `control.rs`;
  `PROTOCOL_VERSION = 2`), `ControlHandler` seam + dispatching `ControlServer` — and the
  `WorkerControlHandler` answering `STATE` from the live `TrafficMeter` + session view + hosted
  worlds. The worker is the **world author**: it holds the signing key and mints signed
  `WorldIdentity` records via `WORLDID`; it enforces author-only `PASSWORD` re-key.
- Verified live outside the gate: boots, becomes gateway, answers
  `NODERA-PROBE 1` → `NODERA-OK 1 0.1.0-SNAPSHOT` and real `NODERA-STATE`/`IDENTITY`/`HOST`/
  `WORLDID` replies.
- The control channel is **loopback-only, versioned, non-authoritative**: peers still verify
  everything the worker serves (Task 0 §3 rule 7). Requiring the worker is a persistence +
  reachability convenience, never a new trust anchor.
- The Option A/B decision is **locked (Option B)**: the worker is the existing, tested Java
  peer run headlessly. A Rust-native peer is forbidden from re-executing regions by the
  single-engine determinism rule (it could only seed/relay/route — L-48); it remains a possible
  later lightweight-only mode, not this task.

## Folder structure (monorepo default)

```
java/peer/src/main/java/dev/nodera/headless/
├── HeadlessPeerMain.java        boots PeerRuntime over a real socket; persistent identity;
│                                serves the ControlServer; config from file/env
├── WorkerControlHandler.java    answers STATE/IDENTITY/HOST/WORLDID… from live runtime state
└── WorkerState.java             maintained pieces/bytes, peers, hosted worlds snapshot

java/peer/src/main/java/dev/nodera/peer/control/
├── ControlProtocol.java         the wire: verbs, version (single source of truth)
└── ControlServer.java           loopback listener + ControlHandler dispatch
```

## Related files

- `java/peer/**` (module), `java/peer/.../control/{ControlProtocol,ControlServer}.java` (+ `ControlServerTest`)
- Mod mirror: `java/neoforge-mod/.../common/{CompanionProtocol,CompanionClient,CompanionGate,CompanionLink}.java` (5g)
- Rust mirror: `rust/nodera-app/src/control.rs` (7b)
- Identity: `java/peer/.../discovery/PersistentIdentityStore.java` (2e);
  world identity types: `java/storage/.../{WorldIdentity,WorldPermissionGrant,WorldPermissions}.java` (2c)
- Runner: `scripts/dev.sh` (builds + runs the worker; `--no-worker` opts out)
- Legacy specs: [`old/Task.32.md`](old/Task.32.md) (32b daemon + decision),
  [`old/Task.33.md`](old/Task.33.md) (verbs, identity, permissions), and
  [`Plan.1.md`](Plan.1.md) (the phase plan that produced them)

## Implementation details (phases)

- **6a — Boot + presence.** ✅ Full spec: [`old/Task.32.md`](old/Task.32.md) §32b.
  `HeadlessPeerMain` builds the same `PeerRuntime` the mod would (2b), holds the persistent
  identity (2e — identity + cache survive Minecraft restarts; the node's continuity is the
  worker's job now), and serves the probe the 5g gate requires. Deps: 2b, 2e.
- **6b — Control v2 + telemetry.** ✅ Full spec: [`old/Task.33.md`](old/Task.33.md).
  The verb table (line-based request → JSON/line reply): `STATE` (dashboard/HUD metrics),
  `IDENTITY` (worker NodeId + public key — the author identity), `HOST`/`JOIN`/`STOP`
  (world lifecycle), `PASSWORD` (author-only re-key), `STATUS` (per-world players/health/
  permissions), `WORLDID` (mint + sign a `WorldIdentity`). Version bumps keep mod + Rust
  mirrors in lockstep — the gate already classifies skew. Deps: 6a, 2k (meters), 2c (types).
- **6c — Host/join delegation + seeding.** 🚧 The worker becomes the *primary* client of
  Tasks 3/4: the tracker announce loop and rendezvous registration move here and persist
  across game sessions (a host closing Minecraft is a player-session leave, not a node
  leave). On `HOST`, the mod extracts region pieces (`RegionSnapshotSplitter`/`PieceManifest`,
  2d) and hands the manifest + save path over; the worker seeds them
  (`ContentTransferService`), announces, and gossips permission grants (5f). Keep the mod's
  in-JVM path behind a fallback flag for worker-less environments. Deps: **5e** (30c
  genesis/extraction production), 2d, 3b, 4b. Related: `WorkerControlHandler`,
  `NoderaHost.activate` (mod side).
- **6d — Out-of-game validation.** ⏳ The worker runs `committee` re-execution and casts
  votes — a companion-only node participates in a quorum in a headless IT (the L-48 exit).
  This is the same live wiring 5b builds for the mod, pointed at the worker's runtime; no new
  consensus code. Deps: 5b, 1e over 2b.

## Testing strategy

- `ControlServerTest` (headless, on the gate): probe → `NODERA-OK`; v2 verb dispatch
  (`STATE`/`IDENTITY`/`HOST`); a bad connection never takes the server down.
- Manual live verification per increment: `printf 'NODERA-STATE 1\n' | nc 127.0.0.1 25610`
  returns the real JSON snapshot; the worker boots, becomes gateway, survives mod restarts.
- 6c: a headless IT proving the request-#3 property — the worker keeps a world announced +
  seeded after the driving "game" client disconnects (extends `SessionContinuityIT` /
  `ArchiveManager` paths).
- 6d: a headless IT where a committee quorum includes a worker-hosted validator
  (`CommitteeMvpIT` over the worker mesh).
- Cross-machine continuity (host closes Minecraft, a second machine still sees + joins the
  world) is the Task 7 (7d) CI acceptance — shared.

## Limitations

- **L-41** RETIRING ([`LIMITATIONS.md`](LIMITATIONS.md)): the worker **is** the always-on
  out-of-game process; RETIRED when its continuous seed/flush is proven to survive a game
  `kill -9` (different process, by construction — the proof is the 6c IT).
- **L-48** OPEN: a companion-only node is seeder/relay/router-capable until 6d ships
  validation (single-engine rule forbids a Rust validator).
- **L-47** (shared with Task 7): no automated installer + gate + cross-machine continuity CI.
- The worker is untrusted by peers like any node — everything it serves verifies by
  hash/signature.

## Acceptance criteria

1. 6a/6b: gate green (`ControlServerTest`); live probe + v2 verbs answered with real data
   (bytes/peers/worlds ≠ zeros); the 5g gate passes against a running worker and fails closed
   without one.
2. 6c: a hosted world stays listed + seeded + joinable after the driving client disconnects
   (headless IT); announce/rendezvous loops live in the worker and persist across game
   sessions; grants gossip.
3. 6d: a committee quorum containing a worker validator commits a region delta headlessly
   (L-48 exit).
4. Both toolchains green; README/Tested + this status table updated; issues per
   `.github/ISSUE_SYSTEM.md`.

## Notes for the implementing model

- **Option B is locked**: the worker reuses the tested Java peer wholesale. Do not port peer
  logic to Rust; do not create a second region engine under any circumstances (determinism
  bet — Task 1 notes).
- `ControlProtocol` is the single source of truth; the mod's `CompanionProtocol` and the Rust
  `control.rs` are mirrors — change all three in one commit, bump the version, and rely on the
  gate's skew classification.
- The control channel carries no secret material beyond the loopback trust boundary; passwords
  are hashed/derived before they reach a verb, never logged, never serialized.
- The worker owns identity + author authority: `PASSWORD` must verify self == author before
  any re-key; `WORLDID` signatures are the per-world trust root (L-20 single-signer — moving
  it to multi-party is 1l's job, not yours).
