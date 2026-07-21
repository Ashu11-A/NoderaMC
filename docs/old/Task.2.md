# Task 2 — `core` Module: Domain Types, Crypto, Canonical Encoding

**Phase:** 0 · **Depends on:** Task 1 · **Modules:** `core`, `testkit`

## Goal

The Minecraft-free vocabulary of the whole system: identities, regions, actions, state,
events, certificates — plus the three consensus-critical services (`CanonicalEncoder`,
`HashService`, `SignatureService`). Everything downstream (simulation, protocol,
consensus, storage) speaks these types. **The canonical encoding is a frozen contract**
after this task: changing it is a network-breaking change.

---

## Folder structure

```
core/src/main/java/dev/nodera/core/
├── NoderaConstants.java
├── identity/
│   ├── NodeId.java              # record NodeId(UUID value)
│   ├── NodeIdentity.java        # NodeId + Ed25519 key pair (private key never leaves)
│   ├── NodeCapabilities.java    # record: cores, memoryBytes, latencyMs, reliability,
│   │                            #   maxPrimaryRegions, maxValidatorRegions, acceptsWorker
│   └── PeerRole.java            # enum BOOTSTRAP, RELAY, SESSION_GATEWAY, REGION_EXECUTOR,
│                                #   REGION_VALIDATOR, PARTIAL_ARCHIVE, FULL_ARCHIVE, WORLD_SEEDER
├── region/
│   ├── DimensionKey.java        # record DimensionKey(String namespace, String path)
│   ├── RegionId.java            # record: DimensionKey + regionX + regionZ; fromChunk() via floorDiv
│   ├── RegionBounds.java        # chunk ranges + halo range helpers
│   ├── RegionReplicaRole.java   # enum PRIMARY, VALIDATOR
│   ├── RegionEpoch.java         # record RegionEpoch(long value) — comparable
│   ├── RegionLease.java         # record: region, epoch, primary NodeId, validators, validFrom/expiresAtTick
│   └── RegionCommittee.java     # record: region, epoch, primary, validators, quorumThreshold
├── action/
│   ├── GameAction.java          # sealed interface
│   ├── PlaceBlockAction.java    # record: NBlockPos, int blockStateId, face
│   ├── BreakBlockAction.java    # record: NBlockPos
│   ├── ActionEnvelope.java      # record: actor NodeId, playerSeq, serverSeq, targetTick,
│   │                            #   RegionId, GameAction, byte[] signature
│   └── ActionBatch.java         # record: RegionId, epoch, baseVersion, tickFrom, tickTo,
│                                #   List<ActionEnvelope> (server-sequence ordered)
├── state/
│   ├── NBlockPos.java           # record (int x, int y, int z) — MC-free
│   ├── StateRoot.java           # record StateRoot(byte[32] hash) — value semantics, hex toString
│   ├── ChunkColumnState.java    # per-chunk palette+heights model for the restricted block set
│   ├── RegionSnapshot.java      # region, version, tick, List<ChunkColumnState>, scheduled events, entities(later)
│   ├── BlockMutation.java       # record: NBlockPos, expectedPreviousStateId, newStateId, flags
│   ├── RegionDelta.java         # record: region, baseVersion, resultingVersion,
│   │                            #   List<BlockMutation>, (reserved lists: blockEntity, entity,
│   │                            #   inventory, scheduledTick), StateRoot resultingRoot
│   └── SnapshotVersion.java     # record (long value)
├── event/
│   ├── RegionEvent.java         # sealed: BlockChangedEvent, (reserved: Entity*, ScheduledTick*, ...)
│   └── CommittedEventEnvelope.java  # region, epoch, version, tick, eventId, event, prevRoot,
│                                    #   resultingRoot, certificateRef
├── consensuscert/
│   ├── SignedVote.java          # record: NodeId, StateRoot, VoteDecision, byte[] signature
│   ├── VoteDecision.java        # enum ACCEPT, REJECT_STATE_ROOT, REJECT_INVALID_ACTION,
│   │                            #   REJECT_WRONG_EPOCH, RESYNC_REQUIRED
│   └── QuorumCertificate.java   # record: region, epoch, version, prevRoot, resultingRoot, List<SignedVote>
└── crypto/
    ├── CanonicalEncoder.java    # the wire/hash encoding — see below
    ├── CanonicalWriter.java     # low-level primitives writer
    ├── CanonicalReader.java     # symmetric reader
    ├── Encodable.java           # interface: void encode(CanonicalWriter w)
    ├── HashService.java         # StateRoot hash(Encodable); sha256(byte[])
    ├── SignatureService.java    # sign(NodeIdentity, byte[]) / verify(publicKey, byte[], sig)
    └── StableHash.java          # long stableHash(long...)/(String) — for rendezvous + RNG seeding

core/src/test/java/dev/nodera/core/
├── crypto/CanonicalEncoderTest.java        # golden files
├── crypto/CanonicalEncoderPropertyTest.java# jqwik round-trip + order-independence-of-input-maps
├── crypto/SignatureServiceTest.java
├── region/RegionIdTest.java                # negative coords!
└── golden/                                  # resources: *.hex golden vectors
```

## Class relationships

```
Encodable ◄─ implemented by every type that is hashed or signed:
             ActionEnvelope, ActionBatch, RegionSnapshot, RegionDelta, BlockMutation,
             ChunkColumnState, RegionLease, RegionCommittee, SignedVote, QuorumCertificate,
             CommittedEventEnvelope
GameAction (sealed) ◄─ PlaceBlockAction | BreakBlockAction        # grows in later tasks
RegionEvent (sealed) ◄─ BlockChangedEvent                          # grows in Task 9
HashService ── uses ── CanonicalEncoder ── uses ── CanonicalWriter/Reader
SignatureService ── signs/verifies exactly the CanonicalEncoder bytes (never Java serialization)
```

Rules:

- Records everywhere; defensive copies of arrays/lists in compact constructors;
  `byte[]`-holding records override `equals`/`hashCode`/`toString` (or wrap in a
  `Bytes` value class — pick one and use it everywhere).
- `Encodable.encode` writes **all** semantic fields except signatures over self
  (`ActionEnvelope.encode` excludes its own `signature`; a helper `signedPortion()`
  returns those bytes for sign/verify).

## Implementation details — canonical encoding (the heart of this task)

Format rules (document in `CanonicalEncoder` Javadoc, test with golden files):

1. Big-endian fixed-width ints/longs; no varints (simplicity beats size; zstd fixes size).
2. Strings: UTF-8, u32 length prefix.
3. Lists: u32 count + elements. Where the semantic type is a *set/map*, encoder sorts by
   a documented key first (e.g. votes sorted by `NodeId`, mutations by position order
   `(y, z, x)`).
4. Every top-level `Encodable` starts with a `u16 typeTag` (registry in
   `CanonicalEncoder.TypeTags`) and `u16 encodingVersion` (starts at 1).
5. Optionals: u8 presence byte + payload.
6. No floats/doubles in hashed state for MVP (block world only). When entity positions
   arrive (Task 9), encode as fixed-point i64s — record that decision here.

Crypto:

- `SignatureService`: JDK `KeyPairGenerator.getInstance("Ed25519")` /
  `Signature.getInstance("Ed25519")`. Public key wire form = X.509 encoded bytes.
- `HashService`: JDK `MessageDigest.getInstance("SHA-256")`; thread-confined instances
  via `ThreadLocal` (MessageDigest is not thread-safe).
- `NodeIdentity.generate()` uses `SecureRandom` (allowed — identity generation is
  outside the deterministic path); persistence of the key pair is Task 5's concern.

`StableHash`: SplitMix64-style mixer over the argument sequence — used by rendezvous
scoring (Task 6) and RNG seeding (Task 3). Must be documented + golden-tested (both sides
of the network compute it).

## Implementation details — NeoForge mod

None directly (module is Minecraft-free). One integration deliverable in `neoforge-mod`:
`dev.nodera.mod.common.McAdapters` — static mapping helpers
`DimensionKey ↔ ResourceKey<Level>`, `NBlockPos ↔ BlockPos`, `RegionId.fromChunk(ChunkPos)`
delegating to core. Keeps rule Task 0 §4.3 satisfied.

## Implementation details — server peer

None yet. `QuorumCertificate`/`CommittedEventEnvelope` are defined now (so encoding is
frozen once) but first *produced* in Task 7 and first *persisted* in Task 9.

## Acceptance criteria

1. Golden-file tests: ≥ 1 vector per `Encodable` type; committed hex files; test fails on
   any byte change.
2. jqwik property tests: encode→decode round-trip identity for every type; map/set input
   order does not change encoded bytes.
3. `RegionIdTest`: `fromChunk` correct for negative coordinates
   (chunk −1 → region −1, chunk −8 → region −1, chunk −9 → region −2).
4. Sign/verify round-trip; tampered payload fails verification.
5. `core` has zero dependencies besides JDK (enforced: ArchUnit test in `testkit`).
