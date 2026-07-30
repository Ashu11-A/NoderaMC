package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;

/**
 * Password → AES content key (Task 23; L-39).
 *
 * <p>A seam so {@code core} (JDK-only) can host the standard {@link Pbkdf2KeyDerivation} while
 * {@code distribution} hosts the memory-hard Argon2id behind a BouncyCastle pin. Both produce the
 * same {@link ContentKey} shape; the world's KDF id selects which one a peer uses, so the two sides
 * of a join always agree.
 *
 * <p><b>Constant-time hygiene.</b> A wrong password must derive a key that simply fails to decrypt
 * (AES-GCM auth-tag mismatch) rather than throwing distinctively — the implementation must not leak
 * which failed. {@link Pbkdf2KeyDerivation} and the Argon2 impl both honour this: derivation is a
 * pure function of (password, salt, params) and always returns a key; correctness is decided later
 * by the cipher.
 *
 * <p>Thread-context: implementations must be safe to call from any thread.
 */
public interface PasswordKeyDerivation {

    /** KDF id for PBKDF2-HMAC-SHA256 (the JDK-built-in, always available). */
    String PBKDF2 = "pbkdf2-hmac-sha256";

    /** KDF id for Argon2id (memory-hard; the production default, in {@code distribution}). */
    String ARGON2ID = "argon2id";

    /** @return this implementation's KDF id (e.g. {@link #PBKDF2} or {@link #ARGON2ID}). */
    String kdfId();

    /**
     * Reject password/salt inputs before either KDF allocates work. Shared bounds keep implementations
     * interoperable and prevent attacker-controlled manifest metadata from driving large allocations.
     *
     * @param password password supplied out-of-band by the joining peer.
     * @param salt public per-world salt from the manifest.
     * @throws IllegalArgumentException if either input violates the shared bounds.
     */
    static void validatePasswordAndSalt(char[] password, Bytes salt) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (password.length > NoderaConstants.PASSWORD_KDF_MAX_PASSWORD_CHARS) {
            throw new IllegalArgumentException(
                    "password exceeds " + NoderaConstants.PASSWORD_KDF_MAX_PASSWORD_CHARS + " characters");
        }
        if (salt == null
                || salt.length() < NoderaConstants.PASSWORD_KDF_SALT_BYTES
                || salt.length() > NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES) {
            throw new IllegalArgumentException(
                    "salt length must be in [" + NoderaConstants.PASSWORD_KDF_SALT_BYTES + ","
                            + NoderaConstants.PASSWORD_KDF_MAX_SALT_BYTES + "] bytes");
        }
    }

    /**
     * Derive the 32-byte AES content key from a password.
     *
     * @param password   the human password; zeroed by the caller after use (best effort).
     * @param salt       the per-world salt.
     * @param iterations the cost (PBKDF2 iterations, or Argon2 passes; must be ≥ the documented min).
     * @return the content key.
     * @throws IllegalArgumentException if an argument is null/empty or {@code iterations} is below
     *                                  the implementation's minimum.
     * @Thread-context any thread.
     */
    ContentKey derive(char[] password, Bytes salt, int iterations);
}
