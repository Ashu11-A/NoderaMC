package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;

/**
 * Thrown by {@link ServerRecompute} when running the SAME request twice on the SAME JVM yields two
 * different roots (Task 5). This is the cheap intra-JVM self-check: it catches nondeterminism
 * <b>inside one process</b> (an unstable hash map iteration, a stray clock/RNG read that slipped past
 * the {@code simulation} forbidden-API test) before it is ever mistaken for a cross-client
 * divergence. It signals an engine bug, not a client disagreement.
 *
 * @Thread-context thrown on the recompute thread.
 */
public final class NondeterminismException extends RuntimeException {

    private final transient RegionId region;

    /**
     * @param region the region whose two recomputes disagreed.
     * @param first  the first recompute's root.
     * @param second the second recompute's root.
     */
    public NondeterminismException(RegionId region, StateRoot first, StateRoot second) {
        super("engine nondeterminism in " + region + ": same request produced " + first
                + " then " + second + " on one JVM — this is an engine bug, not a client divergence");
        this.region = region;
    }

    /** @return the region whose recomputes disagreed. */
    public RegionId region() {
        return region;
    }
}
