# Engine Task 1 — Domain Types, Crypto, Canonical Encoding

<!-- AI-AGENT-INSTRUCTION: This task defines FROZEN CONTRACTS. The canonical encoding, the type-tag
     registry, and the hash/signature choices are network-breaking to change. Do not "improve" an
     encoding, reorder a field, or reuse a retired tag. If a change is genuinely required, it is a
     version bump with a migration path documented here first, and a matching change in
     library/rust/nodera-codec in the same commit. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED
**Category:** engine · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** —  (root task; everything in the project builds on it)
**Consumed by:** every other task in every category

---

## Goal

The Minecraft-free vocabulary of the whole system: identities, regions, actions, state, events, and
certificates — plus the three consensus-critical services (`CanonicalEncoder`, `HashService`,
`SignatureService`). Everything downstream speaks these types. **The canonical encoding is a frozen
contract after this task**: changing it is a network-breaking change.

## Status detail

Landed and stable. `library/java/core` carries 263 XML test executions (260 `@Test` methods and 3 jqwik
`@Property` methods) covering canonical round-trips, deterministic arithmetic, golden-file
encodings, signature verification, and tamper rejection.

Extensions landed after the original delivery, each additive and tag-appended:

- `CommitteeChangeCertificate` (tag 53) — authority-free committee membership changes.
- `ServerAuthorityCertificate` (tag 54) — the interference guard's certified conversion (task 7).
- Task 8's entity vocabulary: `FixedVec3` (Q32.32), `NetworkEntityId`, `PersistedEntityState`,
  entity snapshots/deltas/mutations/credits/transfer records, through type tag 102.
- `CertifiedWorldGenesis` (tag 103), `GenesisRecertification` (tag 104), `ContainerEntry` (tag 105),
  `MovePlayerAction` (tag 106), `CommandAction` (tag 108).
- `WorldRole` and the world-permission tags 92/93 consumed by [`network/Task.3.md`](../network/Task.3.md).
- The tag registry has since grown well past 108 (world-identity/ownership, service-directory, and
  archive types through `TypeTags.NEXT = 118` as of this audit). Those later tags are owned by the
  network/tracker/rendezvous categories; `core` only hosts the constants. The frozen-contract
  rules (append-only, never renumber; both language sides in one commit) are unchanged.

## Dependencies

None. This is the root of the dependency graph.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Identity: Ed25519 `NodeIdentity`, `NodeId`, `NodeCapabilities` | ✅ |
| 2 | Region vocabulary: `RegionId`, leases, epochs, assignment | ✅ |
| 3 | Sealed `GameAction` hierarchy + `ActionEnvelope`/`ActionBatch` | ✅ |
| 4 | State: `RegionSnapshot`, `RegionDelta`, `StateRoot`, `NBlockState`/`NBlockPos` | ✅ |
| 5 | Events: the `RegionEvent` family | ✅ |
| 6 | Certificates: `ValidationVote`, `QuorumCertificate`, `CommitteeChangeCertificate`, `ServerAuthorityCertificate` | ✅ |
| 7 | `CanonicalWriter`/`CanonicalReader`/`Encodable`/`TypeTags` — the frozen encoding | ✅ |
| 8 | `HashService` (SHA-256 over canonical bytes), `SignatureService` (Ed25519), `StableHash` | ✅ |
| 9 | `core/crypto/symmetric`: AES-GCM-256 + PBKDF2 + the KDF seam (JDK-only) | ✅ |
| 10 | `Bytes` — the single byte-array value type | ✅ |

## Design

**Why a hand-written canonical encoding rather than protobuf/CBOR.** Consensus hashes bytes, so the
encoding must be *canonical*: exactly one byte sequence per value, on every implementation, forever.
General-purpose formats permit encoder freedom (field order, integer width, map ordering) that is
invisible in tests and fatal in consensus. `CanonicalWriter` removes the freedom: fixed widths, an
explicit `u16 typeTag` + `u16 encodingVersion` prefix on every `Encodable`, sorted collections at
encode time, and no optional-field ambiguity.

**Why `Bytes` instead of `byte[]`.** Records with array components get identity equality and
mutable contents — both are catastrophic in a hashed value type. `Bytes` is immutable, value-equal,
and hashes correctly, so a whole class of "the certificate compares unequal to itself" bugs cannot
be written.

**Why Ed25519.** Deterministic signatures (no per-signature entropy), small keys, fast batch
verification, and a JDK-native implementation with an interoperable Rust counterpart
(`ed25519-dalek`) that the services use to verify what peers sign.

**The type-tag registry is append-only.** A tag identifies a shape; reusing one makes an old peer
decode a new message as garbage that still passes a length check. `TypeTags` is snapshot-tested so
a renumbering fails CI rather than a production mesh.

## Files

- `library/java/core/src/main/java/dev/nodera/core/crypto/{CanonicalWriter,CanonicalReader,Encodable,TypeTags,HashService,SignatureService,StableHash}.java`
- `library/java/core/src/main/java/dev/nodera/core/{Bytes,identity/,region/,action/,state/,event/}`
- `library/java/core/src/main/java/dev/nodera/core/crypto/symmetric/`
- Rust mirror: `library/rust/nodera-codec/src/` + `fixtures/wire/*.bin`

## Testing

- Canonical round-trip for every `Encodable`; golden-file byte comparison (`fixtures/wire/`).
- Cross-JVM fixture replay (Linux + Windows CI).
- Signature tests: valid verify, tampered payload rejected, wrong key rejected, signed-portion
  boundaries pinned (a field outside the signed portion must not affect verification).
- `MessageCodecTypeTagTest` snapshots the registry; the Rust `tag_mirror` test fails if one side
  appends alone.

See [`TESTING.md`](TESTING.md).

## Acceptance criteria

1. ✅ Every domain type round-trips canonically and byte-identically against its golden fixture.
2. ✅ `HashService` output is stable across JVMs and OSes for the same canonical input.
3. ✅ Signature verification rejects tampering, wrong keys, and truncation.
4. ✅ The type-tag registry snapshot test is green and mirrored in `library/rust/nodera-codec`.
5. ✅ `core` has no dependency other than the JDK.

## Limitations

None owned. This task's contracts are the reason several other rows are *not* re-openable: an
encoding change is a version bump, not a fix.
