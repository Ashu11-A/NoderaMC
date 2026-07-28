package dev.nodera.protocol.session;

/**
 * What a peer is allowed to do on a session, decided at the handshake
 * (Task 14 phase 4, {@code Plan.7} D2).
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum SessionRole {

    /** Full membership: meshes, seeds, relays, tunnels, and may hold committee seats. */
    ADMITTED(1),

    /**
     * Everything except a committee seat.
     *
     * <p>Reached when the peer's rules version or registry fingerprint differs from ours. It is a
     * real membership, not a consolation prize: an observer is a seeder, a relay, a tunnel endpoint
     * and a commit recipient, all of which are version-independent. The one thing it cannot do is
     * vote on a state root, because it would compute a different one.
     */
    OBSERVER(2),

    /** Not admitted at all — wrong network, failed signature, or a mismatched identity claim. */
    REFUSED(3);

    private final int code;

    SessionRole(int code) {
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
     * @return the matching constant, or {@link #REFUSED} for a code this build does not know. An
     *         unrecognised role is treated as the most restrictive one: a peer must never grant
     *         itself membership on the strength of a word it cannot read.
     * @Thread-context any thread.
     */
    public static SessionRole fromCode(int code) {
        for (SessionRole r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        return REFUSED;
    }
}
