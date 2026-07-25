# `java/core`

<!-- AI-AGENT-INSTRUCTION: This module holds FROZEN CONTRACTS. The canonical encoding, the type-tag
     registry, and the hash/signature choices are network-breaking to change: do not reorder a field,
     do not renumber or reuse a tag, do not "improve" an encoding. Any addition is APPENDED, with a
     golden fixture and the matching change in rust/nodera-codec in the SAME commit. `core` depends on
     the JDK and nothing else — keep it that way. Update this file when a package is added or its
     responsibility changes. -->

**The Minecraft-free vocabulary of the whole system.** Every other module speaks these types.

- **Depends on:** the JDK only.
- **Depended on by:** every Java module.
- **Docs:** [`docs/engine/Task.1.md`](../../docs/engine/Task.1.md) ·
  [`docs/engine/Task.0.md`](../../docs/engine/Task.0.md)

---

## Architecture

```
dev.nodera.core
├── identity/       NodeIdentity (Ed25519), NodeId, NodeCapabilities, persisted identity
├── region/         RegionId, dimension keys, leases, epochs, assignment
├── action/         the sealed GameAction hierarchy, ActionEnvelope, ActionBatch
├── state/          RegionSnapshot, RegionDelta, StateRoot, NBlockState/NBlockPos,
│                   entity records, container entries
├── event/          the RegionEvent family
├── consensuscert/  ValidationVote, QuorumCertificate, CommitteeChangeCertificate,
│                   ServerAuthorityCertificate
├── crypto/         CanonicalWriter/Reader, Encodable, TypeTags, HashService,
│   └── symmetric/  SignatureService, StableHash · AES-GCM-256, PBKDF2, the KDF seam
└── Bytes           the single immutable byte-array value type
```

## Why it is shaped this way

**One canonical encoding, hand-written.** Consensus hashes bytes, so the encoding must be
*canonical*: exactly one byte sequence per value, on every implementation, forever. General-purpose
formats permit encoder freedom — field order, integer width, map ordering — that is invisible in tests
and fatal in consensus. `CanonicalWriter` removes the freedom: fixed widths, an explicit
`u16 typeTag` + `u16 encodingVersion` prefix on every `Encodable`, and collections sorted at encode
time.

**`Bytes`, never `byte[]`.** Records with array components get identity equality and mutable contents,
both catastrophic in a hashed value type. `Bytes` is immutable and value-equal, so "the certificate
compares unequal to itself" is a bug that cannot be written.

**Ed25519.** Deterministic signatures (no per-signature entropy), small keys, fast verification, and an
interoperable Rust counterpart the services use to verify what peers sign.

**Type tags are append-only.** A tag identifies a shape; reusing one makes an old peer decode a new
message as garbage that still passes a length check. The registry is snapshot-tested, so a
renumbering fails CI rather than a production mesh.

**Its own Minecraft concepts.** `NBlockState` (an int id) and `NBlockPos` exist so that nothing here
imports a Minecraft type; the mod owns the mapping in both directions.

## Rules

- The JDK is the only dependency, including for `crypto/symmetric` (Argon2id lives in `java/peer`
  behind a pinned BouncyCastle, precisely so `core` stays dependency-free).
- No floats or doubles in hashed state — continuous quantities use Q32.32 fixed point.
- Appending a type tag means appending it in `rust/nodera-codec` in the same commit, with a golden
  fixture in `fixtures/wire/`.

## Tests

233 tests: canonical round-trips for every type, golden-file byte comparison, cross-JVM fixture
replay, signature verification with tamper and wrong-key rejection, and the type-tag registry
snapshot.

```bash
./gradlew :core:test
```
