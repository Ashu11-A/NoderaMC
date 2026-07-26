package dev.nodera.mod.common;

import dev.nodera.distribution.WorldArchive;
import dev.nodera.storage.WorldIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mod's host half of the world-continuity lane: pack the currently-loaded save into the
 * canonical {@link WorldArchive} blob and hand it to the always-on worker over the
 * {@code NODERA-SEED} control verb. From that moment the world's bytes live on the peer network —
 * the worker seeds them, other peers replicate them, and a joiner can re-open the world with the
 * host (player <i>and</i> machine) gone.
 *
 * <p>Seeded <b>continuously</b> (issue #43): at share time, then on a periodic streaming cadence
 * while the world is hosted ({@code archive.streamIntervalTicks}), and once more on server stop
 * (the final flush). Continuous streaming bounds data loss to one interval — player data, broken
 * blocks, and status survive any exit, crash included. The final flush is <b>deadline-bounded</b>
 * ({@code archive.finalFlushTimeoutSeconds}): a slow or hung worker can no longer wedge the
 * "Saving World" screen. All seeding failures log and never break sharing or shutdown.
 *
 * <p>Thread-context: {@link #seedAsync}/{@link #streamTick} from the server thread (the save
 * flush must run there); {@link #seedNow} from any thread with a quiescent save (server stopped).
 */
public final class WorldArchiver {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaHost");

    /** One in-flight seeding at a time; a second request while packing is coalesced away. */
    private static final AtomicBoolean SEEDING = new AtomicBoolean();

    /** Server tick of the last streaming seed (issue #43 continuous streaming cadence). */
    private static final java.util.concurrent.atomic.AtomicLong LAST_STREAM_TICK =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

    /**
     * The observable state of the current/most-recent seeding run — the client's exit-screen
     * progress overlay reads it (issue #43: "sending latest data to the Nodera network").
     */
    public enum SeedPhase { IDLE, PACKING, UPLOADING, DONE, FAILED }

    private static volatile SeedPhase PHASE = SeedPhase.IDLE;

    /** @return the current seeding phase (client overlay poll; any thread). */
    public static SeedPhase phase() {
        return PHASE;
    }

    private WorldArchiver() {
    }

    /**
     * Pure streaming-cadence decision (MC-free, testable): seed when at least
     * {@code intervalTicks} have elapsed since the last streaming seed. First call always fires
     * (bounds loss from the session start too).
     *
     * @param currentTick   the server's current tick.
     * @param lastSeedTick  tick of the previous streaming seed ({@link Long#MIN_VALUE} = never).
     * @param intervalTicks the configured cadence (≤ 0 disables streaming).
     * @return whether a streaming seed is due.
     */
    static boolean streamDue(long currentTick, long lastSeedTick, int intervalTicks) {
        if (intervalTicks <= 0) {
            return false;
        }
        return lastSeedTick == Long.MIN_VALUE || currentTick - lastSeedTick >= intervalTicks;
    }

    /**
     * The continuous-streaming hook (issue #43): called every server tick while hosting; seeds
     * the archive on the configured cadence so the network copy is never more than one interval
     * behind the live world. Coalesced with any in-flight seed.
     *
     * @param server the hosting server.
     * @Thread-context server thread.
     */
    public static void streamTick(MinecraftServer server) {
        int interval = NoderaConfig.ARCHIVE_STREAM_INTERVAL_TICKS.get();
        long tick = server.getTickCount();
        long last = LAST_STREAM_TICK.get();
        if (!streamDue(tick, last, interval) || SEEDING.get()) {
            return;
        }
        if (!LAST_STREAM_TICK.compareAndSet(last, tick)) {
            return;
        }
        seedAsync(server);
    }

    /**
     * Flush the world and seed its archive in the background. No-op without a linked worker, when
     * disabled by config, or when the world has no persisted identity yet.
     *
     * @param server the (integrated or dedicated) server hosting the world.
     * @Thread-context server thread (the save flush must run there).
     */
    public static void seedAsync(MinecraftServer server) {
        if (!CompanionLink.isPresent() || !NoderaConfig.ARCHIVE_SEED_ON_SHARE.get()) {
            return;
        }
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);
        Optional<WorldIdentity> identity = NoderaWorldStore.read(saveRoot);
        if (identity.isEmpty()) {
            return;
        }
        // Save so the packed files reflect the live world rather than the last autosave — but do
        // NOT wait for the full chunk flush. `flush = true` blocks the caller until every dirty
        // chunk is written, and this runs on the SERVER TICK THREAD, so its cost scales with how
        // much of the world is resident. Once the validated lane began pinning its regions with
        // `nodera_delegated` tickets, a far teleport left a large area resident while a new one
        // generated, and one streaming save took **60 seconds** inside a single tick — long enough
        // for vanilla's watchdog to declare the server crashed and force a shutdown (issue #70,
        // seen in the `farlands` suite).
        //
        // Not waiting is sound because this lane is explicitly best-effort: the exact copy is
        // `seedNow`'s final flush, whose own timeout message already states the contract — "the
        // streaming lane already seeded a copy at most one interval old". A periodic snapshot that
        // trails by an autosave is the documented behaviour; a server the watchdog kills is not.
        server.saveEverything(true, false, true);
        String worldIdHex = identity.get().worldId().toHex();
        if (!SEEDING.compareAndSet(false, true)) {
            return;
        }
        Thread.ofPlatform().name("nodera-archive-seed").daemon().start(() -> {
            try {
                seed(saveRoot, worldIdHex);
            } finally {
                SEEDING.set(false);
            }
        });
    }

    /**
     * Seed the archive on the server-stopped final flush — <b>deadline-bounded</b> (issue #43):
     * the pack + upload run on a worker thread and this method waits at most
     * {@code archive.finalFlushTimeoutSeconds}. A slow or hung worker abandons the flush (the
     * streaming lane already seeded a copy at most one interval old) instead of wedging the
     * "Saving World" screen forever.
     *
     * @param saveRoot the save folder.
     * @Thread-context any thread; the caller guarantees no concurrent save writes.
     */
    public static void seedNow(Path saveRoot) {
        if (!CompanionLink.isPresent() || !NoderaConfig.ARCHIVE_SEED_ON_SHARE.get()) {
            return;
        }
        Optional<WorldIdentity> identity = NoderaWorldStore.read(saveRoot);
        if (identity.isEmpty() || !identity.get().shared()) {
            return;
        }
        String worldIdHex = identity.get().worldId().toHex();
        int timeoutSeconds = NoderaConfig.ARCHIVE_FINAL_FLUSH_TIMEOUT_SECONDS.get();
        Thread flush = Thread.ofPlatform().name("nodera-archive-final-flush").daemon()
                .start(() -> seed(saveRoot, worldIdHex));
        try {
            flush.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(Math.max(1, timeoutSeconds)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (flush.isAlive()) {
            LOG.warn("Nodera: final archive flush exceeded {} s — abandoning (the streaming lane "
                    + "already seeded a copy at most one interval old); shutdown continues", timeoutSeconds);
        }
    }

    /**
     * Pack the save into {@code <saveRoot>/nodera/spool/<worldIdHex-prefix>.nar} (atomic temp+move)
     * and return its path — the freshly-packed plaintext archive blob a re-key (issue #37) hands to
     * the worker. MC-free (pure {@link WorldArchive#packSaveDirectory} + file IO).
     *
     * @param saveRoot   the save folder.
     * @param worldIdHex the world id (hex).
     * @return the spooled archive file path.
     * @Thread-context server thread (call after a save flush so the blob is current).
     */
    public static Path packToSpool(Path saveRoot, String worldIdHex) throws java.io.IOException {
        byte[] blob = WorldArchive.packSaveDirectory(saveRoot);
        Path spool = saveRoot.resolve("nodera/spool");
        Files.createDirectories(spool);
        Path archiveFile = spool.resolve(worldIdHex.substring(0, Math.min(12, worldIdHex.length()))
                + ".nar");
        Path tmp = archiveFile.resolveSibling(archiveFile.getFileName() + ".tmp");
        Files.write(tmp, blob);
        Files.move(tmp, archiveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return archiveFile;
    }

    /**
     * Record the version this save last SEEDED to the network (issue #43 freshness guard):
     * {@code <saveRoot>/nodera/seeded-version} holds the archive version the network copy carries.
     * The continuity rehost compares the fetched network version against a local save's recorded
     * version so a STALE network archive can never silently overwrite a newer local save.
     * SEED replies are {@code "<manifestRootHex> <version> <pieceCount>"}.
     */
    private static void recordSeededVersion(Path saveRoot, String seedReply) {
        try {
            String[] parts = seedReply.trim().split("\\s+");
            if (parts.length >= 2) {
                Path marker = saveRoot.resolve("nodera").resolve("seeded-version");
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, parts[1]);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Nodera: could not record seeded version: {}", e.toString());
        }
    }

    /**
     * The archive version this local save last seeded to the network, or {@code -1} when unknown
     * (never seeded / marker unreadable). Consumed by the continuity freshness guard.
     *
     * @param saveRoot the save folder.
     * @return the recorded seeded version, or -1.
     */
    public static long seededVersion(Path saveRoot) {
        try {
            Path marker = saveRoot.resolve("nodera").resolve("seeded-version");
            if (!Files.isRegularFile(marker)) {
                return -1;
            }
            return Long.parseLong(Files.readString(marker).trim());
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Seed a password-protected world's archive as <b>ciphertext</b> (L-59).
     *
     * <p>Found live: a world shared with a password had its content re-encrypted at share and at
     * every re-key, and then the ordinary streaming lane published a fresh <b>plaintext</b> archive
     * a minute later — a newer version than the ciphertext, so the password protected nothing on
     * the content plane. The refresh has to go through the same re-encrypting path the re-key uses:
     * fresh salt, new ciphertext, new manifest root, re-signed identity, re-announce, and the
     * superseded versions evicted.
     *
     * <p>There is deliberately <b>no plaintext fallback</b>. If the encrypted refresh fails, the
     * network keeps serving the last good ciphertext and the log says so; publishing the save in
     * the clear because encryption failed would be the failure this method exists to prevent.
     *
     * @param saveRoot   the save folder.
     * @param worldIdHex the world id (hex).
     * @param password   the world password (non-blank).
     */
    private static void seedEncrypted(Path saveRoot, String worldIdHex, String password) {
        Optional<WorldIdentity> identity = NoderaWorldStore.read(saveRoot);
        if (identity.isEmpty()) {
            LOG.warn("Nodera: cannot seed the encrypted archive — the world has no signed identity");
            return;
        }
        try {
            long startedAt = System.nanoTime();
            PHASE = SeedPhase.PACKING;
            Path archiveFile = packToSpool(saveRoot, worldIdHex);
            PHASE = SeedPhase.UPLOADING;
            String passwordB64 = java.util.Base64.getEncoder().encodeToString(
                    password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Optional<CompanionClient.Rekeyed> sealed = CompanionLink.client().rekey(
                    worldIdHex, archiveFile, passwordB64,
                    NoderaHost.encodeIdentity(identity.get()));
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            if (sealed.isEmpty()) {
                PHASE = SeedPhase.FAILED;
                LOG.warn("Nodera: the worker did not accept the ENCRYPTED world archive — the "
                        + "network keeps the previous ciphertext (nothing is published in the clear)");
                return;
            }
            PHASE = SeedPhase.DONE;
            // The identity was re-signed with the new manifestRef; persist it or the next refresh
            // hands the worker a stale one and the re-key is refused.
            try {
                NoderaWorldStore.write(saveRoot, WorldIdentity.decode(
                        new dev.nodera.core.crypto.CanonicalReader(sealed.get().identity())));
            } catch (IOException e) {
                LOG.warn("Nodera: could not persist the re-signed identity: {}", e.toString());
            }
            if (sealed.get().version() >= 0) {
                recordSeededVersion(saveRoot, "encrypted " + sealed.get().version());
            }
            LOG.info("Nodera: ENCRYPTED world archive seeded to the worker ({} bytes in {} ms — v{})",
                    Files.size(archiveFile), millis, sealed.get().version());
        } catch (IOException | RuntimeException e) {
            PHASE = SeedPhase.FAILED;
            LOG.warn("Nodera: encrypted world-archive seeding failed: {}", e.toString());
        }
    }

    private static void seed(Path saveRoot, String worldIdHex) {
        // A password-protected world never publishes its save in the clear (L-59).
        ShareOptions opts = NoderaPeerService.get().hostOptions();
        if (opts != null && opts.encryptionEnabled()) {
            seedEncrypted(saveRoot, worldIdHex, opts.password());
            return;
        }
        try {
            long startedAt = System.nanoTime();
            PHASE = SeedPhase.PACKING;
            Path archiveFile = packToSpool(saveRoot, worldIdHex);
            PHASE = SeedPhase.UPLOADING;
            Optional<String> seeded = CompanionLink.client().seedArchive(worldIdHex, archiveFile);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            if (seeded.isPresent()) {
                PHASE = SeedPhase.DONE;
                recordSeededVersion(saveRoot, seeded.get());
                LOG.info("Nodera: world archive seeded to the worker ({} bytes in {} ms — {})",
                        Files.size(archiveFile), millis, seeded.get());
            } else {
                PHASE = SeedPhase.FAILED;
                LOG.warn("Nodera: worker did not accept the world archive (worker offline or "
                        + "predates the continuity lane); the world is listed but not yet durable");
            }
        } catch (IOException | RuntimeException e) {
            PHASE = SeedPhase.FAILED;
            LOG.warn("Nodera: world-archive seeding failed: {}", e.toString());
        }
    }
}
