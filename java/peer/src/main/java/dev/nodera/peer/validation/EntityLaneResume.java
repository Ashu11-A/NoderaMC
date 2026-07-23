package dev.nodera.peer.validation;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;

import java.util.Optional;

/**
 * Store-head resolution for entity-lane session reopen (issue #34 / L-50): a region's resumable
 * head is the highest-version snapshot the world store can prove — the latest quorum-committed
 * candidate ({@link WorldStoreVotePersistence}) or the latest server-authoritative external
 * commit ({@link WorldStoreExternalHeads}), whichever is newer. Empty means the region never
 * committed anything and the caller falls back to its derived genesis snapshot.
 *
 * <p>Thread-context: stateless; safe from any thread (delegates lock internally).
 */
public final class EntityLaneResume {

    private EntityLaneResume() {
    }

    /**
     * @param votes     the durable quorum-commit index; not null.
     * @param externals the durable external-commit head index; not null.
     * @param region    the region to resolve; not null.
     * @return the store-head snapshot to resume {@code region} from, or empty when nothing was
     *         ever committed for it.
     */
    public static Optional<RegionSnapshot> resumeHead(
            WorldStoreVotePersistence votes, WorldStoreExternalHeads externals, RegionId region) {
        if (votes == null || externals == null || region == null) {
            throw new IllegalArgumentException("resume arguments must not be null");
        }
        Optional<RegionSnapshot> quorum = votes.latestCommittedSnapshot(region);
        Optional<RegionSnapshot> external = externals.head(region);
        if (quorum.isEmpty()) {
            return external;
        }
        if (external.isEmpty()) {
            return quorum;
        }
        return external.get().version().value() >= quorum.get().version().value()
                ? external : quorum;
    }
}
