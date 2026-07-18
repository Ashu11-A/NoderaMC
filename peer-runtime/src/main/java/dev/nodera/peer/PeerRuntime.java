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

    private final NodeIdentity identity;
    private final NodeId selfId;
    private final NodeCapabilities capabilities;
    private final boolean bootstrapCapable;
    private final PeerTransport transport;
    private final Supplier<String> selfRouteSupplier;
    private final PeerAddress bootstrapAddress; // null iff this runtime is the bootstrap
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
            sendTo(bootstrapAddress, new PeerJoin(selfId, selfRoute, capabilities, false));
        }
    }

    // ---- public API ----------------------------------------------------------------------

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
                msg = MessageCodec.decode(frame);
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
        switch (msg) {
            case PeerJoin j -> onPeerJoin(j);
            case MembershipUpdate u -> onMembershipUpdate(u);
            case PeerGoodbye g -> onPeerGoodbye(g);
            case GatewayClaim c -> onGatewayClaim(c);
            case SessionKeepAlive k -> onKeepAlive(k);
            default -> { /* not a membership message; ignored by this runtime */ }
        }
    }

    private void onPeerJoin(PeerJoin j) {
        PeerEntry entry = new PeerEntry(j.joiner(), j.listenRoute(), j.capabilities(), j.bootstrap());
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
        keepAliveSeqCounter++;
        List<RegionProgress> progress = tickSync == null
                ? List.of()
                : tickSync.localProgress();
        broadcast(new SessionKeepAlive(selfId, keepAliveSeqCounter, progress));
        // Prune members we have an established link with but have not heard from within the window.
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
        return new PeerEntry(selfId, selfRoute, capabilities, bootstrapCapable);
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
            transport.send(to, MessageCodec.encode(msg));
        } catch (TransportException e) {
            // Send failed (peer unreachable / down). Liveness is handled by onPeerDown / timeout.
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
