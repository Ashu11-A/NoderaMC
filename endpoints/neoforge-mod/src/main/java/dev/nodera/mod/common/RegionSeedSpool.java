package dev.nodera.mod.common;

import dev.nodera.peer.control.CompanionClient;
import dev.nodera.peer.control.CompanionLink;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pushes committed region snapshots to the always-on worker (worker L-41).
 *
 * <p>The seat that commits a region lives in this process, and this process is the one that ends
 * when a player closes the game. The world archive already makes that bargain for the save; this
 * makes it for the validated lane's canonical state, so a region stays fetchable from the worker
 * after the node that committed it is gone.
 *
 * <p><b>Three rules, and each of them exists because of the commit path it hangs off.</b>
 *
 * <ul>
 *   <li><b>Never on the caller's thread.</b> A commit callback runs on the lane's thread; splitting,
 *       hashing and a control round trip there would make consensus wait on disk and a socket. The
 *       work runs on one daemon thread with a bounded queue.</li>
 *   <li><b>Drop rather than queue without limit.</b> Regions commit continuously; if the worker is
 *       slow or gone, the useful thing to send later is the <i>newest</i> snapshot, not a backlog of
 *       stale ones. A full queue drops the offer and says so once.</li>
 *   <li><b>Throttle per region.</b> Seeding every commit would re-split the same region many times a
 *       second for no availability gain, since only the newest version is advertised anyway.</li>
 * </ul>
 *
 * @Thread-context {@link #offer} is safe from any thread and never blocks; the push runs on the
 *                 spool's own daemon thread.
 */
public final class RegionSeedSpool {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaRegionSeed");

    /** Minimum gap between two pushes of the same region. */
    static final long DEFAULT_THROTTLE_MILLIS = 30_000L;

    /** Bounded backlog: enough to absorb a burst, small enough that a stall is not a memory leak. */
    static final int QUEUE_DEPTH = 8;

    /**
     * How long {@link #close()} waits for an in-flight push before giving up on it. Bounded low
     * because close runs on the world-unload path: a worker that has wedged must cost the player a
     * blink on the way out, not a hang.
     */
    static final long CLOSE_TIMEOUT_MILLIS = 2_000L;

    private final Supplier<String> worldId;
    private final Pusher pusher;
    private final Supplier<Path> spoolDir;
    private final long throttleMillis;
    private final Map<RegionId, Long> lastPushedAt = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private volatile boolean warnedFull;

    /** What actually hands a snapshot file to the worker; separated so the spool is testable. */
    @FunctionalInterface
    public interface Pusher {
        /** @return {@code true} if the worker accepted it. */
        boolean push(String worldIdHex, Path snapshotFile);
    }

    /**
     * @param worldId        the world to file snapshots under; read per offer, and a blank answer
     *                       means "no world identity yet", which is a skip and not an error.
     * @param spoolDir       where snapshot files are written before the handoff.
     * @param pusher         the control-channel call.
     * @param throttleMillis minimum gap between two pushes of one region.
     */
    public RegionSeedSpool(Supplier<String> worldId, Supplier<Path> spoolDir, Pusher pusher,
                           long throttleMillis) {
        if (worldId == null || spoolDir == null || pusher == null) {
            throw new IllegalArgumentException("worldId, spoolDir and pusher must not be null");
        }
        this.worldId = worldId;
        this.spoolDir = spoolDir;
        this.pusher = pusher;
        this.throttleMillis = Math.max(0L, throttleMillis);
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_DEPTH), r -> {
                    Thread t = new Thread(r, "nodera-region-seed");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Where committed snapshots are staged before the handoff to the worker.
     *
     * <p>Beside the worker's own store rather than inside a save: a joiner has no save directory for
     * the world it is validating, and the file is a handoff that is deleted immediately after.
     */
    public static Path defaultSpoolDir() {
        return Path.of(System.getProperty("user.home"), ".nodera", "spool");
    }

    /** A spool wired to the companion worker and the default spool directory. */
    public static RegionSeedSpool companion() {
        return companion(RegionSeedSpool::defaultSpoolDir);
    }

    /** A spool wired to the companion worker and the mod's spool directory. */
    public static RegionSeedSpool companion(Supplier<Path> spoolDir) {
        return new RegionSeedSpool(
                () -> NoderaPeerService.get().currentWorldIdHex(),
                spoolDir,
                // Null-checked rather than dereferenced: a player can be validating regions before
                // the companion gate has linked, and an NPE here would be swallowed by the push
                // path's catch and read as "the worker declined it" for the rest of the session.
                (world, file) -> {
                    CompanionClient companion = CompanionLink.client();
                    return companion != null && companion.seedRegion(world, file).isPresent();
                },
                DEFAULT_THROTTLE_MILLIS);
    }

    /**
     * Offer one committed snapshot. Returns immediately; whether it is pushed is decided here and
     * the work happens elsewhere.
     *
     * @return whether the snapshot was accepted for pushing (not whether the worker took it).
     */
    public boolean offer(RegionSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String world = worldId.get();
        if (world == null || world.isBlank()) {
            // No world identity means nothing to file this under. A single-player world that was
            // never shared is the ordinary case, not a fault.
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastPushedAt.get(snapshot.region());
        if (last != null && now - last < throttleMillis) {
            return false;
        }
        lastPushedAt.put(snapshot.region(), now);
        try {
            executor.execute(() -> push(world, snapshot));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            // The backlog is full or the spool is closed. Forget the throttle stamp so the NEXT
            // commit of this region is eligible immediately — dropping one snapshot must not also
            // silence the region for a throttle interval.
            lastPushedAt.remove(snapshot.region());
            if (!warnedFull) {
                warnedFull = true;
                LOG.info("region seeding is behind (or stopped); snapshots will be skipped until "
                        + "it catches up — the world stays playable and the archive lane is "
                        + "unaffected");
            }
            return false;
        }
    }

    private void push(String world, RegionSnapshot snapshot) {
        Path file = null;
        try {
            Path dir = spoolDir.get();
            Files.createDirectories(dir);
            CanonicalWriter w = new CanonicalWriter();
            snapshot.encode(w);
            file = dir.resolve("region-" + snapshot.region().regionX() + "_"
                    + snapshot.region().regionZ() + "-v" + snapshot.version().value() + ".bin");
            Files.write(file, w.toBytes().toArray());
            if (pusher.push(world, file)) {
                warnedFull = false;
                LOG.debug("seeded region {} v{} to the worker",
                        snapshot.region(), snapshot.version().value());
            } else {
                LOG.debug("the worker did not accept region {} v{} (offline, or older than the "
                        + "validated-lane seeding verb)", snapshot.region(),
                        snapshot.version().value());
            }
        } catch (java.io.IOException | RuntimeException degraded) {
            // Availability, never correctness: a region that could not be handed over is a region
            // peers fetch from somebody else.
            LOG.debug("could not seed region {}: {}", snapshot.region(), degraded.toString());
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) {
                    // The spool is a handoff, not a store; a leftover file is harmless.
                }
            }
        }
    }

    /**
     * Stop pushing, and do not return while a push is still running. Idempotent.
     *
     * <p>The wait is the point. {@code shutdownNow} only interrupts; the push it interrupts is
     * <b>between</b> creating the spool directory, writing the snapshot file and deleting it again,
     * so a {@code close()} that returned straight away would leave a thread creating and deleting
     * files in a directory the caller believes it is now free to remove. That is what closes the
     * spool's own resources honestly, and it is why closing during a world unload does not race the
     * save directory being torn down underneath it.
     */
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.debug("region seeding did not stop within {}ms; its thread is a daemon and the "
                        + "work is availability-only", CLOSE_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
