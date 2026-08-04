package dev.nodera.mod.common;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serverbound: "this is the peer node I am" — a joining client hands the session its own Nodera
 * identity so the region-ownership plan can make the <i>player</i> an owner, not a guest of a
 * host. Sent once the client's peer is up (after the session payload's mesh dial).
 *
 * <p>Thread-context: immutable record; codec on the netty thread, handler hops to main.
 *
 * @param nodeIdUuid   the client peer's {@code NodeId} as a UUID string.
 * @param publicKeyB64 base64 of the peer's Ed25519 public key (X.509).
 * @param route        the peer's dialable {@code host:port} P2P route.
 * @param signatureB64 base64 of the peer's signature over the session's announce challenge (issue
 *                     #36 F1: {@code challenge ‖ nodeId ‖ publicKey ‖ mcUuid}), or {@code ""}.
 * @param delegationB64 base64 of a {@code SessionDelegation} — this player's always-on worker
 *                     signing that the announced session key speaks in the worker's name for this
 *                     world — or {@code ""} when no worker was reachable. Permissions are anchored
 *                     to the worker's persistent key, and the announced key is a fresh per-session
 *                     one, so without this the world's own author announces a key the world has
 *                     never heard of and is evaluated as an ordinary member of it.
 */
public record NoderaNodeAnnouncePayload(String nodeIdUuid, String publicKeyB64, String route,
                                        String signatureB64, String delegationB64)
        implements CustomPacketPayload {

    /** The payload type id ({@code nodera:node}). */
    public static final Type<NoderaNodeAnnouncePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(dev.nodera.mod.NoderaMod.MOD_ID, "node"));

    /** Wire codec: five UTF-8 strings. */
    public static final StreamCodec<RegistryFriendlyByteBuf, NoderaNodeAnnouncePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeUtf(payload.nodeIdUuid());
                        buf.writeUtf(payload.publicKeyB64());
                        buf.writeUtf(payload.route());
                        buf.writeUtf(payload.signatureB64());
                        buf.writeUtf(payload.delegationB64());
                    },
                    buf -> new NoderaNodeAnnouncePayload(buf.readUtf(), buf.readUtf(), buf.readUtf(),
                            buf.readUtf(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
