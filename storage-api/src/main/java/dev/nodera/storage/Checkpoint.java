package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * A finalised checkpoint for a region (Plan §3.12 / Task 9): a version + root, a {@link ContentId}
 * pointer to the full snapshot blob in the {@link ContentStore}, and a reference to the certificate
 * that finalised it. A new peer syncs by fetching the latest checkpoint's snapshot by content hash,
 * then replaying certified events forward from it — so checkpoints bound replay cost.
 *
 * @param region          the region.
 * @param version         the snapshot version at the checkpoint.
 * @param root            the state root at the checkpoint.
 * @param snapshotContent content id of the full snapshot blob.
 * @param tick            the checkpoint tick.
 * @param lastEventId     the id of the last event folded into this checkpoint (replay resumes at
 *                        {@code lastEventId + 1}); {@code -1} for the genesis checkpoint.
 * @param certificateRef  content-hash reference to the finalising certificate ({@link Bytes#empty()}
 *                        for the genesis checkpoint).
 * @Thread-context immutable, any thread.
 */
public record Checkpoint(
        RegionId region,
        SnapshotVersion version,
        StateRoot root,
        ContentId snapshotContent,
        long tick,
        long lastEventId,
        Bytes certificateRef
) {
    public Checkpoint {
        if (region == null || version == null || root == null || snapshotContent == null
                || certificateRef == null) {
            throw new IllegalArgumentException("no field of Checkpoint may be null");
        }
    }
}
