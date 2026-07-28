<!-- AI-AGENT-INSTRUCTION: This is an ACTIVE PROGRAMME PLAN — the scoping document for the wire and
     service-communication refactor owned by the `network` category ([network 14](../network/Task.14.md)).
     The per-phase specification is the source of truth for SCOPE and STATUS; this file is the source
     of truth for the ROOT CAUSES (§2) and the LOCKED DECISIONS (§3) behind them. When a task file
     contradicts a locked decision here, the task file is the bug — unless the decision is explicitly
     re-opened in §10. A change to the frame, the plane split, or the negotiation contract MUST update
     §4 and the schema in the same commit. Phase 3 is a FLAG DAY: the deployed tracker and rendezvous
     services are redeployed in lockstep with it, never after. Keep every AI-AGENT-INSTRUCTION comment
     intact. -->

> **Active programme plan.** The per-phase specification lives in
> [`../network/Task.14.md`](../network/Task.14.md); the index is [`../ROADMAP.md`](../ROADMAP.md) and
> the documentation format is [`../README.md`](../README.md). This file becomes historical when
> network task 14 closes.

# Plan 7 — Wire protocol: peers on different versions that can still talk

**Status:** 🚧 IN PROGRESS (8 of 8 phases complete; closes when the live mixed-release run
lands) · **Opened:** 2026-07-28 ·
**Category:** [`network`](../network/Task.0.md)

---

## 0. The one-sentence version

Split the wire into a **strict consensus plane** and a **forward-compatible infrastructure plane**,
put a real negotiated handshake in front of membership, and generate both codecs from one schema —
so two peers on different releases keep discovering, seeding, relaying, and tunnelling to each other,
and differ only in whether they may sit on a committee together.

---

## 1. Why this exists

NoderaMC has versioned encodings and **no version negotiation**. A peer authenticates its carrier,
sends `PeerJoin`, and is admitted — with no check of protocol version, rules version, registry
fingerprint, or feature set. Incompatibility does not surface at the handshake, where it could be
reported; it surfaces later as a silently dropped frame, a liveness timeout, or an exception thrown
from inside the region engine mid-execution.

That is not a gap in an otherwise-working design. It is the visible end of five structural faults,
and every symptom in the audit traces to one of them.

---

## 2. The five root causes (normative)

<!-- AI-AGENT-INSTRUCTION: These five IDs are referenced by the LIMITATIONS rows L-86…L-90 and by
     every phase in §5. Do not renumber them. A proposal that does not retire one of these is not
     part of this programme. -->

| ID | Root cause | Why it blocks cross-version communication |
|---|---|---|
| **R1** | **Positional, non-delimited encoding.** Message bodies are fixed-width fields written back to back; nothing below the frame carries its own length | A decoder cannot skip what it does not know. Adding one field to `NodeCapabilities` or `PeerEntry` does not degrade — it makes the rest of the enclosing message decode as garbage or underflow. **There is no such thing as a compatible change** |
| **R2** | **No negotiation.** `ClientHello`/`ServerHello`/`ChallengeResponse`/`WorkerActivation` (tags 1–4) exist only in the codec and its tests — no runtime handler constructs or consumes them. `PeerJoin.clientVersion` is documented as *not* a compatibility input | Nothing compares versions, rules, fingerprints, or features before admission, so incompatibility is discovered by failing rather than by asking |
| **R3** | **Emission is unconditional.** Readers are tolerant, writers are not: `SessionKeepAlive` accepts v1–v2 and always emits v2; `RegionProposal` emits v3; `ExternalDelta` emits v2 | Compatibility runs one way only. A current peer accepts an old peer's keep-alive, then sends one the old peer cannot parse — and the old peer eventually declares it dead |
| **R4** | **One codec for two incompatible requirements.** `MessageCodec` (1,635 lines, 144 `instanceof` arms) carries signed consensus payloads *and* tracker/rendezvous/tunnel infrastructure | Strictness is correct for the first group and is the direct cause of version-lock in the second |
| **R5** | **Ordinal enums at the boundary.** No boundary enum has assigned wire codes. Some are documented frozen; several are not; one is *external* — Minecraft's `Pose.ordinal()` is written as a u32 into canonical ghost state | An upstream refactor silently changes Nodera's hashes, and an unknown value fails the whole message rather than the field |

Three secondary defects the redesign absorbs rather than patches: **no correlation identity** (a
tracker response for the wrong world is merged into the answer; an unsolicited `WorldManifestAnswer`
is filed against a pending fetch), **no sender binding** (`MembershipUpdate`, `GatewayClaim`,
`ContentAvailability.holder`, `RegionRefusal` are accepted from any connected socket), and **dispatch
by fan-out** (`PeerRuntime` handles five types; everything else falls through one last-wins handler
that `HeadlessPeerMain` manually fans to six services).

---

## 3. Locked decisions

<!-- AI-AGENT-INSTRUCTION: A task file that contradicts one of these is the bug. Re-opening a
     decision means moving it to §10 with the reason, in the same commit as the code that departs
     from it. -->

**D1 — The wire has two planes, and they have opposite requirements.** Consensus payloads (actions,
envelopes, proposals, votes, certificates, deltas, snapshots, committed events, grants, ownership,
tombstones) are hashed and signed: they need byte-exact round trip and get **strict positional**
encoding. Everything else (handshake, membership, keep-alive, discovery, tracker, rendezvous, relay,
content, manifests, tunnel, service directory) needs to survive version skew and gets
**forward-compatible TLV**. This is the decision the whole programme rests on.

**D2 — Version skew bars co-validation, not communication.** A peer whose `rulesVersion` or
`registryFingerprint` differs is admitted as an **OBSERVER**: it meshes, seeds, relays, tunnels, and
receives commits, but is seated on no committee. You cannot agree on a state root with a peer whose
engine computes it differently, and no amount of encoding tolerance changes that — so the honest
boundary is drawn there and nowhere earlier. Today that same peer is admitted with no check and then
throws inside `FlatWorldRegionEngine`.

**D3 — A breaking change allocates a new kind; it never bumps a version.** Message *kinds* stay
append-only and are never renumbered. TLV absorbs additive change, so a genuinely incompatible change
gets a new kind — which retires the per-message version zoo of R3, because there is nothing left to
downgrade-negotiate.

**D4 — Unknown means skip, and skipping is answered.** An unknown kind is discarded without killing
the connection and answered with `NACK{kind, UNSUPPORTED}` — never today's silent drop, which is
indistinguishable from a network fault.

**D5 — Signed bytes are opaque to the tolerant plane.** Consensus structures cross the wire as an
opaque `bytes` field inside a TLV message. A peer never re-serializes signed bytes it might spell
differently, and the infrastructure plane never needs to understand consensus schema evolution.

**D6 — The schema is the source, both codecs are generated.** One schema directory generates the
Java codec, the Rust codec, the kind tables, and the fixture manifest. Hand-written codecs are how
the registries drift and how a message reaches production with no fixture. **Protobuf is not a
candidate**: it has no canonical encoding, which the consensus plane requires.

**D7 — Unknown enum codes are preserved, not collapsed** — in the infrastructure plane. In the
consensus plane an unknown code is rejected, because a validator that ignores a field it does not
understand produces a wrong state root.

**D8 — Phase 3 is a flag day, and the deployed services move with it.** The VPS tracker and
rendezvous are redeployed in the same operation, not after. Appending a single field to
`TrackerAnnounce` has already taken the deployed network down once — old trackers hung up on the
longer frame and every peer read an empty world list — and a frame change is strictly larger than a
field.

**D9 — Out of scope: the loopback control protocol and the NeoForge payload channel.** Both are
single-version, single-host boundaries with their own gates (`CompanionGate`, registrar version), and
neither is a cross-version *peer* problem.

---

## 4. The target architecture

### 4.1 Frame

```
NoderaFrame = magic:u32 'NDR2' | epoch:u16 | kind:u16 | flags:u16 | correlationId:u64 | len:u32 | body
```

- **magic + epoch** — a v1 peer and a v2 peer fail immediately with a readable error instead of
  misparsing. Epoch bumps only for what TLV cannot absorb; it is expected to stay at 2.
- **kind** — replaces `tag` (D3).
- **flags** — `REQUEST` / `RESPONSE` / `EVENT`, plus `NO_REPLY_EXPECTED`.
- **correlationId** — 0 for events; echoed by responses. A response whose correlation is not in the
  pending table is dropped before any handler sees it, which is what closes the tracker
  wrong-genesis, manifest-spoof, and event-sync-poisoning findings in one move.
- **len** — the body is skippable, which is what makes D4 implementable.

### 4.2 Infrastructure-plane body: canonical TLV

```
body   = field*                       (ascending fieldId, each id at most once)
field  = fieldId:u16 | wireType:u8 | len:u32 | value
```

Canonical because the order is fixed and duplicates are illegal; forward-compatible because `len`
makes an unknown field skippable. Nested structures (`NodeCapabilities`, `PeerEntry`,
`PieceManifest`) become TLV too — that is the direct retirement of **R1**.

### 4.3 Negotiation

```
Hello     { epoch, productVersion, features:set<u32>, rulesVersion, registryFingerprint,
            nodeId, publicKey, sig }
HelloAck  { epoch, selectedFeatures:set<u32>, consensusCompatible:bool,
            role:ADMITTED|OBSERVER, reject:{code, detail}? }
```

`selectedFeatures` is the **intersection**, and the resulting `PeerSession` profile is what the
encoder consults at emit time — a feature the peer did not accept is never emitted to it, which kills
**R3** structurally rather than per-message. Rejection is coded and explicit, never a timeout. The
identity gap belongs here too: `HelloAck` is issued only after the body `nodeId` is checked against
the transport-authenticated peer, and `PeerRuntime` stops trusting the key carried in `PeerJoin`.

### 4.4 One descriptor per message

```java
MessageType<T>(kind, name, plane, codec, flags, authPolicy, handlerMode)
```

`authPolicy` is declarative — `TRANSPORT_SENDER_EQUALS(field)`, `ROLE_AUTHORIZED(role)`,
`SELF_AUTHENTICATED_COURIER`, `ADVISORY_RECHECK`, `PUBLIC` — and enforced by the router before
dispatch, which converts a list of per-handler security findings into one enforced table.
`handlerMode` replaces the last-wins fall-through and the six-way manual fan-out with registration.

---

## 5. Phase breakdown

| # | Phase | Retires | Size | Risk | Status |
|---|---|---|---|---|---|
| 0 | Conformance harness — no production code | — (measures all five) | S | none | ✅ COMPLETED |
| 1 | Schema extraction; generated tables, byte-identical | R4 (prep), D6 | L | medium | ✅ COMPLETED |
| 2 | Plane split; consensus payloads opaque | R4, D5 | M | low | ✅ COMPLETED |
| 3 | `NDR2` frame + TLV bodies — **flag day** | R1, D4 | L | **high** | ✅ COMPLETED |
| 4 | `Hello`/`HelloAck`, session profile, OBSERVER admission | R2, R3, D2 | M | medium | ✅ COMPLETED |
| 5 | Descriptor registry, auth policies, correlation table | secondary defects | M | medium | ✅ COMPLETED |
| 6 | Explicit enum wire codes; `Pose` mapping | R5, D7 | M | low | ✅ COMPLETED |
| 7 | Cross-version CI — current vs previous release | keeps 0–6 true | S | low | ✅ COMPLETED |

Sequencing: 1 requires 0; 3 requires 2; 4 requires 3; 7 requires 4. Phases 5 and 6 may run parallel
to 4 once 3 has landed. Per-phase deliverables, files, and acceptance criteria are in
[`../network/Task.14.md`](../network/Task.14.md).

---

## 6. Why phase 0 came first

The audit found holes in the safety net that every later phase is verified against, and a harness
that measures the wrong thing is worse than none. What it found once it worked is the argument for
the ordering: **999 canonical-encoding violations across 30 message types**, all pre-existing, all
now zero — malformed UTF-8 replacement-decoded on the Java side only (a live Java/Rust hash
divergence), booleans accepting any nonzero byte in **both** implementations, and decode-time
normalization that gave several messages two valid spellings. None of that was visible before,
because the corpus asserted five tags and the dispatch test covered 25 of 72.

---

## 7. Acceptance for the programme

1. Two peers built from different releases discover each other, mesh, seed a world, and tunnel a
   game session — proven by a CI job that runs current against the previous release (phase 7).
2. A rules-version mismatch yields `OBSERVER` at the handshake, with a coded reason, rather than an
   exception from inside the engine (phase 4).
3. Adding a field to any infrastructure message is a non-event for an un-upgraded peer (phase 3).
4. Every kind has a golden fixture, and both codecs are generated from the schema that produced it
   (phases 0–1).
5. No `ordinal()` on any encode path, enforced by an ArchUnit rule (phase 6).

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| The phase-3 flag day breaks the deployed network | D8: services redeploy in the same operation; the v1 corpus is retained as a **rejection** fixture set so a v1 frame fails loudly, not subtly |
| A generated codec silently changes bytes | Phase 1's acceptance is byte-identity against the phase-0 corpus — 77 fixtures across both languages |
| The plane split is drawn in the wrong place | The test is mechanical: if a message is hashed or signed, it is consensus. Anything ambiguous is consensus, because the cost of wrongly-strict is a lost feature and the cost of wrongly-tolerant is a fork |
| Scope drift into the control protocol | D9 |

---

## 9. Relationship to existing work

- [network 1](../network/Task.1.md) owns the current wire and stays the reference for what exists
  today; task 14 owns what replaces it.
- [network 2](../network/Task.2.md) owns membership and the runtime that will host the descriptor
  registry (phase 5).
- [tracker 5](../tracker/Task.5.md) and [rendezvous 5](../rendezvous/Task.5.md) own the services that
  redeploy with phase 3.
- [`Plan.6.md`](Plan.6.md) (telemetry) is unaffected: its ingest is a separate framed-JSON boundary.

---

## 10. Re-opened decisions

None. Two decisions were **narrowed** in the course of implementing them, and the narrowing is
recorded here because a reader of §3 would otherwise expect more than the code delivers.

**D5 — how far "opaque" reaches.** The decision says consensus structures cross as opaque bytes.
Implementing it made a second boundary visible: a handful of *infrastructure* payloads are themselves
Ed25519-signed over their own canonical encoding — `TrackerAnnounce`, `SignedPeerRecord`,
`ServiceRecord`, `ServiceScoreReport`. Re-spelling any of them as TLV would invalidate every
signature over them, so they cross the tolerant plane opaque too, and the component that must verify
a signature parses them with the strict codec. This is what L-89's exit clause already asked for —
"no infrastructure decoder parses a signed structure" — and it is the reason growing one of those
four remains a coordinated change rather than a non-event.

**D3 — where a feature gates.** "A breaking change allocates a new kind" holds for the infrastructure
plane. On the consensus plane a body version is covered by the proposer's signature, so a sender
cannot demote a message for a peer that has not caught up without invalidating a signature it did not
produce. Those features therefore gate a **committee seat** rather than the bytes: a peer missing one
is an observer. That is D2 doing its job, not an exception to it.
