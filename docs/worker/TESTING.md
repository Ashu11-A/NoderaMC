# Worker — Testing

<!-- AI-AGENT-INSTRUCTION: The worker has its own `java/worker` module; control and tunnel library
     tests remain in `java/peer`, so category counts span both modules and must not be double-counted
     in totals. Crash tests here MUST use real OS processes and real SIGKILLs; a mocked daemon proves
     nothing about the property this category exists for. Keep counts and Last run current. -->

**Category:** worker · **Last run:** 2026-07-28 · `java/worker` plus the peer-side lanes it drives
(`dev.nodera.peer.control`, `dev.nodera.peer.tunnel`) in `java/peer`, plus `scripts/nodera-test.sh run telemetry`

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

The worker is now its own Gradle module (`:worker`) — it was a package inside `:peer` until
2026-07-26, which put the worker executable inside anything depending on the peer library, the mod's
fat jar included. The control endpoint and the tunnel lane stay in `:peer` because they are library
code the Paper plugin uses too.

**Test counts (`scripts/java-test-report.sh`, 2026-07-29):** **198** test cases in `:worker`, plus the peer-module cases named below —
`java/worker/src/test/` (169 in `dev.nodera.headless` + 4 in `dev.nodera.peer.control.TelemetryVerbIT`)
plus `java/peer/src/test/java/dev/nodera/peer/control/` (24 across `ControlServerTest`,
`ControlWatchStreamTest`, `WorkerEventStreamTest`). These are a SUBSET of the `:worker` + `:peer`
module counts and must not be double-counted in totals.

```bash
./gradlew :worker:test
./gradlew :peer:test --tests '*Control*' --tests '*Tunnel*' --tests '*Lan*'
printf 'NODERA-STATE 2\n' | nc 127.0.0.1 25610     # manual live check against a running worker
printf 'NODERA-LAN 2 LIST\n' | nc 127.0.0.1 25610  # what this machine has open to LAN
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
| **Second-instance tests** | State survives the process — a test that only touches one service instance cannot see a restart defect |

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
| `TunnelServiceIT` (7) | Over real sockets: a guest's local port reaches the host's game **both ways**; an unpublished session is refused and the host's game is never dialled (the property that keeps this from being an open proxy); withdrawing a session drops the connections it was carrying; the guest's door is loopback-only |
| `LanSessionServiceTest` (11) | Detection announces nothing; sharing publishes *and* announces; declining keeps the world listed so it can be shared later; stopping differs from declining; closing the world withdraws it whatever was decided; a repeated beacon does not reset the decision |
| `LanBeaconTest` (6) | The payloads an unmodified Minecraft actually broadcasts, including a MOTD with colour codes and brackets, and every malformed shape that must not be mistaken for a joinable world |
| `CompanionProtocolContractTest` (3) | The mod and the worker spell every verb identically — a drift alarm in a test rather than a compile-time coupling between two separately-installed artifacts |
| `ControlWatchStreamTest` (6) | The one verb the worker **writes**: a change is pushed unasked, an unchanged node is not re-sent at the sampling interval, the interval is clamped, an abandoned watcher never disturbs the endpoint, ordinary verbs keep working while a watch is open, and a throwing state renderer ends one stream rather than the node |
| `WorldHostingPersistenceTest` (8) | A **second** hosting service over the same registry file finds the world the first one shared — the restart defect that made the companion app look broken. Pins the negative too: liveness is never restored, so a restored world does not advertise itself as joinable |
| `WorldRegistryStoreTest` (12) | Durability across the process, hosting never demoted by a later seed, ownership not erased by a routine re-share, a corrupt file starting empty rather than stopping the node, owner-only permissions |
| `HeadlessPeerMainStateTest` (1) | Closest production proof for W-DUP-3: `HeadlessPeerMain.openLocalState` starts twice on zipfs after both provider and `FileStore` report no POSIX view; identity and replaced registry survive. Not a launched worker process, so W-DUP-3 remains RETIRING |
| `AtomicFileWriterTest` (3, `:storage`) | Shared writer creates POSIX files owner-only, deletes a secret-bearing temp after failed replacement, and suppresses cleanup failure on the primary exception |
| `WorldKeyStoreTest` (9) | One key per world, stable across restarts, owner-only, a non-hex world id never becoming a path, a corrupt key file reported and never overwritten |
| `OwnershipGossipIT` (6) | Over a real mesh: every peer independently verifies who administers a world; a later rival claim does not displace the owner; a tampered claim is refused **and not relayed**; an envelope naming a different world than its claim is dropped |
| `WorldOwnershipVerbIT` (6) | Over the real control socket: minting a world identity mints its key and claim; `NODERA-PROVE` answers a challenge with a proof verifiable from the world's public key alone; a world this node did not create is refused |
| `RegionSeedSpoolTest` (7) | The mod hands snapshots over without the commit path touching disk or a socket: throttled per region, bounded backlog that drops rather than grows, every failure contained |
| `ArchiveFetchOverSocketsIT` (3) | The archive lane over **real `SocketPeerTransport`** on loopback: a joiner pulls a whole archive; an unpublished/silent bystander cannot stall a fetch a real seeder can serve; **a peer never offers a version it holds nothing of** (`#onlyHeldVersionsAreOffered` — the W-REPL-3 exit test). The live topology is reproduced, not the loopback-transport shortcut |
| `FetchSurvivesSupersessionTest` (2) | A newer version learned mid-fetch does NOT evict the one being downloaded (the W-FETCH-1 exit test); a version nobody is fetching is still superseded, so L-55 stays intact. Verified failing with the guard disabled |
| `HeldVersionBeatsAnUnreachableNewerOneTest` (2) | A fetch prefers the newest version a seeder is advertised for over the newest merely *known* version, and falls back to the newest complete local copy on a stall — the fix for `0/73 piece(s) after 120s` against a version nobody held |
| `ArchiveRetentionWindowTest` (6) | The retention window keeps the newest N archive versions; trimming is bounded and idempotent |
| `ReplicationRepairsEmptyClaimsTest` (4) | A world this node claims but holds nothing of is repaired ahead of placement and bounds (the W-REPL-1 exit test, verified failing without the fix). The sweep's `shouldAdopt(...)` is extracted so it can be driven headlessly at all |
| `WorldDeletionVerbIT` (4) / `WorldDeletionServiceTest` (11) | Over the real control socket, a tombstone signed by the world key + node identity is applied locally and flooded; receivers re-verify the ownership claim; a non-owner cannot delete; a deleted world cannot come back through host/seed |
| `WorkerEventStreamTest` (8) | `NODERA-EVENTS` replays from `sinceSeq`, live delivery preserves order, a slow subscriber is dropped not blocked, keepalive carries the sequence |
| `TelemetryVerbIT` (4) | `NODERA-TELEMETRY GET` before any `SET` reports denied; `SET granted` round-trips through a **new emitter over the same directory**; an unknown argument is refused without changing state |

## 3. Conventions

- **A verb IT must observe a behaviour change**, not a reply string. `ConfigVerbIT` exists because a
  control plane that returns `applied` without applying anything is the worst possible outcome — it
  is indistinguishable from working.
- **Crash tests use real processes and real signals.** `destroyForcibly` / SIGKILL only. A graceful
  stop exercises the path that does not fail, and the whole claim here is about the path that does.
- **Three mirrors, one commit.** Any protocol change lands in `ControlProtocol`, the mod's
  `CompanionProtocol`, and the app's `control.rs` together, with the version bumped.
- **State that must survive the process is tested with a second instance.** A test that mutates one
  service and asserts against that same object cannot see a restart defect — which is exactly how the
  world registry shipped without one for as long as it did.
- **Manual live verification per increment** is recorded in the progress ledger — the worker's most
  important properties (it boots, it becomes gateway, it survives a game restart) are observed on a
  real machine before they are claimed.

## 4. Live evidence

Live worker behaviour is exercised by the mod's scripted suites — see
[`../minecraft/TESTING.md`](../minecraft/TESTING.md). In a suite run, `worker-*.log` shows
`Now hosting world`, `Seeding world archive vN — P piece(s)`, `Seeding region Region[...] of world`, and `Fetched world archive`; the control
verbs on ports 25610/25611/25612 return live JSON with `maintained_pieces`, `connected_worlds`, and
`validation` counters.
