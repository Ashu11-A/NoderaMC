package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * The public half of a password-encrypted world's key derivation (Task 23, <b>reserved by Task
 * 19</b>). It carries the KDF parameters and salt needed to re-derive the content key from a
 * password — never the key, and never the password.
 *
 * <p>This type exists now, and {@link PieceManifest} carries its optional slot now, precisely so
 * that shipping encryption later needs <b>no encoding-version bump</b>: the manifest wire form is
 * already shaped for it. Until Task 23, manifests are constructed with
 * {@code encrypted = false, keyMaterial = null} and every piece hash is over plaintext.
 *
 * <p>The parameters are stored as integers (no floats — the canonical encoding has none by
 * design), which is exactly how Argon2id is parameterised anyway.
 *
 * <p>Wire form: {@code [u16 WORLD_KEY_MATERIAL][u16 ENCODING_VERSION][String kdf][bytes salt]
 * [u64 memoryKib][u32 iterations][u32 parallelism]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param kdf         KDF identifier, e.g. {@code "argon2id"}.
 * @param salt        the per-world KDF salt (never reused across worlds).
 * @param memoryKib   Argon2 memory cost, in KiB.
 * @param iterations  Argon2 time cost (passes).
 * @param parallelism Argon2 lanes.
 */
public record WorldKeyMaterial(
        String kdf,
        Bytes salt,
        long memoryKib,
        int iterations,
        int parallelism
) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference argument is null or a cost parameter is not
     *                                  positive.
     */
    public WorldKeyMaterial {
        Objects.requireNonNull(kdf, "kdf");
        Objects.requireNonNull(salt, "salt");
        if (kdf.isBlank()) {
            throw new IllegalArgumentException("kdf must not be blank");
        }
        if (memoryKib <= 0) {
            throw new IllegalArgumentException("memoryKib must be positive: " + memoryKib);
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive: " + iterations);
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive: " + parallelism);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_KEY_MATERIAL).writeU16(ENCODING_VERSION);
        w.writeString(kdf);
        w.writeBytes(salt);
        w.writeU64(memoryKib);
        w.writeU32(Integer.toUnsignedLong(iterations));
        w.writeU32(Integer.toUnsignedLong(parallelism));
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded key material.
     * @throws IllegalStateException if the next tag is not {@code WORLD_KEY_MATERIAL}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static WorldKeyMaterial decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_KEY_MATERIAL) {
            throw new IllegalStateException("expected WORLD_KEY_MATERIAL tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        String kdf = r.readString();
        Bytes salt = r.readBytesValue();
        long memoryKib = r.readU64();
        int iterations = (int) r.readU32();
        int parallelism = (int) r.readU32();
        return new WorldKeyMaterial(kdf, salt, memoryKib, iterations, parallelism);
    }

    @Override
    public String toString() {
        return "WorldKeyMaterial[" + kdf + " m=" + memoryKib + "KiB t=" + iterations
                + " p=" + parallelism + "]";
    }
}
