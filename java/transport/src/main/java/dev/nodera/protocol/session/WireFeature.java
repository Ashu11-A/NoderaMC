package dev.nodera.protocol.session;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * An optional capability of the wire, negotiated at the handshake (Task 14 phase 4, retiring
 * {@code Plan.7} R3).
 *
 * <p>This is what replaces the per-message version zoo. Where the old wire had
 * {@code SessionKeepAlive} accepting v1–v2 and always emitting v2, {@code RegionProposal} emitting
 * v3, and {@code ExternalDelta} emitting v2 — every reader tolerant, every writer unconditional —
 * a peer now states what it can receive, both sides intersect, and the encoder consults the result.
 * A feature the other peer did not accept is never emitted to it, so compatibility stops being a
 * per-message promise that only runs in one direction.
 *
 * <p>Codes are permanent. A capability that is later withdrawn keeps its number reserved forever;
 * reusing one would make an old peer's "I support 5" mean something new.
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum WireFeature {

    /** Region-progress detail in the session keep-alive (the former keep-alive body version 2). */
    KEEP_ALIVE_REGION_PROGRESS(1),

    /**
     * Batch-root commitment in a region proposal (the former proposal body version 3).
     *
     * <p>A <b>consensus</b> feature, so it gates co-validation rather than emission: a proposal's
     * signature covers its body version, so it cannot be demoted for a peer that has not caught up
     * without invalidating a signature the sender did not produce. A peer missing this is admitted
     * as an observer instead.
     */
    PROPOSAL_BATCH_ROOT(2),

    /** The tick field on an external delta (the former external-delta body version 2). Consensus;
     * see {@link #PROPOSAL_BATCH_ROOT} for why it gates seats rather than bytes. */
    EXTERNAL_DELTA_TICK(3),

    /** Full dial-route lists in tracker answers. */
    TRACKER_ROUTE_LISTS(4),

    /** The service-directory family: discovering rendezvous points through trackers. */
    SERVICE_DIRECTORY(5),

    /** The LAN-tunnel family. */
    LAN_TUNNEL(6),

    /** Owner-signed world tombstones. */
    WORLD_DELETION(7),

    /** Client version and public key carried in membership entries. */
    MEMBERSHIP_PEER_KEYS(8);

    private final int code;

    WireFeature(int code) {
        this.code = code;
    }

    /** The permanent wire code. Never {@code ordinal()}. */
    public int code() {
        return code;
    }

    /** Resolve a code, or empty when this build does not know it. */
    public static Optional<WireFeature> fromCode(int code) {
        for (WireFeature f : values()) {
            if (f.code == code) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * The features that must match for two peers to co-validate.
     *
     * <p>These are the ones whose encoding is covered by a signature, so a difference cannot be
     * papered over by emitting less — see {@link #PROPOSAL_BATCH_ROOT}.
     */
    public static Set<WireFeature> consensusFeatures() {
        return Set.of(PROPOSAL_BATCH_ROOT, EXTERNAL_DELTA_TICK);
    }

    /** Every feature this build supports, as wire codes. */
    public static Set<Integer> all() {
        Set<Integer> out = new TreeSet<>();
        for (WireFeature f : values()) {
            out.add(f.code);
        }
        return out;
    }

    /**
     * The features both sides accept.
     *
     * <p>Intersection, never union and never "whatever the newer side has": the whole failure this
     * retires is a peer emitting something the other end cannot parse, and only the intersection is
     * guaranteed to be understood by both.
     *
     * @param mine   this build's advertised codes.
     * @param theirs the peer's advertised codes.
     * @return the agreed codes, ascending.
     * @Thread-context any thread.
     */
    public static Set<Integer> intersect(Set<Integer> mine, Set<Integer> theirs) {
        Set<Integer> out = new TreeSet<>(mine == null ? Set.of() : mine);
        out.retainAll(theirs == null ? Set.of() : theirs);
        return new LinkedHashSet<>(out);
    }
}
