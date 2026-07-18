package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.util.Arrays;
import java.util.Objects;

/**
 * Memory-hard Argon2id password KDF (Task 23; L-39) — the production-preferred KDF, in
 * {@code distribution} behind the BouncyCastle pin (core stays JDK-only).
 *
 * <p>Argon2id (hybrid data-dependent + data-independent) is the OWASP-recommended password KDF: its
 * memory cost makes GPU/ASIC brute-force expensive in a way PBKDF2's pure-CPU iteration cannot. New
 * encrypted worlds default to it; PBKDF2 remains the always-available JDK fallback. Both implement
 * {@link PasswordKeyDerivation} and produce the same {@link ContentKey} shape.
 *
 * <p>The cost parameters ({@code memoryKib}, {@code iterations}, {@code parallelism}) travel in the
 * manifest's {@link WorldKeyMaterial} so every joining peer derives identically; defaults enforce a
 * documented floor so a misconfigured world cannot ship brute-forceable params.
 *
 * <p>Thread-context: safe from any thread. A fresh {@link Argon2BytesGenerator} is built per call.
 */
public final class Argon2KeyDerivation implements PasswordKeyDerivation {

    /** Argon2id type id (BouncyCastle constant mirror). */
    public static final int ARGON2ID_TYPE = Argon2Parameters.ARGON2_id;

    /** Minimum memory cost, KiB. */
    public static final int MIN_MEMORY_KIB = NoderaConstants.ARGON2_MIN_MEMORY_KIB;

    /** Default memory cost: 32 MiB. */
    public static final int DEFAULT_MEMORY_KIB = NoderaConstants.ARGON2_DEFAULT_MEMORY_KIB;

    public static final int MAX_MEMORY_KIB = NoderaConstants.ARGON2_MAX_MEMORY_KIB;

    /** Minimum iteration count (passes). */
    public static final int MIN_ITERATIONS = NoderaConstants.ARGON2_MIN_ITERATIONS;

    /** Default iteration count. */
    public static final int DEFAULT_ITERATIONS = NoderaConstants.ARGON2_DEFAULT_ITERATIONS;

    public static final int MAX_ITERATIONS = NoderaConstants.ARGON2_MAX_ITERATIONS;

    /** Minimum parallelism (lanes). */
    public static final int MIN_PARALLELISM = NoderaConstants.ARGON2_MIN_PARALLELISM;

    /** Default parallelism. */
    public static final int DEFAULT_PARALLELISM = NoderaConstants.ARGON2_DEFAULT_PARALLELISM;

    public static final int MAX_PARALLELISM = NoderaConstants.ARGON2_MAX_PARALLELISM;

    private final int memoryKib;
    private final int parallelism;

    /** Defaults: 32 MiB, 3 passes, 1 lane. */
    public Argon2KeyDerivation() {
        this(DEFAULT_MEMORY_KIB, DEFAULT_PARALLELISM);
    }

    /**
     * @param memoryKib  memory cost; must be in [{@link #MIN_MEMORY_KIB},
     *                   {@link #MAX_MEMORY_KIB}].
     * @param parallelism lanes; must be in [{@link #MIN_PARALLELISM},
     *                    {@link #MAX_PARALLELISM}].
     * @throws IllegalArgumentException if a bound is outside its allowed range.
     * @Thread-context any thread (construction only).
     */
    public Argon2KeyDerivation(int memoryKib, int parallelism) {
        if (memoryKib < MIN_MEMORY_KIB || memoryKib > MAX_MEMORY_KIB) {
            throw new IllegalArgumentException(
                    "memoryKib must be in [" + MIN_MEMORY_KIB + "," + MAX_MEMORY_KIB + "]: "
                            + memoryKib);
        }
        if (parallelism < MIN_PARALLELISM || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException(
                    "parallelism must be in [" + MIN_PARALLELISM + "," + MAX_PARALLELISM + "]: "
                            + parallelism);
        }
        this.memoryKib = memoryKib;
        this.parallelism = parallelism;
    }

    public static Argon2KeyDerivation from(WorldKeyMaterial material) {
        Objects.requireNonNull(material, "material");
        if (!ARGON2ID.equals(material.kdf())) {
            throw new IllegalArgumentException("unsupported kdf: " + material.kdf());
        }

        final int memoryKib;
        try {
            memoryKib = Math.toIntExact(material.memoryKib());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "memoryKib does not fit in an int: " + material.memoryKib(), e);
        }
        if (material.iterations() < MIN_ITERATIONS || material.iterations() > MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "iterations must be in [" + MIN_ITERATIONS + "," + MAX_ITERATIONS + "]: "
                            + material.iterations());
        }
        if (material.salt().length() < NoderaConstants.PASSWORD_KDF_SALT_BYTES
                || material.salt().length() > NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES) {
            throw new IllegalArgumentException(
                    "salt length must be in [" + NoderaConstants.PASSWORD_KDF_SALT_BYTES + ","
                            + NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES + "] bytes");
        }
        return new Argon2KeyDerivation(memoryKib, material.parallelism());
    }

    /** @return {@link #ARGON2ID}. */
    @Override
    public String kdfId() {
        return ARGON2ID;
    }

    /** @return the memory cost (KiB). */
    public int memoryKib() {
        return memoryKib;
    }

    /** @return the parallelism (lanes). */
    public int parallelism() {
        return parallelism;
    }

    /**
     * @param password   the non-empty password.
     * @param salt       the per-world salt (16–64 bytes).
     * @param iterations passes; must be in [{@link #MIN_ITERATIONS}, {@link #MAX_ITERATIONS}].
     * @return the derived 32-byte AES content key.
     * @Thread-context any thread.
     */
    @Override
    public ContentKey derive(char[] password, Bytes salt, int iterations) {
        PasswordKeyDerivation.validatePasswordAndSalt(password, salt);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException(
                    "iterations must be in [" + MIN_ITERATIONS + "," + MAX_ITERATIONS + "]: "
                            + iterations);
        }

        byte[] passBytes = charsToUtf8(password);
        byte[] key = null;
        try {
            Argon2Parameters params = new Argon2Parameters.Builder(ARGON2ID_TYPE)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memoryKib)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt.toArray())
                    .build();
            Argon2BytesGenerator gen = new Argon2BytesGenerator();
            gen.init(params);
            key = new byte[ContentKey.KEY_BYTES];
            gen.generateBytes(passBytes, key);
            return ContentKey.of(key);
        } finally {
            Arrays.fill(passBytes, (byte) 0);
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    private static byte[] charsToUtf8(char[] chars) {
        int byteCount = 0;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c <= 0x7f) {
                byteCount++;
            } else if (c <= 0x7ff) {
                byteCount += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= chars.length || !Character.isLowSurrogate(chars[i + 1])) {
                    throw new IllegalArgumentException(
                            "password contains malformed UTF-16 at index " + i);
                }
                byteCount += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "password contains malformed UTF-16 at index " + i);
            } else {
                byteCount += 3;
            }
        }

        byte[] encoded = new byte[byteCount];
        int offset = 0;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c <= 0x7f) {
                encoded[offset++] = (byte) c;
            } else if (c <= 0x7ff) {
                encoded[offset++] = (byte) (0xc0 | (c >>> 6));
                encoded[offset++] = (byte) (0x80 | (c & 0x3f));
            } else if (Character.isHighSurrogate(c)) {
                int codePoint = Character.toCodePoint(c, chars[++i]);
                encoded[offset++] = (byte) (0xf0 | (codePoint >>> 18));
                encoded[offset++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
                encoded[offset++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
                encoded[offset++] = (byte) (0x80 | (codePoint & 0x3f));
            } else {
                encoded[offset++] = (byte) (0xe0 | (c >>> 12));
                encoded[offset++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                encoded[offset++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        return encoded;
    }
}
