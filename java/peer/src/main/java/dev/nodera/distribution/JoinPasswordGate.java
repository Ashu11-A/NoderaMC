package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The live-join password gate (L-52): proving you know a world's password <b>to the game server</b>,
 * not merely to its archive.
 *
 * <p>The world password protects the archived <i>content</i> plane — pieces are AES-GCM ciphertext
 * and a joiner without the password cannot read a save it downloads. It never protected the
 * <i>connection</i>: a listed world's game route is public, so anyone who resolved the route could
 * connect and play in a "password-protected" world. That is what this closes.
 *
 * <p>Shape — a challenge-response over a key derived from the password, so the password itself
 * never crosses the wire and a recorded exchange cannot be replayed:
 *
 * <ol>
 *   <li>The host derives {@code gateKey = KDF(password, salt)} once, when it starts hosting, from
 *       freshly generated {@link WorldKeyMaterial} (random salt, production KDF parameters). The
 *       password is not stored; the salt and parameters are public.</li>
 *   <li>Each joining connection gets a fresh {@link #NONCE_BYTES}-byte nonce plus that public key
 *       material.</li>
 *   <li>The joiner derives the same {@code gateKey} from the password its player typed and answers
 *       {@code HMAC-SHA256(gateKey, "nodera-join-gate-v1" ‖ nonce ‖ worldId)}.</li>
 *   <li>The host recomputes and compares in constant time. Anything else — a wrong password, no
 *       answer, a replayed answer from another connection or another world — fails.</li>
 * </ol>
 *
 * <p>The world id is inside the MAC deliberately: without it, a host of world A could relay its own
 * challenge to a joiner of world B and pass B's gate with A's password.
 *
 * <p>This type is Minecraft-free on purpose: the rule is asserted on the ordinary headless gate,
 * and the mod is only the transport that carries the two payloads.
 *
 * <p>Thread-context: stateless; every method is safe from any thread.
 */
public final class JoinPasswordGate {

    /** Challenge nonce length. 32 bytes — collision/replay margin far beyond a session's joins. */
    public static final int NONCE_BYTES = 32;

    /** Domain separator: this MAC is a join proof and can never be read as any other signature. */
    private static final byte[] CONTEXT = "nodera-join-gate-v1".getBytes(StandardCharsets.UTF_8);

    private static final String HMAC = "HmacSHA256";

    private JoinPasswordGate() {
    }

    /**
     * Fresh public key material for a hosting session: a random salt plus the production KDF's
     * parameters. Regenerated on every host start — the gate key is never persisted, so a host
     * that restarts simply issues new material and joiners derive against it from the same
     * password.
     *
     * @param random the randomness source (a {@link SecureRandom} in production).
     * @return the public salt + KDF parameters to send to joiners.
     * @Thread-context any thread.
     */
    public static WorldKeyMaterial newKeyMaterial(SecureRandom random) {
        byte[] salt = new byte[NoderaConstants.PASSWORD_KDF_SALT_BYTES];
        random.nextBytes(salt);
        Bytes saltBytes = Bytes.unsafeWrap(salt);
        return PasswordKeyDerivation.ARGON2ID.equals(PasswordKeyDerivations.production().kdfId())
                ? WorldKeyMaterial.defaultArgon2id(saltBytes)
                : WorldKeyMaterial.pbkdf2(saltBytes,
                        dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation.DEFAULT_ITERATIONS);
    }

    /**
     * A fresh per-connection challenge nonce.
     *
     * @param random the randomness source.
     * @return {@link #NONCE_BYTES} random bytes.
     * @Thread-context any thread.
     */
    public static Bytes newNonce(SecureRandom random) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return Bytes.unsafeWrap(nonce);
    }

    /**
     * Derive the gate key from a password. Deliberately the same cost as the content key: the gate
     * is the only online guess oracle a world has, so a cheap KDF here would undo the expensive one
     * on the content plane.
     *
     * @param password the world password (the caller zeroes it after use, best effort).
     * @param material the public salt + KDF parameters from {@link #newKeyMaterial}.
     * @return the 32-byte gate key.
     * @throws IllegalArgumentException if the password is empty or the material names an unknown KDF.
     * @Thread-context any thread; costs one full KDF derivation (memory-hard).
     */
    public static Bytes gateKey(char[] password, WorldKeyMaterial material) {
        ContentKey key = PasswordKeyDerivations.forMaterial(material)
                .derive(password, material.salt(), material.iterations());
        return key.rawBytes();
    }

    /**
     * The joiner's answer to a challenge.
     *
     * @param gateKey the derived gate key.
     * @param nonce   the host's per-connection nonce.
     * @param worldId the world being joined — bound in so a proof cannot be relayed to another world.
     * @return the MAC to send back.
     * @Thread-context any thread.
     */
    public static Bytes proof(Bytes gateKey, Bytes nonce, Bytes worldId) {
        if (gateKey == null || nonce == null || worldId == null) {
            throw new IllegalArgumentException("gateKey, nonce, and worldId are all required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(gateKey.toArray(), HMAC));
            mac.update(CONTEXT);
            mac.update(nonce.toArray());
            mac.update(worldId.toArray());
            return Bytes.unsafeWrap(mac.doFinal());
        } catch (GeneralSecurityException impossible) {
            // HmacSHA256 is mandatory on every JRE; a missing one is an unusable runtime.
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    /**
     * The host's verdict on a joiner's answer. Constant-time, and false for every malformed input
     * rather than throwing — a joiner controls this value, and a distinctive exception would both
     * leak and complicate the caller.
     *
     * @param gateKey  the host's gate key.
     * @param nonce    the nonce this connection was issued.
     * @param worldId  the world being joined.
     * @param answer   the joiner's MAC (may be null or the wrong length).
     * @return whether the joiner proved knowledge of the password.
     * @Thread-context any thread.
     */
    public static boolean verify(Bytes gateKey, Bytes nonce, Bytes worldId, Bytes answer) {
        if (gateKey == null || nonce == null || worldId == null || answer == null) {
            return false;
        }
        return MessageDigest.isEqual(proof(gateKey, nonce, worldId).toArray(), answer.toArray());
    }
}
