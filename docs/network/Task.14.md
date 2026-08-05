# Network Task 14 — Cross-version wire protocol

<!-- AI-AGENT-INSTRUCTION: This task executes plans/Plan.7.md. Three rules are load-bearing.
     (1) The plane split is the design: if a value is hashed or signed it is CONSENSUS and stays
     strict; anything ambiguous is consensus, because wrongly-tolerant forks the network and
     wrongly-strict only costs a feature. (2) Phase 3 is a FLAG DAY — the deployed tracker and
     rendezvous redeploy in the same operation, and the v1 fixture corpus is retained as a REJECTION
     set. (3) The phase-0 harness is the proof for every later phase: never weaken an assertion in it
     to make a phase pass; if a phase cannot satisfy it, the phase is wrong. Keep this header's
     status accurate. -->

**Status:** 🚧 IN PROGRESS — all eight phases landed; L-86 and L-89 RETIRED on their green headless exit
tests (issue #97); L-87, L-88, L-90 RETIRING pending a live mixed-release run. **Correction
2026-07-29:** phase 4 had shipped `Negotiation`/`PeerSession` with **no production call site** — the
same "exists in the codec and its tests, no runtime handler" defect the phase was written to remove,
moved one class along. Both are now wired into `PeerRuntime` (`HandshakeRunsInProductionTest`); the
rows are unchanged in status, but they are now RETIRING on the live run rather than on green tests
over unreachable code.
**Category:** network · **Owns:** L-86, L-87, L-88, L-89, L-90 · **Last audit:** 2026-07-29
**Depends on:** [network 1](Task.1.md), [network 2](Task.2.md), [tracker 5](../tracker/Task.5.md),
[rendezvous 5](../rendezvous/Task.5.md)
**Consumed by:** [worker 3](../peer/Task.3.md), [minecraft 5](../minecraft/Task.5.md),
[frontend 2](../frontend/Task.2.md), [server 1](../server/Task.1.md)

---

## Goal

Two peers built from different releases stay on the same network. They discover each other, mesh,
seed a world, relay, and tunnel a game session; the only thing a version difference costs them is a
seat on each other's committees, and that is stated at the handshake with a coded reason rather than
discovered by a frame that silently fails to parse. Adding a field to an infrastructure message
becomes a non-event for an un-upgraded peer.

## Status detail

**All eight phases have landed and the whole gate is green** — `./gradlew check` and
`cd rust && cargo test` both clean, with the Rust services rebuilt and the real-binary integration
tests passing against the new frame.

### What the wire is now

```
NoderaFrame = magic:u32 'NDR2' | epoch:u16 | kind:u16 | flags:u16 | correlationId:u64 | len:u32 | body
body        = fieldId:u16 | wireType:u8 | len:u32 | value        (infrastructure plane)
            | fieldId 1 → the strict canonical bytes, opaque      (consensus plane)
```

75 kinds: **50 infrastructure**, **25 consensus**. Three of the 75 are new — `Nack` (73), `Hello`
(74), `HelloAck` (75) — and they are what tags 1–4 were supposed to be.

### Phase by phase

| # | Phase | Landed as |
|---|---|---|
| 0 | Harness | Total sample registry, complete fixture corpus, fail-on-missing, total tag mirror, mutation fuzz in both languages |
| 1 | Schema | `WireRegistry` — one declarative row per kind. `KNOWN_TAGS`, `typeName` and the 144-arm `instanceof` chain are now **views over it**. `library/rust/nodera-codec/src/kinds.rs` and `fixtures/wire/manifest.json` are **generated** from it, and `tag_mirror.rs` re-parses the Java source to prove the generated file is fresh |
| 2 | Planes | `MessagePlane`, and the classification of all 75 kinds. Consensus payloads cross as one opaque `BYTES` field |
| 3 | Frame | `NoderaFrame`, `TlvWriter`/`TlvReader`/`TlvOverlay`, `InfrastructureCodec` (one row per message, encoder and decoder adjacent), `WireCodec`. Mirrored in Rust by `frame.rs`, `tlv.rs`, `wire.rs`. Corpus regenerated; the v1 bytes retained under `fixtures/wire/v1-rejected/` as a **rejection set** both languages assert |
| 4 | Negotiation | `Hello`/`HelloAck`/`Negotiation`/`PeerSession`, feature **intersection**, OBSERVER admission, coded rejects, identity bound to the transport |
| 5 | Router | `MessageType` + `MessageTypes` (a total authorisation table), `MessageRouter` (registration, EXCLUSIVE vs BROADCAST), `CorrelationTable` |
| 6 | Enums | `WireEnum`/`WireEnums` with explicit codes and a totality check; `RegionRefusal` keeps its raw reason code; `PoseCodes` takes Minecraft's `Pose` out of the hash; ArchUnit bans `ordinal()` in the wire package |
| 7 | CI | `CrossVersionIT` + `.github/workflows/cross-version.yml` |

### The flag day

Every Java transport and every Rust service now speaks `NDR2`; `MessageCodec` remains the
**canonical** encoder for hashing, signing and the consensus plane, and is no longer what crosses a
socket. The deployed tracker and rendezvous must be redeployed with this change, not after it
(`Plan.7` D8).

A pre-`NDR2` frame is refused at the first four bytes with a message that names the generation gap,
in both languages. That is asserted rather than assumed: `WireFixtureTest`
`everyRetiredV1FrameIsRefusedRatherThanMisparsed` and the Rust
`every_retired_v1_frame_is_refused_rather_than_misparsed` walk the retained v1 corpus.

### What the work found on the way

Neither of these was in the audit; both came out of running the harness against the new code.

| Defect | Where | Fix |
|---|---|---|
| An unknown field was skipped and then **dropped** on re-encode, and an absent known field was re-emitted with a default. Either way a peer relaying between two newer peers silently rewrote the message | the first TLV decoder | `TlvOverlay`: preserve unknown fields *and* record absent ones, at every nesting depth, plus `markVerbatim` for a value this build can only approximate (an unrecognised role, a health code from a later release) |
| The ArchUnit no-`ordinal()` rule was **vacuous**. `callMethod(Enum.class, "ordinal")` matches nothing, because `PeerRole.BOOTSTRAP.ordinal()` compiles to an invokevirtual whose owner is `PeerRole` | `WireEnumRulesTest` | Matched on the call target's name instead. Verified by planting an `ordinal()` call, watching the rule stay green, fixing it, and watching it fire |

### Phase 0's own findings, for the record

**999 canonical violations across 30 message types, now 0.** All pre-existing.

| Defect | Where | Fix |
|---|---|---|
| Malformed UTF-8 replacement-decoded, then re-encoded as different bytes | Java `CanonicalReader.readString` | Strict `CodingErrorAction.REPORT`. Rust already rejected it, so this was a **live Java/Rust hash divergence** |
| Booleans accepted any nonzero byte — one value, 256 spellings | `CanonicalReader.readBoolean` **and** `nodera-codec`'s `read_bool`; `NodeCapabilities` bypassed both with `!= 0` in each language | Exactly 0 or 1, both sides |
| Piece-index lists sorted and de-duplicated at decode | `ContentRequest`, `ArchiveReplicaAssignment`, `ArchiveReplicaAck` | Decode requires strictly ascending |
| Role sets absorbed duplicates and any order | `NodeCapabilities` in both languages | Decode requires ascending codes |

The two exceptions phase 0 had to document are **both gone**. `PeerJoin` truncation is detectable now
that fields are length-delimited, and `RegionRefusal` re-encodes from the raw code it arrived with
instead of flattening an unknown reason into `UNKNOWN`'s own number. The exception list in
`CanonicalMutationFuzzTest` is empty, and a new entry in it is a regression to argue about.

## Dependencies

| Prerequisite | Why |
|---|---|
| [network 1](Task.1.md) | Owns the wire that this task replaces; its tag registry and framing are the input to the schema extraction |
| [network 2](Task.2.md) | Owns `PeerRuntime` — the dispatch that phase 5 replaces and the admission path phase 4 rewrites |
| [tracker 5](../tracker/Task.5.md), [rendezvous 5](../rendezvous/Task.5.md) | The deployed services speak these frames; phase 3 redeploys them in lockstep (Plan.7 §3 D8) |
| [`../plans/Plan.7.md`](../plans/Plan.7.md) | The locked decisions D1–D9; this task may not contradict them |

## Deliverables

| # | Phase | Deliverable | Status |
|---|---|---|---|
| 0 | Harness | Total sample registry, complete fixture corpus, fail-on-missing, total tag mirror, mutation fuzz in both languages, and the decoder-strictness fixes it exposed | ✅ |
| 1 | Schema | `WireRegistry` for all 75 kinds; the Rust kind table and the fixture manifest generated from it; the hand-maintained lists replaced by views | ✅ |
| 2 | Planes | Every kind classified; consensus payloads behind an opaque `bytes` boundary; their encoding untouched | ✅ |
| 3 | Frame | `NDR2` frame, TLV infrastructure bodies, `epoch = 2`; corpus regenerated; the v1 corpus retained as a rejection set both languages assert | ✅ |
| 4 | Negotiation | `Hello`/`HelloAck`, feature intersection, `PeerSession` consulted at emit, OBSERVER admission, coded rejects, sender-identity binding | ✅ |
| 5 | Router | `MessageType` descriptors, declarative `authPolicy`, pending-correlation table, multi-subscriber registration replacing the fall-through | ✅ |
| 6 | Enums | Explicit wire codes at every boundary, a Nodera-owned `Pose` mapping, per-plane unknown-value policy, ArchUnit rule | ✅ |
| 7 | CI | `CrossVersionIT` + a workflow running the current build against the previous release | ✅ |

## Design

The load-bearing decisions live in [`../plans/Plan.7.md`](../plans/Plan.7.md) §3 and are not restated
here. The three that shape the code most:

**The plane split (D1).** `MessageCodec` today serves signed consensus payloads and tracker queries
from the same 1,635-line switch, under one strictness rule. That rule is correct for the first group
— a byte that round-trips differently is a fork — and it is the whole reason the second group cannot
tolerate a version difference. Splitting them is what lets one plane be strict and the other be
tolerant without either compromising.

**Skew bars co-validation, not communication (D2).** The temptation is to refuse a mismatched peer at
the handshake. That is wrong: content, discovery, relay, and tunnel are all version-independent, and
refusing costs the network a seeder for no safety gain. OBSERVER is the honest boundary.

**Kinds, not versions (D3).** Every per-message version currently in the codec exists because a field
was added to a positional body. Once bodies are TLV, additive change needs no version at all, and a
change TLV cannot absorb is a different message. This is why phase 3 must precede phase 4: there is
no point negotiating a version space that should not exist.

## Files

| Path | Role |
|---|---|
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/WireRegistry.java` | **The schema.** One row per kind; everything else is derived or generated from it |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{MessagePlane,WireKind}.java` | The plane classification and the row type |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{NoderaFrame,FrameFlags}.java` | The `NDR2` frame |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{WireType,TlvField,TlvWriter,TlvReader,TlvOverlay}.java` | Canonical TLV, including preservation of what this build cannot read |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{InfrastructureCodec,WireCodec}.java` | The 50 TLV shapes, and the plane router that turns a message into a frame |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{WireEnum,WireEnums}.java` | Explicit enum wire codes, with a totality check at class initialisation |
| `library/java/transport/src/main/java/dev/nodera/protocol/wire/{AuthPolicy,MessageType,MessageTypes,MessageRouter,CorrelationTable}.java` | Who may send what, and how it is dispatched |
| `library/java/transport/src/main/java/dev/nodera/protocol/session/*.java` | `Hello`, `HelloAck`, `Nack`, `Negotiation`, `PeerSession`, `WireFeature`, `SessionRole`, `RejectCode` |
| `library/java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java` | The **canonical** encoder: hashing, signing, and the consensus plane. No longer what crosses a socket |
| `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/entity/PoseCodes.java` | Minecraft's `Pose`, renumbered into a table Nodera owns |
| `library/rust/nodera-codec/src/{frame,tlv,wire}.rs` | The Rust half of the frame, TLV, and the 24 kinds the services speak |
| `library/rust/nodera-codec/src/kinds.rs` | **Generated** from `WireRegistry.java` — never edited by hand |
| `fixtures/wire/*.bin`, `fixtures/wire/java-only/*.bin` | The `NDR2` corpus — never edited by hand |
| `fixtures/wire/v1-rejected/*.bin` | The retired pre-`NDR2` corpus, asserted to be **refused** by both languages |
| `fixtures/wire/manifest.json` | **Generated**: which kind each fixture pins, and which plane governs it |
| `.github/workflows/cross-version.yml` | The mixed-release job |

## Testing

Run: `./gradlew :core:test :transport:test` and `cd rust && cargo test`.
Counts and suites are in [`TESTING.md`](TESTING.md).

| Suite | Proves |
|---|---|
| `MessageSamples.assertTotal` | Every registry kind has a deterministic sample; a new kind without one fails the build at the commit that adds it |
| `WireFixtureTest` | Every kind's bytes are committed and unchanged; a missing fixture fails; every retired v1 frame is refused |
| `WireSchemaGeneratorTest` | The generated Rust table and fixture manifest match the schema; the `TAG_` constants agree with it; both planes are populated |
| `MessageCodecTypeTagTest` | Every kind dispatches to its own type and round-trips by value; the registry is unique and contiguous |
| `CanonicalMutationFuzzTest` | A mutated frame either fails to decode or re-encodes to itself — **with no documented exceptions** |
| `ForwardCompatibilityTest` (10) | The L-86 and L-89 exit conditions: an appended field is skipped at any depth, an omitted one takes its default, consensus payloads are opaque, an unknown kind is answered |
| `NegotiationTest` (11) | The L-87 and L-88 exit conditions: OBSERVER on skew, coded rejects, identity bound to the carrier, and the feature intersection governing what is emitted |
| `MessageRouterTest` (11) | A peer cannot speak in another's name; an unsolicited response matches nothing; EXCLUSIVE refuses a second handler; BROADCAST reaches every service |
| `WireEnumRulesTest` (9) | Explicit codes everywhere, pinned; no `ordinal()` in the wire package; an unknown code preserved on one plane and refused on the other |
| `CrossVersionIT` (5) | Two builds from different releases mesh, seed and tunnel; an unknown kind costs one frame, not the connection; a future field survives a relay |
| `tag_mirror.rs` (5) | The generated Rust registry matches a fresh parse of the Java schema, totally |
| `fixtures.rs` (4) | Rust re-encodes every shared fixture byte-exactly; truncation and trailing bytes are rejected; the v1 corpus is refused |
| `mutation.rs` | The Rust half of the canonical invariant, on the same corpus, with the same overlay semantics |
| `tlv.rs` / `frame.rs` unit tests | The grammar itself: ascending ids, exact fixed widths, 0/1 booleans, strict UTF-8, a pre-`NDR2` frame diagnosed at the magic |

Still missing, and the reason the **three remaining** rows (L-87, L-88, L-90) are RETIRING rather
than RETIRED: **a live mixed-release run**, two real processes from different builds on one network.
Everything above is headless. (L-86 and L-89 retired on 2026-07-28 — their stated exit tests are all
headless and all green, so by the binding retire-on-green-exit-test rule they did not wait for this run.)

## Acceptance criteria

- [x] Every registry tag has a golden fixture and a dispatch test; a missing fixture fails the build.
- [x] Both codecs enforce the same canonical strictness, proven by the same mutation invariant on the
      same corpus.
- [x] Both registries come from one schema; the Rust table and the fixture manifest are generated
      and their freshness is asserted from the Java source.
- [x] Consensus payloads cross the infrastructure plane as opaque bytes.
- [x] An unknown kind is skipped and answered with a `Nack`, and the connection survives.
- [x] A field added to an infrastructure message is ignored by a peer that does not know it — at the
      top level, inside a nested structure, and across a relay.
- [x] A rules-version mismatch produces OBSERVER at the handshake, with a coded reason.
- [x] A response with no matching correlation never reaches a handler.
- [x] No `ordinal()` on any encode path in the wire package; an ArchUnit rule enforces it, and the
      rule was falsified before being trusted.
- [x] CI runs a mixed-release matrix on every commit, and the current build against the previous
      release when a release tag exists.
- [ ] **A live mixed-release run** — two real processes from different builds on one network. This is
      what keeps the three remaining rows (L-87, L-88, L-90) at RETIRING rather than RETIRED;
      everything above is headless. (L-86 and L-89 retired 2026-07-28 on green headless exit tests.)

## Limitations

| ID | Row | Status |
|---|---|---|
| L-86 | R1 — positional, non-delimited bodies: no change is a compatible change | ✅ RETIRED 2026-07-28 (issue #97) |
| L-87 | R2 — no negotiation before membership | RETIRING |
| L-88 | R3 — tolerant readers, unconditional writers | RETIRING |
| L-89 | R4 — one codec for the consensus and infrastructure planes | ✅ RETIRED 2026-07-28 (issue #97) |
| L-90 | R5 — ordinal enums at the boundary, including Minecraft's `Pose` | RETIRING |

L-86 and L-89 are **RETIRED**: their stated exit tests are headless and all green
(`ForwardCompatibilityTest` ×3 + `CrossVersionIT.aFutureFieldSurvivesARelay` for L-86;
`ForwardCompatibilityTest` ×2 for L-89), so they retired on 2026-07-28 under the binding
retire-on-green-exit-test rule. The remaining three are **RETIRING, not RETIRED**: every exit test
passes headless, but none has run as two real processes from different builds on one network, and the
negotiation/admission behaviour they cover is exactly what a live mixed-release run is for. That run
is the last gate, and the register does not get to claim it before it happens.
