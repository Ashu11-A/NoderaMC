package dev.nodera.consensus;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

/**
 * Identifier of one in-flight proposal (Task 7 consensus/). A {@code (region, epoch, version)}
 * triple names exactly the proposal that a {@link dev.nodera.core.consensuscert.QuorumCertificate}
 * commits, and exactly the {@link VoteCollector} bound.
 *
 * <p>Value semantics: two {@code ProposalKey}s are equal iff their region, epoch and version are
 * equal. Used as a {@link java.util.Map} key and a Caffeine cache key, so it must be (and is)
 * immutable with value-based equality inherited from {@link Record}.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record ProposalKey(RegionId region, RegionEpoch epoch, SnapshotVersion version) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any component is null.
     */
    public ProposalKey {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
    }
}
