package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;

import java.util.Objects;

/**
 * The public half of a password-encrypted world's key derivation (Task 23, <b>reserved by Task
 * 19</b>). It carries the KDF parameters and salt needed to re-derive the content key from a
 * password — never the key, and never the password.
 *
 * <p>Task 19 reserved this type and {@link PieceManifest}'s optional slot before encryption shipped,
 * avoiding an encoding-version bump when Task 23 populated it. Plaintext manifests use
 * {@code encrypted = false, keyMaterial = null}; encrypted manifests carry this public metadata and
 * pin ciphertext piece hashes. No password, content key, wrapped key, or escrow value is present.
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
     * @throws IllegalArgumentException if a reference argument is null, salt length is outside the
     *                                  shared bound, or a cost parameter is outside its allocation/
     *                                  CPU bound.
     */
    public WorldKeyMaterial {
        Objects.requireNonNull(kdf, "kdf");
        Objects.requireNonNull(salt, "salt");
        if (kdf.isBlank()) {
            throw new IllegalArgumentException("kdf must not be blank");
        }
        if (salt.length() < NoderaConstants.PASSWORD_KDF_SALT_BYTES
                || salt.length() > NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES) {
            throw new IllegalArgumentException(
                    "salt length must be in [" + NoderaConstants.PASSWORD_KDF_SALT_BYTES + ","
                            + NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES + "] bytes");
        }
        if (memoryKib <= 0 || memoryKib > NoderaConstants.ARGON2_MAX_MEMORY_KIB) {
            throw new IllegalArgumentException(
                    "memoryKib must be in [1," + NoderaConstants.ARGON2_MAX_MEMORY_KIB + "]: "
                            + memoryKib);
        }
        if (iterations <= 0 || iterations > NoderaConstants.PBKDF2_MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "iterations must be in [1," + NoderaConstants.PBKDF2_MAX_ITERATIONS + "]: "
                            + iterations);
        }
        if (parallelism <= 0 || parallelism > NoderaConstants.ARGON2_MAX_PARALLELISM) {
            throw new IllegalArgumentException(
                    "parallelism must be in [1," + NoderaConstants.ARGON2_MAX_PARALLELISM + "]: "
                            + parallelism);
        }
    }

    /**
     * Build metadata for a new Argon2id world using production defaults.
     *
     * @param salt a freshly generated per-world salt.
     * @return bounded Argon2id metadata.
     */
    public static WorldKeyMaterial defaultArgon2id(Bytes salt) {
        return new WorldKeyMaterial(
                PasswordKeyDerivation.ARGON2ID,
                salt,
                NoderaConstants.ARGON2_DEFAULT_MEMORY_KIB,
                NoderaConstants.ARGON2_DEFAULT_ITERATIONS,
                NoderaConstants.ARGON2_DEFAULT_PARALLELISM);
    }

    /** Build metadata for the JDK-only PBKDF2 fallback. */
    public static WorldKeyMaterial pbkdf2(Bytes salt, int iterations) {
        return new WorldKeyMaterial(PasswordKeyDerivation.PBKDF2, salt, 1, iterations, 1);
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
