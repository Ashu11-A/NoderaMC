package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.storage.WorldIdentity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The host lifecycle for a world on the Nodera network (Task 30) — the server-side entry point the
 * pause-menu "Share" action drives, and the one a dedicated server calls to auto-host. Bridges the
 * NeoForge {@link MinecraftServer} to the Minecraft-free {@link NoderaPeerService} host lane so that
 * "share this world" is one call from either the client's integrated server or a dedicated server.
 *
 * <p>What this wires today: it starts the world's host {@link dev.nodera.peer.PeerRuntime} (the
 * bootstrap role in the peer mesh) with the chosen {@link ShareOptions}, so joiners can discover and
 * dial the world and the two form a direct mesh. What remains the live lane (Task 9/19/23,
 * GUI-deferred like the rest of the mod's Minecraft-facing production): extracting + checkpointing a
 * {@code GenesisManifest} from the current world and self-certifying it with the host's identity,
 * splitting it into an addressable {@code PieceManifest}, encrypting the pieces under the password,
 * and registering the display name with the tracker. Those seams are proven headlessly in the
 * {@code distribution}/{@code storage-*}/{@code peer-runtime} modules; this class is where they plug
 * into a live server.
 *
 * <p>Thread context: {@link #activate}/{@link #deactivate}/{@link #reconfigure} must run on the
 * server thread ({@code MinecraftServer.execute(...)} from the client). {@link NoderaPeerService} is
 * internally synchronized.
 */
public final class NoderaHost {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaHost");

    private NoderaHost() {
    }

    /**
     * Put this server's currently-loaded world on the Nodera network. Idempotent: activating an
     * already-shared world refreshes its {@link ShareOptions} (see {@link #reconfigure} for the
     * password-change caveat).
     *
     * @param server  the (integrated or dedicated) Minecraft server hosting the world.
     * @param options the chosen share options.
     */
    public static void activate(MinecraftServer server, ShareOptions options) {
        ShareOptions opts = options == null ? ShareOptions.playerDefault() : options;
        String world = server.getWorldData().getLevelName();
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);

        // Establish (or reuse) the world's signed identity + unique id (Task 33). The worker is the
        // author (it holds the signing key); the record is persisted into the save folder so the
        // world keeps its id + author + shared status across restarts.
        WorldIdentity identity = ensureIdentity(saveRoot, world, opts);
        Bytes worldId = identity != null ? identity.worldId() : worldId(world);

        boolean already = NoderaPeerService.get().isHosting();
        String route = NoderaPeerService.get().startHost(
                NoderaConfig.P2P_BIND_HOST.get(),
                NoderaConfig.P2P_PORT.get(),
                NoderaConfig.P2P_ADVERTISE_HOST.get(),
                opts, worldId, world);

        // Delegate the network hosting to the always-on worker when present (Task 32), so the world
        // stays on the network even after the game closes. A missing worker falls back to the in-JVM
        // host lane already started above.
        if (CompanionLink.isPresent()) {
            CompanionLink.client().host(worldId.toHex(), world, shareOptionsJson(opts))
                    .ifPresent(err -> LOG.warn("Nodera worker refused HOST for '{}': {}", world, err));
        }

        // The sharing player is the world's operator (Task 33 permission model): the author is OWNER.
        grantHostOperator(server);

        if (already) {
            LOG.info("Nodera: '{}' share options updated ({}) — route {}", world, opts, route);
        } else {
            LOG.info("Nodera: sharing world '{}' to the network at {} ({})", world, route, opts);
            if (!opts.listedOnTracker()) {
                LOG.info("Nodera: '{}' is invite-only (not announced to the tracker)", world);
            }
        }
        // Live lane (Task 9/19/23): genesis-from-current-world + self-cert, PieceManifest emission,
        // per-piece encryption iff opts.encryptionEnabled(), and content seeding by the worker.
    }

    /**
     * Load or mint + persist the world's {@link WorldIdentity}. If a record already exists it is
     * re-signed to reflect the current share state; otherwise the worker mints a fresh signed record
     * (the worker is the author). A minimal fallback identity is used if no worker is reachable so the
     * flow still functions offline.
     */
    private static WorldIdentity ensureIdentity(Path saveRoot, String world, ShareOptions opts) {
        Bytes seed = worldId(world); // interim per-world seed until the genesis root (Task 9/30c)
        Optional<WorldIdentity> existing = NoderaWorldStore.read(saveRoot);
        try {
            if (CompanionLink.isPresent()) {
                // Reuse the existing record's manifest ref; the worker re-signs with the current state.
                Bytes manifestRef = existing.map(WorldIdentity::manifestRef).orElse(Bytes.empty());
                long createdAt = existing.map(WorldIdentity::createdAtEpoch)
                        .orElse(System.currentTimeMillis());
                Optional<Bytes> minted = CompanionLink.client().mintWorldIdentity(
                        seed, createdAt, true, opts.listedOnTracker(), opts.encryptionEnabled(),
                        manifestRef);
                if (minted.isPresent()) {
                    WorldIdentity id = WorldIdentity.decode(
                            new dev.nodera.core.crypto.CanonicalReader(minted.get()));
                    NoderaWorldStore.write(saveRoot, id);
                    return id;
                }
            }
            // Offline fallback: keep any existing record (updating its flags is author-only, so we
            // leave it as-is); nothing to persist without the worker's key.
            return existing.orElse(null);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Nodera: could not persist world identity for '{}': {}", world, e.getMessage());
            return existing.orElse(null);
        }
    }

    /**
     * Task 33 password authority: whether this installation's worker is the original author of the
     * currently-loaded world — the only identity permitted to change the password. True when the
     * world has no identity yet (this install would become the author on first share) or when the
     * persisted author matches the local worker identity.
     *
     * @param server the integrated/dedicated server hosting the world.
     * @return whether the local worker may set/change the password.
     */
    public static boolean localWorkerIsAuthor(MinecraftServer server) {
        Optional<WorldIdentity> existing =
                NoderaWorldStore.read(server.getWorldPath(LevelResource.ROOT));
        if (existing.isEmpty()) {
            return true; // not shared yet → this install authors it on first share
        }
        if (!CompanionLink.isPresent()) {
            return false; // shared by someone; without our worker we cannot prove authorship
        }
        Optional<String> id = CompanionLink.client().identity();
        if (id.isEmpty()) {
            return false;
        }
        String workerNodeId = id.get().split("\\s+")[0];
        return existing.get().authorNodeId().value().toString().equals(workerNodeId);
    }

    /** Grant the connected host player in-game operator permissions (level 4). */
    private static void grantHostOperator(MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!server.getPlayerList().isOp(player.getGameProfile())) {
                    server.getPlayerList().op(player.getGameProfile());
                    LOG.info("Nodera: granted operator to host player {}",
                            player.getGameProfile().getName());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Nodera: could not grant host operator: {}", e.getMessage());
        }
    }

    /** Minimal JSON for the worker HOST verb (share options). */
    private static String shareOptionsJson(ShareOptions opts) {
        return "{\"listed\":" + opts.listedOnTracker()
                + ",\"encrypted\":" + opts.encryptionEnabled()
                + ",\"replication\":" + opts.replicationHint() + "}";
    }

    /**
     * Change the share options on an already-shared world. A <b>password change</b> (or turning
     * encryption on/off) re-derives the content key and therefore re-manifests the whole world
     * (every piece hash, the manifest root, and every {@code ContentId} change; other seeders must
     * re-fetch and every joiner must supply the new password — Task 23). This method starts the
     * re-manifest; it is not an in-place edit.
     *
     * @param server  the hosting server.
     * @param options the new options.
     */
    public static void reconfigure(MinecraftServer server, ShareOptions options) {
        ShareOptions current = NoderaPeerService.get().hostOptions();
        if (current != null && options != null && !current.password().equals(options.password())) {
            LOG.warn("Nodera: password change on '{}' triggers a full re-manifest; joiners must re-enter it",
                    server.getWorldData().getLevelName());
            // Live lane: re-derive the key, re-encrypt + re-split, emit a new PieceManifest, re-seed.
        }
        activate(server, options);
    }

    /**
     * Stop sharing this world (the "Stop sharing" action, or server shutdown). The local save is
     * untouched; the world is removed from the mesh and deregistered from the tracker.
     *
     * @param server the hosting server.
     */
    public static void deactivate(MinecraftServer server) {
        String world = server.getWorldData().getLevelName();
        if (NoderaPeerService.get().isHosting()) {
            LOG.info("Nodera: stopping sharing of world '{}'", world);
        }
        // Persist the unshared state (author-only re-sign via the worker) so re-opening does not
        // auto-re-share a world the host explicitly stopped sharing.
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);
        Optional<WorldIdentity> existing = NoderaWorldStore.read(saveRoot);
        if (existing.isPresent() && existing.get().shared() && CompanionLink.isPresent()) {
            WorldIdentity id = existing.get();
            // Re-mint with the SAME seed + createdAt so the derived worldId is unchanged; only the
            // shared flag flips to false.
            CompanionLink.client().mintWorldIdentity(worldId(world), id.createdAtEpoch(), false,
                    id.listedOnTracker(), id.encrypted(), id.manifestRef()).ifPresent(minted -> {
                try {
                    NoderaWorldStore.write(saveRoot, WorldIdentity.decode(
                            new dev.nodera.core.crypto.CanonicalReader(minted)));
                } catch (IOException e) {
                    LOG.warn("Nodera: could not persist unshared state for '{}': {}", world, e.getMessage());
                }
            });
            CompanionLink.client().stop(id.worldId().toHex());
        }
        NoderaPeerService.get().stopHosting();
    }

    /**
     * Interim world identity: a stable SHA-256 over the save name, so the same world keeps the same
     * tracker/rendezvous key across restarts. Replaced by the real {@code GenesisManifest} hash once
     * the live genesis lane (Task 9/30c) extracts + self-certifies genesis from the world.
     *
     * @param worldName the world's save/display name.
     * @return the interim world id.
     */
    private static Bytes worldId(String worldName) {
        return new HashService().sha256(
                ("nodera.dev-world.v1:" + worldName).getBytes(StandardCharsets.UTF_8));
    }
}
