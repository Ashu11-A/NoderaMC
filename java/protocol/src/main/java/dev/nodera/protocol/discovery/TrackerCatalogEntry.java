package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.WorldHealth;

import java.util.Objects;

/**
 * One world's summary in a tracker {@link TrackerCatalogResponse} directory listing — enough for the
 * multiplayer "Worlds" tab to show and let a player pick it, without the full peer/seeder sets of a
 * {@link TrackerResponse} (those are fetched per-world on selection via {@link TrackerQuery}).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param genesisHash                   the world's genesis hash (the id a client then queries/joins).
 * @param worldName                     the host-registered display name.
 * @param worldPlayerCount              players currently online in-world.
 * @param storedChunks                  distinct pieces held network-wide.
 * @param reliabilityBps                mean network reliability, basis points.
 * @param health                        the tracker's {@link WorldHealth} classification.
 * @param retentionDeadlineEpochMillis  the decommission-countdown deadline, or {@code 0}.
 */
public record TrackerCatalogEntry(
        Bytes genesisHash,
        String worldName,
        long worldPlayerCount,
        long storedChunks,
        int reliabilityBps,
        WorldHealth health,
        long retentionDeadlineEpochMillis) {

    public TrackerCatalogEntry {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(health, "health");
        if (worldPlayerCount < 0) {
            throw new IllegalArgumentException("worldPlayerCount must be non-negative");
        }
        if (storedChunks < 0) {
            throw new IllegalArgumentException("storedChunks must be non-negative");
        }
    }
}
