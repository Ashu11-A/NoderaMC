package dev.nodera.distribution;

import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;
import dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation;

/**
 * The production KDF selection point (Task 23 / L-39) — the previously missing consumer that
 * made {@link Argon2KeyDerivation} test-only: every main-code path that derives a
 * {@code ContentKey} from a world password goes through {@link #production()}, which prefers
 * Argon2id (memory-hard, the documented production default) and falls back to the JDK
 * PBKDF2-HMAC-SHA256 implementation only when the pinned BouncyCastle provider is absent from
 * the runtime classpath (e.g. a stripped deployment). The chosen algorithm's parameters travel
 * in the manifest's {@code WorldKeyMaterial}, so joiners derive identically regardless of which
 * side selected.
 *
 * <p>Thread-context: stateless selector; the returned implementations are safe from any thread.
 */
public final class PasswordKeyDerivations {

    private PasswordKeyDerivations() {
    }

    /** @return Argon2id when available (production default), else the JDK PBKDF2 fallback. */
    public static PasswordKeyDerivation production() {
        return argon2Available() ? new Argon2KeyDerivation() : new Pbkdf2KeyDerivation();
    }

    /**
     * The KDF a piece of public key material names, built with <b>that material's</b> cost
     * parameters. Selection by id (never by what this side prefers) is what makes the two ends of
     * a join derive identically; carrying the material's memory and lane counts into the Argon2
     * instance is what keeps a world encrypted with non-default costs decryptable at all.
     *
     * @param material the public salt + parameters from a manifest or a join challenge.
     * @return the matching derivation.
     * @throws IllegalArgumentException if the material names a KDF this build does not implement.
     * @Thread-context any thread.
     */
    public static PasswordKeyDerivation forMaterial(WorldKeyMaterial material) {
        return switch (material.kdf()) {
            case PasswordKeyDerivation.ARGON2ID ->
                    new Argon2KeyDerivation((int) material.memoryKib(), material.parallelism());
            case PasswordKeyDerivation.PBKDF2 -> new Pbkdf2KeyDerivation();
            default -> throw new IllegalArgumentException("unknown KDF: " + material.kdf());
        };
    }

    /** @return whether the pinned BouncyCastle Argon2 implementation is on the classpath. */
    public static boolean argon2Available() {
        try {
            Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator");
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
