package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.rendezvous.RelayIncoming;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.SignedRecord;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The third {@link PeerTransport} (Task 29): direct-first, relay-fallback, composed over the
 * standalone {@code nodera-rendezvous} service. Replaces the never-built {@code transport-libp2p}
 * (LEGACY.md); behind the transport-api seam, so peer-runtime / committee / distribution cannot tell
 * which transport carried a message.
 *
 * <p><b>Composition (RENDEZVOUS.md §4.7).</b> A {@link TransportSelector} chooses per peer:
 * a {@link #directTransport} (e.g. {@code SocketPeerTransport}) when a direct path exists, else an
 * end-to-end-encrypted {@link RelayCircuit} through the service. Registration and discovery run over
 * {@link RendezvousClient}; the relayed data path is a first-class fallback, not an apology — the
 * committee and gateway traffic must hold on a pure-relay path.
 *
 * <p><b>The service is never authority.</b> Records are Ed25519-signed and verified against the same
 * canonical bytes on both sides; relayed payloads are E2E-encrypted; the transport handshake
 * re-verifies the far end owns the {@code NodeId} of the discovered record (§4.4/§8.1). A discovered
 * peer must be {@link #learn learned} before it can be dialed, so its identity key is known before
 * any circuit trusts it.
 *
 * <p>Thread-context: {@link #start()}/{@link #stop()} from any one thread; {@link #send} is
 * thread-safe; the handler is invoked on per-circuit reader threads.
 */
public final class RendezvousPeerTransport implements PeerTransport {

    private final NodeIdentity identity;

    /**
     * The rendezvous points in use, best first. <b>Volatile and replaceable</b>: the set is now
     * discovered from trackers and re-scored on a cadence, so losing one relay or gaining a better one
     * must reach the accept and refresh loops without a restart.
     */
    private volatile List<RendezvousEndpoint> endpoints;
    private final UUID networkId;
    private final Bytes genesisHash;
    private final NodeCapabilities capabilities;
    private final RendezvousClient client;
    private final TransportSelector selector = new TransportSelector();
    private final PeerTransport directTransport; // nullable — relay-only when absent

    private final ConcurrentHashMap<NodeId, Bytes> knownKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, List<PeerCandidate>> knownCandidates =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, RelayCircuit> circuits = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<java.util.function.Consumer<
            dev.nodera.protocol.service.ServiceDrainNotice>> drainHandlers =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();

    /** Priority of this peer's own direct (host) candidate — dialed before the relay. */
    private static final int DIRECT_CANDIDATE_PRIORITY = 100;
    /** Priority of the relay candidate: usable, but only after every direct candidate failed. */
    private static final int RELAY_CANDIDATE_PRIORITY = 1;
    /** Registration lease; the refresh thread renews at half this interval. */
    private static final Duration REGISTRATION_TTL = Duration.ofMinutes(5);

    /** First backoff after a failed reservation attempt across every endpoint. */
    static final long RESERVE_BACKOFF_MIN_MILLIS = 500;
    /**
     * Backoff ceiling. The accept loop <b>never gives up</b> — it used to sleep 500 ms once and then
     * return, which silently removed this peer's inbound relay path until the process restarted, so a
     * rendezvous restart was a permanent outage for anybody relying on it. Retrying forever with a
     * bounded delay costs one sleeping thread and restores the path the moment a relay comes back.
     */
    static final long RESERVE_BACKOFF_MAX_MILLIS = 30_000;

    private volatile MessageHandler handler;
    private volatile String relayRoute;
    private volatile String lastRegistrationError;

    /**
     * Build a transport for {@code identity} in the {@code (networkId, genesisHash)} namespace.
     *
     * @param identity        this peer's identity.
     * @param endpoints       the rendezvous endpoints (at least one).
     * @param networkId       the network namespace.
     * @param genesisHash     the world namespace.
     * @param capabilities    this peer's declared capabilities (advertised in its record).
     * @param directTransport an optional direct transport for the direct path; {@code null} =
     *                        relay-only.
     */
    public RendezvousPeerTransport(
            NodeIdentity identity,
            List<RendezvousEndpoint> endpoints,
            UUID networkId,
            Bytes genesisHash,
            NodeCapabilities capabilities,
            PeerTransport directTransport) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.endpoints = List.copyOf(endpoints);
        if (this.endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one rendezvous endpoint is required");
        }
        this.networkId = Objects.requireNonNull(networkId, "networkId");
        this.genesisHash = Objects.requireNonNull(genesisHash, "genesisHash");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.client = new RendezvousClient(identity, Duration.ofSeconds(5), Duration.ofSeconds(10));
        this.directTransport = directTransport;
    }

    /** @return this peer's node id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /** @return the path selector, for policy inspection (e.g. reporting the chosen path). */
    public TransportSelector selector() {
        return selector;
    }

    /**
     * Learn a discovered peer's identity key and candidates, so it can be dialed and its relayed
     * circuits authenticated. The record's signature is not re-checked here (the service verified it,
     * and the E2E handshake re-binds the identity); callers that need end-to-end record verification
     * do it before learning.
     *
     * @param record a signed record for the peer.
     * @Thread-context any thread.
     */
    public void learn(SignedRecord record) {
        NodeId peer = record.record().peer();
        knownKeys.put(peer, record.record().publicKey());
        knownCandidates.put(peer, record.record().candidates());
    }

    /**
     * Discover peers in the namespace across all endpoints and learn each returned record.
     *
     * @return the node ids discovered.
     * @throws TransportException on transport failure at every endpoint.
     * @Thread-context any thread.
     */
    public List<NodeId> discover() {
        java.util.LinkedHashSet<NodeId> found = new java.util.LinkedHashSet<>();
        IOException last = null;
        for (RendezvousEndpoint endpoint : endpoints) {
            try {
                RendezvousPeers page = client.discover(endpoint, networkId, genesisHash, 0, 0);
                for (SignedRecord record : page.records()) {
                    learn(record);
                    found.add(record.record().peer());
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (found.isEmpty() && last != null) {
            throw new TransportException("discovery failed at every endpoint", last);
        }
        return List.copyOf(found);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (directTransport != null) {
                directTransport.start();
            }
            // Reserve first so the record can advertise the relay candidate; the accept loop takes
            // over that reservation and re-reserves after each accepted circuit. Tried across every
            // endpoint rather than only the first: a deployment with three relays configured and one
            // in use has redundancy on paper and a single point of failure in practice.
            RendezvousClient.Reserved reserved = reserveAnywhere();
            if (reserved == null) {
                throw new IOException("no rendezvous endpoint granted a reservation");
            }
            relayRoute = reserved.reservation().relayRoute();
            registerSelf();
            Thread acceptor = new Thread(() -> acceptLoop(reserved), "rendezvous-accept-" + nodeId());
            acceptor.setDaemon(true);
            threads.add(acceptor);
            acceptor.start();
            // Registrations are leases, not permanent state: without renewal this peer silently
            // vanished from discovery after REGISTRATION_TTL while still believing it was
            // published (RENDEZVOUS.md §9.3).
            Thread refresher = new Thread(this::refreshLoop, "rendezvous-refresh-" + nodeId());
            refresher.setDaemon(true);
            threads.add(refresher);
            refresher.start();
        } catch (IOException e) {
            running.set(false);
            throw new TransportException("failed to start rendezvous transport", e);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (RelayCircuit circuit : circuits.values()) {
            closeQuietly(circuit);
        }
        circuits.clear();
        for (Thread thread : threads) {
            thread.interrupt();
        }
        threads.clear();
        if (directTransport != null) {
            directTransport.stop();
        }
    }

    @Override
    public void send(PeerAddress to, byte[] frame) {
        dispatch(to, frame, TransportSelector.MessageClass.CONTROL);
    }

    @Override
    public void sendStream(PeerAddress to, long streamId, byte[] payload) {
        // Bulk traffic prefers a non-relayed path; over the relay it crosses as a single sealed
        // frame (bounded by the reservation's byte ceiling).
        dispatch(to, payload, TransportSelector.MessageClass.BULK);
    }

    @Override
    public void setHandler(MessageHandler handler) {
        this.handler = handler;
        if (directTransport != null) {
            directTransport.setHandler(handler);
        }
    }

    private void dispatch(PeerAddress to, byte[] frame, TransportSelector.MessageClass messageClass) {
        NodeId peer = Objects.requireNonNull(to.nodeId(), "rendezvous transport requires a nodeId");
        TransportSelector.Path path = selector.select(peer, messageClass, availablePaths(peer, to));

        if (path == TransportSelector.Path.DIRECT && directTransport != null) {
            // The caller usually addresses by node id alone; the dialable host:port comes from the
            // peer's own advertised host candidate. Without this substitution the direct send had
            // nothing to dial and every attempt failed straight into the relay.
            PeerAddress directTarget = directAddressFor(peer, to);
            if (directTarget != null) {
                try {
                    directTransport.send(directTarget, frame);
                    selector.recordSuccess(peer, TransportSelector.Path.DIRECT);
                    return;
                } catch (RuntimeException directFailed) {
                    selector.recordFailure(peer, TransportSelector.Path.DIRECT);
                    // fall through to the relay fallback
                }
            } else {
                selector.recordFailure(peer, TransportSelector.Path.DIRECT);
            }
        }
        try {
            circuitTo(peer).send(frame);
            selector.recordSuccess(peer, TransportSelector.Path.RELAYED);
        } catch (IOException e) {
            selector.recordFailure(peer, TransportSelector.Path.RELAYED);
            circuits.remove(peer);
            throw new TransportException("relay send to " + peer + " failed", e);
        }
    }

    private Set<TransportSelector.Path> availablePaths(NodeId peer, PeerAddress to) {
        Set<TransportSelector.Path> available = EnumSet.of(TransportSelector.Path.RELAYED);
        // A route the CALLER supplied is a direct path, and for a long time it was not treated as
        // one: this asked only whether the peer had advertised a candidate through rendezvous, so a
        // send addressed to a perfectly dialable `host:port` was judged relay-only and went to a
        // circuit that then failed. {@link #directAddressFor} has always honoured
        // {@code to.route()}; it simply never got the chance, because the selector was never
        // offered DIRECT.
        //
        // That is what kept the always-on peers out of every committee: `NoderaHost` seats a
        // resident by sending `RegionAssigned` to the route in its membership entry, and a live mesh
        // soak reported "12 resident seat(s) were planned but every dispatch failed — first failure
        // was 127.0.0.1:25620 → relay send failed". Loopback, on the same machine, unreachable
        // because nobody would dial it (network L-30).
        boolean callerGaveARoute = to != null && to.route() != null && !to.route().isBlank();
        if (!RELAY_ONLY && directTransport != null && (callerGaveARoute || hasDirectCandidate(peer))) {
            available.add(TransportSelector.Path.DIRECT);
        }
        return available;
    }

    /**
     * Refuse the direct path entirely, so every peer-to-peer byte crosses a rendezvous relay.
     *
     * <p>A testing knob, and only that. Two players on one LAN will always find a direct path and
     * take it — which is correct, and which means an end-to-end run on one machine exercises none of
     * the relay lane it is supposed to be proving. Setting this forces the traffic through the
     * network the test is about.
     *
     * <p>It is NOT a privacy feature and must not be described as one: the relay cannot read what it
     * carries (circuits are end-to-end encrypted), but it does see who is talking to whom, and a
     * peer's own address still reaches the tracker it announces to.
     *
     * <p>Read once, from the environment or a system property, because a transport that could change
     * paths mid-session would make a failure impossible to attribute to either mode.
     */
    private static final boolean RELAY_ONLY = relayOnly();

    private static boolean relayOnly() {
        String value = System.getenv("NODERA_FORCE_RELAY");
        if (value == null || value.isBlank()) {
            value = System.getProperty("NODERA_FORCE_RELAY");
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return !(v.equals("0") || v.equals("false") || v.equals("no"));
    }

    private boolean hasDirectCandidate(NodeId peer) {
        List<PeerCandidate> candidates = knownCandidates.get(peer);
        return candidates != null && !CandidateDialer.directCandidates(candidates).isEmpty();
    }

    private RelayCircuit circuitTo(NodeId peer) throws IOException {
        RelayCircuit existing = circuits.get(peer);
        if (existing != null) {
            return existing;
        }
        Bytes key = knownKeys.get(peer);
        if (key == null) {
            throw new IOException("peer " + peer + " has not been discovered/learned");
        }
        var socket = openConnectAnywhere(peer);
        RelayCircuit circuit = RelayCircuitClient.dial(socket, identity, key);
        circuits.put(peer, circuit);
        startReader(peer, circuit);
        return circuit;
    }

    /**
     * Open a relay circuit to {@code peer} through whichever endpoint bridges it.
     *
     * <p>The target reserved somewhere, and after a drain that somewhere is not necessarily where it
     * was last time. Trying every endpoint costs a connect attempt per relay and is the difference
     * between "the peer moved" and "the peer is gone".
     */
    private java.net.Socket openConnectAnywhere(NodeId peer) throws IOException {
        IOException last = null;
        for (RendezvousEndpoint endpoint : endpoints) {
            try {
                return client.openConnect(endpoint, networkId, genesisHash, peer);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no rendezvous endpoint bridged to " + peer);
    }

    private void acceptLoop(RendezvousClient.Reserved firstReservation) {
        RendezvousClient.Reserved reserved = firstReservation;
        while (running.get()) {
            try {
                RelayIncoming incoming = RelayCircuitClient.readIncoming(reserved, this::onDrain);
                Bytes key = knownKeys.get(incoming.source());
                if (key == null) {
                    // We were connected by a peer we have not discovered: we cannot authenticate it,
                    // so drop this reservation and open a fresh one.
                    closeQuietly(reserved);
                } else {
                    RelayCircuit circuit = RelayCircuitClient.completeAccept(reserved, identity, key);
                    circuits.put(incoming.source(), circuit);
                    startReader(incoming.source(), circuit);
                }
            } catch (IOException | SecurityException e) {
                closeQuietly(reserved);
                if (!running.get()) {
                    return;
                }
            }
            if (!running.get()) {
                return;
            }
            // One reservation carries one circuit (matching the service); reserve again for the next.
            //
            // This loop is the difference between a rendezvous restart being a blip and being an
            // outage. It re-reserves across *every* endpoint, and when none of them will have it, it
            // backs off and tries again instead of returning — the old code slept 500 ms once and then
            // ended, leaving the peer with no inbound relay path and no way to notice.
            long backoff = RESERVE_BACKOFF_MIN_MILLIS;
            reserved = null;
            while (running.get() && reserved == null) {
                reserved = reserveAnywhere();
                if (reserved == null) {
                    sleepQuietly(backoff);
                    backoff = Math.min(backoff * 2, RESERVE_BACKOFF_MAX_MILLIS);
                }
            }
            if (reserved == null) {
                return; // stopped while backing off
            }
            relayRoute = reserved.reservation().relayRoute();
        }
    }

    /**
     * Reserve at the best endpoint that will have us, or {@code null} when none will.
     *
     * <p>A relay that answers with {@code draining} is skipped for this attempt rather than retried:
     * it has told us, in a reply we can read, that a slot it granted would break within its grace
     * period. Treating that refusal as an ordinary failure — which a closed socket would look like —
     * is what turns a planned restart into a stall.
     */
    private RendezvousClient.Reserved reserveAnywhere() {
        List<RendezvousEndpoint> candidates = endpoints;
        for (RendezvousEndpoint endpoint : candidates) {
            try {
                RendezvousClient.Reserved reserved =
                        client.reserve(endpoint, networkId, genesisHash);
                if (reserved.reservation().accepted()) {
                    return reserved;
                }
                lastRegistrationError =
                        endpoint + " refused a reservation: " + reserved.reservation().reason();
                closeQuietly(reserved);
            } catch (IOException e) {
                lastRegistrationError = endpoint + ": " + e.getMessage();
            }
        }
        return null;
    }

    /**
     * Replace the rendezvous endpoints in use and re-register immediately.
     *
     * <p>Called when the directory re-scores, and when a drain notice removes a relay. Re-registering
     * here rather than waiting for the refresh tick is the point: after a drain the peer has up to
     * half a TTL of invisibility to lose, and the new relays hold no record of it at all until it says
     * so.
     *
     * @param newEndpoints the endpoints to use from now on, best first; an empty list is ignored,
     *                     because having no relay at all is worse than keeping a stale one.
     * @throws IllegalArgumentException if {@code newEndpoints} is null.
     * @Thread-context any thread.
     */
    public void setEndpoints(List<RendezvousEndpoint> newEndpoints) {
        Objects.requireNonNull(newEndpoints, "endpoints");
        if (newEndpoints.isEmpty() || newEndpoints.equals(endpoints)) {
            return;
        }
        this.endpoints = List.copyOf(newEndpoints);
        if (!running.get()) {
            return;
        }
        try {
            registerSelf();
        } catch (IOException e) {
            // Not fatal: the refresh loop will try again on its own tick, and the existing circuits
            // keep carrying traffic in the meantime.
            lastRegistrationError = "re-register after an endpoint change failed: " + e.getMessage();
        }
    }

    /** @return the rendezvous endpoints in use, best first. @Thread-context any thread. */
    public List<RendezvousEndpoint> endpoints() {
        return endpoints;
    }

    /**
     * Register a handler for verified drain notices arriving on this peer's relay control channel.
     *
     * <p>The directory is the intended listener: it drops the departing relay, folds in the
     * replacements the notice named, and pushes a new endpoint list back through
     * {@link #setEndpoints}. That loop is what makes a relay restart a migration.
     *
     * @param handler called with each verified notice.
     * @throws IllegalArgumentException if {@code handler} is null.
     * @Thread-context any thread.
     */
    public void onDrainNotice(java.util.function.Consumer<
            dev.nodera.protocol.service.ServiceDrainNotice> handler) {
        drainHandlers.add(Objects.requireNonNull(handler, "handler"));
    }

    private void onDrain(dev.nodera.protocol.service.ServiceDrainNotice notice) {
        for (var handler : drainHandlers) {
            try {
                handler.accept(notice);
            } catch (RuntimeException e) {
                // The accept thread is this peer's only inbound relay path: a listener that throws
                // must not take it down with it.
                lastRegistrationError = "drain-notice handler failed: " + e;
            }
        }
    }

    private void startReader(NodeId peer, RelayCircuit circuit) {
        Thread reader = new Thread(() -> {
            try {
                while (running.get()) {
                    var frame = circuit.receive();
                    if (frame.isEmpty()) {
                        break;
                    }
                    MessageHandler h = handler;
                    if (h != null) {
                        h.onMessage(PeerAddress.of(peer, "relay"), frame.get());
                    }
                }
            } catch (IOException | SecurityException e) {
                // circuit broke; fall through to peer-down
            } finally {
                circuits.remove(peer, circuit);
                closeQuietly(circuit);
                MessageHandler h = handler;
                if (h != null) {
                    h.onPeerDown(PeerAddress.of(peer, "relay"));
                }
            }
        }, "rendezvous-reader-" + peer);
        reader.setDaemon(true);
        threads.add(reader);
        reader.start();
    }

    private void registerSelf() throws IOException {
        long now = System.currentTimeMillis();
        SignedRecord record = client.sign(networkId, genesisHash, RegistrationEvent.REGISTER,
                selfCandidates(), capabilities, now, now + REGISTRATION_TTL.toMillis());
        IOException last = null;
        int registered = 0;
        for (RendezvousEndpoint endpoint : endpoints) {
            try {
                client.register(endpoint, record);
                registered++;
            } catch (IOException e) {
                last = e;
            }
        }
        // Publishing to a subset is a working registration (RENDEZVOUS.md §9.1 merges results);
        // only a total failure is an error worth propagating.
        if (registered == 0 && last != null) {
            throw last;
        }
        lastRegistrationError = null;
    }

    /** @return this peer's relay route, or {@code null} before start / when unreserved. */
    public String relayRoute() {
        return relayRoute;
    }

    /**
     * The rendezvous transport's own inbound route is the direct transport's — a peer that can be
     * dialed directly should be dialed directly.
     */
    @Override
    public String listenRoute() {
        return directTransport == null ? null : directTransport.listenRoute();
    }

    /**
     * The candidates this peer publishes: its <b>direct</b> listen route first, then the relay
     * reservation as fallback (RENDEZVOUS.md §2.5 / §4.7 preference order).
     *
     * <p>Publishing the host candidate is what makes direct-first real. While only a relay
     * candidate was advertised, {@link #hasDirectCandidate} was false for every discovered peer, so
     * {@link TransportSelector.Path#DIRECT} was never even an available path and <em>all</em>
     * traffic on this transport crossed the relay — the opposite of the documented policy, and a
     * standing bandwidth cost on the relay operator.
     */
    private List<PeerCandidate> selfCandidates() {
        List<PeerCandidate> candidates = new java.util.ArrayList<>(2);
        String direct = directTransport == null ? null : directTransport.listenRoute();
        if (direct != null && !direct.isBlank()) {
            candidates.add(new PeerCandidate(CandidateKind.HOST, direct, DIRECT_CANDIDATE_PRIORITY));
        }
        String relay = relayRoute;
        if (relay != null && !relay.isBlank()) {
            candidates.add(new PeerCandidate(CandidateKind.RELAY, relay, RELAY_CANDIDATE_PRIORITY));
        }
        return List.copyOf(candidates);
    }

    /**
     * The address to dial a peer directly on: the caller's own route when it already has one,
     * otherwise the peer's best advertised direct candidate. Returns {@code null} when neither
     * exists, which is exactly the case the relay fallback is for.
     */
    private PeerAddress directAddressFor(NodeId peer, PeerAddress requested) {
        if (requested.route() != null && !requested.route().isBlank()) {
            return requested;
        }
        List<PeerCandidate> candidates = knownCandidates.get(peer);
        if (candidates == null) {
            return null;
        }
        List<PeerCandidate> direct = CandidateDialer.directCandidates(candidates);
        return direct.isEmpty() ? null : PeerAddress.of(peer, direct.get(0).address());
    }

    /** Re-publish this peer's record before the registration lease expires (RENDEZVOUS.md §9.3). */
    private void refreshLoop() {
        long periodMillis = REGISTRATION_TTL.toMillis() / 2;
        while (running.get()) {
            try {
                Thread.sleep(periodMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            try {
                registerSelf();
            } catch (IOException | RuntimeException e) {
                // A rendezvous that is briefly down must not tear this transport down; the next
                // tick retries. Losing the registration only costs discoverability, and the
                // already-open circuits keep carrying traffic meanwhile.
                lastRegistrationError = e.toString();
            }
        }
    }

    private static void closeQuietly(RelayCircuit circuit) {
        try {
            circuit.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void closeQuietly(RendezvousClient.Reserved reserved) {
        try {
            reserved.socket().close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
