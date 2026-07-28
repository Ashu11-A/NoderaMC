package dev.nodera.protocol.wire;

/**
 * Which of the wire's two planes a message belongs to (Task 14, {@code Plan.7} decision D1).
 *
 * <p>The two planes have opposite requirements, and trying to serve both with one encoding is the
 * reason a Nodera peer cannot talk to a peer built from a different release. The split is the
 * decision the whole cross-version programme rests on:
 *
 * <ul>
 *   <li>{@link #CONSENSUS} — the payload is hashed and signed. Its bytes <em>are</em> its identity,
 *       so it gets strict positional encoding and byte-exact round trip. A tolerant decoder here
 *       would fork the network: two peers that spell the same value differently compute different
 *       state roots.</li>
 *   <li>{@link #INFRASTRUCTURE} — the payload only has to be understood. It gets forward-compatible
 *       TLV, so a field added by a newer release is skipped by an older one instead of destroying
 *       the rest of the message.</li>
 * </ul>
 *
 * <p><b>How to classify a new message.</b> The test is mechanical: if the value is hashed or signed,
 * it is {@link #CONSENSUS}. Anything ambiguous is also {@link #CONSENSUS} — the cost of being
 * wrongly strict is a feature that cannot evolve, and the cost of being wrongly tolerant is a
 * network that silently disagrees about state.
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum MessagePlane {

    /**
     * Hashed / signed payloads: actions, envelopes, proposals, votes, certificates, deltas,
     * snapshots, committed events, grants, ownership, tombstones, and the region-assignment
     * control messages that carry the epochs those payloads are validated against.
     *
     * <p>Encoded with the strict positional codec and carried across the infrastructure plane as an
     * opaque byte string (D5), so a peer never re-serialises signed bytes it might spell
     * differently.
     */
    CONSENSUS,

    /**
     * Everything a peer needs in order to <em>reach</em> other peers: handshake, membership,
     * keep-alive, discovery, tracker, rendezvous, relay, content, manifests, tunnel, and the
     * service directory. Encoded as canonical TLV so version skew costs nothing.
     */
    INFRASTRUCTURE;

    /** @return {@code true} for {@link #CONSENSUS}. */
    public boolean isConsensus() {
        return this == CONSENSUS;
    }
}
