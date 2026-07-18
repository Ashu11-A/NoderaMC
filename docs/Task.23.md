# Task 23 — Per-World Content Encryption (Phase 6): Password → AES-GCM; Seeders Hold Ciphertext

**Phase:** 6 · **Depends on:** Task 19 (pieces/manifest) · **Modules:** new `core/crypto/symmetric`;
encrypted-content wrapper in `distribution`/`storage-api`.

## Goal

When a host enables "torrent hosting" they set an **encryption password**. World content (pieces,
manifests at the payload level) is encrypted **AES-GCM** under a content key derived from that
password (Argon2id). Seeders hold **opaque ciphertext** and verify it by hash-of-ciphertext — they
can redundantly store and serve data they cannot read. Only a peer that supplies the password on
join derives the key and decrypts the pieces it renders/simulates. Realises the encryption-password
half of the user spec.

## Context

- Nodera has Ed25519 sign/verify + SHA-256 only; **no symmetric crypto anywhere** (no `Cipher`,
  no KDF) — LIMITATIONS L-39. This task adds the minimum JDK-crypto symmetric layer.
- The content-addressing model (Task 9/19) hashes **bytes**; hashing ciphertext (not plaintext)
  keeps seeders able to verify + serve without the key. This is the key design move: **encryption
  composes with content-addressing** because the address is over the stored (ciphertext) form.
- Integrity is layered: (a) AES-GCM auth-tag per piece (tamper evidence at decrypt), (b) piece hash
  over ciphertext (seeder-side integrity without decrypt), (c) `manifestRoot` / `StateRoot` over the
  plaintext region (canonical truth). All three must agree.

## Folder structure (additions)

```
core/src/main/java/dev/nodera/core/crypto/symmetric/   # new subpackage
├── package-info.java
├── PasswordKeyDerivation.java    # Argon2id (or PBKDF2 fallback) password → 256-bit content key
├── ContentCipher.java            # AES-GCM-256 encrypt/decrypt piece payloads (per-piece nonce)
├── WorldKeyMaterial.java         # (Bytes salt, int kdfIters, Bytes wrappedKey?) — stored per world
└── ContentKey.java               # the derived AES SecretKey handle (in-process only; never serialised)

distribution/src/main/java/dev/nodera/distribution/
└── EncryptedPiece.java           # wraps a piece: AES-GCM ciphertext + nonce + auth tag; hash over ciphertext

storage-api/src/main/java/dev/nodera/storage/
└── (ContentId unchanged — hash is over whatever bytes are stored; under encryption that's ciphertext)
```

## Implementation details — `core/crypto/symmetric`

- **KDF:** prefer Argon2id (JDK ≥ 21 has no built-in Argon2; use a pinned dependency in
  `gradle/libs.versions.toml` — BouncyCastle or `org.bouncycastle:bcprov`). If the dependency is
  undesirable for the MVP, PBKDF2-HMAC-SHA256 (JDK-built-in) is the documented fallback; the
  `WorldKeyMaterial` record carries the KDF id so both sides agree. Record the choice in
  `Plan.md` §5 (libraries) + `NoderaConstants`.
- **Per-piece nonce:** AES-GCM nonce = `StableHash(manifestRoot, pieceIndex)` (12 bytes, derived
  deterministically) — never reuse a nonce under the same key. Nonce is public; it does not weaken
  the key.
- **`WorldKeyMaterial`** (salt + KDF params + optional server-wrapped key) is stored per world in
  the genesis/manifest metadata; the password is NOT stored. Deriving the key requires the password
  at join time. The material travels with the manifest (ciphertext metadata is not secret).
- **No floating-point, no clocks** in the crypto path (consistent with Nodera hygiene).

## Implementation details — integration with Tasks 19/21

- `EncryptedPiece` is what `ContentChunk` (Task 19) carries when the world is encrypted; the piece
  hash is over the ciphertext bytes, so `PieceReassembler`/`ChunkLockMap` verify and lock exactly as
  before — **the data plane is encryption-agnostic**. Decryption happens only after a verified piece
  is unlocked, on the joining/simulating peer that holds the key.
- Replication (Task 21) places ciphertext; the host (`FULL_ARCHIVE`) holds the ciphertext backup.
  A seeder never needs the password.
- A world is either plaintext (default) or encrypted (torrent-hosting opted in); `PieceManifest`
  carries a `boolean encrypted` + the `WorldKeyMaterial` (minus the key itself). Mixing is
  impossible within one world.

## Potential limitations (staged in `LIMITATIONS.md` §B; security-relevant → written normally)

- **L-39** — world content is plaintext on the P2P net today. Exit: AES-GCM-256 content encryption
  under Argon2id(password)-derived key; seeders store ciphertext, verify by hash; join requires
  password; ciphertext-integrity + wrong-password-rejection + nonce-uniqueness tests green.

Security notes (this task handles secrets — the crypto must be correct, not improvised):
- Never roll custom crypto; use AES-GCM and a standard KDF as-is.
- Nonce uniqueness under a static content key is mandatory (GCM catastrophe on reuse); the
  deterministic `StableHash(manifestRoot, pieceIndex)` nonce is safe because each (key, nonce) pair
  is used once by construction.
- The password is a human secret — rate-limit join attempts; consider that a holder-of-last-resort
  (host) losing the password means the world becomes unrestorable (document this; it is the intended
  trade of "encryption password" — there is no escrow).

## Acceptance criteria

1. `ContentCipherTest`: encrypt → decrypt round-trip; tampered ciphertext rejected (auth tag);
   wrong key rejected.
2. `PasswordKeyDerivationTest`: same (password, salt, params) ⇒ same key; different salt ⇒ different
   key; cost params enforce a documented minimum.
3. `EncryptedDistributionIT`: an encrypted world is seeded by peers that **never receive the key**;
   they serve verified ciphertext; a joining peer with the password decrypts + reassembles to the
   engine's `StateRoot`; a peer without the password cannot.
4. Nonce uniqueness: across all pieces of a manifest, no nonce repeats (property test).
5. `./gradlew check` green; L-39 → RETIRING; README/Tested + Plan §5 (library pin) updated.
