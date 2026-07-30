package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One piece's ciphertext, the wire/stored form of a piece under per-world encryption (Task 23;
 * L-39).
 *
 * <p>Carries the AES-GCM ciphertext (payload + auth tag) and the deterministic nonce that produced
 * it. The nonce is public (it derives from plaintext-side identifiers — see
 * {@link dev.nodera.core.crypto.symmetric.ContentCipher#nonceFor}); shipping it next to the
 * ciphertext is fine, because the security rests on the auth tag + the key, never on nonce secrecy.
 *
 * <p>The piece hash — what {@code PieceManifest} pins and what a seeder verifies — is over the
 * <b>ciphertext</b>, so a seeder re-hashes this type's {@link #ciphertext()} to serve and verify
 * content it cannot decrypt. The data plane (Task 19's reassembler, lock map) is therefore
 * encryption-agnostic: it moves and verifies opaque bytes; decryption happens only on the
 * rendering/simulating peer that holds the {@link dev.nodera.core.crypto.symmetric.ContentKey}.
 *
 * <p>Wire form: {@code [u16 ENCRYPTED_PIECE][u16 ENCODING_VERSION][bytes nonce][bytes ciphertext]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param nonce      the 12-byte AES-GCM nonce.
 * @param ciphertext the ciphertext (payload + 128-bit GCM auth tag).
 */
public record EncryptedPiece(Bytes nonce, Bytes ciphertext) implements Encodable {

    /** Shared hasher (thread-safe via {@link ThreadLocal} confinement in {@link HashService}). */
    private static final HashService HASHES = new HashService();

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null, the nonce is not 12 bytes, or the
     *                                  ciphertext is shorter than the GCM tag.
     */
    public EncryptedPiece {
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertext, "ciphertext");
        if (nonce.length() != dev.nodera.core.crypto.symmetric.ContentCipher.NONCE_BYTES) {
            throw new IllegalArgumentException(
                    "nonce must be " + dev.nodera.core.crypto.symmetric.ContentCipher.NONCE_BYTES
                            + " bytes, got " + nonce.length());
        }
        // GCM ciphertext is plaintext + 16-byte tag, so even an empty plaintext yields 16 bytes.
        if (ciphertext.length() < dev.nodera.core.crypto.symmetric.ContentCipher.TAG_BITS / 8) {
            throw new IllegalArgumentException("ciphertext shorter than the GCM auth tag");
        }
    }

    /** @return the SHA-256 of the ciphertext — the value a seeder verifies and a manifest pins. */
    public Bytes ciphertextHash() {
        return HASHES.sha256(ciphertext);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENCRYPTED_PIECE).writeU16(ENCODING_VERSION);
        w.writeBytes(nonce);
        w.writeBytes(ciphertext);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @throws IllegalStateException if the next tag is not {@code ENCRYPTED_PIECE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static EncryptedPiece decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ENCRYPTED_PIECE) {
            throw new IllegalStateException("expected ENCRYPTED_PIECE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes nonce = r.readBytesValue();
        Bytes ciphertext = r.readBytesValue();
        return new EncryptedPiece(nonce, ciphertext);
    }

    @Override
    public String toString() {
        return "EncryptedPiece[nonce=" + nonce.toShortHex(4)
                + " ct=" + ciphertext.length() + "B " + ciphertextHash().toShortHex(4) + "]";
    }
}
