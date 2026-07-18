package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Gets a brand-new peer into a world through <b>any</b> of three independent mechanisms (Task 20),
 * so that no single point of failure — including the original host — can lock people out of a world
 * permanently.
 *
 * <h2>The three mechanisms, tried in order</h2>
 *
 * <ol>
 *   <li><b>Configured list</b> — multiple bootstrap entries from config. Cheapest and most likely
 *       to be current.</li>
 *   <li><b>{@link CachedPeerStore} redial</b> — addresses remembered from prior sessions, most
 *       recently seen first. This is the mechanism that survives the configured host going away
 *       forever.</li>
 *   <li><b>{@link InvitationCodec} blob</b> — a signed string a friend pasted. The social path, for
 *       a peer that has never been in this world and has no working config.</li>
 * </ol>
 *
 * <p>The order is by expected freshness, not by trust — none of the three is trusted. Candidates
 * from every source are de-duplicated while preserving order, so a route appearing in both config
 * and cache is dialed once, at its earliest position.
 *
 * <p>LAN multicast and DNS seeds are deliberately out of scope here (backlog): both need
 * environment-specific machinery, and three working mechanisms already satisfy the survivability
 * requirement.
 *
 * <h2>Verification is downstream</h2>
 *
 * <p>A successful dial proves only that something answered. The joining peer then verifies the
 * genesis hash and certificate chain of the state it is offered — state self-verifies, peers do
 * not. This class therefore does no trust evaluation at all; it produces an ordered candidate list
 * and reports which candidate answered.
 *
 * <p>Thread-context: the class is stateless apart from its injected collaborators; {@link #join}
 * runs on the calling thread and calls the {@link Dialer} synchronously.
 */
public final class BootstrapClient {

    /** How a candidate route was learned — reported back so callers can log/diagnose. */
    public enum Source {
        /** From the configured bootstrap list. */
        CONFIGURED,
        /** Redialed from the on-disk cached peer store. */
        CACHED,
        /** From a pasted, signature-verified invitation blob. */
        INVITATION
    }

    /**
     * One dial candidate.
     *
     * @param route  the transport route to dial.
     * @param source how it was learned.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Candidate(String route, Source source) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if {@code route} is null/blank or {@code source} is null.
         */
        public Candidate {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(source, "source");
            if (route.isBlank()) {
                throw new IllegalArgumentException("route must not be blank");
            }
        }
    }

    /**
     * Attempts one connection. Supplied by the runtime, which owns the concrete transport.
     *
     * @Thread-context called on {@link #join}'s thread.
     */
    @FunctionalInterface
    public interface Dialer {
        /**
         * @param candidate the route to try.
         * @return {@code true} if the connection succeeded.
         */
        boolean dial(Candidate candidate);
    }

    private final List<String> configuredRoutes;
    private final CachedPeerStore cachedPeers;
    private final SignatureService signatures;

    /**
     * @param configuredRoutes the configured bootstrap list (may be empty).
     * @param cachedPeers      the on-disk peer cache (may be empty).
     * @param signatures       verifier for invitation blobs.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread (construction only).
     */
    public BootstrapClient(
            List<String> configuredRoutes, CachedPeerStore cachedPeers, SignatureService signatures) {
        Objects.requireNonNull(configuredRoutes, "configuredRoutes");
        this.configuredRoutes = List.copyOf(configuredRoutes);
        this.cachedPeers = Objects.requireNonNull(cachedPeers, "cachedPeers");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
    }

    /**
     * Build the ordered, de-duplicated candidate list for a world.
     *
     * @param genesisHash    the world to join.
     * @param invitationBlob a pasted invitation, or {@code null} if none.
     * @return the candidates, in dial order.
     * @throws IllegalArgumentException if {@code genesisHash} is null, or the invitation is present
     *                                  but malformed.
     * @throws IllegalStateException    if the invitation's signature does not verify, or it is for
     *                                  a different world — an invitation to world B must never
     *                                  silently be used to join world A.
     * @Thread-context any thread.
     */
    public List<Candidate> candidates(Bytes genesisHash, String invitationBlob) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        // A LinkedHashSet over routes gives de-duplication that preserves first-seen order, so a
        // route present in both config and cache keeps its (earlier) configured position.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Candidate> out = new ArrayList<>();

        for (String route : configuredRoutes) {
            if (route != null && !route.isBlank() && seen.add(route)) {
                out.add(new Candidate(route, Source.CONFIGURED));
            }
        }
        for (CachedPeerStore.CachedPeer peer : cachedPeers.forWorld(genesisHash)) {
            if (seen.add(peer.route())) {
                out.add(new Candidate(peer.route(), Source.CACHED));
            }
        }
        if (invitationBlob != null && !invitationBlob.isBlank()) {
            InvitationCodec.Invitation invitation =
                    InvitationCodec.decode(invitationBlob, signatures);
            if (!invitation.genesisHash().equals(genesisHash)) {
                throw new IllegalStateException(
                        "invitation is for world " + invitation.genesisHash().toShortHex(6)
                                + ", not " + genesisHash.toShortHex(6));
            }
            for (String route : invitation.routes()) {
                if (seen.add(route)) {
                    out.add(new Candidate(route, Source.INVITATION));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Try every candidate in order until one answers.
     *
     * @param genesisHash    the world to join.
     * @param invitationBlob a pasted invitation, or {@code null}.
     * @param dialer         performs the connection attempt.
     * @return the candidate that answered, or empty if every mechanism failed.
     * @throws IllegalArgumentException if a required argument is null.
     * @Thread-context calling thread; dials synchronously and in order.
     */
    public Optional<Candidate> join(Bytes genesisHash, String invitationBlob, Dialer dialer) {
        Objects.requireNonNull(dialer, "dialer");
        for (Candidate candidate : candidates(genesisHash, invitationBlob)) {
            boolean connected;
            try {
                connected = dialer.dial(candidate);
            } catch (RuntimeException e) {
                // A dead address must not abort the whole bootstrap: the next mechanism is exactly
                // what this class exists to reach.
                connected = false;
            }
            if (connected) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** @return the configured bootstrap routes. */
    public List<String> configuredRoutes() {
        return configuredRoutes;
    }

    /** @return the backing cached-peer store. */
    public CachedPeerStore cachedPeers() {
        return cachedPeers;
    }
}
