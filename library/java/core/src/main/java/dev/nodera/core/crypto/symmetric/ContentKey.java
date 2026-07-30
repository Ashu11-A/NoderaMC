package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-256 content key derived from a world's encryption password (Task 23; L-39).
 *
 * <p>An <b>in-process handle only</b>: it is never serialised, never written to disk, and never sent
 * over the wire. The password is a human secret held out-of-band; the derived key exists solely to
 * encrypt/decrypt piece payloads on the peers that legitimately render the world. A seeder never
 * holds one — it serves ciphertext it cannot read.
 *
 * <p>Wraps a raw 32-byte AES key as a {@link SecretKey}; {@link SecretKeySpec} is the JDK's
 * plain-bytes key spec, immutable and safe to retain.
 *
 * <p>Thread-context: immutable, safe for any thread.
 */
public final class ContentKey {

    /** AES-256 key length, in bytes. */
    public static final int KEY_BYTES = 32;

    private final SecretKey secret;

    private ContentKey(SecretKey secret) {
        this.secret = secret;
    }

    /**
     * Wrap a 32-byte raw key. The bytes are defensively copied.
     *
     * @param rawKey exactly 32 bytes.
     * @throws IllegalArgumentException if {@code rawKey} is null or not 32 bytes.
     * @Thread-context any thread.
     */
    public static ContentKey of(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "content key must be " + KEY_BYTES + " bytes, got "
                            + (rawKey == null ? "null" : rawKey.length));
        }
        return new ContentKey(new SecretKeySpec(rawKey.clone(), "AES"));
    }

    /** @return the underlying AES {@link SecretKey} (for the cipher; caller must not retain). */
    SecretKey secretKey() {
        return secret;
    }

    /** @return a fresh defensive copy of the raw key bytes (for KDF chaining / tests). */
    public Bytes rawBytes() {
        return Bytes.unsafeWrap(secret.getEncoded());
    }

    /** Never prints key material. */
    @Override
    public String toString() {
        return "ContentKey[" + KEY_BYTES + " bytes, redacted]";
    }
}
