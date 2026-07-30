package dev.nodera.endpoint.share;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.WorldKeyMaterial;

import java.util.Base64;

/**
 * The host's live-join password challenge (L-52), as data.
 *
 * <p>Sent during the <b>configuration</b> phase so a joiner that cannot answer never reaches the
 * world at all. It carries only public values: which world is being gated, how to derive the gate
 * key from the password (the same KDF parameters an encrypted manifest publishes), and this
 * connection's single-use nonce. The password never crosses the wire in either direction.
 *
 * <p>The Minecraft wire wrapper is {@code dev.nodera.mod.common.NoderaJoinChallengePayload}, which
 * holds one of these and adds a {@code StreamCodec}. They are separate so that answering a challenge
 * — which is pure key derivation — needs no Minecraft class.
 *
 * @param worldIdHex  the hosted world's id (hex) — bound into the answer's MAC.
 * @param kdf         KDF id, e.g. {@code "argon2id"}.
 * @param saltB64     base64 of the public per-session salt.
 * @param memoryKib   Argon2 memory cost (KiB); ignored by PBKDF2.
 * @param iterations  KDF passes/iterations.
 * @param parallelism Argon2 lanes; ignored by PBKDF2.
 * @param nonceB64    base64 of this connection's challenge nonce.
 */
public record JoinChallenge(String worldIdHex, String kdf, String saltB64,
                            long memoryKib, int iterations, int parallelism,
                            String nonceB64) {

    /**
     * Flatten a gate challenge into its wire-shaped form.
     *
     * @param challenge the host's issued challenge.
     * @return the flattened challenge.
     */
    public static JoinChallenge of(HostJoinGate.Challenge challenge) {
        WorldKeyMaterial material = challenge.material();
        Base64.Encoder b64 = Base64.getEncoder();
        return new JoinChallenge(
                challenge.worldIdHex(),
                material.kdf(),
                b64.encodeToString(material.salt().toArray()),
                material.memoryKib(),
                material.iterations(),
                material.parallelism(),
                b64.encodeToString(challenge.nonce().toArray()));
    }

    /**
     * The KDF parameters this challenge names.
     *
     * @return the key material to derive the gate key with.
     * @throws IllegalArgumentException if the values are outside the shared bounds (a malformed or
     *                                  hostile challenge is refused here rather than deep in a KDF).
     */
    public WorldKeyMaterial material() {
        return new WorldKeyMaterial(kdf, Bytes.unsafeWrap(Base64.getDecoder().decode(saltB64)),
                memoryKib, iterations, parallelism);
    }

    /** @return the challenge nonce. */
    public Bytes nonce() {
        return Bytes.unsafeWrap(Base64.getDecoder().decode(nonceB64));
    }
}
