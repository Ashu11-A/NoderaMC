package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

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
        Bytes worldId = worldId(world);
        boolean already = NoderaPeerService.get().isHosting();
        String route = NoderaPeerService.get().startHost(
                NoderaConfig.P2P_BIND_HOST.get(),
                NoderaConfig.P2P_PORT.get(),
                NoderaConfig.P2P_ADVERTISE_HOST.get(),
                opts, worldId, world);
        if (already) {
            LOG.info("Nodera: '{}' share options updated ({}) — route {}", world, opts, route);
        } else {
            LOG.info("Nodera: sharing world '{}' to the network at {} ({})", world, route, opts);
            if (!opts.listedOnTracker()) {
                LOG.info("Nodera: '{}' is invite-only (not announced to the tracker)", world);
            }
        }
        // Live lane (Task 9/19/23): genesis-from-current-world + self-cert, PieceManifest emission,
        // per-piece encryption iff opts.encryptionEnabled(), and tracker registration of the display
        // name go here. Deferred with the mod's other Minecraft-facing production, proven headlessly.
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
        if (NoderaPeerService.get().isHosting()) {
            LOG.info("Nodera: stopping sharing of world '{}'", server.getWorldData().getLevelName());
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
