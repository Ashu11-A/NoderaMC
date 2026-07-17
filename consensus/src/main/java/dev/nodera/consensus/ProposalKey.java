package dev.nodera.consensus;

import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

/**
 * Identifier of one in-flight proposal (Task 7 consensus/). A {@code (region, epoch, version)}
 * triple names exactly the proposal that a {@link dev.nodera.core.consensuscert.QuorumCertificate}
 * commits, exactly the {@link VoteCollector} bound, and exactly the key under which the
 * {@link EquivocationDetector} remembers each voter's last claimed root.
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

    /**
     * Convenience factory accepting a raw {@code long} version.
     *
     * @param region  the region the proposal commits into; not null.
     * @param epoch   the region epoch the proposal targets; not null.
     * @param version the base snapshot version; non-negative.
     * @return a new {@link ProposalKey}.
     */
    public static ProposalKey of(RegionId region, RegionEpoch epoch, long version) {
        return new ProposalKey(region, epoch, new SnapshotVersion(version));
    }
}
