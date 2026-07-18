package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * JDK-built-in PBKDF2-HMAC-SHA256 password KDF (Task 23; L-39).
 *
 * <p>The KDF that keeps {@code core} third-party-free: it uses only {@link SecretKeyFactory} with
 * {@code PBKDF2WithHmacSHA256}, producing a 32-byte AES key. It is the always-available fallback; the
 * memory-hard Argon2id (the production default) lives in {@code distribution}. Both are selected by
 * KDF id so a joining peer matches the world's chosen KDF.
 *
 * <p><b>Minimum cost.</b> {@link #MIN_ITERATIONS} enforces a documented floor so a misconfigured
 * world cannot ship a brute-forceable low iteration count. The default
 * {@link #DEFAULT_ITERATIONS} is deliberately high for an interactive password.
 *
 * <p>Thread-context: safe from any thread. {@link SecretKeyFactory} is obtained per call (the JDK
 * instance is not guaranteed thread-safe).
 */
public final class Pbkdf2KeyDerivation implements PasswordKeyDerivation {

    /** JDK algorithm name. */
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** Minimum permitted iteration count (the documented floor). */
    public static final int MIN_ITERATIONS = NoderaConstants.PBKDF2_MIN_ITERATIONS;

    /** Default iteration count for an interactive password. */
    public static final int DEFAULT_ITERATIONS = NoderaConstants.PBKDF2_DEFAULT_ITERATIONS;

    /** Maximum accepted cost from world metadata; bounds CPU work before derivation. */
    public static final int MAX_ITERATIONS = NoderaConstants.PBKDF2_MAX_ITERATIONS;

    /** @return the default-cost instance. */
    public static Pbkdf2KeyDerivation defaultInstance() {
        return new Pbkdf2KeyDerivation();
    }

    /** @return {@link #PBKDF2}. */
    @Override
    public String kdfId() {
        return PBKDF2;
    }

    /**
     * @param password   the password.
     * @param salt       the per-world salt (non-empty).
     * @param iterations the cost; must be ≥ {@link #MIN_ITERATIONS}.
     * @return the derived 32-byte AES content key.
     * @throws IllegalArgumentException if an argument is null/empty or {@code iterations} is below
     *                                  the minimum.
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
        PBEKeySpec spec = new PBEKeySpec(password, salt.toArray(), iterations, ContentKey.KEY_BYTES * 8);
        byte[] key = null;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            key = factory.generateSecret(spec).getEncoded();
            return ContentKey.of(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 key derivation failed", e);
        } finally {
            spec.clearPassword();
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }
}
