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
 * <p>Seeded twice per session: once at share time (the world becomes durable the moment it is
 * shared) and once on server stop (the final flush — the archive that carries everything the
 * session changed). Both runs are asynchronous; a seeding failure never breaks sharing or
 * shutdown, it only logs (the world stays playable, merely less durable).
 *
 * <p>Thread-context: {@link #seedAsync} from the server thread (it snapshots paths, then packs on
 * a background thread); {@link #seedNow} from any thread with a quiescent save (server stopped).
 */
public final class WorldArchiver {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaHost");

    /** One in-flight seeding at a time; a second request while packing is coalesced away. */
    private static final AtomicBoolean SEEDING = new AtomicBoolean();

    private WorldArchiver() {
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
        // Flush chunks/level data so the packed files reflect the live world, not the last autosave.
        server.saveEverything(true, true, true);
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
     * Seed the archive synchronously — the server-stopped final flush, when the save is quiescent
     * and there is no tick loop left to stay off of.
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
        seed(saveRoot, identity.get().worldId().toHex());
    }

    private static void seed(Path saveRoot, String worldIdHex) {
        try {
            long startedAt = System.nanoTime();
            byte[] blob = WorldArchive.packSaveDirectory(saveRoot);
            Path spool = saveRoot.resolve("nodera/spool");
            Files.createDirectories(spool);
            Path archiveFile = spool.resolve(worldIdHex.substring(0, Math.min(12, worldIdHex.length()))
                    + ".nar");
            Path tmp = archiveFile.resolveSibling(archiveFile.getFileName() + ".tmp");
            Files.write(tmp, blob);
            Files.move(tmp, archiveFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Optional<String> seeded = CompanionLink.client().seedArchive(worldIdHex, archiveFile);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            if (seeded.isPresent()) {
                LOG.info("Nodera: world archive seeded to the worker ({} bytes in {} ms — {})",
                        blob.length, millis, seeded.get());
            } else {
                LOG.warn("Nodera: worker did not accept the world archive (worker offline or "
                        + "predates the continuity lane); the world is listed but not yet durable");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Nodera: world-archive seeding failed: {}", e.toString());
        }
    }
}
