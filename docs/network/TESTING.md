# Network — Testing

<!-- AI-AGENT-INSTRUCTION: Counts here are AUTHORITATIVE and come from `./gradlew check` XML reports
     plus `cargo test`, never from memory. Update counts and Last run in the same commit that changes
     them, and keep them in agreement with the root README module table. Crash tests MUST use real
     forced process kills; a graceful-stop test proves the wrong thing and must not be counted as
     crash coverage. -->

**Category:** network · **Last run:** 2026-07-26 · **719 Java tests + 35 Rust (`nodera-codec`) ·
0 failing · 0 skipped** — the Java figure includes the telemetry emitter core (task 12)

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `transport` | Wire plane + socket/rendezvous carriers; tags through 61; authenticated handshake; bind-failure invariants | 93 | ✅ |
| `storage` | Event-sourced, RocksDB, and client tiers; paired append; transfer stages; forced-kill WAL recovery; identity/permission stores | 112 | ✅ |
| `peer` | Distribution, runtime, discovery, archival, diagnostics, headless worker, validation lane | 468 | 🚧 |
| `rust/nodera-codec` | Byte-exact canonical encoding port, Ed25519 verify, tag mirror, framing, fixture conformance | 35 | ✅ |

`peer` is marked 🚧 because its scope is incomplete (task 2's migration lane), not because anything
fails.

```bash
./gradlew :transport:test :storage:test :peer:test
cd rust && cargo test -p nodera-codec
```

---

## 1. The testing strategy

| Layer | What it proves | Examples |
|---|---|---|
| Headless ITs over `LoopbackTransport` **and real TCP** | The lanes work end to end with no Minecraft | `SessionContinuityIT`, `DistributionIT`, `ArchiveRepairIT` |
| Property tests | Every deterministic policy is order-independent and pure-integer | placement, selection, election, scoring |
| Forced-kill crash proofs | Durability survives a process that never ran a hook | `RocksCrashRecoveryIT`, `CrashRecoveryIT`, `CompanionCrashSurvivalIT` |
| Adversarial / bounds | Corrupt blobs, tampered payloads, byte-budget exhaustion, flood input | corrupt-blob rejection, oversize refusal, LRU bounds |
| Cross-language conformance | The two canonical-encoding implementations cannot drift | `fixtures/wire/*.bin` + `nodera-codec` `fixtures`/`tag_mirror` |
| Real-binary ITs | The Rust services behave as the Java client expects | `TrackerServiceIT`, `RendezvousRelayIT`, `WorldContinuityIT` |

## 2. Landmark tests

| Test | What it proves |
|---|---|
| `SessionContinuityIT` | Real-TCP base-peer-disconnection continuity with gateway re-election |
| `RocksCrashRecoveryIT` | A forcibly killed writer JVM reopens with contiguous ids, an unbroken `prevRoot → resultingRoot` chain, and a live head |
| `DistributionIT` | A region reassembled from 3 seeders each holding < 40%, hashing to the **engine's own** root; a partial download resumes after a seeder disconnects |
| `MultiBootstrapIT` | Join succeeds via each of three independent mechanisms with the original bootstrap **offline** |
| `ArchiveRepairIT` | A killed ×5 manifest is re-replicated to factor with no data loss |
| `EncryptedDistributionIT` | Three keyless seeders serve ciphertext that only a password joiner decrypts back to the engine root |
| `EncryptedArchiveTest` | Wrong password fails the GCM tag and yields *empty*, never garbage |
| `CrashRecoveryIT` | A destroyed JVM (no hooks ran), primary dropped, snapshot ×5 restored into real stores, certified replay to the committed root |
| `LagHandoffIT` / `LiveLagHandoffIT` | A laggard primary is replaced under epoch+1 with the neighbour untouched; the live lane is driven by real forwarded-action latency |
| `EventSyncOverTransportIT` | A fresh peer replays a certified chain to the certified root; an uncertified tail never syncs |
| `WorldContinuityIT` | Share → listed → P2P fetch byte-exact → host game closed → host worker killed → a second peer still reproduces the world, over the **real** tracker and rendezvous binaries |
| `ResidentQuorumIT` | A committee holds full strength through a player's logout thanks to standing workers, with the no-resident counterfactual asserted |
| `ByzantineMeshIT` | A genuinely adversarial peer on the mesh is outvoted, cannot forge a seat, and gains one seat rather than two when equivocating |
| `SocketPeerTransportAuthTest` (5) | Key-proven NodeId attribution at accept; legacy, forged, and replayed hellos refused; a peer dying mid-handshake fails the sender in milliseconds rather than at the timeout |
| `FsContentStoreRelocationTest` (5) | Blobs survive relocation and a **reopened** store finds them; an identical blob at the destination is merged, not refused |
| `ConfigVerbIT` | A pushed setting actually changes worker behaviour, over the real control socket |
| `GrantGossipIT` (6) | Grants converge across co-hosts; a non-author's signed grant is refused everywhere and not even relayed |
| `TrafficDirectionSplitTest` | Upload and download never share a field |

## 3. Conventions

- **A crash test kills the process.** `destroyForcibly` / SIGKILL only; a graceful stop exercises the
  path that does not fail.
- **Every appended wire tag** lands with a Java golden fixture and the Rust re-encode test in the same
  commit. Fixtures are generated, never hand-edited.
- **Bounds are tested with floods**, not with a comment. Any cache keyed by remote input has a test
  that overruns it.
- **Deterministic policies get order-independence property tests**, because "same inputs, same output"
  is the property placement and election depend on.
- **No wall clocks** in anything feeding consensus; meters take injected time so their tests are
  deterministic.

## 4. The former known flake — fixed 2026-07-26

`SocketPeerTransportAuthTest` used to fail under the full gate at maximum parallelism, and the
advice here was to re-run it with `--no-parallel --max-workers=2` before assuming a regression.
That advice was hiding a real defect.

The failure was `auth handshake timed out`, thrown from `SocketPeerTransport.writeFrame`: a sender
waits for its own read loop to see the remote's challenge and write the signed hello. The reader's
teardown closed the socket **without releasing that latch**, so a connection that died mid-handshake
left every sender waiting out the full timeout and then reporting a *timeout* — which reads as "slow
peer" when the peer is gone. Under CPU starvation the same wait tripped on a peer that was merely
slow to be scheduled.

Teardown now releases the latch, a sender distinguishes "closed during the handshake" from "timed
out", and the timeout is a named constant (`AUTH_HANDSHAKE_TIMEOUT_SECONDS`) that only has to cover
a starved scheduler because a dead peer no longer spends it.
`aPeerThatDiesMidHandshakeFailsTheSenderImmediately` asserts on the **clock** as well as the
exception — a fail-fast that takes 30 s is the bug wearing a passing test's clothes. It returns in
about 4 ms.
