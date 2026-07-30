/**
 * Per-world content encryption (Task 23; retires L-39) — the minimum JDK-crypto symmetric layer.
 *
 * <p>When a host enables torrent hosting with an encryption password, world content (piece payloads)
 * is encrypted **AES-GCM-256** under a content key derived from that password. Seeders hold opaque
 * ciphertext and verify it by hash-of-ciphertext — they redundantly store and serve data they cannot
 * read. Only a peer that supplies the password on join derives the key and decrypts what it renders.
 *
 * <h2>Why encryption composes with content-addressing</h2>
 *
 * <p>Content ids are hashes over the <b>stored</b> bytes. Under encryption the stored bytes are
 * ciphertext, so a seeder re-hashes ciphertext to verify and serve — no key needed. Three integrity
 * layers agree: (a) the AES-GCM auth tag (tamper evidence at decrypt), (b) the piece hash over
 * ciphertext (seeder-side integrity without decrypt), (c) {@code manifestRoot}/{@code StateRoot} over
 * the plaintext region (canonical truth).
 *
 * <h2>Deterministic nonce = convergent encryption</h2>
 *
 * <p>The per-piece nonce is derived from plaintext-side identifiers
 * ({@code regionRoot, snapshotVersion, pieceIndex} — {@link dev.nodera.core.crypto.symmetric.ContentCipher#nonceFor}),
 * <b>not</b> from {@code manifestRoot} (that would be circular). Same plaintext ⇒ same nonce ⇒ same
 * ciphertext ⇒ same {@code ContentId}, so dedup survives encryption. A repeated {@code (key, nonce)}
 * pair implies byte-identical plaintext — the one nonce-reuse GCM tolerates (it leaks only equality,
 * which content addressing already reveals).
 *
 * <h2>core stays JDK-only</h2>
 *
 * <p>This package uses only JDK crypto (AES-GCM, PBKDF2-HMAC-SHA256). The memory-hard Argon2id KDF
 * lives in {@code distribution} behind a BouncyCastle pin; both implement
 * {@link dev.nodera.core.crypto.symmetric.PasswordKeyDerivation} and are selected by the world's KDF
 * id, so {@code core} never takes a third-party dependency.
 *
 * <p>Thread-context: {@link dev.nodera.core.crypto.symmetric.ContentCipher} and the KDFs are
 * thread-safe; {@link dev.nodera.core.crypto.symmetric.ContentKey} is an in-process handle, never
 * serialised.
 */
package dev.nodera.core.crypto.symmetric;
