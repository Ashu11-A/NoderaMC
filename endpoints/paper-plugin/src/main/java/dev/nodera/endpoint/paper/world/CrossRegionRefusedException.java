package dev.nodera.endpoint.paper.world;

import dev.nodera.core.region.RegionId;

/**
 * The named error a cross-Folia-region delta is refused with (server task 4 stage 1, L-64).
 *
 * <p>"Named" is the load-bearing word. The register's rule for this category is that a refusal
 * fails closed <b>with the fix named in the message</b> (docs/server/LIMITATIONS.md §B reading
 * guide), and the caller's correct response — discard and resync — is not something a caller can
 * infer from a generic {@link IllegalStateException}. So the type is distinguishable by
 * {@code catch}, and the message names the two regions, the transfer, the fact that nothing was
 * written, and what to do instead.
 *
 * @Thread-context immutable, any thread.
 */
public final class CrossRegionRefusedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * The stable token every message starts with.
     *
     * <p>Live suites and callers grep for this rather than for prose, so the explanation can be
     * improved without turning three suites into timeouts (the "live suites assert stale log
     * strings" incident).
     */
    public static final String CODE = "NODERA-XREGION-REFUSED";

    private final transient RegionId source;
    private final transient RegionId target;
    private final long transferId;

    CrossRegionRefusedException(long transferId, RegionId source, RegionId target, String because) {
        super(message(transferId, source, target, because));
        this.transferId = transferId;
        this.source = source;
        this.target = target;
    }

    /** @return the region the delta would have taken state out of. */
    public RegionId source() {
        return source;
    }

    /** @return the region the delta would have put state into. */
    public RegionId target() {
        return target;
    }

    /** @return the transfer id that was refused; nothing under it was journalled. */
    public long transferId() {
        return transferId;
    }

    private static String message(
            long transferId, RegionId source, RegionId target, String because) {
        return CODE + ": transfer " + transferId + " spans " + source + " and " + target
                + ", which " + because + ", so there is no joint critical section to commit in."
                + " NOTHING was written and no transfer stage was journalled — both regions are"
                + " exactly as they were. Fix: discard this delta and resync the two regions from"
                + " their certified heads, then retry the interaction. The joint-transfer commit"
                + " that makes this span atomic instead of a retry is server task 4 stage 2"
                + " (docs/server/LIMITATIONS.md L-64); until it is wired here, a span wider than"
                + " one Folia section is refused rather than raced.";
    }
}
