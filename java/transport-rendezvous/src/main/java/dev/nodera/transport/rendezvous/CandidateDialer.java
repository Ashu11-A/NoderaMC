package dev.nodera.transport.rendezvous;

import dev.nodera.protocol.rendezvous.PeerCandidate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Tries a peer's direct reachability candidates in preference order (Task 29; rendezvous.md §4.4).
 *
 * <p>A should attempt B's direct candidates before falling back to the relay: a direct path cuts
 * latency, relay bandwidth, and the dependency on central infrastructure (§3.3). Relay-kind
 * candidates are skipped here — those are the fallback the {@link RelayCircuitClient} handles. A
 * connected socket is <b>not</b> proof of identity; the caller still runs the identity-binding
 * handshake ({@link EndToEndCipher}) before trusting the far end (§4.4).
 *
 * <p>Thread-context: {@link #dial} blocks; call from a dialing thread.
 */
public final class CandidateDialer {

    private final Duration perCandidateTimeout;

    /**
     * @param perCandidateTimeout how long to wait on each candidate before trying the next.
     */
    public CandidateDialer(Duration perCandidateTimeout) {
        this.perCandidateTimeout = Objects.requireNonNull(perCandidateTimeout, "perCandidateTimeout");
    }

    /**
     * The direct candidates of {@code candidates}, best-priority first.
     *
     * @param candidates a peer's advertised candidates.
     * @return the direct ones, highest priority first.
     * @Thread-context any thread.
     */
    public static List<PeerCandidate> directCandidates(List<PeerCandidate> candidates) {
        List<PeerCandidate> direct = new ArrayList<>();
        for (PeerCandidate candidate : candidates) {
            if (candidate.isDirect()) {
                direct.add(candidate);
            }
        }
        direct.sort(Comparator.comparingInt(PeerCandidate::priority).reversed());
        return direct;
    }

    /**
     * Try each direct candidate in order; return the first that connects.
     *
     * @param candidates a peer's advertised candidates.
     * @return a connected socket, or empty if no direct candidate was reachable.
     * @Thread-context a dialing thread.
     */
    public Optional<Socket> dial(List<PeerCandidate> candidates) {
        for (PeerCandidate candidate : directCandidates(candidates)) {
            RendezvousEndpoint endpoint;
            try {
                endpoint = RendezvousEndpoint.parse(candidate.address());
            } catch (IllegalArgumentException malformed) {
                continue; // a claim we cannot even parse is not worth a dial
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()),
                        (int) perCandidateTimeout.toMillis());
                socket.setTcpNoDelay(true);
                return Optional.of(socket);
            } catch (IOException unreachable) {
                closeQuietly(socket);
            }
        }
        return Optional.empty();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort close of a socket we failed to connect
        }
    }
}
