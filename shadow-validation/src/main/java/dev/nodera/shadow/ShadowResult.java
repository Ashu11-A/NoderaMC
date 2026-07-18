package dev.nodera.shadow;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * A shadow worker's report for one executed batch (Task 5). <b>Root only, no delta</b> — Phase 1
 * uploads nothing that could be committed; the client's only output is the {@link StateRoot} it
 * computed, which the server compares against its own reference recompute.
 *
 * @param region           the region executed.
 * @param epoch            the epoch the batch ran under.
 * @param baseVersion      the snapshot version the batch started from.
 * @param resultingVersion the post-batch snapshot version ({@code baseVersion.next()}).
 * @param resultingRoot    the SHA-256 state root the worker computed; the consensus-shaped output.
 * @param clientNodeId     the worker that produced this result.
 * @param workerNanos      wall-clock cost measured <b>around</b> the engine call (the engine itself
 *                         never reads a clock); diagnostic only, never hashed.
 * @Thread-context immutable, any thread.
 */
public record ShadowResult(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        SnapshotVersion resultingVersion,
        StateRoot resultingRoot,
        NodeId clientNodeId,
        long workerNanos
) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any reference argument is null.
     */
    public ShadowResult {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (baseVersion == null) {
            throw new IllegalArgumentException("baseVersion must not be null");
        }
        if (resultingVersion == null) {
            throw new IllegalArgumentException("resultingVersion must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (clientNodeId == null) {
            throw new IllegalArgumentException("clientNodeId must not be null");
        }
    }
}
