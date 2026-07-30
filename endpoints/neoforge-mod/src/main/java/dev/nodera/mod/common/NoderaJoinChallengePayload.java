package dev.nodera.mod.common;

import dev.nodera.endpoint.share.HostJoinGate;
import dev.nodera.endpoint.share.JoinChallenge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The Minecraft wire wrapper around a {@link JoinChallenge}.
 *
 * <p>It holds the challenge and adds nothing but a {@code StreamCodec}. Answering one is pure key
 * derivation, so everything that does that reads {@link JoinChallenge} in {@code :endpoint} instead.
 * The field order below is the wire contract and is unchanged by the split.
 *
 * @param challenge the host's live-join password challenge (L-52).
 */
public record NoderaJoinChallengePayload(JoinChallenge challenge) implements CustomPacketPayload {

    /** The payload type id ({@code nodera:join_challenge}). */
    public static final Type<NoderaJoinChallengePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "join_challenge"));

    /** Wire codec: the world id, the KDF parameters, and the nonce. */
    public static final StreamCodec<FriendlyByteBuf, NoderaJoinChallengePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        JoinChallenge c = payload.challenge();
                        buf.writeUtf(c.worldIdHex());
                        buf.writeUtf(c.kdf());
                        buf.writeUtf(c.saltB64());
                        buf.writeVarLong(c.memoryKib());
                        buf.writeVarInt(c.iterations());
                        buf.writeVarInt(c.parallelism());
                        buf.writeUtf(c.nonceB64());
                    },
                    buf -> new NoderaJoinChallengePayload(new JoinChallenge(
                            buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readUtf())));

    /**
     * Flatten a gate challenge onto the wire.
     *
     * @param challenge the host's issued challenge.
     * @return the payload to send.
     */
    public static NoderaJoinChallengePayload of(HostJoinGate.Challenge challenge) {
        return new NoderaJoinChallengePayload(JoinChallenge.of(challenge));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
