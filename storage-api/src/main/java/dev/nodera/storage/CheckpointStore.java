package dev.nodera.storage;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

import java.util.List;
import java.util.Optional;

/**
 * Per-region checkpoint index (Plan §3.12 / Task 9). Checkpoints are retained outside the full peer
 * (Invariant 5); {@link #latest} bounds the replay a new or returning peer must perform.
 *
 * @Thread-context implementations document their own thread-safety.
 */
public interface CheckpointStore {

    /** Record a checkpoint (must have a strictly greater version than the current latest). */
    void put(Checkpoint checkpoint);

    /** @return the highest-version checkpoint for {@code region}, or empty if none. */
    Optional<Checkpoint> latest(RegionId region);

    /** @return the checkpoint at exactly {@code version}, or empty. */
    Optional<Checkpoint> at(RegionId region, SnapshotVersion version);

    /** @return every checkpoint for {@code region}, in version order. */
    List<Checkpoint> all(RegionId region);
}
