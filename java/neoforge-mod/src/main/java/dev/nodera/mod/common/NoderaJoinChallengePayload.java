package dev.nodera.mod.common;

import dev.nodera.endpoint.share.HostJoinGate;
import dev.nodera.core.Bytes;
import dev.nodera.distribution.WorldKeyMaterial;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Base64;

/**
 * The host's live-join password challenge (L-52), sent during the <b>configuration</b> phase so a
 * joiner that cannot answer never reaches the world at all.
 *
 * <p>It carries only public values: which world is being gated, how to derive the gate key from the
 * password (the same KDF parameters an encrypted manifest publishes), and this connection's
 * single-use nonce. The password never crosses the wire in either direction.
 *
 * @param worldIdHex  the hosted world's id (hex) — bound into the answer's MAC.
 * @param kdf         KDF id, e.g. {@code "argon2id"}.
 * @param saltB64     base64 of the public per-session salt.
 * @param memoryKib   Argon2 memory cost (KiB); ignored by PBKDF2.
 * @param iterations  KDF passes/iterations.
 * @param parallelism Argon2 lanes; ignored by PBKDF2.
 * @param nonceB64    base64 of this connection's challenge nonce.
 */
public record NoderaJoinChallengePayload(String worldIdHex, String kdf, String saltB64,
                                         long memoryKib, int iterations, int parallelism,
                                         String nonceB64)
        implements CustomPacketPayload {

    /** The payload type id ({@code nodera:join_challenge}). */
    public static final Type<NoderaJoinChallengePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "join_challenge"));

    /** Wire codec: the world id, the KDF parameters, and the nonce. */
    public static final StreamCodec<FriendlyByteBuf, NoderaJoinChallengePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeUtf(payload.worldIdHex());
                        buf.writeUtf(payload.kdf());
                        buf.writeUtf(payload.saltB64());
                        buf.writeVarLong(payload.memoryKib());
                        buf.writeVarInt(payload.iterations());
                        buf.writeVarInt(payload.parallelism());
                        buf.writeUtf(payload.nonceB64());
                    },
                    buf -> new NoderaJoinChallengePayload(buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readUtf()));

    /**
     * Flatten a gate challenge onto the wire.
     *
     * @param challenge the host's issued challenge.
     * @return the payload to send.
     */
    public static NoderaJoinChallengePayload of(HostJoinGate.Challenge challenge) {
        WorldKeyMaterial material = challenge.material();
        Base64.Encoder b64 = Base64.getEncoder();
        return new NoderaJoinChallengePayload(
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
