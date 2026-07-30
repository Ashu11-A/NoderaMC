package dev.nodera.protocol.session;

/**
 * Why a peer was not admitted, or why a frame was not acted on (Task 14 phases 3–4).
 *
 * <p>Every one of these used to be reported the same way: silence. A frame whose kind the receiver
 * did not know was dropped without a word, a peer whose engine could not agree with ours was
 * admitted and then threw from inside the region engine, and an incompatible build was discovered
 * when a liveness timer expired. All three look identical from the other end — a peer that has
 * stopped responding — which is the least useful diagnosis available.
 *
 * <p>A coded answer costs one frame and turns each of them into a statement.
 *
 * <p>Codes are explicit and permanent; append, never renumber.
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum RejectCode {

    /** No objection — used by {@code HelloAck} when the peer is admitted. */
    NONE(0),

    /** The kind is not one this build knows. The connection survives; the frame does not. */
    UNSUPPORTED_KIND(1),

    /** The frame's wire epoch is not this build's. Nothing below the header can be trusted. */
    UNSUPPORTED_EPOCH(2),

    /** The body did not parse as the kind claimed. */
    MALFORMED_BODY(3),

    /**
     * The peer's rules version differs, so the two engines would compute different state roots.
     * It is admitted as an observer rather than refused: content, discovery, relay and tunnel are
     * all version-independent, and refusing costs the network a seeder for no safety gain.
     */
    RULES_VERSION_MISMATCH(4),

    /** The peer's registry fingerprint differs — same reasoning as {@link #RULES_VERSION_MISMATCH}. */
    REGISTRY_FINGERPRINT_MISMATCH(5),

    /** The node id in the body is not the identity the transport authenticated. */
    IDENTITY_MISMATCH(6),

    /** The signature over the hello does not verify against the key it carries. */
    BAD_SIGNATURE(7),

    /** The peer is asking to join a different network. */
    WRONG_NETWORK(8),

    /** The sender is not authorised to send this kind on this connection. */
    NOT_AUTHORISED(9),

    /** A response arrived whose correlation id matches no outstanding request. */
    UNSOLICITED_RESPONSE(10),

    /** The receiver is shutting down or over capacity. Retry elsewhere. */
    UNAVAILABLE(11);

    private final int code;

    RejectCode(int code) {
        this.code = code;
    }

    /** The permanent wire code. Never {@code ordinal()}. */
    public int code() {
        return code;
    }

    /**
     * Resolve a wire code.
     *
     * @param code the code from the wire.
     * @return the matching constant, or {@link #UNAVAILABLE} for one this build does not know —
     *         an unrecognised reason is still a refusal, and treating it as success would be worse
     *         than treating it as a generic one.
     * @Thread-context any thread.
     */
    public static RejectCode fromCode(int code) {
        for (RejectCode c : values()) {
            if (c.code == code) {
                return c;
            }
        }
        return UNAVAILABLE;
    }
}
