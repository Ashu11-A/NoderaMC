package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.source.DiagnosticsSource;
import dev.nodera.diagnostics.source.SnapshotBuilder;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.GatewayClaim;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The Minecraft-free P2P session runtime — membership, gossip, heartbeat failure detection, and
 * deterministic gateway migration (Plan §4 {@code peer-runtime}; Phase 6 continuity beta).
 *
 * <p>A {@code PeerRuntime} makes two players stay connected to each other even after the bootstrap
 * peer they joined through goes offline:
 *
 * <ul>
 *   <li>The bootstrap runtime ({@link #bootstrap}) listens and, on each {@link PeerJoin}, admits
 *       the peer, replies with the full {@link MembershipUpdate}, and gossips the new member.</li>
 *   <li>A player runtime ({@link #peer}) dials the bootstrap, announces itself, learns the member
 *       set, and forms a <b>direct link to every other player</b> (single-dialer policy: the
 *       numerically-smaller {@link NodeId} initiates, so each pair gets exactly one link).</li>
 *   <li>When the gateway (initially the bootstrap) is lost — detected either instantly by the
 *       transport's {@link MessageHandler#onPeerDown} when its socket closes, or by the heartbeat
 *       failure timeout — every survivor runs the deterministic {@link GatewayElection} for the
 *       next epoch and converges on the same successor. The player↔player links are held by the
 *       transport and are unaffected, so play continues.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * All session state is confined to a single-thread "state executor". Transport callbacks (network
 * threads) and the heartbeat timer both hand work to it, so mutations never race. Listener
 * callbacks fire on that same thread. {@link #sessionView()} and the id/role accessors read a
 * {@code volatile} snapshot and are safe from any thread.
 */
public final class PeerRuntime implements DiagnosticsSource {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaPeerRuntime");

    private final NodeIdentity identity;
    private final NodeId selfId;
    private final NodeCapabilities capabilities;
    private final boolean bootstrapCapable;
    private final PeerTransport transport;
    private final Supplier<String> selfRouteSupplier;
    // Not final: a bootstrap-capable runtime (a headless worker) starts as its own session of one
    // and may later be told to dial into a world's live session via joinSession(). Volatile because
    // joinSession() may be called from any thread while the state thread reads it in the heartbeat.
    private volatile PeerAddress bootstrapAddress; // null iff this runtime has no session to dial
    private final PeerRuntimeConfig config;
    private final PeerEventListener listener;
    private final MessageCounters messageCounters; // nullable; if null, per-type counting is off
    private final TickSync tickSync; // nullable; if null, heartbeats carry empty regional progress

    private final java.util.concurrent.ExecutorService stateExec;
    private final ScheduledExecutorService heartbeatExec;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // ---- state confined to stateExec ----
    private final Map<NodeId, PeerEntry> members = new HashMap<>();
    private final Map<NodeId, Long> lastSeenNanos = new HashMap<>();
    private final Map<NodeId, Long> keepAliveSeq = new HashMap<>();
    private final Set<NodeId> heard = new HashSet<>();
    private long epoch;
    private NodeId gatewayId;
    private long keepAliveSeqCounter;
    private String selfRoute = "";
    /**
     * What each peer agreed to, by the handshake (R2/R3, network L-87/L-88).
     *
     * <p>Written on the state thread and read from any thread ({@link #sessionOf} is asked by the
     * lanes that hand out committee seats), hence concurrent rather than plain.
     *
     * <p>Bounded by the member set: an entry is dropped when the peer leaves or is evicted, so a
     * stranger that says hello and disappears cannot accumulate. A map keyed by remote input and
     * pruned by nothing is the shape of cache this category treats as a security bug.
     */
    private final Map<NodeId, dev.nodera.protocol.session.PeerSession> sessions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile SessionView currentView = new SessionView(0L, null, List.of());
    private volatile List<PeerLink> peerLinks = List.of();

    private PeerRuntime(Builder b) {
        this.identity = b.identity;
        this.selfId = b.identity.nodeId();
        this.capabilities = b.capabilities;
        this.bootstrapCapable = b.bootstrapCapable;
        this.transport = b.transport;
        this.selfRouteSupplier = b.selfRouteSupplier;
        this.bootstrapAddress = b.bootstrapAddress;
        this.config = b.config;
        this.listener = b.listener != null ? b.listener : new PeerEventListener() {};
        this.messageCounters = b.messageCounters;
        this.tickSync = b.tickSync;
        if (tickSync != null && !selfId.equals(tickSync.nodeId())) {
            throw new IllegalArgumentException("tickSync nodeId must match runtime identity");
        }
        this.stateExec = Executors.newSingleThreadExecutor(named("nodera-peer-state-" + shortId()));
        this.heartbeatExec = Executors.newSingleThreadScheduledExecutor(
                named("nodera-peer-hb-" + shortId()));
    }

    // ---- construction --------------------------------------------------------------------

    /**
     * Start a bootstrap runtime: the coordinator/entry point that other peers join. It becomes the
     * initial gateway at epoch 0.
     *
     * @param identity          this node's identity.
     * @param capabilities      this node's capability profile.
     * @param transport         the transport to run over (already constructed, not yet started).
     * @param selfRouteSupplier supplies this node's dialable route, valid after the transport
     *                          starts (e.g. {@code socketTransport::listenRoute}).
     * @param config            timing config.
     * @param listener          lifecycle observer (may be {@code null}).
     * @return a started bootstrap runtime.
     */
    public static PeerRuntime bootstrap(NodeIdentity identity, NodeCapabilities capabilities,
                                        PeerTransport transport, Supplier<String> selfRouteSupplier,
                                        PeerRuntimeConfig config, PeerEventListener listener) {
        return bootstrap(identity, capabilities, transport, selfRouteSupplier, config, listener, null);
    }

    /**
     * Bootstrap factory with per-type message counters enabled (Task 18). The runtime records TX/RX
     * counts keyed by {@code MessageCodec} type name on every encoded send and decoded inbound
     * message; the diagnostics collector reads them via {@link #messageCounters()}.
     */
    public static PeerRuntime bootstrap(NodeIdentity identity, NodeCapabilities capabilities,
                                        PeerTransport transport, Supplier<String> selfRouteSupplier,
                                        PeerRuntimeConfig config, PeerEventListener listener,
                                        MessageCounters messageCounters) {
        return bootstrap(identity, capabilities, transport, selfRouteSupplier, config, listener,
                messageCounters, null);
    }

    /**
     * Bootstrap factory with optional Task 18 counters and Task 25 regional tick synchronization.
     * Existing factories delegate here with a null synchronizer, preserving empty progress behavior.
     */
    public static PeerRuntime bootstrap(NodeIdentity identity, NodeCapabilities capabilities,
                                        PeerTransport transport, Supplier<String> selfRouteSupplier,
                                        PeerRuntimeConfig config, PeerEventListener listener,
                                        MessageCounters messageCounters, TickSync tickSync) {
        PeerRuntime rt = new Builder(identity, capabilities, transport, selfRouteSupplier, config)
                .bootstrapCapable(true)
                .listener(listener)
                .messageCounters(messageCounters)
                .tickSync(tickSync)
                .build();
        rt.start();
        return rt;
    }

    /**
     * Start a player-peer runtime that joins an existing session through {@code bootstrapAddress}.
     *
     * @param identity          this node's identity.
     * @param capabilities      this node's capability profile.
     * @param transport         the transport to run over (already constructed, not yet started).
     * @param selfRouteSupplier supplies this node's dialable route, valid after the transport
     *                          starts.
     * @param bootstrapAddress  the address of the bootstrap peer to join through.
     * @param config            timing config.
     * @param listener          lifecycle observer (may be {@code null}).
     * @return a started player runtime that has sent its {@link PeerJoin}.
     */
    public static PeerRuntime peer(NodeIdentity identity, NodeCapabilities capabilities,
                                   PeerTransport transport, Supplier<String> selfRouteSupplier,
                                   PeerAddress bootstrapAddress, PeerRuntimeConfig config,
                                   PeerEventListener listener) {
        return peer(identity, capabilities, transport, selfRouteSupplier, bootstrapAddress, config,
                listener, null);
    }

    /** Player-peer factory with per-type message counters enabled (Task 18) — see {@link #bootstrap}. */
    public static PeerRuntime peer(NodeIdentity identity, NodeCapabilities capabilities,
                                   PeerTransport transport, Supplier<String> selfRouteSupplier,
                                   PeerAddress bootstrapAddress, PeerRuntimeConfig config,
                                   PeerEventListener listener, MessageCounters messageCounters) {
        return peer(identity, capabilities, transport, selfRouteSupplier, bootstrapAddress, config,
                listener, messageCounters, null);
    }

    /** Player-peer factory with optional counters and regional tick synchronization. */
    public static PeerRuntime peer(NodeIdentity identity, NodeCapabilities capabilities,
                                   PeerTransport transport, Supplier<String> selfRouteSupplier,
                                   PeerAddress bootstrapAddress, PeerRuntimeConfig config,
                                   PeerEventListener listener, MessageCounters messageCounters,
                                   TickSync tickSync) {
        PeerRuntime rt = new Builder(identity, capabilities, transport, selfRouteSupplier, config)
                .bootstrapCapable(false)
                .bootstrapAddress(Objects.requireNonNull(bootstrapAddress, "bootstrapAddress"))
                .listener(listener)
                .messageCounters(messageCounters)
                .tickSync(tickSync)
                .build();
        rt.start();
        return rt;
    }

    private void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        transport.setHandler(new Handler());
        transport.start();
        this.selfRoute = Objects.requireNonNullElse(selfRouteSupplier.get(), "");
        stateExec.execute(this::onStarted);
        long periodMs = config.keepAliveInterval().toMillis();
        heartbeatExec.scheduleAtFixedRate(
                () -> stateExec.execute(this::onHeartbeatTick),
                periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void onStarted() {
        PeerEntry self = selfEntry();
        members.put(selfId, self);
        lastSeenNanos.put(selfId, System.nanoTime());
        if (bootstrapCapable && bootstrapAddress == null) {
            // Bootstrap: I am the initial gateway.
            epoch = 0L;
            gatewayId = selfId;
            publishView();
            listener.onGatewayChanged(null, selfId, 0L);
        } else {
            // Player: announce myself to the bootstrap; the reply seeds my view.
            publishView();
            announceSelf(bootstrapAddress);
        }
    }

    // ---- public API ----------------------------------------------------------------------

    /**
     * Dial into an already-running session through {@code remote}, after this runtime has started.
     *
     * <p>This is what turns an always-on headless peer from a bystander into a <b>member</b>. A
     * worker boots as its own bootstrap (a session of one) because it has no world to join yet;
     * when a world starts hosting, the mod hands the worker the server's P2P route and the worker
     * calls this. It announces itself with {@link #selfJoin()}; the reply is an ordinary
     * {@code MembershipUpdate}, so the existing ingest path seeds the member set and adopts the
     * remote gateway ({@code u.epoch() >= epoch} holds — both sides start at epoch 0 and the
     * incoming view wins ties, so the worker yields its self-elected gateway claim).
     *
     * <p>Idempotent and re-targetable: calling it again with the same address re-announces (the
     * heartbeat already re-announces while the view has not seeded, so a dropped first packet is
     * recovered); calling it with a different address re-points the runtime at a new session.
     * {@code null} detaches, leaving the runtime a session of one again.
     *
     * <p>The runtime stays {@code bootstrapCapable}: a worker that outlives the hosting game is
     * exactly who <i>should</i> win the next gateway election and keep the world's session alive.
     *
     * @param remote the session peer to announce to (typically the hosting game's server node).
     * @Thread-context any thread.
     */
    public void joinSession(PeerAddress remote) {
        this.bootstrapAddress = remote;
        if (remote == null || stopped.get()) {
            return;
        }
        stateExec.execute(() -> announceSelf(remote));
    }

    /**
     * Announce this node to one discovered peer <b>without</b> re-pointing the session.
     *
     * <p>{@link #joinSession} answers "which peer is my way into the session"; this answers "here
     * is another member I found — introduce myself". It is what {@code PeerDiscoveryService} calls
     * for every peer a tracker or rendezvous service reports, so membership is no longer limited to
     * whatever the single bootstrap route happened to gossip. The reply is an ordinary
     * {@code MembershipUpdate} and lands on the existing ingest path, so a peer learned this way is
     * indistinguishable from one learned through the bootstrap.
     *
     * <p>Announcing to a peer already in the view is harmless (the ingest is idempotent); announcing
     * to self is ignored. Failures are swallowed — an unreachable discovered peer is the normal
     * case, not an error.
     *
     * @param remote the peer to introduce this node to; {@code null} is ignored.
     * @Thread-context any thread.
     */
    public void announceTo(PeerAddress remote) {
        if (remote == null || stopped.get() || selfId.equals(remote.nodeId())) {
            return;
        }
        if (remote.route() != null && remote.route().equals(selfRoute)) {
            return; // our own advertised route; dialing it would mesh us with ourselves
        }
        stateExec.execute(() -> announceSelf(remote));
    }

    /** @return the session peer this runtime dials, or {@code null} if it is a session of one. */
    public PeerAddress sessionAddress() {
        return bootstrapAddress;
    }

    /** @return this runtime's node id. */
    public NodeId nodeId() {
        return selfId;
    }

    /** @return the latest published session view (safe from any thread). */
    public SessionView sessionView() {
        return currentView;
    }

    /** @return the current gateway as of the latest view, or {@code null} if not yet known. */
    public NodeId gatewayId() {
        return currentView.gatewayId();
    }

    /** @return {@code true} if this runtime currently believes it is the session gateway. */
    public boolean isGateway() {
        return selfId.equals(currentView.gatewayId());
    }

    /** @return the transport route this runtime advertised (valid after start). */
    public String selfRoute() {
        return selfRoute;
    }

    /** @return {@code true} if this runtime is the bootstrap/full-archival peer. */
    public boolean isBootstrap() {
        return bootstrapCapable;
    }

    /** @return the per-type message counters (null if counting was not enabled). */
    public MessageCounters messageCounters() {
        return messageCounters;
    }

    /** @return the regional tick synchronizer, or {@code null} when not wired. */
    public TickSync tickSync() {
        return tickSync;
    }

    /**
     * Feed one locally verified regional certificate into the optional Task 25 synchronizer.
     * Runtime factories without a synchronizer remain source- and behavior-compatible.
     *
     * @return {@code true} if the certified assignment/progress snapshot advanced.
     */
    public boolean onCertifiedCommit(RegionCommittee assignment, long lastAppliedTick) {
        return tickSync != null && tickSync.onCertifiedCommit(assignment, lastAppliedTick);
    }

    /**
     * Feed a locally verified commit from elsewhere in the network into the lag reference without
     * claiming local application progress for that region.
     *
     * @return {@code true} if the certified network reference advanced.
     */
    public boolean onCertifiedNetworkReference(long committedTick) {
        return tickSync != null && tickSync.onCertifiedNetworkReference(committedTick);
    }

    /**
     * {@link DiagnosticsSource} contribution (Task 18): publish the session view + per-peer links.
     * Reads only {@code volatile} snapshots, so it is safe to call from the collector's sample
     * thread (the server tick thread) — not the runtime's state thread.
     */
    @Override
    public void contribute(SnapshotBuilder b) {
        SessionView v = currentView;
        String role = bootstrapCapable ? "bootstrap" : "peer";
        boolean selfGateway = selfId.equals(v.gatewayId());
        b.session(new SessionInfo(v.epoch(), v.gatewayId(), selfGateway, v.size(), role, peerLinks));
    }

    /**
     * Stop the runtime: best-effort broadcast a goodbye, tear down timers and the transport.
     * Idempotent. Safe from any thread; does not block on the state thread from within it.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            stateExec.execute(() -> broadcast(new PeerGoodbye(selfId, epoch, "goodbye")));
        } catch (RuntimeException ignored) {
            // executor may already be shutting down.
        }
        heartbeatExec.shutdownNow();
        stateExec.shutdown();
        try {
            if (!stateExec.awaitTermination(2, TimeUnit.SECONDS)) {
                stateExec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stateExec.shutdownNow();
        }
        transport.stop();
    }

    // ---- transport handler (network threads) ---------------------------------------------

    private final class Handler implements MessageHandler {
        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            final NoderaMessage msg;
            try {
                msg = WireCodec.decode(frame);
            } catch (RuntimeException e) {
                return; // drop malformed frame
            }
            if (messageCounters != null) {
                messageCounters.recordRx(MessageCodec.typeName(MessageCodec.typeTagOf(msg)));
            }
            submit(() -> dispatch(from, msg));
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
            submit(() -> handleTransportDown(peer));
        }
    }

    private void submit(Runnable r) {
        try {
            stateExec.execute(r);
        } catch (RuntimeException ignored) {
            // runtime stopping; drop.
        }
    }

    // ---- dispatch (state thread) ---------------------------------------------------------

    private void dispatch(PeerAddress from, NoderaMessage msg) {
        if (!authorised(from, msg)) {
            return;
        }
        if (msg instanceof dev.nodera.protocol.session.Hello h) { onHello(from, h);
        } else if (msg instanceof dev.nodera.protocol.session.HelloAck a) { onHelloAck(from, a);
        } else if (msg instanceof PeerJoin j) { onPeerJoin(j);
        } else if (msg instanceof MembershipUpdate u) { onMembershipUpdate(u);
        } else if (msg instanceof PeerGoodbye g) { onPeerGoodbye(g);
        } else if (msg instanceof GatewayClaim c) { onGatewayClaim(c);
        } else if (msg instanceof SessionKeepAlive k) { onKeepAlive(k);
        } else {
                // Not a membership message: hand it to the application lane (e.g. the
                // dev.nodera.peer.validation committee flow). Runs on the state thread like
                // every other dispatch, so application handlers see serialized delivery.
                ApplicationMessageHandler app = applicationHandler;
                if (app != null) {
                    app.onApplicationMessage(from, msg);
                }
            }
    }

    /**
     * The NDR2 authorisation table, applied once before any handler runs.
     *
     * <p>The table ({@code MessageTypes}) was built with the wire and then consulted by nothing: the
     * only non-test reference to {@code MessageType.permits} was inside {@code MessageRouter}, which
     * has no production caller either. So the property the table states — "a peer may only speak for
     * itself" — held in its own tests and nowhere on a running node, and every membership row was
     * accepted from any connected socket exactly as it was before the table existed. A peer could
     * therefore announce a {@code GatewayClaim} naming somebody else, or a {@code PeerGoodbye}
     * evicting them, and the mesh would act on it.
     *
     * <p>Only six kinds carry {@code TRANSPORT_SENDER_EQUALS}, and every one of them is a statement a
     * peer makes about <b>itself</b> — join, goodbye, gateway claim, keep-alive, content
     * availability, inventory advertisement. None is ever relayed on another peer's behalf, so
     * refusing a mismatch cannot drop legitimate traffic.
     *
     * <p>Two deliberate non-refusals. A carrier that does not authenticate yields a null peer id and
     * the check defers to the handler — that is the policy's own documented contract, not a gap
     * introduced here. And a kind with no table row is dispatched rather than dropped: an unknown
     * descriptor is this build's ignorance of the message, never evidence against its sender.
     *
     * @return whether the message may be acted on.
     */
    private boolean authorised(PeerAddress from, NoderaMessage msg) {
        NodeId authenticated = from == null ? null : from.nodeId();
        if (authenticated == null) {
            return true;
        }
        dev.nodera.protocol.wire.MessageType type;
        try {
            type = dev.nodera.protocol.wire.MessageTypes.of(msg);
        } catch (RuntimeException noDescriptor) {
            return true;
        }
        if (type.permits(msg, authenticated)) {
            return true;
        }
        LOG.warn("Refused a {} from {}: it names a different peer as its sender",
                type.kind().name(), authenticated.value());
        return false;
    }

    /**
     * Non-membership messages decoded off this runtime's transport (committee proposals, votes,
     * commit announcements, content transfer, …). Called on the runtime's state thread —
     * deliveries are serialized; do not block.
     */
    @FunctionalInterface
    public interface ApplicationMessageHandler {
        void onApplicationMessage(PeerAddress from, NoderaMessage message);
    }

    private volatile ApplicationMessageHandler applicationHandler;

    /**
     * Register the application-lane handler for non-membership messages. One handler; the last
     * registration wins. Pass {@code null} to detach.
     *
     * @Thread-context any thread.
     */
    public void onApplicationMessage(ApplicationMessageHandler handler) {
        this.applicationHandler = handler;
    }

    /**
     * Admission gate consulted before a joiner enters the membership view (L-49 / L-18: the
     * previously unconditional {@code onPeerJoin} was the register's concrete Sybil hole). The
     * default admits everyone (existing behavior for permissionless test meshes); a hosted world
     * installs {@code WorldPermissions::canJoin} so a {@code BANNED} peer is refused — never
     * entered, never replied to, never gossiped.
     */
    @FunctionalInterface
    public interface JoinAdmission {
        boolean admit(dev.nodera.core.identity.NodeId joiner);
    }

    private volatile JoinAdmission joinAdmission = joiner -> true;

    /** Install the membership admission gate; null resets to admit-all. */
    public void setJoinAdmission(JoinAdmission admission) {
        this.joinAdmission = admission == null ? joiner -> true : admission;
    }

    // ---- the handshake (R2/R3, network L-87 and L-88) -------------------------------------

    /**
     * What this build is, for deciding who it can co-validate with.
     *
     * <p>Volatile and replaceable because the runtime starts before the world it will serve is
     * known: a worker boots, elects itself gateway, and only later learns a rules version and a
     * registry fingerprint. The default is this build's own identity with an unbound rule set, so
     * two peers of the same build always agree and nothing regresses for a caller that never sets
     * one; a caller that <i>does</i> set it is the one that can detect real skew.
     */
    private volatile dev.nodera.protocol.session.Negotiation.LocalProfile localProfile;

    private final dev.nodera.core.crypto.SignatureService signatures =
            new dev.nodera.core.crypto.SignatureService();

    /**
     * Declare the rule set and registry this runtime validates under, so the handshake can answer a
     * skew instead of letting it be discovered mid-execution.
     *
     * @param profile this build's negotiation profile; {@code null} restores the default.
     * @Thread-context any thread.
     */
    public void setLocalProfile(dev.nodera.protocol.session.Negotiation.LocalProfile profile) {
        this.localProfile = profile == null ? defaultProfile() : profile;
    }

    private dev.nodera.protocol.session.Negotiation.LocalProfile profile() {
        dev.nodera.protocol.session.Negotiation.LocalProfile p = localProfile;
        if (p == null) {
            p = defaultProfile();
            localProfile = p;
        }
        return p;
    }

    private dev.nodera.protocol.session.Negotiation.LocalProfile defaultProfile() {
        return dev.nodera.protocol.session.Negotiation.LocalProfile.of(
                dev.nodera.core.NoderaConstants.PRODUCT_VERSION, 0, 0L,
                dev.nodera.protocol.service.ServiceRecord.DEFAULT_NETWORK, capabilities);
    }

    /**
     * What this peer negotiated with {@code peer}.
     *
     * <p>{@code conservative} for a peer that has not completed a handshake — the only safe
     * assumption about an unknown peer is the smallest one. Callers that must not regress on a lost
     * or unanswered {@code Hello} should ask {@link #isNegotiatedObserver(NodeId)} instead, which
     * distinguishes "answered, and the answer was observer" from "never answered".
     *
     * @param peer the peer to ask about.
     * @return the negotiated session profile.
     * @Thread-context any thread.
     */
    public dev.nodera.protocol.session.PeerSession sessionOf(NodeId peer) {
        dev.nodera.protocol.session.PeerSession s = sessions.get(peer);
        return s != null ? s : dev.nodera.protocol.session.PeerSession.conservative(peer);
    }

    /**
     * Whether the handshake positively answered "this peer cannot co-validate here".
     *
     * <p>The distinction from {@link #sessionOf} is deliberate and is what makes this safe to gate
     * on. A peer that never completed a handshake is <b>not</b> reported as an observer: an absent
     * answer is not a refusal, and treating it as one would turn a dropped frame into an
     * unvalidatable world. Only a received {@code HelloAck} naming {@code OBSERVER} — a rules or
     * registry mismatch, or an unshared consensus feature — answers {@code true} here.
     *
     * @param peer the peer to ask about.
     * @return {@code true} only if this peer is known to be barred from co-validation.
     * @Thread-context any thread.
     */
    public boolean isNegotiatedObserver(NodeId peer) {
        dev.nodera.protocol.session.PeerSession s = sessions.get(peer);
        return s != null && s.role() != dev.nodera.protocol.session.SessionRole.ADMITTED;
    }

    /** This peer's opening message, signed. */
    private dev.nodera.protocol.session.Hello hello() {
        return dev.nodera.protocol.session.Negotiation.hello(identity, profile());
    }

    /**
     * Introduce this node to {@code remote}: say hello, then ask to join.
     *
     * <p>Every announce goes through here so there is exactly one place where the handshake can be
     * forgotten, and it is not forgotten. The ordering is the useful part: both frames go out on the
     * same carrier and are dispatched in order on the receiver's state thread, so by the time the
     * {@code PeerJoin} is handled the answer to the {@code Hello} has already been decided.
     *
     * <p>It is deliberately <b>not</b> a request/response gate. The join is not withheld pending an
     * answer, because a peer that cannot agree a state root can still seed, relay and tunnel, and
     * because a lost {@code Hello} must not be able to cost a peer its membership. What the answer
     * governs is co-validation — see {@link #isNegotiatedObserver}.
     *
     * @Thread-context the state thread.
     */
    private void announceSelf(PeerAddress remote) {
        sendTo(remote, hello());
        sendTo(remote, selfJoin());
    }

    /**
     * Answer a peer's {@code Hello} and record what was agreed.
     *
     * <p>The identity in the body is checked against the identity the carrier authenticated, which
     * is the one thing production could never do before: a peer used to be able to name itself and
     * be believed. On an unauthenticated carrier {@code from.nodeId()} is null and the check is
     * skipped — stated, rather than assumed, by passing it through.
     */
    private void onHello(PeerAddress from, dev.nodera.protocol.session.Hello h) {
        dev.nodera.protocol.session.HelloAck ack = dev.nodera.protocol.session.Negotiation.respond(
                h, profile(), from.nodeId(), signatures);
        // Record our own view of the peer before answering: the answer IS the agreement, and a
        // refusal recorded only on the far end is an agreement one side does not know about.
        if (ack.role() == dev.nodera.protocol.session.SessionRole.REFUSED) {
            sessions.remove(h.nodeId());
        } else {
            sessions.put(h.nodeId(), dev.nodera.protocol.session.PeerSession.of(h.nodeId(), ack));
        }
        // Answered on the address the frame arrived on. The Hello carries no route — a peer does not
        // get to tell us where it lives any more than it gets to tell us who it is; the carrier
        // already knows both.
        sendTo(from, ack);
    }

    /**
     * Record the answer to our own {@code Hello}.
     *
     * <p>A refusal is a coded statement and is kept as one: the peer is left with no session, so
     * {@link #isNegotiatedObserver} reports it as unusable for co-validation and nothing here
     * pretends the handshake succeeded. It is not a reason to tear down membership — a peer we
     * cannot agree a root with can still seed, relay and tunnel, which is the whole point of the
     * observer role.
     */
    private void onHelloAck(PeerAddress from, dev.nodera.protocol.session.HelloAck a) {
        NodeId peer = from.nodeId();
        if (peer == null) {
            return; // an answer from nobody in particular cannot be attributed to a session
        }
        if (a.refused()) {
            sessions.remove(peer);
            return;
        }
        sessions.put(peer, dev.nodera.protocol.session.PeerSession.of(peer, a));
    }

    private void onPeerJoin(PeerJoin j) {
        if (!joinAdmission.admit(j.joiner())) {
            // Refused: no membership entry, no snapshot reply, no gossip — the banned peer
            // learns nothing about the mesh from this node.
            return;
        }
        // Carry the joiner's key into the membership entry: it is what every other member will
        // later verify this peer's committee work against. Dropping it here would admit the peer
        // to the session but leave it permanently ineligible for a seat.
        PeerEntry entry = new PeerEntry(j.joiner(), j.listenRoute(), j.capabilities(), j.bootstrap(),
                j.publicKey(), j.clientVersion());
        boolean isNew = !members.containsKey(j.joiner());
        members.put(j.joiner(), entry);
        markSeen(j.joiner());
        heard.add(j.joiner());
        if (isNew) {
            listener.onPeerJoined(j.joiner());
        }
        // Reply to the joiner with the full current view, then gossip the new member to everyone.
        sendTo(PeerAddress.of(j.joiner(), j.listenRoute()), snapshotUpdate());
        if (isNew) {
            broadcastExcept(j.joiner(), snapshotUpdate());
            publishView();
        }
    }

    private void onMembershipUpdate(MembershipUpdate u) {
        boolean changed = false;
        for (PeerEntry e : u.members()) {
            if (e.nodeId().equals(selfId)) {
                continue;
            }
            if (!joinAdmission.admit(e.nodeId())) {
                // A banned peer must not enter via gossip either: every membership ingest
                // point runs the same admission gate (L-49).
                continue;
            }
            if (!members.containsKey(e.nodeId())) {
                members.put(e.nodeId(), e);
                markSeen(e.nodeId());
                listener.onPeerJoined(e.nodeId());
                changed = true;
            } else {
                members.put(e.nodeId(), e); // refresh route/caps
            }
        }
        if (u.epoch() >= epoch && u.gatewayId() != null) {
            adoptGateway(u.gatewayId(), u.epoch());
            changed = true;
        }
        if (changed) {
            publishView();
        }
    }

    private void onPeerGoodbye(PeerGoodbye g) {
        if (g.who().equals(selfId)) {
            return;
        }
        boolean removed = removeMember(g.who(), g.reason());
        if (removed) {
            // propagate once so the whole mesh converges.
            broadcast(g);
        }
    }

    private void onGatewayClaim(GatewayClaim c) {
        if (c.epoch() >= epoch) {
            adoptGateway(c.gatewayId(), c.epoch());
            publishView();
        }
    }

    private void onKeepAlive(SessionKeepAlive k) {
        if (k.from().equals(selfId)) {
            return;
        }
        markSeen(k.from());
        heard.add(k.from());
        keepAliveSeq.merge(k.from(), k.seq(), Math::max);
        if (tickSync != null) {
            tickSync.onKeepAlive(k);
        }
        listener.onKeepAlive(k.from(), k.seq(), k.regionProgress());
    }

    // ---- failure detection & migration (state thread) ------------------------------------

    private void handleTransportDown(PeerAddress peer) {
        NodeId who = peer.nodeId();
        if (who == null) {
            who = memberByRoute(peer.route());
        }
        if (who == null || who.equals(selfId)) {
            return;
        }
        if (removeMember(who, "transport-down")) {
            broadcast(new PeerGoodbye(who, epoch, "transport-down"));
        }
    }

    private void onHeartbeatTick() {
        // Ordered by consequence, and each step isolated from the next. The keep-alive at the bottom
        // is the one thing this method exists to guarantee — a node that skips it is dropped by
        // every peer that can see it — so nothing above it may be able to prevent it from running.
        try {
            reannounceToBootstrap();
        } catch (RuntimeException ignored) {
            // A bootstrap that will not take a frame is the situation the keep-alive has to survive,
            // not a reason to skip it.
        }
        keepAliveSeqCounter++;
        List<RegionProgress> progress = tickSync == null
                ? List.of()
                : tickSync.localProgress();
        broadcast(new SessionKeepAlive(selfId, keepAliveSeqCounter, progress));
        pruneSilentMembers();
    }

    private void reannounceToBootstrap() {
        if (bootstrapAddress != null && members.size() == 1) {
            // The startup PeerJoin is a single message over a possibly-not-yet-listening socket
            // (sendTo swallows transport failures by design). If it was lost, this runtime would
            // never mesh — so re-announce every heartbeat until the membership view seeds.
            announceSelf(bootstrapAddress);
        }
        if (isGateway() && members.size() > 1) {
            // Anti-entropy: the join-time gossip is one message per event; a lost one leaves a
            // member with a permanently partial view (observed on CI: a player stuck at
            // {self, bootstrap} never learning the other player). The gateway re-broadcasts the
            // full membership snapshot every heartbeat — onMembershipUpdate ingest is idempotent,
            // so a converged mesh just refreshes routes.
            broadcast(snapshotUpdate());
        }
    }

    /** Prune members we have an established link with but have not heard from within the window. */
    private void pruneSilentMembers() {
        long now = System.nanoTime();
        long timeoutNanos = config.failureTimeout().toNanos();
        List<NodeId> lost = new ArrayList<>();
        for (Map.Entry<NodeId, Long> e : lastSeenNanos.entrySet()) {
            NodeId id = e.getKey();
            if (id.equals(selfId) || !heard.contains(id)) {
                continue;
            }
            if (now - e.getValue() > timeoutNanos) {
                lost.add(id);
            }
        }
        for (NodeId id : lost) {
            if (removeMember(id, "heartbeat-timeout")) {
                broadcast(new PeerGoodbye(id, epoch, "heartbeat-timeout"));
            }
        }
    }

    /**
     * Remove a member and, if it was the gateway, run the deterministic re-election. Returns
     * {@code true} if the member was actually present (so callers gossip the departure once).
     */
    private boolean removeMember(NodeId who, String reason) {
        PeerEntry removed = members.remove(who);
        if (removed == null) {
            return false;
        }
        lastSeenNanos.remove(who);
        heard.remove(who);
        // What was negotiated with a peer is only meaningful while the peer is a member; keeping it
        // is what would make `sessions` a map keyed by remote input that nothing prunes.
        sessions.remove(who);
        listener.onPeerLeft(who, reason);
        if (who.equals(gatewayId)) {
            reElect();
        }
        publishView();
        return true;
    }

    private void reElect() {
        long newEpoch = epoch + 1;
        NodeId elected = GatewayElection.elect(members.values(), newEpoch);
        NodeId previous = gatewayId;
        epoch = newEpoch;
        gatewayId = elected;
        listener.onGatewayChanged(previous, elected, newEpoch);
        if (elected.equals(selfId)) {
            // I won: assert it to the mesh and reseed membership at the new epoch.
            broadcast(new GatewayClaim(selfId, newEpoch));
            broadcast(snapshotUpdate());
        }
    }

    private void adoptGateway(NodeId newGateway, long newEpoch) {
        boolean changed = newEpoch > epoch || !Objects.equals(gatewayId, newGateway);
        NodeId previous = gatewayId;
        epoch = Math.max(epoch, newEpoch);
        gatewayId = newGateway;
        if (changed) {
            listener.onGatewayChanged(previous, newGateway, epoch);
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private void markSeen(NodeId id) {
        lastSeenNanos.put(id, System.nanoTime());
    }

    private NodeId memberByRoute(String route) {
        if (route == null) {
            return null;
        }
        for (PeerEntry e : members.values()) {
            if (route.equals(e.route())) {
                return e.nodeId();
            }
        }
        return null;
    }

    private PeerEntry selfEntry() {
        return new PeerEntry(selfId, selfRoute, capabilities, bootstrapCapable,
                identity.publicKeyBytes(), dev.nodera.core.NoderaConstants.CLIENT_AGENT);
    }

    /** The join announce this runtime sends to a session it is dialing into. */
    private PeerJoin selfJoin() {
        return new PeerJoin(selfId, selfRoute, capabilities, bootstrapCapable,
                identity.publicKeyBytes(), dev.nodera.core.NoderaConstants.CLIENT_AGENT);
    }

    private MembershipUpdate snapshotUpdate() {
        // Ensure the self entry carries the freshly-learned route.
        members.put(selfId, selfEntry());
        return new MembershipUpdate(epoch, gatewayId != null ? gatewayId : selfId,
                new ArrayList<>(members.values()));
    }

    private void publishView() {
        currentView = new SessionView(epoch, gatewayId, new ArrayList<>(members.values()));
        peerLinks = buildPeerLinks();
        listener.onSessionChanged(currentView);
    }

    /**
     * Build the per-peer link snapshot (Task 18) on the state thread, then publish it via the
     * {@code volatile} {@link #peerLinks} field so the diagnostics collector can read it safely
     * from the sample thread.
     */
    private List<PeerLink> buildPeerLinks() {
        long now = System.nanoTime();
        List<PeerLink> out = new ArrayList<>(members.size());
        for (PeerEntry e : members.values()) {
            NodeId id = e.nodeId();
            boolean self = id.equals(selfId);
            Long seen = lastSeenNanos.get(id);
            long agoMillis = self || seen == null ? -1L : Math.max(0L, (now - seen) / 1_000_000L);
            long kas = self ? keepAliveSeqCounter : keepAliveSeq.getOrDefault(id, 0L);
            boolean gateway = id.equals(gatewayId);
            String role = gateway ? "gateway" : (e.bootstrap() ? "bootstrap" : "peer");
            boolean up = self || heard.contains(id);
            out.add(new PeerLink(id, e.route(), e.bootstrap(), role, agoMillis, kas, up));
        }
        return List.copyOf(out);
    }

    /** Send to every member except self, subject to the single-dialer reachability rule. */
    private void broadcast(NoderaMessage msg) {
        broadcastExcept(null, msg);
    }

    private void broadcastExcept(NodeId skip, NoderaMessage msg) {
        for (PeerEntry m : new ArrayList<>(members.values())) {
            if (m.nodeId().equals(selfId) || m.nodeId().equals(skip)) {
                continue;
            }
            if (!canSend(m)) {
                continue;
            }
            sendTo(PeerAddress.of(m.nodeId(), m.route()), msg);
        }
    }

    /**
     * Single-dialer reachability: I initiate to the bootstrap (if I am a player) and to any player
     * with a larger id; otherwise I only send once the peer has reached me (I have heard from it).
     * This gives each pair exactly one underlying connection.
     */
    private boolean canSend(PeerEntry m) {
        if (heard.contains(m.nodeId())) {
            return true;
        }
        if (bootstrapCapable) {
            return false; // the bootstrap never initiates; peers dial it.
        }
        if (m.bootstrap()) {
            return true; // players dial the bootstrap.
        }
        return selfId.value().compareTo(m.nodeId().value()) < 0; // smaller id dials larger.
    }

    private void sendTo(PeerAddress to, NoderaMessage msg) {
        try {
            if (messageCounters != null) {
                messageCounters.recordTx(MessageCodec.typeName(MessageCodec.typeTagOf(msg)));
            }
            // R3 (network L-88): encode through the peer's negotiated profile, not straight through
            // WireCodec. This is the one place every membership send passes, which is why it is the
            // only place the profile has to be honoured — a tolerant reader paired with an
            // unconditional writer is what made compatibility one-directional, and the writer finally
            // knows who it is writing to. A peer with no negotiated session encodes exactly as before
            // (`conservative` demotes only where a feature is known to be unsupported, and an
            // unnegotiated peer of the same build reads everything this build emits).
            // The flags must stay what the one-argument WireCodec.encode would have used — these are
            // events, and re-labelling every membership frame as a request while "honouring the
            // profile" would be a wire change disguised as a compatibility fix.
            NodeId peer = to.nodeId();
            dev.nodera.protocol.session.PeerSession session = peer == null ? null : sessions.get(peer);
            transport.send(to, session == null
                    ? WireCodec.encode(msg)
                    : session.encodeFor(msg, dev.nodera.protocol.wire.FrameFlags.EVENT, 0L));
        } catch (TransportException e) {
            // Send failed (peer unreachable / down). Liveness is handled by onPeerDown / timeout.
        } catch (RuntimeException e) {
            // Also swallowed, and the distinction matters more than it looks. This method's contract
            // is "a send that fails is not the caller's problem" — but it honoured that only for the
            // checked type, so an unchecked failure from a transport escaped into whatever was
            // driving the send. Observed live: a joiner's bootstrap address has no node id, the
            // rendezvous transport threw NPE on it, and the exception unwound `onHeartbeatTick` at
            // its FIRST statement — so the `SessionKeepAlive` broadcast at the end of that method
            // never ran, on any tick. Every other peer then timed the node out while it sat there
            // believing it was connected.
        }
    }

    private String shortId() {
        return selfId.value().toString().substring(0, 8);
    }

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // ---- builder -------------------------------------------------------------------------

    private static final class Builder {
        private final NodeIdentity identity;
        private final NodeCapabilities capabilities;
        private final PeerTransport transport;
        private final Supplier<String> selfRouteSupplier;
        private final PeerRuntimeConfig config;
        private boolean bootstrapCapable;
        private PeerAddress bootstrapAddress;
        private PeerEventListener listener;
        private MessageCounters messageCounters;
        private TickSync tickSync;

        Builder(NodeIdentity identity, NodeCapabilities capabilities, PeerTransport transport,
                Supplier<String> selfRouteSupplier, PeerRuntimeConfig config) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            this.transport = Objects.requireNonNull(transport, "transport");
            this.selfRouteSupplier = Objects.requireNonNull(selfRouteSupplier, "selfRouteSupplier");
            this.config = Objects.requireNonNull(config, "config");
        }

        Builder bootstrapCapable(boolean v) {
            this.bootstrapCapable = v;
            return this;
        }

        Builder bootstrapAddress(PeerAddress a) {
            this.bootstrapAddress = a;
            return this;
        }

        Builder listener(PeerEventListener l) {
            this.listener = l;
            return this;
        }

        Builder messageCounters(MessageCounters c) {
            this.messageCounters = c;
            return this;
        }

        Builder tickSync(TickSync sync) {
            this.tickSync = sync;
            return this;
        }

        PeerRuntime build() {
            return new PeerRuntime(this);
        }
    }
}
