package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.WorldRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The on-disk record of which worlds this peer shares and which it supports — the thing that makes
 * the always-on worker survive its own restart.
 *
 * <h2>The defect this exists to close</h2>
 *
 * <p>{@link WorldHostingService} held its world set in a {@code ConcurrentHashMap} and nothing else.
 * A worker restart therefore left the node announcing nothing, advertising none of the pieces still
 * sitting in its content store, and reporting an empty world list to the companion app — which is
 * why the app appeared to "not retrieve data from the worker". It was retrieving the worker's state
 * faithfully; the worker had forgotten.
 *
 * <p>Reproduced before the fix: host a world, restart the worker with the same identity and archive
 * directory, ask for {@code NODERA-STATE}, and {@code connected_worlds} comes back {@code []}.
 *
 * <h2>Write-through, not write-behind</h2>
 *
 * <p>Every mutation saves immediately. The set changes when a player shares or stops sharing a world
 * and when the replication lane adopts one — a handful of events per session, each of which is
 * exactly the event a crash must not lose. Batching them would trade nothing measurable for the
 * chance of losing the newest one.
 *
 * @Thread-context safe from any thread; each mutation performs blocking IO on the caller's thread
 *                 under this store's monitor, so saves are serialised and never interleave.
 */
public final class WorldRegistryStore {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private final Path file;

    /** worldIdHex → row. Insertion-ordered so a reloaded registry reads in share order. */
    private final Map<String, WorldRegistry.Entry> entries = new LinkedHashMap<>();

    /**
     * @param file the registry file (e.g. {@code ~/.nodera/worlds.dat}); its parent is created on
     *             the first save.
     */
    public WorldRegistryStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        load();
    }

    /** @return the file this store reads and writes. */
    public Path file() {
        return file;
    }

    /**
     * Read the registry from disk into memory. Called once at construction.
     *
     * <p>A registry that cannot be decoded is reported and treated as empty rather than thrown: a
     * worker that refuses to start because one file went bad is worse for the network than a worker
     * that starts having forgotten what it was seeding, and the file is left in place so an operator
     * can look at it.
     */
    private synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            WorldRegistry stored = WorldRegistry.decode(new CanonicalReader(Files.readAllBytes(file)));
            for (WorldRegistry.Entry entry : stored.entries()) {
                entries.put(entry.worldIdHex().toLowerCase(Locale.ROOT), entry);
            }
            LOG.info("Restored {} world(s) from {}", entries.size(), file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read world registry " + file, e);
        } catch (RuntimeException malformed) {
            LOG.warn("World registry {} is unreadable ({}) — starting with an empty registry; the "
                    + "file has been left in place", file, malformed.getMessage());
        }
    }

    /** @return every world this peer shares or supports, in the order they were added. */
    public synchronized List<WorldRegistry.Entry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * @param worldIdHex the world.
     * @return that world's row, if this peer keeps it.
     */
    public synchronized Optional<WorldRegistry.Entry> find(String worldIdHex) {
        return Optional.ofNullable(entries.get(key(worldIdHex)));
    }

    /**
     * Record (or update) a world this peer keeps on the network.
     *
     * <p>An existing row keeps its {@code addedAt} — the date a world entered the network from this
     * node does not change because it was re-shared — and keeps ownership already recorded, so a
     * re-host cannot silently drop the administrator binding.
     *
     * <p>Hosting is the stronger claim: a world already hosted is never demoted to "supporting" by a
     * later seed call, mirroring {@link WorldHostingService}'s own rule.
     *
     * @param worldIdHex      the world.
     * @param name            its display name.
     * @param supporting      whether this node merely keeps the bytes alive.
     * @param ownershipRecord the canonical {@code WorldOwnership} bytes when this node administers
     *                        the world, or {@link Bytes#empty()} to leave any existing binding as
     *                        it is.
     * @return the stored row.
     */
    public synchronized WorldRegistry.Entry put(String worldIdHex, String name, boolean supporting,
                                                Bytes ownershipRecord) {
        String key = key(worldIdHex);
        long now = System.currentTimeMillis();
        WorldRegistry.Entry existing = entries.get(key);
        Bytes ownership = ownershipRecord == null || ownershipRecord.isEmpty()
                ? (existing == null ? Bytes.empty() : existing.ownershipRecord())
                : ownershipRecord;
        // Hosting wins: once a row says this node hosts the world, a later seed call cannot demote
        // it, and the reverse (seeding → hosting) is allowed.
        boolean nowSupporting = supporting && (existing == null || existing.supporting());
        WorldRegistry.Entry row = new WorldRegistry.Entry(
                Bytes.fromHex(key),
                name == null || name.isBlank() ? (existing == null ? key : existing.name()) : name,
                nowSupporting,
                existing == null ? now : existing.addedAtEpochMillis(),
                existing == null ? now : existing.updatedAtEpochMillis(),
                ownership);
        entries.put(key, row);
        save();
        return row;
    }

    /**
     * Note that a world's content changed here, bumping its "last updated".
     *
     * @param worldIdHex the world.
     * @param updatedAtEpochMillis when the content changed.
     */
    public synchronized void touch(String worldIdHex, long updatedAtEpochMillis) {
        String key = key(worldIdHex);
        WorldRegistry.Entry existing = entries.get(key);
        if (existing == null || existing.updatedAtEpochMillis() == updatedAtEpochMillis) {
            return;
        }
        entries.put(key, new WorldRegistry.Entry(existing.worldId(), existing.name(),
                existing.supporting(), existing.addedAtEpochMillis(), updatedAtEpochMillis,
                existing.ownershipRecord()));
        save();
    }

    /**
     * Forget a world entirely — this peer neither shares nor supports it any more.
     *
     * <p>The world's <b>key</b> is untouched. Stopping a share is a statement about what this node
     * serves, not a renunciation of authority over the world, and a re-share must not mint a second
     * administrator key for a world that already has one.
     *
     * @param worldIdHex the world.
     * @return whether a row was removed.
     */
    public synchronized boolean remove(String worldIdHex) {
        if (entries.remove(key(worldIdHex)) == null) {
            return false;
        }
        save();
        return true;
    }

    private void save() {
        CanonicalWriter w = new CanonicalWriter(512);
        new WorldRegistry(new ArrayList<>(entries.values())).encode(w);
        LocalFiles.writeAtomically(file, w.toByteArray());
    }

    private static String key(String worldIdHex) {
        Objects.requireNonNull(worldIdHex, "worldIdHex");
        String trimmed = worldIdHex.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("missing worldId");
        }
        return trimmed;
    }
}
