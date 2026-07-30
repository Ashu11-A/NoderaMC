# Network Task 8 — Per-World Content Encryption

<!-- AI-AGENT-INSTRUCTION: There is NO KEY ESCROW and there must never be one — password loss means
     no recovery, and that is the design. Key material is never serialized, never logged, never in a
     toString, and never crosses the loopback trust boundary. KDF costs and input sizes are bounded
     BEFORE allocation. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (opt-in create/join UI → [minecraft 4](../minecraft/Task.4.md), [minecraft 5](../minecraft/Task.5.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 4](Task.4.md)
**Consumed by:** [minecraft 5](../minecraft/Task.5.md), [worker 3](../peer/Task.3.md)

---

## Goal

When a host enables torrent hosting they set an **encryption password**. World content is encrypted
under a content key derived from it; seeders hold **opaque ciphertext** and verify it by
hash-of-ciphertext, so they can redundantly store and serve data they cannot read. Only a peer that
supplies the password derives the key and decrypts what it renders or simulates.

## Status detail

Complete. AES-GCM-256 encrypts each piece under a password-derived content key; Argon2id is the
production KDF behind a pinned BouncyCastle, with a JDK PBKDF2 fallback; KDF costs and input sizes
are bounded before allocation; nonces are deterministic and domain-separated. Ciphertext is
content-addressed, so keyless seeders verify what they serve.

`EncryptedDistributionIT` proves three keyless partial seeders can serve ciphertext that a password
joiner alone decrypts back to the engine's `StateRoot`; wrong password and tampering fail closed.
`WorldArchiveService.seedEncryptedArchive` encrypts the whole-save archive **before** it touches the
content store, so the worker and every seeder move only ciphertext. A `JoinAttemptThrottle` applies
exponential lockouts, and refused attempts never touch the KDF.

Remaining: the join-side password prompt in the GUI and live re-key propagation —
[`minecraft/Task.4.md`](../minecraft/Task.4.md) and [`minecraft/Task.5.md`](../minecraft/Task.5.md).

## Dependencies

- [network 4](Task.4.md) — the piece plane being encrypted.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | JDK-only AES-GCM-256 + PBKDF2 + the KDF seam in `core` | ✅ |
| 2 | Bounded Argon2id behind pinned BouncyCastle | ✅ |
| 3 | Deterministic domain-separated 96-bit nonces | ✅ |
| 4 | `EncryptedPiece` / `EncryptedRegion` + ciphertext content-addressing | ✅ |
| 5 | `WorldKeyMaterial` — public KDF parameters travel, secrets never do | ✅ |
| 6 | `WorldArchive.encryptArchive` / `decryptArchive` | ✅ |
| 7 | `JoinAttemptThrottle` — exponential lockouts before the KDF | ✅ |
| 8 | Opt-in create/join UI and live re-key propagation | → [minecraft](../minecraft/Task.0.md) |

## Design

**Seeders hold what they cannot read, and that is the point.** Encrypting *before* content addressing
means the hash covers the ciphertext, so a seeder verifies integrity without the key. Redundancy and
confidentiality stop being in tension: a stranger can help keep your world alive without being able
to look at it.

**Bound the KDF before you allocate.** An unbounded Argon2id cost parameter arriving from the network
is a denial-of-service primitive. Costs and input sizes are validated before any allocation, and a
refused join attempt never reaches the KDF at all — otherwise the throttle would be the expensive
path.

**Deterministic nonces, domain-separated.** Convergent encryption needs the same plaintext to produce
the same ciphertext so that content addressing still deduplicates. Domain separation stops the same
key/nonce pair from being reused across contexts, which is the failure mode that breaks GCM.

**No escrow, stated plainly.** Password loss means no recovery. Anything else means a recovery path,
which means someone holds a key, which defeats the model. This is documented rather than mitigated.

**What still leaks, on purpose.** Manifests stay plaintext: region ids, piece sizes and counts, KDF
parameters, and cadence are visible; block contents are not. Convergent encryption also leaks
plaintext *equality* — a property of the scheme, recorded rather than hidden.

**A re-key must actually revoke.** Because a new key produces new ciphertext and a new manifest root,
a re-key that merely *appends* leaves the old blob decryptable with the old password. Superseded
versions are therefore evicted from the manifest table, from holdings, and from the content store.

## Files

- `library/java/core/src/main/java/dev/nodera/core/crypto/symmetric/`
- `peer/src/main/java/dev/nodera/distribution/{EncryptedPiece,EncryptedRegion,ContentCipher,PasswordKeyDerivations,WorldKeyMaterial}.java`

## Testing

- `EncryptedDistributionIT` — three keyless partial seeders serve ciphertext; a password joiner
  recovers the plaintext engine root; wrong password and tamper fail closed.
- `EncryptedRegionTest` — nonce uniqueness; ciphertext hashing.
- `EncryptedArchiveTest` — exact round-trip; wrong password yields *empty* via the GCM tag, never
  garbage; tampered ciphertext rejected; plaintext identity pinned in the region root.
- `RekeyVerbIT` — a second re-key supersedes the old ciphertext, so the old password stops working.

## Acceptance criteria

1. ✅ Seeders store and verify ciphertext without the key.
2. ✅ A password joiner decrypts to the plaintext engine root.
3. ✅ Wrong password and tampering fail closed (GCM tag, never partial output).
4. ✅ KDF costs and sizes are bounded before allocation; refused attempts skip the KDF.
5. ✅ A re-key evicts the superseded ciphertext.
6. ⏳ The GUI collects the password on create and on join.

## Limitations

None open. **L-39** is RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The live join
password gate on the *Minecraft session* (as distinct from the content plane) is **L-52** in
[`minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md).
