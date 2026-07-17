package dev.nodera.mod.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The one Nodera play-channel payload (Task 4 design: a single self-describing payload type).
 *
 * <p>The dedicated server sends it to each client on login to hand over the server's Nodera P2P
 * <b>bootstrap route</b> ({@code host:port} of the {@code SocketPeerTransport}). The client then
 * dials that route out-of-band to join the peer mesh. This vanilla play channel is only used to
 * <i>discover</i> the P2P endpoint; all session-continuity traffic flows over the direct sockets,
 * which is why it survives the vanilla server connection going away.
 *
 * <p>Thread-context: immutable record; the {@link StreamCodec} runs on the netty thread, the
 * handler hops to the main thread via {@code IPayloadContext.enqueueWork}.
 *
 * @param bootstrapRoute the server's dialable {@code host:port} P2P route.
 */
public record NoderaSessionPayload(String bootstrapRoute) implements CustomPacketPayload {

    /** The payload type id ({@code nodera:session}). */
    public static final Type<NoderaSessionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "session"));

    /** Wire codec: a single UTF-8 string (the bootstrap route). */
    public static final StreamCodec<RegistryFriendlyByteBuf, NoderaSessionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeUtf(payload.bootstrapRoute()),
                    buf -> new NoderaSessionPayload(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
