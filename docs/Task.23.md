# Task 23 — Per-World Content Encryption (Phase 6): Password → AES-GCM; Seeders Hold Ciphertext

**Phase:** 6 · **Depends on:** Task 19 (pieces/manifest) · **Modules:** new `core/crypto/symmetric`
(JDK-only: AES-GCM + PBKDF2 + the KDF seam); `distribution` (Argon2id impl + `EncryptedPiece`);
`storage-api` untouched.

## Goal

When a host enables "torrent hosting" they set an **encryption password**. World content (piece
payloads) is encrypted **AES-GCM** under a content key derived from that password (Argon2id). Seeders hold **opaque ciphertext** and verify it by hash-of-ciphertext — they
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
├── PasswordKeyDerivation.java    # KDF seam + JDK PBKDF2 impl here; Argon2id impl in `distribution`
├── ContentCipher.java            # AES-GCM-256 encrypt/decrypt piece payloads (per-piece nonce)
├── WorldKeyMaterial.java         # (Bytes salt, int kdfIters, Bytes wrappedKey?) — stored per world
└── ContentKey.java               # the derived AES SecretKey handle (in-process only; never serialised)

distribution/src/main/java/dev/nodera/distribution/
└── EncryptedPiece.java           # wraps a piece: AES-GCM ciphertext + nonce + auth tag; hash over ciphertext

storage-api/src/main/java/dev/nodera/storage/
└── (ContentId unchanged — hash is over whatever bytes are stored; under encryption that's ciphertext)
```

## Implementation details — `core/crypto/symmetric`

- **KDF:** `PasswordKeyDerivation` is an interface in `core/crypto/symmetric` with the JDK-built-in
  `Pbkdf2KeyDerivation` (PBKDF2-HMAC-SHA256) beside it — `core` must stay JDK-only (Task 0 §4 rule
  1; a BouncyCastle dependency in `core` would break the layering contract). The preferred
  `Argon2KeyDerivation` lives in `distribution` with the pinned dependency
  (`org.bouncycastle:bcprov` in `gradle/libs.versions.toml`). `WorldKeyMaterial.kdfId` selects the
  KDF so both sides agree; new worlds default to Argon2id (that is the L-39 exit test). Record the
  pin in `Plan.md` §5 (libraries) + the cost params in `NoderaConstants`.
- **Per-piece nonce:** the nonce cannot be derived from `manifestRoot` — that is circular: the root
  is computed from piece hashes, piece hashes are over ciphertext, and producing the ciphertext
  needs the nonce. Derive it from the plaintext side instead: nonce = first 12 bytes of
  `StableHash(regionRoot, snapshotVersion, pieceIndex)` — all three are known before encryption.
  Uniqueness argument: under one key, a repeated `(regionRoot, version, index)` triple implies the
  byte-identical plaintext piece, which produces the byte-identical ciphertext — nonce reuse on an
  *equal* message leaks only equality, which content addressing reveals anyway. Distinct plaintexts
  always get distinct nonces (different region state ⇒ different `regionRoot`). This is deliberate
  convergent encryption; it is also what keeps ciphertext content-addressing deterministic (same
  plaintext ⇒ same ciphertext ⇒ same `ContentId`, so dedup survives encryption; a random nonce
  would break that). Nonce is public; it does not weaken the key.
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
- A world is either plaintext (default) or encrypted (torrent-hosting opted in); this task
  populates the `encrypted` + `WorldKeyMaterial` fields **reserved on `PieceManifest` since
  Task 19** — no encoding-version bump. Manifests themselves stay **plaintext by design**: seeders
  and the tracker need the piece-hash list and geometry to verify, serve, and place ciphertext they
  cannot read. What leaks is structure metadata (region ids, piece counts/sizes, update cadence) —
  never block content; document this bound explicitly.

## Potential limitations (staged in `LIMITATIONS.md` §B; security-relevant → written normally)

- **L-39** — world content is plaintext on the P2P net today. Exit: AES-GCM-256 content encryption
  under Argon2id(password)-derived key; seeders store ciphertext, verify by hash; join requires
  password; ciphertext-integrity + wrong-password-rejection + nonce-uniqueness tests green.

Security notes (this task handles secrets — the crypto must be correct, not improvised):
- Never roll custom crypto; use AES-GCM and a standard KDF as-is.
- Nonce uniqueness under a static content key is mandatory (GCM catastrophe on reuse of a nonce
  with *different* plaintexts). The deterministic `StableHash(regionRoot, snapshotVersion,
  pieceIndex)` nonce (see above — it cannot be derived from `manifestRoot`; that is circular) is
  safe because a repeated (key, nonce) pair implies the byte-identical plaintext, which is the one
  reuse GCM tolerates (it leaks only message equality — already public via content addressing).
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
4. Nonce uniqueness: across all pieces of a manifest no nonce repeats; across manifests under one
   key, a repeated nonce occurs only for byte-identical plaintext (property test over the
   `(regionRoot, version, index)` derivation).
5. `./gradlew check` green; L-39 → RETIRING; README/Tested + Plan §5 (library pin) updated.
