package dev.nodera.mod.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The one Nodera play-channel payload (Task 4 design: a single self-describing payload type).
 *
 * <p>The hosting server sends it to each client on login to hand over the server's Nodera P2P
 * <b>bootstrap route</b> ({@code host:port} of the {@code SocketPeerTransport}) plus the shared
 * world's identity (worldId + display name). The client dials the route out-of-band to join the
 * peer mesh, and the identity arms the continuity lane — the client knows <i>which</i> world to
 * recover from the network if the host goes away, regardless of how it joined (Nodera multiplayer
 * list, direct IP, quick play). This vanilla play channel is only used to <i>discover</i> the
 * session; all continuity traffic flows over the direct sockets, which is why it survives the
 * vanilla server connection going away.
 *
 * <p>Both endpoints run the same mod build (assumption A0 — the handshake enforces the registrar
 * version), so extending this payload is a mod-internal change, not a frozen-wire change.
 *
 * <p>Thread-context: immutable record; the {@link StreamCodec} runs on the netty thread, the
 * handler hops to the main thread via {@code IPayloadContext.enqueueWork}.
 *
 * @param bootstrapRoute the server's dialable {@code host:port} P2P route.
 * @param worldIdHex     the shared world's id (hex), or {@code ""} when the world has none yet.
 * @param worldName      the world's display name, or {@code ""}.
 */
public record NoderaSessionPayload(String bootstrapRoute, String worldIdHex, String worldName)
        implements CustomPacketPayload {

    /** Route-only convenience (world identity unknown/absent). */
    public NoderaSessionPayload(String bootstrapRoute) {
        this(bootstrapRoute, "", "");
    }

    /** The payload type id ({@code nodera:session}). */
    public static final Type<NoderaSessionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "session"));

    /** Wire codec: three UTF-8 strings (route, worldId hex, world name). */
    public static final StreamCodec<RegistryFriendlyByteBuf, NoderaSessionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeUtf(payload.bootstrapRoute());
                        buf.writeUtf(payload.worldIdHex());
                        buf.writeUtf(payload.worldName());
                    },
                    buf -> new NoderaSessionPayload(buf.readUtf(), buf.readUtf(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
