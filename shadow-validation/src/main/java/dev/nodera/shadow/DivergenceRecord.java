package dev.nodera.shadow;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * A recorded cross-client divergence (Task 5): a shadow worker's root did not match the server's
 * reference root for the same {@code (region, epoch, baseVersion)}. Each divergence is the seed of a
 * committed {@code ReplayFixtureTest} — the Phase 1 gate is <b>zero unexplained divergences</b>, so
 * every one found in development becomes a reproducing regression test and is fixed.
 *
 * @param region       the region that diverged.
 * @param epoch        the epoch the batch ran under.
 * @param baseVersion  the base version the batch started from.
 * @param expectedRoot the server's reference root (truth).
 * @param gotRoot      the client's reported root.
 * @param clientNodeId the client that diverged.
 * @Thread-context immutable, any thread.
 */
public record DivergenceRecord(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        StateRoot expectedRoot,
        StateRoot gotRoot,
        NodeId clientNodeId
) {
    public DivergenceRecord {
        if (region == null || epoch == null || baseVersion == null
                || expectedRoot == null || gotRoot == null || clientNodeId == null) {
            throw new IllegalArgumentException("no field of DivergenceRecord may be null");
        }
    }
}
