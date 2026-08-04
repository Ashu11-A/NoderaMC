package dev.nodera.mod.common;

import dev.nodera.endpoint.share.JoinChallenge;
import dev.nodera.endpoint.lane.LanePlan;
import dev.nodera.endpoint.share.AnnounceChallenges;
import dev.nodera.endpoint.share.HostJoinGate;
import dev.nodera.endpoint.share.JoinerIdentity;
import dev.nodera.endpoint.share.NodeAnnounceProof;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
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
        modBus.addListener(ModNetworking::onRegisterConfigurationTasks);
    }

    /**
     * Client-dist hook for answering a live-join password challenge (L-52): the challenge in, the
     * MAC out, or empty when this client has no password for that world. Volatile: written on the
     * mod-loading thread, read on the proof-derivation thread.
     */
    private static volatile java.util.function.Function<
            JoinChallenge, java.util.Optional<dev.nodera.core.Bytes>> joinProofProvider;

    /** Register the client-side answerer for live-join password challenges. */
    public static void setJoinProofProvider(java.util.function.Function<
            JoinChallenge, java.util.Optional<dev.nodera.core.Bytes>> provider) {
        joinProofProvider = provider;
    }

    /**
     * Server-side: add the password step to this connection's configuration, but only while a
     * password-protected world is actually hosted. A plaintext world registers nothing, so its
     * joins are unchanged.
     */
    private static void onRegisterConfigurationTasks(
            net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent event) {
        if (HostJoinGate.get().isArmed()) {
            event.register(new JoinPasswordTask(event.getListener()));
        }
    }

    /** Client-dist hook for the region-ownership plan payload (set by {@code ClientBootstrap}). */
    private static volatile java.util.function.Consumer<LanePlan> planListener;

    /** Register the client-side listener for region-ownership plan broadcasts. */
    public static void setPlanListener(java.util.function.Consumer<LanePlan> listener) {
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
        // L-52, the live-join password gate. Configuration phase, not play: a joiner who cannot
        // answer must never be created as a player in the world.
        registrar.configurationToClient(NoderaJoinChallengePayload.TYPE,
                NoderaJoinChallengePayload.STREAM_CODEC, ModNetworking::handleJoinChallenge);
        registrar.configurationToServer(NoderaJoinProofPayload.TYPE,
                NoderaJoinProofPayload.STREAM_CODEC, ModNetworking::handleJoinProof);
    }

    /**
     * Client-side: answer the host's password challenge. The derivation is a memory-hard KDF, so it
     * runs on its own short-lived thread — blocking the client's main thread (or the netty thread)
     * for the length of an Argon2 pass during a join is exactly the kind of stall players read as
     * a hang.
     */
    private static void handleJoinChallenge(NoderaJoinChallengePayload payload,
                                            IPayloadContext context) {
        var provider = joinProofProvider;
        Thread.ofPlatform().name("nodera-join-proof").daemon().start(() -> {
            String proofB64 = "";
            try {
                var proof = provider == null ? java.util.Optional.<dev.nodera.core.Bytes>empty()
                        : provider.apply(payload.challenge());
                if (proof.isPresent()) {
                    proofB64 = java.util.Base64.getEncoder()
                            .encodeToString(proof.get().toArray());
                }
            } catch (RuntimeException malformed) {
                // A malformed challenge (or a KDF this build cannot do) is answered with "no
                // proof" rather than a dropped connection: the host then refuses us with a
                // message the player can read instead of a silent timeout.
                org.slf4j.LoggerFactory.getLogger("NoderaJoin")
                        .warn("Nodera: join challenge could not be answered: {}",
                                malformed.toString());
            }
            context.reply(new NoderaJoinProofPayload(proofB64));
        });
    }

    /**
     * Server-side: judge the answer. A pass finishes the configuration step and the join continues
     * normally; anything else ends the connection with a stated reason — never a silent hang and
     * never a player in the world.
     */
    private static void handleJoinProof(NoderaJoinProofPayload payload, IPayloadContext context) {
        dev.nodera.core.Bytes answer;
        try {
            answer = payload.proofB64().isBlank() ? dev.nodera.core.Bytes.empty()
                    : dev.nodera.core.Bytes.unsafeWrap(
                            java.util.Base64.getDecoder().decode(payload.proofB64()));
        } catch (IllegalArgumentException notBase64) {
            answer = dev.nodera.core.Bytes.empty();
        }
        HostJoinGate.Verdict verdict = HostJoinGate.get().verify(
                context.connection(),
                JoinerIdentity.of(context.connection().getRemoteAddress()),
                answer,
                System.currentTimeMillis());
        if (verdict == HostJoinGate.Verdict.PASS || verdict == HostJoinGate.Verdict.NOT_GATED) {
            context.finishCurrentTask(JoinPasswordTask.TYPE);
            return;
        }
        org.slf4j.LoggerFactory.getLogger("NoderaJoin")
                .info("Nodera: refused a joiner at the password gate ({})", verdict);
        context.disconnect(verdict == HostJoinGate.Verdict.THROTTLED
                ? JoinPasswordTask.THROTTLED : JoinPasswordTask.REFUSED);
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
                dev.nodera.core.identity.NodeId sessionNode = new dev.nodera.core.identity.NodeId(
                        java.util.UUID.fromString(payload.nodeIdUuid()));
                // The delegation, if one verifies, is what makes this player's PERSISTENT identity
                // visible to the permission set. It is checked against the key just proven and the
                // world actually being played, so a delegation lifted from another announce or
                // minted for another world is not a delegation at all — and a session without one
                // simply speaks for itself, which is the behaviour that predates this.
                PlayerNodeRegistry.PlayerNode announced =
                        dev.nodera.endpoint.world.SessionAuthority.resolve(
                                sessionNode, publicKey, payload.route(), payload.delegationB64(),
                                NoderaHost.hostedWorldId(), System.currentTimeMillis(),
                                reason -> log.info("Nodera: {}", reason));
                PlayerNodeRegistry.announce(player.getUUID(), announced);
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
            // Bind this player's own always-on worker into the session it is playing in (L-30).
            // This is the first message that carries the world seed, which the worker's validation
            // lane must be bound to before it can hold a seat — and it happens ahead of the lane
            // listener, and outside the deterministic-validation switch that listener honours,
            // because a worker's membership is worth having even where this build's client lane is
            // off: it is what keeps the world alive when this game exits.
            LanePlan plan = payload.plan();
            NoderaPeerService.get().bindCompanionToSession(plan.worldSeed());
            var listener = planListener;
            if (listener != null) {
                listener.accept(plan);
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
                // The session key proves who is talking; the delegation says whose authority it
                // carries. Without the second one this player is a stranger to the world's
                // permission set no matter how well the first one verifies — which is how a
                // world's own author ended up being de-opped on joining it.
                context.reply(new NoderaNodeAnnouncePayload(
                        identity.nodeId().value().toString(),
                        java.util.Base64.getEncoder().encodeToString(
                                identity.publicKeyBytes().toArray()),
                        runtime.selfRoute(),
                        java.util.Base64.getEncoder().encodeToString(proof.toArray()),
                        NoderaPeerService.get().sessionDelegationB64()));
            }
        });
    }
}
