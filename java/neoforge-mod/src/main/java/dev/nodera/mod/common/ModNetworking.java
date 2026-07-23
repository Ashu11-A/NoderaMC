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

    /**
     * Client-dist hook for the session's world identity (worldIdHex, worldName) — set by
     * {@code ClientBootstrap} so this common class never classloads client-only types. Volatile:
     * written on the mod-loading thread, read on the netty thread.
     */
    private static volatile java.util.function.BiConsumer<String, String> sessionWorldListener;

    private ModNetworking() {
    }

    /** Register the client-side listener for the session's world identity (continuity arming). */
    public static void setSessionWorldListener(java.util.function.BiConsumer<String, String> listener) {
        sessionWorldListener = listener;
    }

    /** Called from {@link dev.nodera.mod.NoderaMod}. Idempotent: subscribing twice is a no-op. */
    public static void register(IEventBus modBus) {
        modBus.addListener(ModNetworking::onRegisterPayloads);
    }

    /** Client-dist hook for the region-ownership plan payload (set by {@code ClientBootstrap}). */
    private static volatile java.util.function.Consumer<NoderaLanePlanPayload> planListener;

    /** Register the client-side listener for region-ownership plan broadcasts. */
    public static void setPlanListener(java.util.function.Consumer<NoderaLanePlanPayload> listener) {
        planListener = listener;
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(NoderaSessionPayload.TYPE, NoderaSessionPayload.STREAM_CODEC,
                ModNetworking::handleSessionOnClient);
        // The no-host ownership pair: joiners announce their peer node; the session broadcasts the
        // deterministic plan inputs every member derives the identical region plan from.
        registrar.playToServer(NoderaNodeAnnouncePayload.TYPE,
                NoderaNodeAnnouncePayload.STREAM_CODEC, ModNetworking::handleNodeAnnounce);
        registrar.playToClient(NoderaLanePlanPayload.TYPE,
                NoderaLanePlanPayload.STREAM_CODEC, ModNetworking::handleLanePlan);
    }

    /** Server-side: a player announced its peer node — record it and re-plan region ownership. */
    private static void handleNodeAnnounce(NoderaNodeAnnouncePayload payload,
                                           IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            try {
                PlayerNodeRegistry.announce(player.getUUID(), new PlayerNodeRegistry.PlayerNode(
                        new dev.nodera.core.identity.NodeId(
                                java.util.UUID.fromString(payload.nodeIdUuid())),
                        dev.nodera.core.Bytes.unsafeWrap(
                                java.util.Base64.getDecoder().decode(payload.publicKeyB64())),
                        payload.route()));
            } catch (RuntimeException malformed) {
                return; // a malformed announce simply never becomes an owner
            }
            NoderaHost.replanEntityLane(player.serverLevel().getServer());
        });
    }

    /** Client-side: the session broadcast new plan inputs — hand them to the client lane. */
    private static void handleLanePlan(NoderaLanePlanPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var listener = planListener;
            if (listener != null) {
                listener.accept(payload);
            }
        });
    }

    /**
     * Client-side receipt of the server's P2P bootstrap route: join the peer mesh. Runs on the
     * netty thread; the actual runtime start is trivial and thread-safe, but we still hop to the
     * main thread for lifecycle cleanliness.
     */
    private static void handleSessionOnClient(NoderaSessionPayload payload, IPayloadContext context) {
        String advertiseHost = NoderaConfig.CLIENT_P2P_ADVERTISE_HOST.get();
        context.enqueueWork(() -> {
            // The hosting JVM is already in the mesh as the server peer — starting a second,
            // in-JVM client peer that dials itself only produces heartbeat-timeout churn and a
            // phantom third node in the ownership plan.
            if (NoderaPeerService.get().isHosting()) {
                return;
            }
            NoderaPeerService.get().onServerSessionInfo(payload.bootstrapRoute(), advertiseHost);
            var listener = sessionWorldListener;
            if (listener != null && !payload.worldIdHex().isBlank()) {
                listener.accept(payload.worldIdHex(), payload.worldName());
            }
            // No-host ownership: announce this client's peer node so the session's region plan
            // makes this PLAYER an owner of its own field-of-view regions.
            var identity = NoderaPeerService.get().clientIdentity();
            var runtime = NoderaPeerService.get().clientRuntime();
            if (identity != null && runtime != null && runtime.selfRoute() != null) {
                context.reply(new NoderaNodeAnnouncePayload(
                        identity.nodeId().value().toString(),
                        java.util.Base64.getEncoder().encodeToString(
                                identity.publicKeyBytes().toArray()),
                        runtime.selfRoute()));
            }
        });
    }
}
