package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM-256 encrypt/decrypt of piece payloads (Task 23; L-39).
 *
 * <h2>Per-piece deterministic nonce (convergent encryption)</h2>
 *
 * <p>The nonce is derived from <b>plaintext-side</b> identifiers — {@code (regionRoot,
 * snapshotVersion, pieceIndex)} — never from {@code manifestRoot} (that would be circular: the root
 * is computed from piece hashes, which are over ciphertext, which needs the nonce). See
 * {@link #nonceFor(StateRoot, SnapshotVersion, int)}. Same plaintext ⇒ same nonce ⇒ same ciphertext
 * ⇒ same {@code ContentId}, so dedup survives encryption and seeders can verify ciphertext without
 * the key.
 *
 * <p>Nonces are public; revealing them does not weaken the key. The one nonce-reuse case GCM
 * tolerates is byte-identical plaintext under the same (key, nonce), which leaks only equality —
 * already public via content addressing.
 *
 * <h2>Integrity</h2>
 *
 * <p>GCM's 128-bit auth tag is the tamper alarm: a flipped ciphertext bit or a wrong key fails
 * {@link #decrypt} with {@code false}, never returning corrupt plaintext. Combined with the
 * ciphertext piece hash (seeder-side) and the plaintext {@code regionRoot} (canonical truth), all
 * three must agree for a piece to unlock.
 *
 * <p>Thread-context: safe from any thread. A fresh {@link Cipher} is obtained per operation (the JDK
 * instance is not thread-safe).
 */
public final class ContentCipher {

    /** AES-GCM transform. */
    public static final String TRANSFORM = "AES/GCM/NoPadding";

    /** GCM nonce length, in bytes (96 bits — the GCM standard). */
    public static final int NONCE_BYTES = 12;

    /** GCM authentication-tag length, in bits. */
    public static final int TAG_BITS = 128;

    /** Shared SHA-256 service; thread-safe through per-thread digest confinement. */
    private static final HashService HASHES = new HashService();

    private ContentCipher() {}

    /**
     * Derive the deterministic 12-byte nonce for a piece from plaintext-side identifiers.
     *
     * @param regionRoot the region's committed state root (known before encryption).
     * @param version    the snapshot version.
     * @param pieceIndex the piece index.
     * @return the 12-byte nonce.
     * @throws IllegalArgumentException if an argument is null or the index is negative.
     * @Thread-context any thread.
     */
    public static Bytes nonceFor(StateRoot regionRoot, SnapshotVersion version, int pieceIndex) {
        if (regionRoot == null || version == null) {
            throw new IllegalArgumentException("regionRoot and version must not be null");
        }
        if (pieceIndex < 0) {
            throw new IllegalArgumentException("pieceIndex must be non-negative: " + pieceIndex);
        }
        CanonicalWriter w = new CanonicalWriter(80);
        w.writeString("nodera.content-nonce.v1");
        w.writeBytes(regionRoot.hash());
        w.writeU64(version.value());
        w.writeU32(Integer.toUnsignedLong(pieceIndex));
        byte[] hash = HASHES.sha256Bytes(w.toByteArray());
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(hash, 0, nonce, 0, NONCE_BYTES);
        return Bytes.unsafeWrap(nonce);
    }

    /**
     * Encrypt a piece payload under a content key with a given nonce.
     *
     * @param key     the content key.
     * @param nonce   the 12-byte nonce (from {@link #nonceFor}).
     * @param plaintext the piece bytes.
     * @return the ciphertext (payload + GCM auth tag).
     * @throws IllegalArgumentException if an argument is null, the nonce is not 12 bytes, or the
     *                                  plaintext is empty.
     * @Thread-context any thread.
     */
    public static Bytes encrypt(ContentKey key, Bytes nonce, Bytes plaintext) {
        if (key == null || nonce == null || plaintext == null) {
            throw new IllegalArgumentException("key, nonce, plaintext must not be null");
        }
        if (nonce.length() != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be " + NONCE_BYTES + " bytes");
        }
        if (plaintext.isEmpty()) {
            throw new IllegalArgumentException("plaintext must not be empty");
        }
        Cipher cipher = gcmCipher();
        try {
            cipher.init(Cipher.ENCRYPT_MODE,
                    key.secretKey(),
                    new GCMParameterSpec(TAG_BITS, nonce.toArray()));
            return Bytes.unsafeWrap(cipher.doFinal(plaintext.toArray()));
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypt a ciphertext; returns empty on any failure (tamper, wrong key, truncation) rather than
     * throwing distinctively, so the failure mode leaks nothing.
     *
     * @param key        the content key.
     * @param nonce      the 12-byte nonce.
     * @param ciphertext the ciphertext (payload + tag).
     * @return the plaintext, or empty if the key is wrong or the tag does not verify.
     * @throws IllegalArgumentException if an argument is null or the nonce is not 12 bytes.
     * @Thread-context any thread.
     */
    public static java.util.Optional<Bytes> decrypt(ContentKey key, Bytes nonce, Bytes ciphertext) {
        if (key == null || nonce == null || ciphertext == null) {
            throw new IllegalArgumentException("key, nonce, ciphertext must not be null");
        }
        if (nonce.length() != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be " + NONCE_BYTES + " bytes");
        }
        Cipher cipher = gcmCipher();
        try {
            cipher.init(Cipher.DECRYPT_MODE,
                    key.secretKey(),
                    new GCMParameterSpec(TAG_BITS, nonce.toArray()));
            return java.util.Optional.of(Bytes.unsafeWrap(cipher.doFinal(ciphertext.toArray())));
        } catch (Exception e) {
            // AEADBadTagException (wrong key / tamper), IllegalBlockSize, BadPadding — all collapse
            // to "decrypt failed" so a wrong password is indistinguishable from corruption here.
            return java.util.Optional.empty();
        }
    }

    private static Cipher gcmCipher() {
        try {
            return Cipher.getInstance(TRANSFORM);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }
}
