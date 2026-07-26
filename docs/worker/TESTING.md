# Worker — Testing

<!-- AI-AGENT-INSTRUCTION: The worker has no module of its own — its code lives in `java/peer`, so its
     counts are a SUBSET of that module's and must not be double-counted in totals. Crash tests here
     MUST use real OS processes and real SIGKILLs; a mocked daemon proves nothing about the property
     this category exists for. Keep counts and Last run current. -->

**Category:** worker · **Last run:** 2026-07-26 · Covered inside `java/peer` (549 tests, 0 failing),
plus the headless `scripts/e2e-telemetry.sh`

The worker is packages inside `java/peer` (`dev.nodera.headless`, `dev.nodera.peer.control`), not a
separate Gradle module, so its tests are counted there — see
[`../network/TESTING.md`](../network/TESTING.md) for the module totals.

```bash
./gradlew :peer:test --tests '*Control*' --tests '*Worker*' --tests '*Companion*'
printf 'NODERA-STATE 1\n' | nc 127.0.0.1 25610     # manual live check against a running worker
```

---

## 1. The testing strategy

| Level | What it proves |
|---|---|
| Unit (`ControlServerTest`) | The endpoint answers, dispatches verbs, and survives a bad connection |
| Verb ITs over the **real control socket** | A verb does not merely return a success string — it changes worker behaviour |
| Real-process crash tests | The daemon is genuinely a different process, proven by killing another one |
| Quorum ITs | A companion-only node is a real committee member, not a spectator |
| Manual live checks | The endpoint returns non-zero real data, per increment |

## 2. Landmark tests

| Test | What it proves |
|---|---|
| `ControlServerTest` | Probe → `NODERA-OK`; v2 verb dispatch; a bad connection never takes the server down |
| `ConfigVerbIT` | A pushed setting **actually changes worker behaviour** over the real control socket, and the cross-language key contract matches the app's golden string |
| `RekeyVerbIT` | The re-key crypto and identity round trip: the blob decrypts under the **new** password only; a wrong-author identity is rejected; a second re-key supersedes the old ciphertext so the old password stops working |
| `WorldContinuityIT` | Share → listed → P2P fetch byte-exact → host game closed → **host worker killed** → a second peer still reproduces the world, over the real tracker and rendezvous binaries |
| `CompanionCrashSurvivalIT` | The actual worker distribution runs as a separate daemon; a co-located stand-in game process is **SIGKILLed** (no shutdown hooks); the daemon keeps answering verbs with every seeded piece still maintained |
| `WorkerQuorumValidationIT` | Three **companion-only** workers form a committee over the transport, converge on the byte-identical reference-engine root, persist co-signed certificates, and fail over to epoch+1 |
| `ResidentQuorumIT` | A committee holds full strength through a player's logout thanks to standing workers, with the no-resident counterfactual asserted |
| `GrantGossipIT` (6) | Grants converge across co-hosts; a non-author's correctly-signed grant is refused by every receiver and is not even relayed by the attacker's own node |
| `SupersededManifestEvictionTest` (3) | A replica drops a superseded manifest version and does not re-adopt a late-arriving older one |
| `SeedRegionVerbIT` (4) | `NODERA-SEED-REGION` seeds a committed region from a transient control connection; with nothing connected the save **and** the region are both still held and both ride one announce; a file that is not a snapshot is refused rather than advertised |
| `WorkerHeartbeatHoldingsTest` (4) | The announce heartbeat re-reads holdings every time, so content seeded after a world was hosted is advertised; an unreachable tracker never stops it; the cadence keeps its floor |
| `RegionPieceSeedingTest` (7) | The two lanes keep separate ladders — a region version cannot supersede the world's save — with an idempotent re-seed, a bounded per-region window, and a deterministic capped announce |
| `CommittedRegionSeederTest` (6) | A commit is filed under the one world this node hosts, and hosting several seeds nothing rather than advertising a region to peers fetching a different world |
| `RegionSeedSpoolTest` (7) | The mod hands snapshots over without the commit path touching disk or a socket: throttled per region, bounded backlog that drops rather than grows, every failure contained |

## 3. Conventions

- **A verb IT must observe a behaviour change**, not a reply string. `ConfigVerbIT` exists because a
  control plane that returns `applied` without applying anything is the worst possible outcome — it
  is indistinguishable from working.
- **Crash tests use real processes and real signals.** `destroyForcibly` / SIGKILL only. A graceful
  stop exercises the path that does not fail, and the whole claim here is about the path that does.
- **Three mirrors, one commit.** Any protocol change lands in `ControlProtocol`, the mod's
  `CompanionProtocol`, and the app's `control.rs` together, with the version bumped.
- **Manual live verification per increment** is recorded in the progress ledger — the worker's most
  important properties (it boots, it becomes gateway, it survives a game restart) are observed on a
  real machine before they are claimed.

## 4. Live evidence

Live worker behaviour is exercised by the mod's scripted suites — see
[`../minecraft/TESTING.md`](../minecraft/TESTING.md). In a suite run, `worker-*.log` shows
`Now hosting world`, `Seeding world archive vN — P piece(s)`, `Seeding region Region[...] of world`, and `Fetched world archive`; the control
verbs on ports 25610/25611/25612 return live JSON with `maintained_pieces`, `connected_worlds`, and
`validation` counters.
