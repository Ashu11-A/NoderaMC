# Network — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: Per-task status ledger for the network category. On every outcome-changing
     commit touching this category: update the §1 row, append a dated §2 milestone note naming the
     EVIDENCE (test or IT name), then reconcile ../ROADMAP.md §2 and the root README bar. Never
     rewrite an old note — append a new one. -->

**Category:** network · **Last audit:** 2026-07-29 · Tasks completed: **12 / 15**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Wire protocol, transports, handshake | ✅ COMPLETED | Tags through 60; authenticated accept handshake (L-53 retired) |
| [2](Task.2.md) | Peer runtime, membership, gateway | 🚧 IN PROGRESS | Election + continuity + certified committee changes ✅; migration end-to-end remains |
| [3](Task.3.md) | Event-sourced + durable storage | ✅ COMPLETED | RocksDB tier, forced-kill recovery, certified forward sync |
| [4](Task.4.md) | Torrent data plane | ✅ COMPLETED | Production consumer live (world-continuity lane); L-33 render half remains |
| [5](Task.5.md) | Discovery + multi-bootstrap + identity | ✅ COMPLETED | Serving role moved to the tracker service |
| [6](Task.6.md) | Placement, replication, repair | ✅ COMPLETED | `WorldReplicationService` closed the "announced but never replicated" gap |
| [7](Task.7.md) | Reliability, quotas, retention | ✅ COMPLETED | Countdown network-visible on every announce |
| [8](Task.8.md) | Per-world content encryption | ✅ COMPLETED | Re-key now actually revokes (L-55) |
| [9](Task.9.md) | Crash safety + active-player stream | ✅ COMPLETED | Continuous streaming + bounded final flush + freshness guard |
| [10](Task.10.md) | Tick-lag + low-TPS handoff | ✅ COMPLETED | Gained a live call site 2026-07-25 |
| [11](Task.11.md) | Telemetry core | ✅ COMPLETED | Honest CERTIFIED/PENDING/SOLO region status |
| [12](Task.12.md) | Telemetry emitter core | ✅ COMPLETED | 21 tests + the cross-language registry mirror; L-76 RETIRING |
| [13](Task.13.md) | Measured service selection | 🚧 IN PROGRESS | 30 new Java tests; the mod's own transport still reads a static list (L-91, renumbered from a duplicate L-84 on 2026-07-28) |
| [14](Task.14.md) | Cross-version wire protocol | 🚧 IN PROGRESS | All 8 phases landed; `NDR2` + TLV live on both languages; L-86 + L-89 RETIRED on green headless exit tests (issue #97); L-87/L-88/L-90 RETIRING pending a live mixed-release run |
| [15](Task.15.md) | Structural benchmarking + structural code report | ✅ COMPLETED | Four JMH lanes (`:peer:jmh`), ranked report with load-scaling + baseline diff, bytecode dead-code/cost report and JDWP worker probe (`:worker:structureReport`), both ratcheted and both on the `benchmarks` workflow |

---

## 2. Milestone notes (newest first)

### 2026-07-29 — The peer system can be measured, and the tree can be audited (task 15)

Two instruments landed, each with a ratchet so its numbers cannot quietly get worse.

**`./gradlew :peer:jmh` — four benchmark lanes** over the real classes: peer discovery (route parse,
directory ingest at 16/256/1024 peers, warm-start cache, gateway election), chunk synchronisation
(snapshot → blob → pieces → rarest-first order → holder choice → verify → reassemble → state root,
across three piece sizes and two holder counts), the wire codec, and the always-on worker's
per-second internal latency. `scripts/bench-report.py` renders `build/reports/nodera/BENCHMARKS.md`:
a ranked bottleneck table, a **cost-growth** table (cost ratio ÷ input ratio per parameter, so
superlinear work is visible before the network is large enough for it to hurt), and a diff against
`fixtures/bench/baseline.json` that calls a regression only when a result is both >25% slower and
outside its own error bars.

**`./gradlew :worker:structureReport` — the structural code report.** ASM reads every module's
bytecode for the reference graph and for in-loop cost (allocation, boxing, string building, linear
scans, regex compilation, monitors) plus methods past HotSpot's 8000-byte compile limit; then JDI
attaches to the **real, unmodified `nodera-headless` process** over JDWP and records which classes
load and which methods execute while the twelve control verbs the companion app sends are driven
against it. Evidence: `StructuralReportTest` (probe observed 356 classes / 409 methods / 942 entry
events), `fixtures/structure/budget.json`.

Its first run found eight `*Action#decode(CanonicalReader)` full-frame decoders with no caller
anywhere (production goes through `GameAction.decode` into `decodeBody`), a `WorldArchive.Prepared`
record referenced only by itself, 33 classes only tests and benchmarks reference, and 7 quadratic
`List.contains`/`remove`-in-a-loop sites — one of them on the worker's `NODERA-CONFIG` path.

The running process also corrected the analysis twice, which is the point of having it: references
now resolve through supertypes (nothing in the bytecode names the anonymous class implementing an
interface), and a `<clinit>` is an entry point when its class is used (every `static final`
singleton is built there). Report section 4.3 — "static analysis contradicted by the running
process" — is now empty.

### 2026-07-28 — L-86 and L-89 RETIRED (issue #97)

The two cross-version rows whose exit tests are entirely headless retired this pass. Each row's
*stated* exit clause names specific unit/IT methods with no live-run component, and every one of them
is green at the last gate, so by the binding rule — *retire when the stated exit test is green, not
when the narrative "remaining" note looks small* — they move to
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) now rather than waiting on the live mixed-release run
that still holds L-87/L-88/L-90.

**L-86 (R1 — positional, non-delimited bodies):** a field a newer peer appended is skipped at any
depth, and a field an older peer omitted takes its default. Evidence:
`ForwardCompatibilityTest.aFieldAppendedByANewerPeerIsIgnored`,
`aFieldAppendedInsideANestedStructureIsIgnored`,
`aFieldTheSenderOmitsTakesItsDocumentedDefault`, and
`CrossVersionIT.aFutureFieldSurvivesARelay`.

**L-89 (R4 — one codec for two opposite requirements):** a consensus payload crosses the tolerant
plane as one opaque `BYTES` field, and no infrastructure decoder parses a signed structure. Evidence:
`ForwardCompatibilityTest.aConsensusPayloadCrossesAsOpaqueBytes` +
`theTwoPlanesAreExactlyPopulated`.

All six named methods were confirmed to exist and pass under
`./gradlew :transport:test --tests 'dev.nodera.protocol.wire.ForwardCompatibilityTest' --tests 'dev.nodera.protocol.wire.CrossVersionIT'`;
the full gate (`./gradlew check`, `cd rust && cargo test` — the codec mirror matters) is green. Task
14 stays 🚧 IN PROGRESS: L-87, L-88 and L-90 remain RETIRING, each pending the live mixed-release run.

### 2026-07-28 — `MessageRouter.answerFor` no longer compiles to a `SwitchBootstraps` type-switch

The Android bytecode guard (issue #94, [`mobile/LIMITATIONS.md`](../mobile/LIMITATIONS.md) M-5)
rejected `MessageRouter.answerFor`: its `switch (outcome) { case Outcome.UnknownKind u -> … }` is a
Java-21 **type-pattern switch**, which compiles to an `invokedynamic` on
`java.lang.runtime.SwitchBootstraps.typeSwitch` (verified in the class file — constant pool
`InvokeDynamic typeSwitch`, bootstrap `SwitchBootstraps.typeSwitch`). ART does not implement it and
D8 cannot desugar it, so the first encode through that path would crash on Android. This is the same
class of defect as M-8: a new type-switch slipped back into the worker's closure after M-8's rewrite
of the original ten. It is a genuine violation — the guard's compiled-dex `invoke-custom` /
`SwitchBootstraps` check is the correct signal, not an over-match — so the fix rewrites the switch to
an `instanceof` chain (byte-identical behaviour), following the convention already used in
`CommitteeSession` and `EntityRuleSet`.

A regression is now pinned at the Java gate, not only in the dex: `AndroidTypeSwitchGuardTest` scans
this module's compiled `.class` files and fails if any reference `SwitchBootstraps` / `typeSwitch`,
so `./gradlew check` catches the next slip without needing Android build-tools. `MessageRouterTest`
also gains `answerForOnlyAnswersTheOutcomesThatCarryANack`, pinning the rewritten chain against all
five `Outcome` variants. Evidence: `scripts/check-android-bytecode.sh` reports *0 invoke-custom sites,
0 SwitchBootstraps references*; `:transport:test` green (186 cases). Task 1 remains ✅ COMPLETED.

### 2026-07-28 — One secure atomic writer for identity and worker state

`AtomicFileWriter.writeOwnerOnly` now owns POSIX-at-create, non-POSIX capability selection, atomic
replacement fallback, and failed-write/move temp cleanup for both `PersistentIdentityStore` and the
worker's `LocalFiles`. `AtomicFileWriterTest` (3) proves owner-only output, temp deletion, and
suppressed cleanup errors. Task 3 remains completed; storage report count is 157.

### 2026-07-28 — L-85 gets a headless proof; the live-gated rows are re-checked and stay

**A seam, not a rewrite.** L-85's status line said the row had no headless proof because
`TrackerClient` is final and concrete. That is now false: `TrackerLookup` (`:peer`) is the read
half of a tracker — `endpoints`, `query`, `routes` — which `TrackerClient` implements and
`WorldArchiveService` depends on instead of the class. Announcing is deliberately *not* on the
interface: it is a write with a cadence and an identity behind it, and no content lane needs it.

**What that buys.** `SeederRouteSurvivesTheTrackerAnswerTest` (3, `:worker`) can now hand in a
tracker that answers the seeder query and **not** the routes query — the exact live shape behind
`no routable seeder for world d454b2264b84`. It asserts the entry's own route is kept, that the
routes query still supplements an entry that carried none, and that an `mc/` claim is rejected
from both sources. The first case was **verified failing** with the fix line reverted, so it is
testing the fix and not the fixture. `WorldArchiveService.routeOf` is public for the same reason
the bug existed: "known" and "routable" have to be readable apart.

L-85 stays **OPEN** regardless — its exit test is a live join, and that is unchanged.

**Nothing else could be retired, and the register now says why per row.** Each remaining row was
re-checked against its exit-test column. The notable finding is for L-87, L-88 and L-90: their
exit-test columns name only headless tests, all green here under `--rerun-tasks --no-build-cache`
(`NegotiationTest` 10, `WireEnumRulesTest` 9, `CrossVersionIT` 5, 0 failures), but `Task.14.md`
holds an explicit unticked acceptance criterion — a live mixed-release run of two real processes
from different builds. The CI job meant to automate half of that (`cross-version.yml`
`previous-release`) is currently a **no-op**, because it skips unless a release tag other than the
moving `latest` exists and none does. So the gate is real and cannot be cleared from here.

### 2026-07-28 — Documentation sweep: audit, register hygiene, refactoring register

Category-wide status reconciliation against the tree. No status changed: tasks 1, 3–12 stay
✅ COMPLETED and tasks 2, 13, 14 stay 🚧 IN PROGRESS, each matching the code and the exit tests
named in its file. Every `Last audit` is bumped to 2026-07-28.

**Register hygiene.** `LIMITATIONS.md` carried two different rows both numbered **L-84**: the
task-2 "joining peer could not send to its own bootstrap" row and the task-13 "the mod composes
its rendezvous transport from a static config list" row. The task-13 row has been renumbered to
**L-91**; `Task.13.md`, `Task.0.md` and this ledger carry the new id. No row text was lost, and
the open/retiring summary is corrected: 11 rows total (OPEN: L-30, L-85; RETIRING: L-33, L-76,
L-84, L-86…L-91).

**No limitation retired this pass.** L-30 and L-85 stay OPEN (both need a live run; their headless
exit tests are unchanged). L-33, L-76, L-84, L-86…L-91 stay RETIRING — each needs a live suite or
wild telemetry that has not been produced. Every exit test named in the register was confirmed to
exist in the tree (`BootstrapAddressHasNoNodeIdTest`, `HeartbeatSurvivesABadBootstrapTest`,
`CallerRouteIsDirectTest`, `ForwardCompatibilityTest`, `NegotiationTest`, `MessageRouterTest`,
`WireEnumRulesTest`, `CrossVersionIT`, `ResidentQuorumIT`, `EventSyncOverTransportIT`,
`TelemetryEmitterTest`, `TelemetryRegistryMirrorTest`, `WorkerStateParserRendezvousTest`).

**New: [`REFACTORING.md`](REFACTORING.md).** A register built from `build/jscpd/jscpd-report.json`
filtered to this category's four modules plus manual god-class/dead-code review. The five highest-
signal candidates, in order: extract `IntegerEmaMeter` from the `TickSkewMeter`/`TpsMeter` pair
(48 %/39 % duplicated); extract `DurableAppendJournal` from the two `Durable*Journal` siblings
(40 %); extract a `SealedRecord` helper for the four signed canonical records; remove the dead-tag
runtime classes (`EchoTest` 17, `RelayEnvelope` 18, handshake 1–4, `InventoryAdvertisement` 29,
`ArchiveReplicaAssignment`/`Ack` 30/31) — the dominant source of cross-file duplication; and
last, behind the fixture and tag-mirror gates, derive `MessageCodec`/`InfrastructureCodec`
dispatch from `WireRegistry`.

**Counts grep-verified.** `@Test` annotations: transport 184, storage 154, peer 574 (was listed
595; `rg` over `java/peer/src/test` gives 574), `nodera-codec` `#[test]` 73. The headless-worker
tests live in `java/worker` and are accounted under the worker category.

### 2026-07-28 — the wire was rebuilt, and a peer from another release can now stay on the network

Task 14, all eight phases. The frame is `NDR2` — magic, epoch, kind, flags, correlation, length —
and infrastructure bodies are canonical TLV. The consequence is the one the programme exists for: a
field a peer has never heard of is skipped rather than destroying the rest of the message, so there
is finally such a thing as a compatible change.

**Evidence.** `ForwardCompatibilityTest` (10), `NegotiationTest` (11), `MessageRouterTest` (11),
`WireEnumRulesTest` (9), `CrossVersionIT` (5), `WireSchemaGeneratorTest` (6), plus the Rust
`tag_mirror` (5), `fixtures` (4), `mutation` (1) and the `tlv`/`frame` unit tests. Full gate green:
`./gradlew check` and `cd rust && cargo test`.

**The flag day happened.** Every Java transport and every Rust service speaks the new frame;
`MessageCodec` is now the canonical encoder for hashing, signing and the consensus plane and is no
longer what crosses a socket. The old corpus is retained under `fixtures/wire/v1-rejected/`, where
both languages assert it is **refused at the magic** rather than misparsed. The deployed tracker and
rendezvous need redeploying with this, not after it.

**Two things the work found that the audit had not.**

- The first TLV decoder was *lossy in both directions*: an unknown field was skipped and then
  dropped on re-encode, and an absent known field was re-emitted with a default. Either way a peer
  relaying between two newer peers silently rewrote the message. `TlvOverlay` fixes both, at every
  nesting depth.
- The ArchUnit no-`ordinal()` rule was **vacuous**. `callMethod(Enum.class, "ordinal")` matches
  nothing, because the call site's bytecode owner is the concrete enum. Found by planting an
  `ordinal()` call and watching the rule stay green.

**Why the rows are RETIRING and not RETIRED.** Every exit test passes headless. None of it has run
as two real processes from different builds on one network, and until it does the register does not
get to claim it.


### 2026-07-28 — The harness first, and what it found

[`plans/Plan.7.md`](../plans/Plan.7.md) opened the cross-version wire programme, and its phase 0 —
the conformance harness, no production code — landed green on both gates. The ordering was
deliberate: every later phase is verified against this corpus, and a harness that measures the wrong
thing is worse than none.

It was measuring the wrong thing. The golden corpus asserted **five** tags of 72 and wrote a missing
fixture silently instead of failing, so a message could reach production with bytes nobody had ever
reviewed — the file appeared in the working tree as a side effect of a green run. The dispatch test
covered 25 of 72 types from a hand-maintained class list. `TypeTagsTest` had silently dropped
`CONTAINER_ACTION = 29`, a tag live on the wire. The Rust truncation test called the decoder and
discarded the result, so a decoder that accepted a half-frame would have passed it, and its append
test routed every fixture to `DiscoveryMessage`, which meant rendezvous fixtures were rejected as
*unknown tag* rather than proving trailing bytes are detected. The tag mirror checked a hand-written
list of constants plus a watermark, which cannot see a renumbering below the watermark.

All of that is now total and driven off one registry (`MessageSamples`, 72 samples, 25 + 52
fixtures), and the mirror was falsified before being trusted — perturbing `TRACKER_QUERY` to 26 fails
with the intended message.

Then the new mutation fuzz — same invariant, both languages, same corpus — reported **999
canonical-encoding violations across 30 message types**, all pre-existing:

- **Malformed UTF-8 was replacement-decoded on the Java side and rejected on the Rust side.** Java
  substituted U+FFFD and re-encoded different bytes: a live Java/Rust hash divergence.
- **Booleans accepted any nonzero byte in *both* implementations** — one value with 256 wire
  spellings. `NodeCapabilities` bypassed the shared boolean reader with `!= 0` in each language
  independently. These two together took the Java count from 999 to 51.
- **Decode-time normalization** gave several messages two valid spellings: `ContentRequest`,
  `ArchiveReplicaAssignment`, and `ArchiveReplicaAck` sorted and de-duplicated their piece indexes,
  and role sets absorbed duplicates and any order.

All fixed on both sides; the count is 0. No valid frame changed — encoders already emitted the
canonical form, so the fixture corpus regenerated byte-identically. Two exceptions remain, each named
in the test with the phase that removes it: `RegionRefusal`'s unknown-reason collapse (phase 6) and
`PeerJoin`'s optional-tail truncation ambiguity (phase 3, and it *is* L-86).

Evidence: `MessageSamples.assertTotal`, `WireFixtureTest` (5), `MessageCodecTypeTagTest` (72/72),
`CanonicalMutationFuzzTest` (3), `TypeTagsTest`, `tag_mirror.rs` (5), `fixtures.rs` (3),
`mutation.rs` (1). `./gradlew check` and `cargo test` both green.

### 2026-07-27 — The route the tracker had already sent

The instrumentation added in the previous pass paid for itself on the first run. The recovery
screen stopped saying "Migrating world…" for two minutes and said, in two seconds:

```
Recovery failed: no routable seeder for world d454b2264b84
```

That is a routing failure, not a throughput one — which settles a question two earlier passes had
guessed at and got wrong. `ArchiveFetchThroughputTest` had already shown the lane moves 18 MB in
1.9 s in-process; the pieces were never slow, they were never requested.

`resolveSeeders` asks the tracker twice: a seeder query, then a routes query. The seeder query's
answer carries `PeerEntry`, and a `PeerEntry` **has a `route`** — which this code dropped on the
floor, keeping only the node id, and then depended on the second query to supply the very thing it
had just discarded. Any tracker that does not answer the routes query — an older build, a dropped
datagram — leaves every seeder known and unreachable. The host had announced to that same tracker
three seconds earlier.

Fixed: the route on the entry is kept, both sources go through one `rememberRoute` that rejects
`mc/` game-endpoint claims, and the lookup logs what it found
(`N seeder(s), M routable, over K tracker(s)`) so the next failure of this shape names itself.

Also in this pass, from the same screenshot — both defects mine, introduced two passes earlier:

- the failure text asserted "this world is password protected" for a world shared with
  `encryption=false`. `CompanionClient.fetchArchive` now returns the worker's own reason instead of
  discarding it, and continuity prints that verbatim. A cause the client cannot observe is a cause
  it must not name — this was the second wrong guess printed in that exact spot;
- the message was drawn with `drawCenteredString`, which does not wrap, so it ran off both edges
  with its first and last words cut off. It now wraps to `width - 80`. This is MC-GUI-2, written
  into new code after being catalogued.

And the fetch deadline is now a **stall** deadline rather than a wall clock. A flat budget asks
"has this taken too long?", which is wrong in both directions: it kills a large world that is
transferring perfectly well, and it lets a download that is receiving nothing look busy for two
minutes. Progress is the question; a fetch that keeps moving keeps going.

### 2026-07-27 — The joiner that could not speak to the peer it was joining

Third live reproduction of a stuck "Migrating world…", and the first one whose cause is in the
transport rather than the archive lane. The host quit to the title screen — the ordinary continuity
case — and the other player's client sat on the recovery screen while both workers were online and
the seeder held every piece (208 pieces / 51.4 MiB on the dashboard).

The joiner's log carried this sixteen times:

```
Exception in thread "nodera-peer-state-4cf0c965" java.lang.NullPointerException:
    rendezvous transport requires a nodeId
  at RendezvousPeerTransport.dispatch(RendezvousPeerTransport.java:255)
  at PeerRuntime.sendTo(PeerRuntime.java:792)
  at PeerRuntime.onHeartbeatTick(PeerRuntime.java:610)
```

The address is the **bootstrap**: a route with nobody's name on it, because a joiner dials an
address before it can know who is listening — learning that is what the `PeerJoin` it is trying to
send is for. `dispatch` rejected it at line one, above the code that has honoured a caller-supplied
route since L-30. So the joiner could never announce itself, its membership stayed `{self}`, and
the download had no mesh behind it.

Three fixes, at three layers, because one alone would have left the shape intact:

- an unnamed address with a dialable route goes over the direct transport, and one without a route
  fails as a `TransportException` — the type every caller is written to tolerate, never an unchecked
  throw that unwinds a scheduler thread;
- `PeerRuntime.sendTo` absorbs unchecked failures as well as checked ones, honouring the contract it
  already documented;
- `onHeartbeatTick` isolates the bootstrap re-announce, so the `SessionKeepAlive` broadcast at the
  bottom cannot be skipped by anything above it.

Evidence: `BootstrapAddressHasNoNodeIdTest` (3) and `HeartbeatSurvivesABadBootstrapTest`, both
verified failing with the fixes disabled.

Measured in passing, because the same run looked like a throughput problem and was not:
`ArchiveFetchThroughputTest` moves an 18 MB archive between two services in **1.9 s (~9.8 MB/s)**.
The lane's own pacing is not slow; what was slow was a joiner with no mesh.

### 2026-07-27 — A peer stops being told where its relays are

The rendezvous list was a configuration string, and three things followed from that: adding a rendezvous
to the network reached nobody, losing one was an outage for everyone configured to use it, and no peer
could prefer the relay that actually worked for it. `RendezvousDirectory` replaces it — ask the trackers,
verify every row, probe the candidates, keep the best few, report what was measured.

Two properties are worth naming. **A peer scores what it measured**, because a relay with excellent global
availability that *this* peer cannot reach is useless to this peer; the tracker's aggregate is the
evidence a joining peer has and the tie-break when a peer's own probes cannot separate two relays. And
**several endpoints are selected, all of them used**: registering with several and querying only the first
converts redundancy into a silent single point of failure.

Two sentinels had to be made explicit, and a test rather than review caught the second: `Reachability`
reports `-1` for unreachable, and a score with no reporters carries `rttP95 == 0`. Read naively, both make
an unusable relay the fastest-scoring thing in the directory.

**A real defect fixed on the way through.** `RendezvousPeerTransport`'s accept loop slept 500 ms after a
failed re-reservation and then *returned*, ending the peer's inbound relay path until the process
restarted — so a rendezvous restart was a permanent, invisible outage for anybody who had reserved there.
It now retries across every endpoint with capped backoff, forever. A second one on the app side:
`worker_env` never passed `NODERA_RENDEZVOUS_ENDPOINTS`, so a user's configured relay was silently
ignored; `the_configured_rendezvous_endpoints_reach_the_worker` pins it.

### 2026-07-26 — The node remembers who behaved

`PersistedCoordinatorState` has existed since Task 6 with nowhere to live. The consequence was
quiet enough to survive a year of audits: every restart reset the epoch counter that defends against
stale proposals, and every restart made the node reputation-blind, so a peer that had spent an
evening disagreeing with committed roots came back with a spotless score.

`DurableCoordinatorState` gives it a file beside the action, credit and vote journals. The live
session attaches it on open, checkpoints every 30 s — a crash is the case durability exists for, and
a crash never reaches `close()` — and flushes on close.

Two decisions worth keeping: the file is **local, never consensus** (a node's opinion of its peers
is a view, and two honest nodes may hold different ones — which is why it sits beside the journals
and not in the certified chain), and **damage is recovered from rather than thrown**. Losing this
state costs the node its memory of who behaved, which observing the network rebuilds; refusing to
start would cost it the world. `DurableCoordinatorStateTest` (6) covers both, including the heal:
after a corrupt file is read, the next flush replaces it with a readable one.

### 2026-07-26 — The "known flake" was a missing latch release

`SocketPeerTransportAuthTest` had a documented workaround — run the transport suites with
`--no-parallel --max-workers=2` before assuming a regression — and a CI failure on a telemetry
branch made it worth looking at rather than re-running.

The exception was `auth handshake timed out`, thrown from `SocketPeerTransport.writeFrame`. A sender
in authenticated mode waits for its own read loop to see the remote's challenge and write the signed
hello; the read loop's `finally` closed the socket but **never released that latch**. So a
connection that died mid-handshake left every sender blocked for the full 10 s and then reported a
*timeout* — which sends whoever reads the log looking for a slow peer when the peer is gone. CPU
starvation was not the cause; it was the trigger.

Three changes, and the middle one is the fix:

- teardown releases `authHelloWritten`, so a dead peer costs a sender **4 ms** instead of 10 s;
- a sender distinguishes "connection closed during the auth handshake" from "timed out", because
  the two send a reader to different places;
- the timeout became a named constant at 30 s, which now only has to cover a starved scheduler.

`aPeerThatDiesMidHandshakeFailsTheSenderImmediately` asserts on the clock as well as the exception —
a fail-fast that takes 30 s is the bug wearing a passing test's clothes. The full gate at maximum
parallelism, which is what used to flake, is green.

### 2026-07-25 — The emitter core lands, and consent gates collection

`dev.nodera.telemetry` is in (`TelemetryEmitterTest`, 21). The decision worth recording is the one
the acceptance criterion is written around: **consent gates collection, not transmission.**

`consentDeniedProducesNoEventsAtAll` asserts that a denied node builds no event object at all —
not that it builds them and drops them. The difference is not cosmetic. A design that collects into
a buffer and discards it later is one flag away from shipping data nobody agreed to, and it spends
the CPU cost of measurement on the people who declined.

Two supporting decisions came out of writing it:

- **Bucketing lives in one class.** If each call site coarsened its own values they would drift, and
  two events would disagree about what "large" means. `Buckets` is the only place a measurement
  becomes a statistic, and its boundaries are what the receiver's declared ranges are written against.
- **The install id is regenerated on every grant.** Turning telemetry off and on again produces a
  *new* installation as far as the pipeline is concerned, so a revocation cannot be undone by
  re-granting — nothing the client sends links the two eras.

`TelemetryRegistryMirrorTest` runs `nodera-telemetry --print-schema` and compares it with the Java
registry, the same mechanism the wire-tag mirror uses. It skips when the binary is absent, so the
pure-Java gate stays honest, and `scripts/e2e-telemetry.sh` + the `telemetry` CI job build it first.

### 2026-07-25 — A twelfth task: the meters get a second consumer

[Task 11](Task.11.md) built one snapshot per tick for *this node's own screens*. [Task 12](Task.12.md)
adds the second consumer — a consented, bucketed projection of the same measurements, shipped to the
telemetry plane ([`../plans/Plan.6.md`](../plans/Plan.6.md)).

Worth recording as a boundary rather than a feature: the two consumers are deliberately not the same
code path. The HUD reads exact numbers about the machine it runs on, because that is the machine's
own business; telemetry reads **buckets**, behind a consent gate, because a byte-exact world size is
a fingerprint and a bucket index is a statistic. Sharing the projection between them would have made
the privacy properties depend on which caller happened to be asking.

Registered **L-76**: nothing measures NoderaMC in the wild, so every claim about behaviour on real
machines is currently the authors' own machines plus CI.

### 2026-07-25 — 



### 2026-07-25 — Settings that lied stop lying: L-57 and L-58 RETIRED

Both rows described settings the app exposed but the node did not honour as documented.

**L-57** asked for the download cap's overshoot bound to be *measured*, and measuring it found the
documented claim was false: the gate admitted a request whenever any credit remained and then charged
the whole request, so a multi-piece batch overshot by several pieces while the docs promised one.
`admitRequestBytes` now discounts the request's largest piece and requires the rest to fit — bounding
the overshoot at exactly one piece, while still letting a budget smaller than a single piece make
progress instead of deadlocking the download.

**L-58** was reported as `restart_required`, and that framing hid the real defect: the blobs a node
holds *are* its seeding obligations, so re-pointing the archive directory without moving them would
leave a world listed on the tracker while every piece request missed — worse than refusing the change.
`FsContentStore.relocateTo` moves the content first and re-points the store only after; content
addressing makes it safe. The p2p bind port stays restart-required — an open listener is a different
problem from a movable directory.

### 2026-07-25 — Permission and archive hygiene: L-54 and L-55 RETIRED

Both were "noted" follow-ups that had never been built, and both were quietly security-relevant.

**L-54 — grants now exist on the mesh, not just on the author's disk.** A co-hosting peer's
permission set stayed author-local, so an operator promotion or a ban simply did not exist for the
rest of the network. `WorldGrantGossipService` relays every grant over `WorldGrantGossip` (tag 60).
The transport carries the decision without ever being trusted with it: the grant is opaque bytes and
every receiver re-verifies both the signature and the granter's authority against the world's author
key. `GrantGossipIT` (6) covers the attacks too.

**L-55 — a password re-key now actually revokes the old password.** A re-key appended a new encrypted
manifest and left the previous one seeded, and the superseded blob is still decryptable with the OLD
password — so rotating it revoked nothing. `supersedeOlderVersions` drops every older version from
the manifest table, from holdings, and from the content store itself.

### 2026-07-25 — Mesh population and boundary independence

Three live-play defects shared one root: *a region's committee was whatever players happened to be
standing in it, and nothing on the live lane ever noticed when its primary stopped keeping up.*

1. **Workers hold committee seats through a player's disconnect.** `ResidentQuorumIT` runs the
   scenario — two standing workers plus player nodes — and shows the committee holding at quorum size
   across a logout, still committing, with the certificate co-signed by a peer that has no Minecraft
   process. The counterfactual (no residents ⇒ committee of one) is asserted alongside.
2. **A laggy player no longer wedges its regions for everyone.** The handoff lane existed and was
   tested but had **no live call site**. `forwardLagTickBps` measures the age of the oldest forwarded
   action the primary has not answered — literally what the player at the boundary is waiting on
   (`LiveLagHandoffIT`, 4).
3. **Region status stops reading as a permanent "unsigned".** CERTIFIED / PENDING / **SOLO**: a
   committee of one is a population shortfall that no amount of waiting fixes, and the panel now says
   so (`RegionCertificationTest`, 8).

Two latent faults surfaced en route and were fixed: a `CommitAnnounce` for a **revoked** replica threw
an illegal pipeline transition out of the peer state thread (killing it), and a newer-epoch
`RegionAssigned` re-activated a **live** replica from the genesis snapshot, rewinding it to v0.

### 2026-07-24 — Discovery and telemetry audit remediation

An end-to-end audit of the tracker → rendezvous → worker → mod → companion chain found four
capabilities that were *built and tested but never connected*, each of which made the system quietly
weaker than its own docs claimed.

1. **Direct-first was structurally unreachable.** `RendezvousPeerTransport` advertised only a RELAY
   candidate, so no discovered peer ever had a direct candidate and every byte crossed the relay — the
   exact inversion the reference warns about. It now publishes a HOST candidate from the direct
   transport's listen route and renews at half the TTL instead of silently vanishing from discovery.
2. **Trackers were never a peer-discovery plane.** Session membership came exclusively from one
   bootstrap route plus its gossip. `PeerDiscoveryService` sweeps every tracker and rendezvous and
   introduces this node to each routable peer — merged, never arbitrated.
3. **A shared world's *bytes* were not shared.** Announcing published where a world was; nothing
   replicated it. `WorldReplicationService` runs the placement policy over the tracker directory and
   adopts the worlds this node is placed for.
4. **The piece map had no source.** A `NODERA-PIECES` verb, a parser, and a feed replaced an
   always-empty grid.

Alongside: scheme-aware tracker endpoints with a real UDP surface (bounded against reflection
amplification, silent rather than truncating when an answer would exceed the bound, with a TCP
fallback), and per-peer traffic totals replacing hardcoded zeros.

### 2026-07-23 — Rust cluster complete; world-continuity lane

The standalone tracker and rendezvous services landed, retiring the embedded Java tracker and the
never-built libp2p transport plan. In the same period the world-continuity lane made "the host
disconnects — the world must not die with them" hold end to end: a save is packed into the canonical
`WorldArchive`, filed under the piece plane, seeded to the always-on worker, served to the swarm, and
fetched back by any peer's worker. `WorldContinuityIT` proves the chain over the real service
binaries, including full host-worker death.
