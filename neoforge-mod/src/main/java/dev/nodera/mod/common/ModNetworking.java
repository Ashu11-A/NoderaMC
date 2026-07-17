package dev.nodera.mod.common;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the Nodera play-channel payload and the protocol-version registrar.
 *
 * <p>Design rule (Task 4): a single self-describing payload type carries Nodera play-channel
 * traffic. For the Phase 6 continuity beta that payload is {@link NoderaSessionPayload}: the
 * server → client hand-off of the P2P bootstrap route. Everything else (membership, keep-alives,
 * gateway migration) flows over the direct {@code SocketPeerTransport}, off the vanilla channel,
 * which is exactly why it survives the vanilla server going offline.
 *
 * <p>Thread context: {@code register} subscribes on the mod event bus; the
 * {@link RegisterPayloadHandlersEvent} fires on the mod-loading thread. The client handler runs on
 * the netty thread and hops to the main thread via {@link IPayloadContext#enqueueWork}.
 */
public final class ModNetworking {

    /** NeoForge payload registrar version string (Task 0 §2: wire protocol version "1"). */
    public static final String PROTOCOL_VERSION = "1";

    private ModNetworking() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod}. Idempotent: subscribing twice is a no-op. */
    public static void register(IEventBus modBus) {
        modBus.addListener(ModNetworking::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(NoderaSessionPayload.TYPE, NoderaSessionPayload.STREAM_CODEC,
                ModNetworking::handleSessionOnClient);
    }

    /**
     * Client-side receipt of the server's P2P bootstrap route: join the peer mesh. Runs on the
     * netty thread; the actual runtime start is trivial and thread-safe, but we still hop to the
     * main thread for lifecycle cleanliness.
     */
    private static void handleSessionOnClient(NoderaSessionPayload payload, IPayloadContext context) {
        String advertiseHost = NoderaConfig.CLIENT_P2P_ADVERTISE_HOST.get();
        context.enqueueWork(() ->
                NoderaPeerService.get().onServerSessionInfo(payload.bootstrapRoute(), advertiseHost));
    }
}
