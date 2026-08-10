package dev.nodera.mod.common;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.peer.control.CompanionLink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Pulls one region's committed state from the always-on worker and hands it back decoded.
 *
 * <h2>The mirror of {@link RegionSeedSpool}</h2>
 *
 * <p>The seed spool exists because the process that commits a region is usually a game client, and
 * that is the process that goes away — so the snapshot is pushed to the worker, which stays. This is
 * the same handoff in the other direction: the worker owns the piece plane and does the transfer,
 * the game owns the server thread and does the writing, and a file on loopback is the boundary. A
 * region does not belong on a line protocol.
 *
 * <h2>Why it is off the game thread</h2>
 *
 * <p>A fetch is a network transfer with a no-progress budget measured in tens of seconds. Waiting
 * for it on the tick loop is the same freeze that block proposals used to cause, with the same
 * consequence — an expired keepalive, a kicked client, and a continuity lane correctly concluding
 * the host is gone.
 *
 * @Thread-context {@link #request} from any thread; the completion callback runs on the fetch
 *                 thread, so a caller that needs the server thread must hop itself.
 */
public final class RegionFetchSpool implements AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaRegionFetch");

    /** The transfer's no-progress budget, in seconds. */
    private static final long FETCH_TIMEOUT_SECONDS = 60L;

    /** How long a region stays un-refetchable after an attempt, successful or not. */
    private static final long RETRY_MILLIS = 30_000L;

    private final Supplier<String> worldId;
    private final Supplier<Path> spoolDir;
    private final Fetcher fetcher;
    private final Map<RegionId, Long> lastAttempt = new ConcurrentHashMap<>();
    private final ExecutorService fetches = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nodera-region-fetch");
        t.setDaemon(true);
        return t;
    });

    /** The worker call, injectable so the spool can be driven without a companion. */
    @FunctionalInterface
    public interface Fetcher {
        /**
         * @return {@code true} when the worker wrote the region to {@code dest}.
         */
        boolean fetch(String worldIdHex, RegionId region, Path dest, String haveIndexRootHex);
    }

    /**
     * @param worldId  the world currently open, or {@code null} when there is none.
     * @param spoolDir where fetched snapshots are staged.
     * @param fetcher  the worker call.
     */
    public RegionFetchSpool(Supplier<String> worldId, Supplier<Path> spoolDir, Fetcher fetcher) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.spoolDir = Objects.requireNonNull(spoolDir, "spoolDir");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    /** A spool wired to the companion worker and the mod's spool directory. */
    public static RegionFetchSpool companion() {
        return new RegionFetchSpool(
                () -> NoderaPeerService.get().currentWorldIdHex(),
                RegionSeedSpool::defaultSpoolDir,
                (world, region, dest, haveRoot) -> {
                    // Null-checked rather than dereferenced, for the same reason the seed spool is:
                    // a player can be validating regions before the companion gate has linked, and
                    // an NPE swallowed here would read as "the worker declined it" all session.
                    CompanionClient companion = CompanionLink.client();
                    return companion != null && companion.fetchRegion(
                            world, region.dimension().toString(),
                            region.regionX(), region.regionZ(),
                            dest, haveRoot, FETCH_TIMEOUT_SECONDS).isPresent();
                });
    }

    /**
     * Ask for a region, if one is not already on its way.
     *
     * <p>Throttled per region: a fetch is expensive and the condition that triggers one — a replica
     * that knows it is wrong — persists until the fetch lands, so an unthrottled caller would ask
     * once per tick forever.
     *
     * @param region        the region wanted.
     * @param haveIndexRoot the chunk-index root this node already holds, or {@code null}.
     * @param onFetched     called with the decoded snapshot on the fetch thread.
     * @return whether a fetch was started.
     * @Thread-context any thread.
     */
    public boolean request(RegionId region, String haveIndexRoot, Consumer<RegionSnapshot> onFetched) {
        Objects.requireNonNull(region, "region");
        String world = worldId.get();
        if (world == null || world.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long previous = lastAttempt.get(region);
        if (previous != null && now - previous < RETRY_MILLIS) {
            return false;
        }
        lastAttempt.put(region, now);
        fetches.execute(() -> run(world, region, haveIndexRoot, onFetched));
        return true;
    }

    private void run(String world, RegionId region, String haveIndexRoot,
                     Consumer<RegionSnapshot> onFetched) {
        Path dest = null;
        try {
            Path dir = spoolDir.get();
            Files.createDirectories(dir);
            // Named by the root being asked for, not by a version: two fetches of the same region at
            // different states are different files, and the same state twice is the same file.
            dest = dir.resolve("fetch-" + region.regionX() + "_" + region.regionZ() + "-"
                    + (haveIndexRoot == null ? "any" : haveIndexRoot.substring(
                            0, Math.min(12, haveIndexRoot.length()))) + ".bin");
            if (!fetcher.fetch(world, region, dest, haveIndexRoot)) {
                LOG.debug("worker had nothing for region {}", region);
                return;
            }
            byte[] encoded = Files.readAllBytes(dest);
            RegionSnapshot snapshot = RegionSnapshot.decode(
                    new dev.nodera.core.crypto.CanonicalReader(encoded));
            LOG.info("fetched region {} — {} column(s), {} byte(s)",
                    region, snapshot.chunks().size(), encoded.length);
            onFetched.accept(snapshot);
        } catch (Exception failure) {
            // Never fatal: a region that cannot be fetched is a region this node keeps failing to
            // validate, which is already the state it was in.
            LOG.warn("could not fetch region {}: {}", region, failure.toString());
        } finally {
            if (dest != null) {
                try {
                    Files.deleteIfExists(dest);
                } catch (java.io.IOException leftover) {
                    LOG.debug("left {} behind: {}", dest, leftover.toString());
                }
            }
        }
    }

    @Override
    public void close() {
        fetches.shutdownNow();
        try {
            fetches.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        lastAttempt.clear();
    }
}
