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
 * <p><b>Composition (rendezvous.md §4.7).</b> A {@link TransportSelector} chooses per peer:
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
    private final List<RendezvousEndpoint> endpoints;
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
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile MessageHandler handler;
    private volatile String relayRoute;

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
            // over that reservation and re-reserves after each accepted circuit.
            RendezvousClient.Reserved reserved = client.reserve(endpoints.get(0), networkId, genesisHash);
            relayRoute = reserved.reservation().relayRoute();
            registerSelf();
            Thread acceptor = new Thread(() -> acceptLoop(reserved), "rendezvous-accept-" + nodeId());
            acceptor.setDaemon(true);
            threads.add(acceptor);
            acceptor.start();
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
        TransportSelector.Path path = selector.select(peer, messageClass, availablePaths(peer));

        if (path == TransportSelector.Path.DIRECT && directTransport != null) {
            try {
                directTransport.send(to, frame);
                selector.recordSuccess(peer, TransportSelector.Path.DIRECT);
                return;
            } catch (RuntimeException directFailed) {
                selector.recordFailure(peer, TransportSelector.Path.DIRECT);
                // fall through to the relay fallback
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

    private Set<TransportSelector.Path> availablePaths(NodeId peer) {
        Set<TransportSelector.Path> available = EnumSet.of(TransportSelector.Path.RELAYED);
        if (directTransport != null && hasDirectCandidate(peer)) {
            available.add(TransportSelector.Path.DIRECT);
        }
        return available;
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
        var socket = client.openConnect(endpoints.get(0), networkId, genesisHash, peer);
        RelayCircuit circuit = RelayCircuitClient.dial(socket, identity, key);
        circuits.put(peer, circuit);
        startReader(peer, circuit);
        return circuit;
    }

    private void acceptLoop(RendezvousClient.Reserved firstReservation) {
        RendezvousClient.Reserved reserved = firstReservation;
        while (running.get()) {
            try {
                RelayIncoming incoming = RelayCircuitClient.readIncoming(reserved);
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
            try {
                reserved = client.reserve(endpoints.get(0), networkId, genesisHash);
            } catch (IOException e) {
                if (running.get()) {
                    // Back off briefly rather than hot-looping if the service is unreachable.
                    sleepQuietly(500);
                }
                if (!running.get()) {
                    return;
                }
                reserved = null;
            }
            if (reserved == null) {
                return;
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
                selfCandidates(), capabilities, now, now + Duration.ofMinutes(5).toMillis());
        for (RendezvousEndpoint endpoint : endpoints) {
            client.register(endpoint, record);
        }
    }

    private List<PeerCandidate> selfCandidates() {
        // Advertise the relay candidate; a direct transport that exposes its listen route can add a
        // host candidate here in a later pass (the direct path is discovered per-candidate anyway).
        String route = relayRoute;
        if (route == null) {
            return List.of();
        }
        return List.of(new PeerCandidate(CandidateKind.RELAY, route, 1));
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
