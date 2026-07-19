package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The peer half of the tracker (Task 28): announces this peer to configured
 * {@code nodera-tracker} endpoints and queries them for a world's peers and seeders.
 *
 * <p>It replaces the <b>serving</b> role that Task 20 embedded in a Java peer
 * ({@code TrackerService}, deleted with this class's arrival — see {@code docs/LEGACY.md}). The
 * peer-local caches it fed, {@link PeerDirectory} and {@link ArchiveInventory}, stay exactly where
 * they were: repair and rarest-first selection still read them. Only the process that answers
 * strangers moved out.
 *
 * <h2>Nothing here is trusted</h2>
 *
 * <p>A tracker can hide peers or invent unreachable ones. It cannot forge state (every piece is
 * hash-verified against a manifest) or identities (records are Ed25519-signed by the peer they
 * describe). So this client treats a response as a set of <i>hints</i>: addresses to dial and
 * seeders to try. When several endpoints answer, their peer lists are merged rather than
 * arbitrated — a tracker that omits peers loses influence instead of winning it.
 *
 * <h2>The tracker paces the traffic</h2>
 *
 * <p>Each ack carries {@code nextAnnounceAfterSeconds}; {@link #announceIntervalSeconds()} reports
 * the most recently accepted value so a caller's loop can honour it. An operator widening the
 * interval under load therefore does not need every peer to ship a new build.
 *
 * <p>Thread-context: thread-safe. Each call opens, uses and closes its own short-lived socket, so
 * concurrent announces and queries never share connection state. {@link #close()} is idempotent.
 */
public final class TrackerClient implements AutoCloseable {

    /** Absolute cap on a single frame — mirrors {@code SocketPeerTransport.MAX_FRAME_BYTES}. */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** Interval assumed before any tracker has answered. */
    public static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 120;

    /**
     * One tracker endpoint.
     *
     * @param host the host name or literal address.
     * @param port the TCP port.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Endpoint(String host, int port) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if the host is blank or the port is out of range.
         */
        public Endpoint {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw new IllegalArgumentException("host must not be blank");
            }
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("port out of range: " + port);
            }
        }

        /**
         * Parse a {@code host:port} route, as it appears in config.
         *
         * @param route the route.
         * @return the endpoint.
         * @throws IllegalArgumentException if the route is malformed.
         * @Thread-context any thread.
         */
        public static Endpoint parse(String route) {
            Objects.requireNonNull(route, "route");
            int idx = route.lastIndexOf(':');
            if (idx <= 0 || idx == route.length() - 1) {
                throw new IllegalArgumentException("malformed tracker endpoint: " + route);
            }
            String host = route.substring(0, idx);
            // Strip the brackets of a literal IPv6 route so InetSocketAddress accepts it.
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            try {
                return new Endpoint(host, Integer.parseInt(route.substring(idx + 1)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed tracker endpoint port: " + route, e);
            }
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private final List<Endpoint> endpoints;
    private final NodeIdentity identity;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile int announceIntervalSeconds = DEFAULT_ANNOUNCE_INTERVAL_SECONDS;

    /**
     * @param endpoints the configured tracker endpoints; may be empty (the client then no-ops,
     *                  which is the correct behaviour for a LAN-only deployment).
     * @param identity  this peer's identity — used to sign announces. The private key never leaves
     *                  it, and no tracker ever sees signing material.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread (construction only).
     */
    public TrackerClient(List<Endpoint> endpoints, NodeIdentity identity) {
        this(endpoints, identity, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    /**
     * @param endpoints      the configured tracker endpoints.
     * @param identity       this peer's identity.
     * @param connectTimeout socket connect timeout.
     * @param readTimeout    socket read timeout — an unresponsive tracker must never block a peer's
     *                       announce loop indefinitely.
     * @throws IllegalArgumentException if an argument is null or a timeout is negative.
     * @Thread-context any thread (construction only).
     */
    public TrackerClient(List<Endpoint> endpoints, NodeIdentity identity,
                         Duration connectTimeout, Duration readTimeout) {
        this.endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        this.identity = Objects.requireNonNull(identity, "identity");
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.readTimeout = requirePositive(readTimeout, "readTimeout");
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /** @return the configured endpoints, in order. */
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    /**
     * @return the announce interval the trackers most recently asked for, in seconds.
     * @Thread-context any thread.
     */
    public int announceIntervalSeconds() {
        return announceIntervalSeconds;
    }

    /**
     * Build and sign an announce for this peer.
     *
     * @param genesisHash    the world.
     * @param event          the lifecycle event.
     * @param routes         the routes this peer is reachable on.
     * @param capabilities   this peer's declared capabilities, roles included.
     * @param holdings       what this peer holds for the world.
     * @param worldName      the display name — honoured by the tracker only from a
     *                       {@code FULL_ARCHIVE} host, so a non-host may pass {@code ""}.
     * @param retentionDeadlineEpochMillis the countdown to surface, or {@code 0}.
     * @param reliabilityBps this peer's reliability in basis points.
     * @param nowEpochMillis the current wall clock — the tracker's freshness window is checked
     *                       against it, so a peer with a badly wrong clock is rejected loudly
     *                       rather than silently ignored.
     * @return the signed announce.
     * @Thread-context any thread.
     */
    public TrackerAnnounce buildAnnounce(
            Bytes genesisHash, AnnounceEvent event, List<String> routes,
            NodeCapabilities capabilities, List<ManifestHolding> holdings,
            String worldName, long retentionDeadlineEpochMillis, int reliabilityBps,
            long nowEpochMillis) {
        // Signature covers everything but itself, so the placeholder below is never signed over.
        TrackerAnnounce unsigned = new TrackerAnnounce(
                genesisHash, identity.nodeId(), identity.publicKeyBytes(), event, routes,
                capabilities, holdings, worldName, retentionDeadlineEpochMillis, reliabilityBps,
                nowEpochMillis, Bytes.empty());
        Bytes signature = identity.sign(unsigned.signedPortion());
        return new TrackerAnnounce(
                genesisHash, identity.nodeId(), identity.publicKeyBytes(), event, routes,
                capabilities, holdings, worldName, retentionDeadlineEpochMillis, reliabilityBps,
                nowEpochMillis, signature);
    }

    /**
     * Send an announce to every configured endpoint.
     *
     * <p>Endpoints are independent: one unreachable tracker does not stop the others, and the
     * method never throws for a network failure — a peer whose announce loop died on a refused
     * connection would silently vanish from every world list.
     *
     * @param announce the signed announce.
     * @return the per-endpoint acks, in endpoint order; an endpoint that failed is absent.
     * @Thread-context any thread.
     */
    public Map<Endpoint, TrackerAnnounceAck> announce(TrackerAnnounce announce) {
        Objects.requireNonNull(announce, "announce");
        Map<Endpoint, TrackerAnnounceAck> acks = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, announce).ifPresent(reply -> {
                if (reply instanceof TrackerAnnounceAck ack) {
                    acks.put(endpoint, ack);
                    if (ack.accepted()) {
                        announceIntervalSeconds = ack.nextAnnounceAfterSeconds();
                    }
                }
            });
        }
        return acks;
    }

    /**
     * Query every configured endpoint and merge the answers.
     *
     * <p>Merging, not arbitrating: peers and seeders from all responses are unioned (first
     * occurrence wins for a given {@code NodeId}), and the counters come from the response
     * reporting the most peers. A tracker that omits peers therefore dilutes its own influence
     * instead of censoring the world.
     *
     * @param genesisHash the world.
     * @return the merged response, or empty if no endpoint answered.
     * @Thread-context any thread.
     */
    public Optional<TrackerResponse> query(Bytes genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        TrackerQuery query = new TrackerQuery(genesisHash);
        List<TrackerResponse> responses = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            exchange(endpoint, query).ifPresent(reply -> {
                if (reply instanceof TrackerResponse response) {
                    responses.add(response);
                }
            });
        }
        return merge(genesisHash, responses);
    }

    /**
     * Merge several trackers' answers for one world.
     *
     * @param genesisHash the world.
     * @param responses   the answers received.
     * @return the merged answer, or empty when {@code responses} is empty.
     * @Thread-context any thread; pure function.
     */
    static Optional<TrackerResponse> merge(Bytes genesisHash, List<TrackerResponse> responses) {
        if (responses.isEmpty()) {
            return Optional.empty();
        }
        if (responses.size() == 1) {
            return Optional.of(responses.get(0));
        }
        TrackerResponse best = responses.get(0);
        for (TrackerResponse candidate : responses) {
            if (candidate.peers().size() > best.peers().size()) {
                best = candidate;
            }
        }
        Map<Object, PeerEntry> peers = new LinkedHashMap<>();
        for (TrackerResponse response : responses) {
            for (PeerEntry entry : response.peers()) {
                peers.putIfAbsent(entry.nodeId(), entry);
            }
        }
        return Optional.of(new TrackerResponse(
                genesisHash,
                best.worldName(),
                List.copyOf(peers.values()),
                best.seeders(),
                best.worldPlayerCount(),
                best.storedChunks(),
                best.reliabilityBps(),
                best.health(),
                best.retentionDeadlineEpochMillis()));
    }

    /**
     * Whether a peer with these capabilities should announce world display metadata.
     *
     * @param capabilities the peer's capabilities.
     * @return {@code true} only for the {@code FULL_ARCHIVE} host (rule 0).
     * @Thread-context any thread.
     */
    public static boolean isWorldHost(NodeCapabilities capabilities) {
        return capabilities.hasRole(PeerRole.FULL_ARCHIVE);
    }

    private Optional<NoderaMessage> exchange(Endpoint endpoint, NoderaMessage request) {
        if (closed.get()) {
            return Optional.empty();
        }
        byte[] frame = MessageCodec.encode(request);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                    (int) connectTimeout.toMillis());
            socket.setSoTimeout((int) readTimeout.toMillis());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readInt();
            if (length < 0 || length > MAX_FRAME_BYTES) {
                // A hostile or broken tracker must not be able to make a peer allocate a gigabyte.
                throw new IOException("bad reply frame length: " + length);
            }
            byte[] reply = new byte[length];
            in.readFully(reply);
            return Optional.of(MessageCodec.decode(reply));
        } catch (IOException | RuntimeException e) {
            // Unreachable, slow, or misbehaving trackers are an expected steady state, not an
            // error to propagate: discovery degrades, the peer keeps playing.
            return Optional.empty();
        }
    }

    /**
     * Stop using this client. In-flight exchanges finish; later ones are no-ops.
     *
     * @Thread-context any thread; idempotent.
     */
    @Override
    public void close() {
        closed.set(true);
    }
}
