package dev.nodera.mod.common;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The joiner's answer to a live-join password challenge (L-52): a MAC over the host's nonce and the
 * world id, keyed by the gate key its player's password derives.
 *
 * <p>An empty {@code proofB64} is a legitimate, deliberate value: it says "this client has no
 * password for this world". Answering promptly is friendlier than timing out — the host refuses the
 * connection with a message the player can act on.
 *
 * @param proofB64 base64 of the HMAC answer, or {@code ""} when the client has no password.
 */
public record NoderaJoinProofPayload(String proofB64) implements CustomPacketPayload {

    /** The payload type id ({@code nodera:join_proof}). */
    public static final Type<NoderaJoinProofPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "join_proof"));

    /** Wire codec: one base64 string. */
    public static final StreamCodec<FriendlyByteBuf, NoderaJoinProofPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeUtf(payload.proofB64()),
                    buf -> new NoderaJoinProofPayload(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
