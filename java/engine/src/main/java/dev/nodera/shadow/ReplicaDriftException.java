package dev.nodera.shadow;

import dev.nodera.core.state.NBlockPos;

/**
 * Thrown when a {@link RegionDelta}'s compare-and-set guard fails against a local replica (Task 5):
 * the section a {@link dev.nodera.core.state.BlockMutation} expected to overwrite does not hold the
 * {@code expectedPreviousStateId}. The replica has drifted from the chain the delta was computed
 * against — the caller must not guess; it re-snapshots (client → {@code ResyncRequest}).
 *
 * @Thread-context thrown on the applying thread.
 */
public final class ReplicaDriftException extends RuntimeException {

    private final transient NBlockPos pos;
    private final int expected;
    private final int actual;

    /**
     * @param pos      the position whose section failed the CAS.
     * @param expected the {@code expectedPreviousStateId} the delta carried.
     * @param actual   the state id actually present in the local replica.
     */
    public ReplicaDriftException(NBlockPos pos, int expected, int actual) {
        super("replica drift at " + pos + ": delta expected previous state " + expected
                + " but replica holds " + actual + " — re-snapshot required");
        this.pos = pos;
        this.expected = expected;
        this.actual = actual;
    }

    /** @return the position whose section failed the CAS. */
    public NBlockPos pos() {
        return pos;
    }

    /** @return the {@code expectedPreviousStateId} the delta carried. */
    public int expected() {
        return expected;
    }

    /** @return the state id actually present in the local replica. */
    public int actual() {
        return actual;
    }
}
