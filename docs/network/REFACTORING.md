# Network — Refactoring Register

Source: `build/jscpd/jscpd-report.json` (filtered to `java/transport`, `java/storage`,
`java/peer`, `rust/nodera-codec`) + manual review of god classes, long methods, and dead code.
Line counts from `build/loccount.txt`. Audited **2026-07-28**. Scope: the four modules this
category owns; `build/`/`target/` and generated code are excluded. `% duplicated` is
`(Σ duplicated line-runs ÷ file lines) × 100`, so a small file flagged against several partners
can exceed 100 %.

> This register records candidates and a sequencing rationale only. No code is changed here —
> every row is evidence for a future refactor branch, and rows that touch the frozen wire
> (`MessageCodec`, `WireRegistry`, deleted tags) must clear the cross-language fixture and tag-
> mirror gates in the same commit.

## Duplication and structural candidates

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---:|---|---|
| `transport/.../protocol/EchoTest.java` | 29 | 458.6 | `core/.../action/ActionEnvelope` (+ many) | **Dead code.** Tag 17 has a decoder but no production sender (REFERENCE §11 #9). It is the single largest source of cross-file duplication noise. Remove the runtime class; the frozen tag number is retired, not reclaimed. |
| `transport/.../protocol/content/ArchiveReplicaAck.java` | 59 | 125.4 | `ArchiveReplicaAssignment` | **Dead code.** Tag 31; `ArchiveRepairService` re-audits rather than trusting acks, and no path sends it (REFERENCE §11 #10). Remove; mirror stays in Rust for the frozen registry. |
| `transport/.../protocol/content/ArchiveReplicaAssignment.java` | 62 | 71.0 | `ArchiveReplicaAck` | **Dead code** (tag 30), same row as above. Remove together. |
| `storage/.../storage/CertifiedWorldGenesis.java` | 164 | 118.3 | `WorldAdminProof` | Extract a shared `SealedRecord` helper (canonical bytes + Ed25519 signature + author key) shared by these two and by `PeerCandidate`/`ServiceDirectoryEntry`. |
| `transport/.../protocol/rendezvous/PeerCandidate.java` | 76 | 85.5 | `core/.../action/ActionEnvelope` | Same signed-record pattern as the row above; fold into `SealedRecord`. |
| `transport/.../protocol/discovery/AnnounceEvent.java` | 43 | 83.7 | `CandidateKind` | Two small enums with identical shape; a `WireEnum`-derived template removes the boilerplate (the phase-6 enum machinery already exists). |
| `transport/.../protocol/rendezvous/RelayIncoming.java` | 41 | 75.6 | `content/ManifestHolding` | Small records that differ only in payload type; a generic `TaggedBytes<T>` carrier would collapse them — but only after the TLV plane makes the wrapper free. |
| `transport/.../protocol/rendezvous/RelayConnect.java` | 39 | 74.4 | `PunchSync` | Shared relay-control-record base (`reservationId` + HMAC proof). |
| `transport/.../protocol/rendezvous/PunchSync.java` | 49 | 42.9 | `RelayConnect` | Paired with `RelayConnect`; same shared base. |
| `transport/.../protocol/service/ServiceDirectoryEntry.java` | 67 | 52.2 | `core/.../action/ActionEnvelope` | Third caller of the signed-record pattern; `SealedRecord`. |
| `transport/.../protocol/handshake/ClientHello.java` | 52 | 50.0 | `ChallengeResponse` | **Dead code.** Tags 1–4 were superseded by `Hello`/`HelloAck` (74/75); they exist only in the codec and its tests (REFERENCE §11 #9). Remove runtime + tests; keep the frozen tag numbers. |
| `transport/.../protocol/handshake/ChallengeResponse.java` | 30 | 43.3 | `EchoTest` | **Dead code**, same row (tag 3). |
| `transport/.../protocol/handshake/{ServerHello,WorkerActivation}.java` | — | — | (handshake family) | **Dead code** (tags 2, 4), not jscpd-flagged but listed for the same reason. |
| `transport/.../protocol/RelayEnvelope.java` | — | — | — | **Dead code.** Tag 18 has no runtime sender; the client↔client relay lane was never built (REFERENCE §11 #8). Remove runtime + test. |
| `transport/.../protocol/discovery/InventoryAdvertisement.java` | — | — | — | **Dead code.** Tag 29; the tracker refuses it outright (holdings ride the signed announce), leaving no consumer (REFERENCE §11 #10). |
| `peer/.../diagnostics/metric/TpsMeter.java` | 206 | 48.1 | `TickSkewMeter` | Extract an `IntegerEmaMeter` base (ring + injected clock + basis-point blend). The two meters differ only in what they feed the EMA. |
| `peer/.../diagnostics/metric/TickSkewMeter.java` | 251 | 39.4 | `TpsMeter` | Paired with `TpsMeter`; both are pure-integer, injected-time by category rule. |
| `transport/.../protocol/session/RejectCode.java` | 88 | 47.7 | `wire/WireType` | Both are explicit-code enums; the phase-6 `WireEnum` totality check already covers them — deduplicate the lookup-table boilerplate. |
| `storage/.../storage/event/EventSourcedWorldStore.java` | 74 | 43.2 | `rocksdb/RocksWorldStore` | The shared append guard already exists (`EventChainGuard`); both stores should route every append through it so the duplicated invariants collapse onto one caller. |
| `peer/.../peer/validation/DurableActionJournal.java` | 187 | 39.6 | `DurableInventoryCreditJournal` | Extract a `DurableAppendJournal` base (atomic temp-and-move, fsync, tail read). The two journals are siblings. |
| `peer/.../peer/validation/DurableInventoryCreditJournal.java` | — | 39.6 | `DurableActionJournal` | Paired; same base. |
| `transport/.../protocol/codec/MessageCodec.java` | 1534 | 13.9 | (many) | **God class.** 1,534 lines, 75 `instanceof` arms. Task 14 demoted it to the canonical-only encoder, but the dispatch is still one block. Derive the dispatch from `WireRegistry` (the schema already generates the Rust table) so the Java side is a view over the same rows. Frozen contract — change behind a fixture regen. |
| `transport/.../protocol/wire/InfrastructureCodec.java` | 922 | 15.2 | (many) | 50 TLV shapes, encoder and decoder adjacent. Already one-row-per-message; a code generator off `WireRegistry` would remove the hand-maintenance. |
| `peer/.../peer/PeerRuntime.java` | 888 | 2.7 | — | **God class.** Split membership/gossip, gateway election binding, and message dispatch into collaborators behind the existing `PeerEventListener` seam. Low duplication but high churn surface. |
| `peer/.../peer/discovery/TrackerClient.java` | 754 | 10.1 | — | Split announce / query / catalog / routes / service-directory into separate client classes behind one socket. |
| `transport/.../transport/socket/SocketPeerTransport.java` | 703 | 7.1 | — | Split the authenticated handshake (`TransportAuth`) from the frame read/write loop. |
| `transport/.../transport/rendezvous/RendezvousPeerTransport.java` | 704 | 4.1 | — | Split `TransportSelector` path management from the circuit/reservation lifecycle. |
| `peer/.../distribution/ContentTransferService.java` | 671 | 6.9 | — | Split the serve path from the download/pacing path; the two share only the budget seam. |
| `storage/.../storage/rocksdb/RocksWorldStore.java` | 512 | 17.2 | `EventSourcedWorldStore` | See `EventSourcedWorldStore` row — route appends through `EventChainGuard`. |

## Unwired capabilities — peer-system review, 2026-07-29

Source: `./gradlew :worker:structureReport` §2.2 ("classes only tests and benchmarks reference"),
cross-checked by grep for non-test call sites. These are **not** duplication candidates: each is a
complete, tested capability with **zero production callers**. This repository's dominant defect is not
broken code, it is unreached code — six instances were found in one sweep on 2026-07-24 and the
handshake was another on 2026-07-29 — so the inventory is kept here rather than left to be
rediscovered.

The distinction that matters per row is **wire it or delete it**. A row that a limitation register
cites as evidence must be wired (a green test over an unreachable class is a false claim about the
product); a row nothing cites can be deleted, and deleting it is cheaper than maintaining it.

| Class | Module | Verdict | Why |
|---|---|---|---|
| ~~`protocol.session.Negotiation`~~ | transport | **WIRED 2026-07-29** | L-87/L-88 cited it as their exit evidence while `PeerRuntime.dispatch` never handled a `Hello`. Now sent, answered and recorded on every announce path (`HandshakeRunsInProductionTest`) |
| ~~`protocol.session.PeerSession`~~ | transport | **WIRED 2026-07-29** | Same triangle: `shapeForEmit` was the R3 mechanism with no caller. `PeerRuntime.sendTo` now encodes through `encodeFor` |
| `peer.archival.ArchiveManager` | peer | **Decide: wire or delete** | `assignedManifests` and `reconcile` have no non-test caller, and `WorldReplicationService` already computes placement from `policy.expectedHolders` directly. Two placement implementations, one of which runs. A second defect is visible inside `reconcile`: both branches of `if (assigned.contains(root))` do the same `retained.put`, so the assigned check has no effect on the result — whichever way the row is decided, that line is wrong today |
| `peer.archival.ArchiveAuditTask` | peer | **Decide: wire or delete** | Referenced only by `ArchiveManager`, `ArchiveRepairService` and tests. The audit→repair lane is a closed triangle with no entry point; the live repair path is `WorldReplicationService`'s sweep |
| `peer.archival.ArchiveRepairService` | peer | **Decide: wire or delete** | Third member of the same triangle. Also the reason `ArchiveReplicaAssignment`/`Ack` (tags 30/31) are dead in the table above — the sender that would emit them is this |
| `peer.archival.CustodyAudit` | peer | Delete unless a register cites it | No caller and no citation found |
| `peer.committee.CommitteeManager` | peer | **Wire — a task claims it** | Verified: the only non-test reference is a javadoc mention in `NodeCapabilities`. [`Task.2.md`](Task.2.md) lists it under "Landed" as the mechanism for authority-free certified committee changes; nothing reaches it |
| `peer.sync.EventSyncService` | peer | **Wire — a register cites it** | Verified: zero non-test, non-self references. L-30 cites `EventSyncOverTransportIT` as headless proof that the mechanism works, so that proof is over a class no peer runs. Same correction the handshake needed |
| `peer.discovery.BootstrapClient` | peer | Delete unless a register cites it | Superseded by `PeerDiscoveryService` + `TrackerClient` |
| `peer.discovery.PeerDirectory` | peer | **Decide: wire or delete** | Verified: the only non-test references are a javadoc `{@link}` in `TrackerClient` and `package-info`. `DiscoveryBenchmark.directoryIngest`/`directoryOnline` are two of the four benchmark lanes, so `BENCHMARKS.md` ranks and load-scales a class production never calls — the numbers are real, the relevance is not |
| `peer.validation.GenesisApprovalFlow` | peer | **Check before deciding** | Engine L-20 (single-signer genesis) retired; if this was its mechanism, the retirement rests on it |
| `peer.validation.RegionDelegabilityGate` | peer | Decide with the engine agent | Delegability rules also exist in `coordinator.EntityDelegabilityRules`, itself test-only |
| `peer.PeerShutdownHook` | peer | Delete or install | A shutdown hook nothing installs is a shutdown that does not happen |
| `distribution.ChunkLockEditability` | peer | **Wire — L-33 overstates without it** | Verified and it is the sharpest row here. Both production constructions (`WorkerValidationService.java:365` and `:2093`) call `new WorldMutationApplier(world)`, the one-argument form, which installs `ChunkEditability.ALL_EDITABLE`. So the seam exists, the adapter exists, and **every chunk is editable in production** — L-33's "the edit half is done … a delta touching a piece-locked chunk aborts atomically" describes a wiring nobody installed. Wiring it means plumbing the download lane's live `ChunkLockMap` (held by `PieceDownloader`) into the validation lane, which is [network 4](Task.4.md) scope, not a local fix |
| `distribution.JoinAttemptThrottle` | peer | **Wire — security** | Verified: zero non-test, non-self references. An unreached throttle is an unthrottled join path, which makes this a security property rather than a tidiness question |
| `distribution.WorldArchive.Prepared` | peer | Delete | The only entry in §2.1: referenced by nothing at all, tests included |
| `protocol.wire.MessageRouter`, `protocol.wire.CorrelationTable` | transport | **Decide: wire or delete** | Verified: `MessageRouter` has zero non-test, non-self references. Request/response correlation is a stated NDR2 capability, so the frame's `correlationId` is written by every send and consumed by nothing |
| `transport.rendezvous.HolePunchCoordinator` | transport | **Check with the rendezvous agent** | NAT punching is an §A envelope mechanism (A-4 relies on the bulk lane reaching peers at all) |

Two rules for whoever takes this on. First, **a row is not dead because the tool says so** — the
report excludes anything whose caller could be outside our bytecode, but a call site added in the
same pass that reads this table will not be in it either; re-run `:worker:structureReport` rather than
trusting this list's date. Second, **wiring is not free**: every row above becomes a live code path
with live failure modes, so a row wired without a test that would fail if the call site were removed
has simply moved the defect (`CompanionSessionBindingIsCalledTest` is the cheap shape of that test).

## Sequencing

Ordered by ratio of risk removed to blast radius. Each item is one branch and one PR.

1. **Extract `IntegerEmaMeter` from `TickSkewMeter` / `TpsMeter`.** Highest duplication outside
   dead code, both classes are pure functions with injected time (the determinism rule already
   enforces this), and no frozen contract is involved. The safest place to land the first shared
   base and prove the pattern.
2. **Extract `DurableAppendJournal` from the two `Durable*Journal` siblings.** Same shape, same
   risk profile; both are local-only durability (the coordinator-state precedent), and the
   extraction is mechanical.
3. **Extract a `SealedRecord` helper for the signed canonical records** (`CertifiedWorldGenesis`,
   `WorldAdminProof`, `PeerCandidate`, `ServiceDirectoryEntry`). Four callers of one pattern;
   touches storage and transport but only behind each record's own `encode`, so the canonical
   bytes (the frozen contract) are unchanged and the fixture gate proves it.
4. **Remove the dead-tag runtime classes** (`EchoTest` 17, `RelayEnvelope` 18, handshake 1–4,
   `InventoryAdvertisement` 29, `ArchiveReplicaAssignment`/`Ack` 30/31). They are the dominant
   source of cross-file duplication and several are the #1 partner for unrelated records. Tag
   numbers stay frozen in `WireRegistry`; only the runtime classes and their tests go. Coordinate
   with the tracker/rendezvous agents because the Rust mirror still lists the tags.
5. **Derive `MessageCodec` and `InfrastructureCodec` dispatch from `WireRegistry`.** The largest
   god classes and the highest blast radius: both touch the frozen wire. Land it last, behind the
   existing fixture-regeneration and tag-mirror gates, and only after items 1–4 have reduced the
   noise those gates have to reason about.
