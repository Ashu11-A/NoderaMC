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
