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

    /**
     * NeoForge payload registrar version. Bumped {@code "1"→"2"} for issue #36's challenge-response
     * announce (F1): the session/announce payloads gained fields, so mismatched mod builds must
     * fail the handshake loudly rather than silently mis-decode.
     */
    public static final String PROTOCOL_VERSION = "2";

    /**
     * Server-side per-login announce challenges (issue #36 F1). Issued in
     * {@code ServerBootstrap.onPlayerLoggedIn}, consumed here before an announce is trusted.
     */
    private static final AnnounceChallenges ANNOUNCE_CHALLENGES = new AnnounceChallenges();

    /** @return the host's announce-challenge store (server side). */
    public static AnnounceChallenges announceChallenges() {
        return ANNOUNCE_CHALLENGES;
    }

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
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("NoderaAnnounce");
            try {
                dev.nodera.core.Bytes publicKey = dev.nodera.core.Bytes.unsafeWrap(
                        java.util.Base64.getDecoder().decode(payload.publicKeyB64()));
                // F1: prove possession of the announced key over the server's single-use challenge
                // BEFORE trusting the node. No valid, unexpired challenge or a bad signature drops
                // the announce — a spoofed NodeId+key never becomes an owner.
                java.util.Optional<dev.nodera.core.Bytes> challenge =
                        ANNOUNCE_CHALLENGES.consume(player.getUUID(), System.currentTimeMillis());
                if (challenge.isEmpty()) {
                    log.warn("Nodera: announce from {} dropped — no valid challenge", player.getUUID());
                    return;
                }
                dev.nodera.core.Bytes signature = payload.signatureB64().isBlank()
                        ? dev.nodera.core.Bytes.empty()
                        : dev.nodera.core.Bytes.unsafeWrap(
                                java.util.Base64.getDecoder().decode(payload.signatureB64()));
                if (!NodeAnnounceProof.verify(publicKey, signature, challenge.get(),
                        payload.nodeIdUuid(), player.getUUID().toString())) {
                    log.warn("Nodera: announce from {} dropped — announce proof failed",
                            player.getUUID());
                    return;
                }
                PlayerNodeRegistry.announce(player.getUUID(), new PlayerNodeRegistry.PlayerNode(
                        new dev.nodera.core.identity.NodeId(
                                java.util.UUID.fromString(payload.nodeIdUuid())),
                        publicKey, payload.route()));
            } catch (RuntimeException malformed) {
                return; // a malformed announce simply never becomes an owner
            }
            // F4: reconcile this now-verified player's vanilla op status with their key-checked role
            // (op operators/owner, disconnect BANNED). The announce proof authenticated the key.
            dev.nodera.mod.server.OperatorBridge.get()
                    .syncPlayer(player.serverLevel().getServer(), player);
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
            // The world id is the rendezvous namespace: pass it through so a joiner behind a NAT
            // that cannot reach the host's socket can still register, discover, and fall back to
            // the relay — the host has always done this; the joiner used to be socket-only, which
            // made the fallback one-sided and therefore useless.
            NoderaPeerService.get().onServerSessionInfo(payload.bootstrapRoute(), advertiseHost,
                    payload.worldIdHex());
            var listener = sessionWorldListener;
            if (listener != null && !payload.worldIdHex().isBlank()) {
                listener.accept(payload.worldIdHex(), payload.worldName());
            }
            // No-host ownership: announce this client's peer node so the session's region plan
            // makes this PLAYER an owner of its own field-of-view regions.
            var identity = NoderaPeerService.get().clientIdentity();
            var runtime = NoderaPeerService.get().clientRuntime();
            if (identity != null && runtime != null && runtime.selfRoute() != null
                    && context.player() != null && !payload.challengeB64().isBlank()) {
                // F1: prove key possession over the server's challenge, bound to this MC UUID.
                dev.nodera.core.Bytes challenge = dev.nodera.core.Bytes.unsafeWrap(
                        java.util.Base64.getDecoder().decode(payload.challengeB64()));
                dev.nodera.core.Bytes proof = NodeAnnounceProof.sign(identity, challenge,
                        context.player().getUUID().toString());
                context.reply(new NoderaNodeAnnouncePayload(
                        identity.nodeId().value().toString(),
                        java.util.Base64.getEncoder().encodeToString(
                                identity.publicKeyBytes().toArray()),
                        runtime.selfRoute(),
                        java.util.Base64.getEncoder().encodeToString(proof.toArray())));
            }
        });
    }
}
