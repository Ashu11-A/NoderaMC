package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
import dev.nodera.protocol.rendezvous.RelayConnect;
import dev.nodera.protocol.rendezvous.RelayReservation;
import dev.nodera.protocol.rendezvous.RelayReserve;
import dev.nodera.protocol.rendezvous.RendezvousDiscover;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.RendezvousRegister;
import dev.nodera.protocol.rendezvous.SignedPeerRecord;
import dev.nodera.protocol.rendezvous.SignedRecord;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The peer half of the rendezvous service (Task 29): registers this peer's signed candidate record,
 * discovers others in a {@code (network, world)} namespace, and opens relay reservations / circuits.
 *
 * <p>Nothing here is trusted (rendezvous.md §8.1). A rendezvous point can hide peers or invent
 * unreachable ones; it cannot forge a record (records are Ed25519-signed by the peer they describe
 * and verified against the same canonical bytes on both sides) or read a relayed payload
 * ({@link EndToEndCipher}). This client treats a discovery response as a set of hints.
 *
 * <p>Thread-context: register/discover open, use, and close their own short-lived socket, so they
 * are safe to call concurrently. A reservation / connect returns a long-lived socket owned by the
 * caller.
 */
public final class RendezvousClient {

    private final NodeIdentity identity;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    /**
     * Build a client for {@code identity}.
     *
     * @param identity       this peer's identity — signs records; the private key never leaves it.
     * @param connectTimeout socket connect timeout.
     * @param readTimeout    socket read timeout for request/reply exchanges.
     */
    public RendezvousClient(NodeIdentity identity, Duration connectTimeout, Duration readTimeout) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
    }

    /** @return this client's node id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /**
     * Build and sign this peer's registration record.
     *
     * @param networkId    the network namespace.
     * @param genesisHash  the world namespace.
     * @param event        the lifecycle event.
     * @param candidates   the peer's advertised reachability candidates.
     * @param capabilities the peer's declared capabilities.
     * @param issuedAt     the issue instant (epoch millis).
     * @param expiresAt    the record's self-expiry (epoch millis).
     * @return the signed record.
     * @Thread-context any thread.
     */
    public SignedRecord sign(
            UUID networkId,
            Bytes genesisHash,
            RegistrationEvent event,
            List<PeerCandidate> candidates,
            NodeCapabilities capabilities,
            long issuedAt,
            long expiresAt) {
        SignedPeerRecord record = new SignedPeerRecord(networkId, genesisHash, identity.nodeId(),
                identity.publicKeyBytes(), event, candidates, capabilities, issuedAt, expiresAt);
        Bytes signature = identity.sign(record.signedBytes());
        return new SignedRecord(record, signature);
    }

    /**
     * Register (or refresh / unregister) at one endpoint.
     *
     * @param endpoint the rendezvous endpoint.
     * @param record   the signed record.
     * @return the reflexive address the service observed for this peer, if it confirmed.
     * @throws IOException on transport failure.
     * @Thread-context any thread.
     */
    public Optional<String> register(RendezvousEndpoint endpoint, SignedRecord record)
            throws IOException {
        NoderaMessage reply = exchange(endpoint, new RendezvousRegister(record));
        if (reply instanceof ObservedAddress observed) {
            return Optional.of(observed.observedRoute());
        }
        return Optional.empty();
    }

    /**
     * Discover peers in a namespace at one endpoint (one page).
     *
     * @param endpoint    the rendezvous endpoint.
     * @param networkId   the network namespace.
     * @param genesisHash the world namespace.
     * @param cursor      the page cursor ({@code 0} to start).
     * @param limit       the page size ({@code 0} for the service default).
     * @return the returned page.
     * @throws IOException on transport failure.
     * @Thread-context any thread.
     */
    public RendezvousPeers discover(
            RendezvousEndpoint endpoint, UUID networkId, Bytes genesisHash, int cursor, int limit)
            throws IOException {
        NoderaMessage reply = exchange(endpoint,
                new RendezvousDiscover(networkId, genesisHash, cursor, limit));
        if (reply instanceof RendezvousPeers peers) {
            return peers;
        }
        throw new IOException("unexpected discovery reply: " + reply.getClass().getSimpleName());
    }

    /**
     * Reserve an inbound relay slot, returning the granted reservation and the still-open control
     * socket the reserver waits for inbound circuits on.
     *
     * @param endpoint    the rendezvous endpoint.
     * @param networkId   the network namespace.
     * @param genesisHash the world namespace.
     * @return the reservation and its control socket (caller-owned; close to release).
     * @throws IOException on transport failure or a refused reservation.
     * @Thread-context any thread.
     */
    public Reserved reserve(RendezvousEndpoint endpoint, UUID networkId, Bytes genesisHash)
            throws IOException {
        Socket socket = connect(endpoint);
        try {
            RendezvousFrames.write(socket.getOutputStream(),
                    MessageCodec.encode(new RelayReserve(networkId, genesisHash, identity.nodeId())));
            byte[] frame = RendezvousFrames.read(socket.getInputStream())
                    .orElseThrow(() -> new IOException("service closed before the reservation"));
            NoderaMessage reply = MessageCodec.decode(frame);
            if (reply instanceof RelayReservation reservation && reservation.accepted()) {
                // The reservation is long-lived: leave the socket open and the read timeout off, so
                // the reserver can block on an inbound circuit for as long as its reservation lasts.
                socket.setSoTimeout(0);
                return new Reserved(socket, reservation);
            }
            socket.close();
            throw new IOException("reservation refused");
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Open a relay circuit to a reserved target, returning the still-open socket to bridge over.
     *
     * @param endpoint    the rendezvous endpoint.
     * @param networkId   the network namespace.
     * @param genesisHash the world namespace.
     * @param target      the reserved destination peer.
     * @return the connected circuit socket (caller-owned).
     * @throws IOException on transport failure.
     * @Thread-context any thread.
     */
    public Socket openConnect(
            RendezvousEndpoint endpoint, UUID networkId, Bytes genesisHash, NodeId target)
            throws IOException {
        Socket socket = connect(endpoint);
        try {
            RendezvousFrames.write(socket.getOutputStream(), MessageCodec.encode(
                    new RelayConnect(networkId, genesisHash, identity.nodeId(), target)));
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private NoderaMessage exchange(RendezvousEndpoint endpoint, NoderaMessage request)
            throws IOException {
        try (Socket socket = connect(endpoint)) {
            RendezvousFrames.write(socket.getOutputStream(), MessageCodec.encode(request));
            byte[] frame = RendezvousFrames.read(socket.getInputStream())
                    .orElseThrow(() -> new IOException("service closed before replying"));
            return MessageCodec.decode(frame);
        }
    }

    private Socket connect(RendezvousEndpoint endpoint) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                (int) connectTimeout.toMillis());
        socket.setSoTimeout((int) readTimeout.toMillis());
        socket.setTcpNoDelay(true);
        return socket;
    }

    /**
     * A granted reservation and its open control socket.
     *
     * @param socket      the still-open control socket the reserver waits on.
     * @param reservation the granted reservation (limits + proof).
     */
    public record Reserved(Socket socket, RelayReservation reservation) {}
}
