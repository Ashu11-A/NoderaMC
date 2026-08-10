# Network — Testing

<!-- AI-AGENT-INSTRUCTION: Counts here are AUTHORITATIVE and come from `./gradlew check` XML reports
     plus `cargo test`, never from memory. Update counts and Last run in the same commit that changes
     them, and keep them in agreement with the root README module table. Crash tests MUST use real
     forced process kills; a graceful-stop test proves the wrong thing and must not be counted as
     crash coverage. -->

**Category:** network · **Last run:** 2026-08-10 · **1,217 Java test cases + 79 Rust
(`nodera-codec`) `#[test]` · 0 failing** — the Java figure sums the module table below
(189 + 168 + 860), whose cells are README's, re-measured from the JUnit XML of the nine-module run
recorded in commit `44069df`. Worker cases are also described under the worker category.

> Re-measured on 2026-08-06 after the Plan 11 round 2 reduction (issue #210), which deleted the
> archival audit triangle and the second discovery resolver from `:peer` along with their dedicated
> suites. The whole Java tree comes to **2,291 tests / 0 failed / 0 skipped** as the sum of README's
> nine measured module cells; to measure it rather than add it up, run `scripts/test-totals.sh
> --java`. The **2,238** this file used to carry predates that re-measurement, as did `transport`
> 186 and `peer` 835. The Rust count is unchanged by round 2, which touched no `nodera-codec` source.
>
> Module figures are total XML-reported cases. **Twelve skips is no longer a state the gate
> tolerates:** the `java` job in [`build.yml`](../../.github/workflows/build.yml) now builds the
> real-binary artefacts those suites wait for *before* `./gradlew check`, and a step named `Nothing
> skipped into green` fails the job on any non-zero skip count. Running a single module task by hand
> still leaves those suites without their artefacts; that is a property of the shortcut, not a
> tolerated state of the gate.

> **The live suites are Java scenarios now.** Every `scripts/e2e-<id>.sh` became `dev.nodera.testkit.scenario.<Id>Scenario` and runs through one command:
> `scripts/nodera-test.sh run <id>` (`list` shows them all). The stages, evidence strings and timeouts were carried over, so a report maps onto an old run line by line. The tooling is documented in [`docs/testing/`](../testing/Task.0.md).

| Module | Scope | Tests | Status |
|---|---|---:|:---:|
| `transport` | The `NDR2` wire and both planes: all 76 kinds sampled, fixtured and dispatch-tested through the one `CodecRegistry` table; canonical TLV; negotiation and OBSERVER admission; the authorisation table and router; explicit enum codes; socket/rendezvous carriers; canonical mutation fuzz; the Android `SwitchBootstraps` guard. Cross-language fixture coverage is **derived from `nodera-codec`'s own `SUPPORTED_MESSAGE_TAGS`** rather than a hand-written tag list | 189 | ✅ |
| `storage` | Event-sourced, RocksDB, and client tiers; paired append; transfer stages; forced-kill WAL recovery; identity/permission stores; secure atomic writes including Android-denied store inspection; the `BlobDirectory` seam that lets the same content store write into an Android document tree (frontend M-1) | 168 | ✅ |
| `peer` | Distribution, runtime, discovery, archival, diagnostics, validation lane, durable coordinator state, commons-safe replication, the endpoint tenant boundary, the L-16 prediction feed, and the NDR2 authorisation table **as the runtime applies it** (`SenderAuthorisationIsEnforcedTest` — a forged goodbye cannot evict, a forged join cannot enrol) | 860 | 🚧 |
| `library/rust/nodera-codec` | Byte-exact canonical encoding port, the `NDR2` frame and TLV, Ed25519 verify, total parsed kind mirror against the Java schema, fixture conformance, canonical mutation fuzz | 79 | ✅ |

`peer` is marked 🚧 because its scope is incomplete (task 2's migration lane), not because anything
fails.

```bash
./gradlew :transport:test :storage:test :peer:test
cargo test -p nodera-codec

# Task 14: accept a deliberate wire change after reviewing the bytes it emits.
./gradlew :transport:test --tests '*WireFixtureTest' -Dnodera.fixtures.regenerate=true

# Task 14: re-render the artefacts generated from the schema (the Rust kind table, the
# fixture manifest) after appending a kind to WireRegistry.
./gradlew :transport:test --tests '*WireSchemaGeneratorTest' -Dnodera.wire.regenerate=true
```

---

## 1. The testing strategy

| Layer | What it proves | Examples |
|---|---|---|
| Headless ITs over `LoopbackTransport` **and real TCP** | The lanes work end to end with no Minecraft | `SessionContinuityIT`, `DistributionIT`, `DepartureIsRepairedByPlacementTest` |
| Property tests | Every deterministic policy is order-independent and pure-integer | placement, selection, election, scoring |
| Forced-kill crash proofs | Durability survives a process that never ran a hook | `RocksCrashRecoveryIT`, `EntityTransferCrashRecoveryIT`, `CompanionCrashSurvivalIT` |
| Adversarial / bounds | Corrupt blobs, tampered payloads, byte-budget exhaustion, flood input | corrupt-blob rejection, oversize refusal, LRU bounds |
| Cross-language conformance | The two canonical-encoding implementations cannot drift | `fixtures/wire/*.bin` + `nodera-codec` `fixtures`/`tag_mirror` |
| Real-binary ITs | The Rust services behave as the Java client expects | `TrackerServiceIT`, `RendezvousRelayIT`, `WorldContinuityIT` |

## 2. Landmark tests

| Test | What it proves |
|---|---|
| `SessionContinuityIT` | Real-TCP base-peer-disconnection continuity with gateway re-election |
| `RocksCrashRecoveryIT` | A forcibly killed writer JVM reopens with contiguous ids, an unbroken `prevRoot → resultingRoot` chain, and a live head |
| `DistributionIT` | A region reassembled from 3 seeders each holding < 40%, hashing to the **engine's own** root; a partial download resumes after a seeder disconnects |
| `DepartureIsRepairedByPlacementTest` | A holder's departure re-ranks every world it held and promotes a peer that was not holding it, so the replication factor is restored by the sweep with nothing pushed on the way out |
| `EncryptedDistributionIT` | Three keyless seeders serve ciphertext that only a password joiner decrypts back to the engine root |
| `EncryptedArchiveTest` | Wrong password fails the GCM tag and yields *empty*, never garbage |
| `EntityTransferCrashRecoveryIT` | A destroyed JVM (no hooks ran) reopens with the transfer journal intact and the entity neither duplicated nor lost |
| `LiveLagHandoffIT` | A laggard primary is replaced under epoch+1 with the neighbour untouched, driven by real forwarded-action latency |
| `EventSyncOverTransportIT` | A fresh peer replays a certified chain to the certified root; an uncertified tail never syncs |
| `WorldContinuityIT` | Share → listed → P2P fetch byte-exact → host game closed → host worker killed → a second peer still reproduces the world, over the **real** tracker and rendezvous binaries |
| `ResidentQuorumIT` | A committee holds full strength through a player's logout thanks to standing workers, with the no-resident counterfactual asserted |
| `ByzantineMeshIT` | A genuinely adversarial peer on the mesh is outvoted, cannot forge a seat, and gains one seat rather than two when equivocating |
| `SocketPeerTransportAuthTest` (5) | Key-proven NodeId attribution at accept; legacy, forged, and replayed hellos refused; a peer dying mid-handshake fails the sender in milliseconds rather than at the timeout |
| `FsContentStoreRelocationTest` (5) | Blobs survive relocation and a **reopened** store finds them; an identical blob at the destination is merged, not refused |
| `ConfigVerbIT` | A pushed setting actually changes worker behaviour, over the real control socket |
| `GrantGossipIT` (6) | Grants converge across co-hosts; a non-author's signed grant is refused everywhere and not even relayed |
| `TrafficDirectionSplitTest` | Upload and download never share a field |
| `MessageSamples.assertTotal` (task 14) | Every one of the 76 registry kinds has a deterministic sample; appending one without a sample fails the build at the commit that adds it |
| `WireFixtureTest` (5) | Every tag's golden bytes are committed and unchanged, across the 25-file cross-language corpus and the 52-file Java-only corpus. A **missing** fixture fails — it used to be written silently, which meant bytes nobody had reviewed |
| `MessageCodecTypeTagTest` (76/76) | Every kind dispatches to its own type and round-trips by value; the registry is unique and contiguous. Coverage was 25 of 72 before task 14 |
| `CodecRegistry` totality (class initialisation) | A kind `WireRegistry` declares but the codec table cannot encode fails when the class loads, not at the first send. Encoder and decoder are one row, so a tag cannot have one without the other — which is exactly the drift the two-dispatcher layout allowed |
| `SessionKeepAliveCodecTest` (8) | Tag 23 is body version 2 and nothing else (0.2.0, issue #214): the golden v2 frame is byte-exact, progress is canonically sorted and duplicate-free on both the record and the decoder, and a **retired v1 frame is refused at the version** rather than reinterpreted as empty progress |
| `CanonicalMutationFuzzTest` + `mutation.rs` | The same invariant in both languages on the same corpus: a mutated frame either fails to decode or re-encodes to exactly its own bytes. Found 999 pre-existing canonical violations across 30 message types, now 0 — **and the documented-exception list is now empty**, where it used to hold `RegionRefusal` and `PeerJoin` |
| `ForwardCompatibilityTest` (10) | A field a newer peer appended is skipped — at the top level and inside a nested `NodeCapabilities` — and a field an older peer omitted takes its default. Consensus payloads cross as one opaque field. **These are the exit tests that retired L-86 and L-89 (2026-07-28, issue #97):** `aFieldAppendedByANewerPeerIsIgnored`, `aFieldAppendedInsideANestedStructureIsIgnored`, `aFieldTheSenderOmitsTakesItsDocumentedDefault`, `CrossVersionIT.aFutureFieldSurvivesARelay` (L-86); `aConsensusPayloadCrossesAsOpaqueBytes`, `theTwoPlanesAreExactlyPopulated` (L-89) — all green |
| `NegotiationTest` (11) | A rules-version mismatch yields OBSERVER with a coded reason instead of an engine throw; a peer cannot name itself; the emitted feature set is the intersection. L-87 and L-88 |
| `MessageRouterTest` (11) | A peer cannot speak in another peer's name; an answer nobody asked for matches nothing; an EXCLUSIVE kind refuses a second handler rather than replacing the first |
| `WireEnumRulesTest` (9) | Every boundary enum has a total, pinned code table, and no class in the wire package calls `ordinal()`. The ArchUnit rule was **falsified before being trusted** — the obvious spelling matches nothing |
| `CrossVersionIT` (5) | Two builds from different releases mesh, seed and tunnel; an unknown kind costs one frame rather than the connection; a field from a later release survives being relayed through a peer that cannot read it |
| `WireSchemaGeneratorTest` (6) | The generated Rust kind table and fixture manifest match the schema they are rendered from; a stale one fails |
| `WireFixtureTest.everyRetiredV1FrameIsRefusedRatherThanMisparsed` | Every pre-`NDR2` frame this project used to emit is refused at the magic, in both languages. The flag day, asserted rather than assumed |
| `tag_mirror.rs` (5) | The **generated** Rust kind table is compared against a fresh parse of `WireRegistry.java`, totally and in both directions — which is what makes "generated" mean something: a stale file fails, and so does a kind that exists on one side only |
| `fixtures.rs` (3) | Rust re-encodes every shared fixture byte-exactly; truncation is asserted to be rejected, and appending is routed to the family that owns the tag |

### 2.1 What the Plan 11 round-2 reduction took out of this table

Four landmark suites were removed from this file on 2026-08-06 (issue #210, commits `0b02aa5` and
`24e6f0e`) because they no longer exist. Three go with the design they covered; **one is a real
gap.**

| Removed suite | Verdict |
|---|---|
| `ArchiveRepairIT` | **Not a gap, and the replacement is stronger.** It proved a killed ×5 manifest was re-replicated by the push-side audit→repair triangle. That triangle had no production entry point and was deleted; the durability property is now held by placement, which `DepartureIsRepairedByPlacementTest` pins. Evacuating on the way out needed the departing node to still be alive — a crash and a closed laptop both break that, and the sweep does not care. |
| `CrashRecoveryIT`, `LagHandoffIT` | **Not a gap.** Both drove the retired central-coordinator committee session. `EntityTransferCrashRecoveryIT` and `RocksCrashRecoveryIT` still prove forced-kill durability with real `destroyForcibly()` kills, and `LiveLagHandoffIT` still proves epoch+1 replacement of a laggard primary — over the transport that ships. |
| `MultiBootstrapIT` | **Real gap.** It proved Task 20 acceptance #2: with the original bootstrap **offline**, a new client still reaches the mesh by each of three independent routes — a configured alternate, a cached address, and a pasted invitation — so no single host is a gatekeeper. Round 2 deleted `BootstrapClient`, `CachedPeerStore` and `InvitationCodec` as a second discovery resolver superseded by `PeerDiscoveryService` + `TrackerClient`. That is a defensible deletion, but **the gatekeeper property lost its only test with them.** `PinnedTrackerEndpointsTest` proves a config push cannot remove the operator's tracker and `ServiceScoreBoardTest` proves a draining service is not chosen; neither asserts that a *new* client can still join when its first tracker is down. Nothing in the tree does. |

The `MultiBootstrapIT` row is a live gap in the discovery lane, not a bookkeeping artefact: it is
tracked against L-34 in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) and needs a multi-tracker
join test written against `PeerDiscoveryService` before that row's evidence means anything again.

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

## 3b. Measurement, not correctness (task 15)

Two lanes exist to answer questions a pass/fail suite cannot. Neither is part of `./gradlew check` —
both take minutes, and a benchmark that blocks every merge on a shared runner gets deleted the first
time the runner is noisy. Both run in the `benchmarks` workflow (pull request · `main` · weekly ·
manual) and both are ratcheted so their numbers cannot quietly get worse.

```bash
./gradlew :peer:jmh -Pbench.quick     # 79 measurements, ~8 min (full run: drop the flag)
./gradlew :peer:benchmarkReport       # + build/reports/nodera/BENCHMARKS.md
python3 scripts/bench-report.py --check           # vs fixtures/bench/baseline.json
./gradlew :peer:structureReport                 # + STRUCTURE.md, structure.json, the JDWP profile
./gradlew :peer:structureReport -Pstructure.debug=false   # static half only (~5 s)
```

| Lane | Asserts | Evidence |
|---|---|---|
| `:peer:jmh` (`dev.nodera.bench`) | discovery / chunk-sync / wire / runtime latency are measured per stage, ranked, and diffed against a baseline; superlinear growth is detected from the `@Param` sweep rather than guessed | `build/reports/nodera/BENCHMARKS.md` |
| `:peer:structureReport` (`dev.nodera.structure`) | the debugger observes the real worker loading classes, executing methods, and answering all twelve control verbs; the analysis reaches >1000 methods; every budget in `fixtures/structure/budget.json` holds | `build/reports/nodera/STRUCTURE.md` |

`StructuralReportTest` is tagged `structure` and **excluded from `:peer:test`**, so the module test
counts in the table above are unchanged by it. That exclusion is why `structureReport` declares every
module's `classes`/`testClasses` as task dependencies: an uncompiled module is a module whose callers
vanish, and the analysis would report their callees as dead.

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

## Service selection (Task 13)

```bash
./gradlew :peer:test --tests '*ServiceScoreBoardTest*'      # 20 — scoring and selection
./gradlew :peer:test --tests '*RendezvousDirectoryTest*'    # 11 — discovery + migration, real sockets
./gradlew :transport:test --tests '*ServiceMessageCodecTest*'  # 15 — the wire family + the formula
```

The decisive ones:

- `RendezvousDirectoryTest.a_peer_with_no_configured_rendezvous_learns_one_from_a_tracker` — the
  requirement of the whole lane, driven against a tracker socket.
- `RendezvousDirectoryTest.a_forged_row_is_refused_and_never_dialled` — the trust boundary: a tracker may
  hide a relay or list a dead one, and may not put words in a service's mouth.
- `RendezvousDirectoryTest.a_drain_notice_migrates_the_peer_to_the_replacement_it_names` — the handover,
  with no tracker round trip and no wait for a sweep.
- `RendezvousDirectoryTest.a_sweep_reports_what_it_measured_back_to_the_tracker` — closes the scoring
  loop. Without it the aggregate every peer reads is only the services' own self-praise.
- `RendezvousDirectoryTest.an_unreachable_tracker_leaves_the_previous_selection_in_place` — the
  degradation path: tracker down ⇒ discovery degrades, the relays already selected keep working.
- `ServiceScoreBoardTest.a_failed_probe_is_not_a_fast_probe` — the sentinel that stops a dead relay
  scoring best.
- `ServiceScoreBoardTest.a_recent_recovery_pulls_the_score_back_up` — an outage ages out of the window
  instead of condemning a relay forever.
- `ServiceMessageCodecTest` + `library/rust/nodera-codec/tests/fixtures.rs` — the six frames round-trip
  byte-exactly across languages, **and** the Java-computed composite equals the Rust-computed one. A
  divergence there would silently reorder every peer's failover list.

## Running a suite against the real deployed services

Every suite in `scripts/` normally spawns its own tracker and rendezvous on loopback. `--official`
points the whole run at [the published index](https://github.com/Ashu11-A/NoderaMC/blob/services/index.json) instead, so the
Minecraft clients, the headless peers and the dedicated server all discover and route through the
production architecture rather than through two processes that only ever talk to themselves:

```bash
scripts/nodera-test.sh run continuity
scripts/nodera-test.sh run --official all      # the flag is exported, so every child suite inherits it
```

Nothing local binds 25600/25601 in this mode and the run does not demand those ports be free.

**It is not the default and must not become one.** A suite that needs the internet goes red when
somebody else's host reboots, and all of these run in CI. The default path is byte-identical to what
it was before the flag existed.

### What the flag fixed on its way in

The endpoint list reached the four components by four different routes, and three of them were
wrong in a way one tracker on the default port hides:

| Component | Before | Now |
|---|---|---|
| Headless workers | the real list, via env | unchanged |
| Minecraft client | hardcoded `127.0.0.1:$TRACKER_PORT` | the real list |
| Dedicated server | **no `[tracker]` section at all** — fell back to `NoderaConfig`'s compiled `127.0.0.1:25600` | the real list |
| Paper/Folia endpoint | hardcoded `127.0.0.1:$TRACKER_PORT` | the real list |

So `--trackers 2` started two trackers and told the mod about one, and changing `TRACKER_PORT` left
the dedicated server announcing into the void while every other component used the new port. Both
were invisible because the compiled fallback and the launcher default were the same string.
