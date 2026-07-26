package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.PlayerView;
import dev.nodera.peer.validation.EntityLaneBootstrap;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.peer.discovery.PersistentIdentityStore;
import dev.nodera.mod.server.entity.LiveEntityLaneSession;
import dev.nodera.mod.server.entity.MinecraftEntityAdapters;
import dev.nodera.storage.GenesisManifest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static LiveEntityLaneSession entityLane;

    /** The hosted world's permission set (L-49): drives the mesh admission gate; grants apply here. */
    private static volatile dev.nodera.storage.WorldPermissions hostedPermissions;

    /** The hosted world's save root — where grants are persisted (issue #36 F5). */
    private static volatile Path hostedSaveRoot;

    /**
     * Rough on-disk size of a save, for the telemetry size bucket.
     *
     * <p>Best-effort and bounded: it walks the save tree, gives up on any I/O error, and the result
     * is bucketed to whole megabytes before it leaves the machine. A precise byte count is a
     * fingerprint; an order of magnitude is the fact worth having.
     */
    private static long saveSizeBytes(Path saveRoot) {
        try (java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(saveRoot, 4)) {
            return tree.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> {
                try {
                    return java.nio.file.Files.size(path);
                } catch (java.io.IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (java.io.IOException | RuntimeException e) {
            return 0L;
        }
    }

    /** @return the hosted world's permission set, or null when not hosting. */
    public static dev.nodera.storage.WorldPermissions hostedPermissions() {
        return hostedPermissions;
    }

    /**
     * Ingest a signed grant into the hosted permission set and, if accepted, persist the whole set
     * (issue #36 F5). Used by the {@code /nodera op|deop} + GRANT control path (Phase 5).
     *
     * @param grant the signed grant.
     * @return whether it was accepted (and therefore persisted).
     */
    public static synchronized boolean applyGrant(dev.nodera.storage.WorldPermissionGrant grant) {
        dev.nodera.storage.WorldPermissions perms = hostedPermissions;
        if (perms == null || !perms.apply(grant)) {
            return false;
        }
        Path root = hostedSaveRoot;
        if (root != null) {
            try {
                dev.nodera.storage.WorldPermissionStore.write(root, perms.snapshot());
            } catch (java.io.IOException e) {
                LOG.warn("Nodera: failed to persist permission grants: {}", e.toString());
            }
        }
        return true;
    }

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

        // Task 30c: extract + self-certify the world's genesis on first share (persisted; reused on
        // every later activation). The signer is the host's persistent node identity — genesis stays
        // the one self-signed trust root (L-20; multi-party genesis is Task 16).
        dev.nodera.core.identity.NodeIdentity hostIdentity = new PersistentIdentityStore(
                saveRoot.resolve("nodera/server-identity.bin")).loadOrGenerate();
        Bytes genesisSeed;
        try {
            genesisSeed = WorldGenesisService.ensure(server, hostIdentity)
                    .manifest().genesisRoot().hash();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Nodera: genesis certification failed for '{}' ({}); using interim world id",
                    world, e.toString());
            genesisSeed = worldId(world);
        }

        // Establish (or reuse) the world's signed identity + unique id (Task 33). The worker is the
        // author (it holds the signing key); the record is persisted into the save folder so the
        // world keeps its id + author + shared status across restarts. A fresh identity derives its
        // worldId from the certified genesis root (30c); an existing record keeps its id.
        WorldIdentity identity = ensureIdentity(saveRoot, world, opts, genesisSeed);
        Bytes worldId = identity != null ? identity.worldId() : genesisSeed;

        boolean already = NoderaPeerService.get().isHosting();
        String route;
        try {
            route = NoderaPeerService.get().startHost(
                    NoderaConfig.P2P_BIND_HOST.get(),
                    NoderaConfig.P2P_PORT.get(),
                    NoderaConfig.P2P_ADVERTISE_HOST.get(),
                    opts, worldId, world, hostIdentity);
        } catch (RuntimeException e) {
            // Issue #39 defense-in-depth: startHost degrades internally (returns null on a bind
            // failure), but never let any transport failure escape activate into the server tick
            // loop. The game server + worker lane below still run so the world stays playable.
            LOG.warn("Nodera: host peer start threw for '{}' ({}); continuing in vanilla-only mode",
                    world, e.getMessage());
            route = null;
        }

        // Open the actual Minecraft game server to the network — the piece that makes a shared
        // world JOINABLE, not merely listed. An integrated server is published on a real TCP port
        // (the "Open to LAN" mechanism pointed at the network); a dedicated server already listens.
        // The endpoint travels to joiners as an "mc/host:port" route claim in the tracker announce.
        String mcRoute = openGameServer(server);
        NoderaPeerService.get().setGameRoute(mcRoute);

        // Delegate the network hosting to the always-on worker when present (Task 32), so the world
        // stays on the network even after the game closes. A missing worker falls back to the in-JVM
        // host lane already started above.
        notifyWorker(server, worldId, world, opts, mcRoute);

        // The sharing player is the world's operator (Task 33 permission model): the author is OWNER.
        grantHostOperator(server);

        // L-49 admission: the world's permission model now gates the P2P mesh — a BANNED peer is
        // refused at join and filtered from gossip ingest (PeerRuntime.JoinAdmission). The
        // permission set starts author-only; grants applied to it take effect on the same gate.
        if (identity != null) {
            dev.nodera.storage.WorldPermissions permissions =
                    new dev.nodera.storage.WorldPermissions(worldId, identity.authorNodeId(),
                            identity.authorPublicKey());
            // F5: reload persisted grants — apply() re-verifies every signature, so a tampered
            // nodera-permissions.dat simply drops the forged grants instead of granting authority.
            int loaded = 0;
            for (dev.nodera.storage.WorldPermissionGrant g
                    : dev.nodera.storage.WorldPermissionStore.read(saveRoot)) {
                if (permissions.apply(g)) {
                    loaded++;
                }
            }
            hostedPermissions = permissions;
            hostedSaveRoot = saveRoot;
            if (loaded > 0) {
                LOG.info("Nodera: reloaded {} persisted permission grant(s)", loaded);
            }
            NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
            if (host != null) {
                host.runtime().setJoinAdmission(permissions::canJoin);
            }
        }

        // L-52: the password stops being a property of the archive alone. While a password-protected
        // world is hosted, every joining connection must prove it knows the password in the
        // configuration phase, before a player is created. Arming derives a memory-hard key, so it
        // runs off the server thread; a plaintext world disarms (nothing to prove).
        armJoinGate(worldId, opts, world);

        // Continuity lane: the world becomes durable the moment it is shared — pack the save and
        // seed the archive to the always-on worker (final flush happens again on server stop).
        WorldArchiver.seedAsync(server);

        // Telemetry: a share happened, whether it was password-protected, and roughly how big the
        // world is. No name, no id, no seed — the event type cannot carry them.
        ModTelemetry.worldShared(opts.password() != null && !opts.password().isBlank(),
                saveSizeBytes(saveRoot), already ? "rehost" : "existing_world");

        if (route == null) {
            // Issue #39: the P2P mesh did not come up, but openGameServer + notifyWorker above still
            // ran, so the world is playable over direct/LAN — just not on the Nodera network.
            LOG.warn("Nodera: '{}' running in vanilla-only mode — the P2P mesh did not start; "
                    + "direct/LAN joins still work. Retry Share once the port is free.", world);
        } else if (already) {
            LOG.info("Nodera: '{}' share options updated ({}) — route {}", world, opts, route);
        } else {
            LOG.info("Nodera: sharing world '{}' to the network at {} ({})", world, route, opts);
            if (!opts.listedOnTracker()) {
                LOG.info("Nodera: '{}' is invite-only (not announced to the tracker)", world);
            }
        }
        // Live lane (Task 9/19/23): genesis-from-current-world + self-cert, PieceManifest emission,
        // per-piece encryption iff opts.encryptionEnabled(), and content seeding by the worker.

        // Task 12 validated entity lane: opt-in until soak-proven; a bootstrap failure must never
        // break sharing itself.
        if (NoderaConfig.ENTITY_LANE_AUTO.get()) {
            try {
                activateEntityLaneFromWorld(server);
            } catch (RuntimeException e) {
                LOG.warn("Nodera: entity lane bootstrap failed for '{}': {}", world, e.getMessage());
            }
        }
    }

    /**
     * Load or mint + persist the world's {@link WorldIdentity}. If a record already exists it is
     * re-signed to reflect the current share state; otherwise the worker mints a fresh signed record
     * (the worker is the author). A minimal fallback identity is used if no worker is reachable so the
     * flow still functions offline.
     */
    private static WorldIdentity ensureIdentity(Path saveRoot, String world, ShareOptions opts,
                                                Bytes seed) {
        Optional<WorldIdentity> existing = NoderaWorldStore.read(saveRoot);
        // A world minted before 30c derived its id from the interim name seed; re-minting with the
        // genesis seed would silently change its worldId (breaking tracker/joiner continuity). Match
        // the existing derivation and keep whichever seed produced the persisted id.
        Bytes mintSeed = existing.map(id -> {
            Bytes interim = worldId(world);
            return WorldIdentity.deriveWorldId(interim, id.authorPublicKey(), id.createdAtEpoch())
                    .equals(id.worldId()) ? interim : seed;
        }).orElse(seed);
        try {
            // A rehosting peer is not the author: re-minting with the local worker's key would
            // derive a DIFFERENT worldId (author is part of the derivation) and silently fork the
            // world's identity on the tracker. Keep the author's record; only the author re-signs.
            if (existing.isPresent() && CompanionLink.isPresent()) {
                String workerNodeId = CompanionLink.client().identity()
                        .map(id -> id.split("\\s+")[0]).orElse("");
                if (!existing.get().authorNodeId().value().toString().equals(workerNodeId)) {
                    return existing.get();
                }
            }
            if (CompanionLink.isPresent()) {
                // Reuse the existing record's manifest ref; the worker re-signs with the current state.
                Bytes manifestRef = existing.map(WorldIdentity::manifestRef).orElse(Bytes.empty());
                long createdAt = existing.map(WorldIdentity::createdAtEpoch)
                        .orElse(System.currentTimeMillis());
                Optional<Bytes> minted = CompanionLink.client().mintWorldIdentity(
                        mintSeed, createdAt, true, opts.listedOnTracker(), opts.encryptionEnabled(),
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
     * Arm (or disarm) the live-join password gate for the world being shared (L-52).
     *
     * <p>Off the server thread: arming derives the gate key with the same memory-hard KDF the
     * content plane uses, which is deliberately expensive and has no business inside a tick. Until
     * it completes the gate stays disarmed, so the only risk of the asynchrony is a join in the
     * first fraction of a second after a share — and that joiner is the sharer's own client.
     *
     * @param worldId the world's id.
     * @param opts    the share options carrying the password (blank ⇒ disarm).
     * @param world   the world's display name, for the log line.
     */
    private static void armJoinGate(Bytes worldId, ShareOptions opts, String world) {
        if (!opts.encryptionEnabled()) {
            HostJoinGate.get().disarm();
            return;
        }
        char[] password = opts.password().toCharArray();
        Thread.ofPlatform().name("nodera-join-gate-arm").daemon().start(() -> {
            try {
                HostJoinGate.get().arm(worldId, password);
                LOG.info("Nodera: '{}' is password-gated — joiners must supply the world password",
                        world);
            } catch (RuntimeException e) {
                // Fail CLOSED: a gate that could not be armed must not leave a password-protected
                // world open to anyone who resolves its route, so the world seals instead.
                HostJoinGate.get().seal();
                LOG.error("Nodera: could not arm the join password gate for '{}' ({}) — the world "
                        + "is NOT joinable until you re-share it", world, e.toString());
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        });
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

    /**
     * Op the world author only (issue #36 F3 fix — no longer blanket-ops every online player). The
     * integrated-server owner is the author when {@link #localWorkerIsAuthor} holds; a dedicated
     * server ops nobody automatically (operator authority flows through signed grants + the
     * {@link dev.nodera.mod.server.OperatorBridge}). Anyone else's op comes from a key-checked role.
     */
    private static void grantHostOperator(MinecraftServer server) {
        if (server.isDedicatedServer() || !localWorkerIsAuthor(server)) {
            return;
        }
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (server.isSingleplayerOwner(player.getGameProfile())) {
                    dev.nodera.mod.server.OperatorBridge.get().opAuthor(server, player);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Nodera: could not grant author operator: {}", e.getMessage());
        }
    }

    /**
     * Op the integrated-server owner as author on login (covers an auto-re-share that ran before the
     * owner joined, when {@link #grantHostOperator} saw no players). Safe no-op on a dedicated server
     * or when this install did not author the world.
     */
    public static void syncAuthorOnLogin(MinecraftServer server, ServerPlayer player) {
        if (server.isDedicatedServer() || hostedPermissions == null || !localWorkerIsAuthor(server)) {
            return;
        }
        if (server.isSingleplayerOwner(player.getGameProfile())) {
            dev.nodera.mod.server.OperatorBridge.get().opAuthor(server, player);
        }
    }

    /** The published game port while this JVM hosts an open game server, or {@code -1}. */
    private static volatile int publishedGamePort = -1;

    /**
     * Whether the local client player is fully constructed. {@code IntegratedServer.publishServer}
     * dereferences {@code minecraft.player} (permission re-grant), so publishing before the client
     * has its {@code LocalPlayer} NPEs the server tick loop (crash 2026-07-22_21.10.26). Written by
     * the client dist ({@code ClientBootstrap} on LoggingIn/LoggingOut); always {@code false} on a
     * dedicated server, which never publishes.
     */
    private static volatile boolean clientPlayerReady;

    /** An integrated share is waiting for the client player before it can open the game port. */
    private static volatile boolean gamePublishPending;

    /** Client dist callback: the local player exists (LoggingIn) / is gone (LoggingOut). */
    public static void setClientPlayerReady(boolean ready) {
        clientPlayerReady = ready;
    }

    /**
     * Ensure the Minecraft server itself accepts network connections and return its advertised
     * {@code host:port}, or {@code null} when it cannot be opened (yet). A dedicated server
     * already listens on its configured port. An integrated server is published once per world
     * session (vanilla cannot un-publish until the world closes) — and only once the local client
     * player is ready, because vanilla's publish path re-grants that player's permissions; until
     * then the publish is parked ({@link #gamePublishPending}) and {@link #tickGamePublish}
     * completes it.
     */
    private static String openGameServer(MinecraftServer server) {
        String advertise = NoderaPeerService.resolveHost(NoderaConfig.P2P_ADVERTISE_HOST.get());
        if (server.isDedicatedServer()) {
            return advertise + ":" + server.getPort();
        }
        if (publishedGamePort > 0 && server.isPublished()) {
            return advertise + ":" + publishedGamePort;
        }
        if (!clientPlayerReady || server.getPlayerList().getPlayerCount() == 0) {
            gamePublishPending = true;
            LOG.info("Nodera: game-server publish deferred until the host player is in the world");
            return null;
        }
        gamePublishPending = false;
        int port = NoderaConfig.GAME_PORT.get();
        if (port == 0) {
            port = net.minecraft.util.HttpUtil.getAvailablePort();
        }
        if (!NoderaConfig.HOST_ONLINE_AUTH.get()) {
            // Dev/e2e lane: offline dev accounts cannot pass Mojang session auth, and the test
            // series joins with them. A real host keeps the default (true).
            server.setUsesAuthentication(false);
            LOG.warn("Nodera: host.onlineAuth=false — joiners are NOT session-authenticated");
        }
        try {
            // null GameType keeps the world's own mode; cheats follow the world's own setting.
            if (server.publishServer(null, server.getWorldData().isAllowCommands(), port)) {
                publishedGamePort = port;
                LOG.info("Nodera: game server open for joiners on port {}", port);
                return advertise + ":" + port;
            }
        } catch (RuntimeException e) {
            // Do NOT retry: vanilla may have bound the listener before throwing, and a second
            // publish would double-bind the port. Re-opening the world recovers cleanly.
            LOG.warn("Nodera: game-server publish failed ({}) — world is listed but not joinable; "
                    + "re-open the world or use the pause-menu Share to retry", e.toString());
            return null;
        }
        LOG.warn("Nodera: could not open the game server on port {} — world is listed but not joinable",
                port);
        return null;
    }

    /**
     * Complete a parked integrated-server publish once the host player is fully in the world.
     * Called every server tick post ({@code ServerBootstrap}); a cheap flag check when idle.
     */
    public static void tickGamePublish(MinecraftServer server) {
        if (!gamePublishPending || server.isDedicatedServer()
                || !NoderaPeerService.get().isHosting()
                || !clientPlayerReady || server.getPlayerList().getPlayerCount() == 0) {
            return;
        }
        String mcRoute = openGameServer(server);
        gamePublishPending = false;
        if (mcRoute != null) {
            NoderaPeerService.get().setGameRoute(mcRoute);
            refreshWorkerPresence(server);
        }
    }

    /**
     * Hand (or refresh) the world at the always-on worker: share options, the live game endpoint
     * (or its absence, once the game closes), and the current player count. Safe no-op without a
     * linked worker.
     */
    private static void notifyWorker(MinecraftServer server, Bytes worldId, String world,
                                     ShareOptions opts, String mcRoute) {
        if (!CompanionLink.isPresent()) {
            return;
        }
        int players = server.getPlayerList() == null ? 0 : server.getPlayerList().getPlayerCount();
        CompanionLink.client().host(worldId.toHex(), world, shareOptionsJson(opts, mcRoute, players))
                .ifPresent(err -> LOG.warn("Nodera worker refused HOST for '{}': {}", world, err));
    }

    /**
     * Refresh the worker's view of the currently-hosted world (player count, game endpoint).
     * Called on player join/leave; cheap and safe on the server thread.
     */
    public static void refreshWorkerPresence(MinecraftServer server) {
        if (!NoderaPeerService.get().isHosting()) {
            return;
        }
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);
        Optional<WorldIdentity> id = NoderaWorldStore.read(saveRoot);
        if (id.isEmpty()) {
            return;
        }
        ShareOptions opts = NoderaPeerService.get().hostOptions();
        notifyWorker(server, id.get().worldId(), server.getWorldData().getLevelName(),
                opts == null ? ShareOptions.playerDefault() : opts,
                NoderaPeerService.get().gameRoute());
    }

    /** Minimal JSON for the worker HOST verb (share options + live session state). */
    private static String shareOptionsJson(ShareOptions opts, String mcRoute, int players) {
        return "{\"listed\":" + opts.listedOnTracker()
                + ",\"encrypted\":" + opts.encryptionEnabled()
                + ",\"replication\":" + opts.replicationHint()
                + (mcRoute == null || mcRoute.isBlank() ? "" : ",\"mc\":\"" + mcRoute + "\"")
                + ",\"players\":" + Math.max(0, players) + "}";
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
            // Issue #37 / L-51: a password change is a full re-key — re-pack the save, re-encrypt the
            // archive under the new password (fresh salt → new key → new ciphertext → new manifestRoot
            // + bumped version), re-sign the WorldIdentity with the new manifestRef, persist it, and
            // re-announce (the worker does that in the verb). The plaintext save on the author's own
            // machine is the source — no old password is needed (nothing is escrowed). Surfaced
            // honestly to the sharer; NEVER silent success.
            Path saveRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            WorldIdentity currentId = NoderaWorldStore.read(saveRoot).orElse(null);
            if (currentId == null) {
                LOG.warn("Nodera: password re-key for '{}' skipped — no persisted identity",
                        server.getWorldData().getLevelName());
                activate(server, options);
                return;
            }
            String worldIdHex = currentId.worldId().toHex();
            String newPwdB64 = java.util.Base64.getEncoder().encodeToString(
                    options.password().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.util.Optional<CompanionClient.Rekeyed> reKeyed = java.util.Optional.empty();
            if (dev.nodera.mod.common.CompanionLink.isPresent()) {
                try {
                    // Flush so the packed blob reflects the live world, then hand it to the worker.
                    server.saveEverything(true, true, true);
                    Path spool = WorldArchiver.packToSpool(saveRoot, worldIdHex);
                    reKeyed = dev.nodera.mod.common.CompanionLink.client()
                            .rekey(worldIdHex, spool, newPwdB64, encodeIdentity(currentId));
                } catch (RuntimeException | java.io.IOException e) {
                    LOG.warn("Nodera: password re-key call failed for '{}': {}",
                            server.getWorldData().getLevelName(), e.toString());
                }
            }
            if (reKeyed.isPresent()) {
                // Persist the re-signed identity (carrying the new manifestRef). ensureIdentity would
                // reuse this manifestRef on a later activate, but we do NOT re-activate here: a full
                // activate re-seeds a PLAINTEXT archive (live encryption at share is L-39's domain),
                // which would supersede the re-key's encrypted manifest as newest and let joiners
                // bypass the password. The host peer is already up; the worker re-announced.
                WorldIdentity reSigned = WorldIdentity.decode(
                        new dev.nodera.core.crypto.CanonicalReader(reKeyed.get().identity()));
                try {
                    NoderaWorldStore.write(saveRoot, reSigned);
                } catch (java.io.IOException e) {
                    LOG.warn("Nodera: could not persist re-keyed identity for '{}': {}",
                            server.getWorldData().getLevelName(), e.toString());
                }
                LOG.info("Nodera: password re-key complete for '{}' (new manifest root {})",
                        server.getWorldData().getLevelName(), reSigned.manifestRef().toShortHex(8));
                // The new password is in force from here: the reported options must say so (the next
                // change compares against them), and the live-join gate must test the NEW password —
                // otherwise "joiners must use the new password" would be true of the archive and
                // false of the game server, which is the exact split L-52 closed.
                NoderaPeerService.get().updateHostOptions(options);
                armJoinGate(currentId.worldId(), options, server.getWorldData().getLevelName());
                tellHost(server, "Nodera: password changed — joiners must use the new password.");
                return;
            }
            // Failure: surface honestly and re-share with the UNCHANGED password (never silent success).
            LOG.warn("Nodera: password re-key FAILED for '{}': worker unavailable or declined",
                    server.getWorldData().getLevelName());
            tellHost(server, "Nodera: password change FAILED — the world keeps its current password.");
            activate(server, options.withPassword(current.password()));
            return;
        }
        activate(server, options);
    }

    /** Canonical-encode a {@link WorldIdentity} to bytes (for the re-key verb). */
    static dev.nodera.core.Bytes encodeIdentity(WorldIdentity identity) {
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        identity.encode(w);
        return w.toBytes();
    }

    /**
     * Send a chat line to the SHARER (the integrated-server owner), best-effort. Issue #47: this
     * used to broadcast to every online player — a password-change notice reached joiners who
     * had no business seeing host-lane messages. Target only the involved player; fall back to
     * broadcast solely on a dedicated server (no owner concept there).
     */
    private static void tellHost(MinecraftServer server, String message) {
        try {
            net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(message);
            boolean delivered = false;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (server.isSingleplayerOwner(p.getGameProfile())) {
                    p.sendSystemMessage(line);
                    delivered = true;
                    break;
                }
            }
            if (!delivered && server.isDedicatedServer()) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    p.sendSystemMessage(line);
                }
            }
        } catch (RuntimeException ignored) {
            // chat is best-effort; never let it derail a reconfigure
        }
    }

    /**
     * Bootstrap the live entity lane from the currently-loaded world (the Task 5b → Task 12 wiring):
     * derives the interim {@link GenesisManifest} from the world seed, plans decentralized region
     * ownership from the host player's field of view ({@code EntityLaneBootstrap}), and activates
     * the locally-primary regions. Solo-host MVP: every connected player rides the host's node
     * identity until the join flow assigns remote peers their own ({@code peers} therefore empty —
     * a one-member committee whose quorum is the host itself).
     *
     * <p>Called on the server thread, which only captures the field-of-view plan (player position,
     * view distance, level, seed — cheap, thread-bound reads). The heavy activation — RocksDB open,
     * journal recovery, session composition — runs on a dedicated bootstrap thread so a login never
     * stalls the tick loop; commits already marshal back through the session's world executor.
     *
     * @param server the (integrated or dedicated) server hosting the world.
     * @return whether the bootstrap was started (false when the host peer is not running or no
     *         player is present to anchor a field-of-view plan).
     */
    /**
     * Tell every resident peer which regions it validates, by sending it a
     * {@link dev.nodera.protocol.assignment.RegionAssigned} per seat it holds in the new plan.
     *
     * <p>This is the outbound half of "a world is served by peers, not by whoever is logged in".
     * The plan itself is derived identically by everyone (it is a pure function of the player
     * views, the tick, and the resident set), but a headless peer has no Minecraft connection to
     * receive the client lane-plan payload over — so its seats go out on the P2P transport it is
     * already a session member of.
     *
     * <p>Best-effort per peer: one unreachable resident must not stop the others from being seated,
     * and it must never stop the local lane from opening. A resident that misses an assignment
     * simply does not vote, and the committee falls back to the members that did answer.
     *
     * @return the number of seats successfully dispatched.
     */
    private static int assignResidentSeats(
            NoderaPeerService.HostContext host,
            List<EntityLaneBootstrap.PlannedRegion> plan,
            Map<NodeId, dev.nodera.protocol.membership.PeerEntry> residents) {
        if (residents.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (EntityLaneBootstrap.PlannedRegion planned : plan) {
            for (NodeId validator : planned.lease().validators()) {
                dev.nodera.protocol.membership.PeerEntry entry = residents.get(validator);
                if (entry == null) {
                    continue; // a player's node, not a resident — it gets the client payload
                }
                // Committee order is primary-first: the worker rebuilds the lease from it.
                List<NodeId> committee = new ArrayList<>();
                committee.add(planned.lease().primary());
                committee.addAll(planned.lease().validators());
                try {
                    host.transport().send(
                            dev.nodera.transport.PeerAddress.of(entry.nodeId(), entry.route()),
                            dev.nodera.protocol.codec.MessageCodec.encode(
                                    new dev.nodera.protocol.assignment.RegionAssigned(
                                            planned.region(), planned.lease().epoch(),
                                            dev.nodera.core.region.RegionReplicaRole.VALIDATOR,
                                            dev.nodera.core.state.SnapshotVersion.INITIAL,
                                            planned.lease().expiresAtTick(), committee)));
                    sent++;
                } catch (RuntimeException unreachable) {
                    LOG.debug("Nodera: could not seat resident {} on {}: {}",
                            entry.nodeId(), planned.region(), unreachable.toString());
                }
            }
        }
        return sent;
    }

    public static boolean activateEntityLaneFromWorld(MinecraftServer server) {
        NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
        if (host == null || server.getPlayerList().getPlayers().isEmpty()) {
            return false;
        }
        // No-host ownership: EVERY connected player with an announced peer node is an owner in
        // the FOV plan under its own identity; the session server's peer is just one more member
        // (it signs for players whose clients have not announced a node yet — transitional).
        NodeId local = host.identity().nodeId();
        lastPlanKey = planKey(server);
        Map<NodeId, PlayerView> views = new java.util.LinkedHashMap<>();
        Map<NodeId, PlayerNodeRegistry.PlayerNode> memberNodes = new java.util.LinkedHashMap<>();
        List<NoderaLanePlanPayload.Member> members = new ArrayList<>();
        ServerLevel anchorLevel = null;
        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(p.getUUID());
            NodeId memberNode = node != null ? node.nodeId() : local;
            ServerLevel level = p.serverLevel();
            if (anchorLevel == null) {
                anchorLevel = level;
            }
            PlayerView view = PlayerView.fromBlock(
                    MinecraftEntityAdapters.dimension(level),
                    p.blockPosition().getX(), p.blockPosition().getZ(), viewDistance);
            views.putIfAbsent(memberNode, view);
            if (node != null) {
                memberNodes.putIfAbsent(memberNode, node);
            }
            String route = node != null ? node.route()
                    : nullToEmpty(NoderaPeerService.get().hostRoute());
            String keyB64 = java.util.Base64.getEncoder().encodeToString(
                    (node != null ? node.publicKey() : host.identity().publicKeyBytes()).toArray());
            members.add(new NoderaLanePlanPayload.Member(
                    memberNode.value().toString(), keyB64, route, p.getUUID().toString(),
                    MinecraftEntityAdapters.dimension(level).namespace(),
                    MinecraftEntityAdapters.dimension(level).path(),
                    p.blockPosition().getX(), p.blockPosition().getZ(), viewDistance));
        }
        // Resident validators: session members that hold no player view — the always-on headless
        // peers. They are why a world does not need extra humans logged in to be validated: they
        // fill the committee seats player geometry leaves empty. Excluded are this node and every
        // node that already has a view (a player's own peer is ranked geometrically), plus any
        // member whose entry carries no route or key — an unaddressable or unverifiable peer
        // cannot hold a seat.
        Map<NodeId, dev.nodera.protocol.membership.PeerEntry> residents = new java.util.LinkedHashMap<>();
        for (dev.nodera.protocol.membership.PeerEntry entry : host.runtime().sessionView().members()) {
            if (entry.nodeId().equals(local) || views.containsKey(entry.nodeId())
                    || entry.route().isEmpty() || !entry.hasPublicKey()) {
                continue;
            }
            residents.put(entry.nodeId(), entry);
        }
        final ServerLevel level = anchorLevel;
        long worldSeed = level.getSeed();
        long gameTime = level.getGameTime();
        Path saveRoot = server.getWorldPath(LevelResource.ROOT);
        Thread.ofPlatform().name("nodera-entity-lane-boot").daemon().start(() -> {
            try {
                List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                        views, local, gameTime, NoderaConstants.QUORUM_MVP_SIZE, residents.keySet());
                List<LiveEntityLaneSession.RegionBinding> bindings = new ArrayList<>();
                for (EntityLaneBootstrap.PlannedRegion planned : plan) {
                    // Activate every region this node participates in: primary regions drive
                    // proposals, validator regions re-execute + vote (the committee model — the
                    // same rule every player's client applies to the same broadcast plan).
                    if (planned.locallyPrimary()
                            || planned.lease().validators().contains(local)) {
                        bindings.add(new LiveEntityLaneSession.RegionBinding(
                                level, EntityLaneBootstrap.initialSnapshot(planned.region()),
                                planned.lease()));
                    }
                }
                // Prefer the world's certified genesis (30c); the seed-derived interim manifest
                // remains the fallback for a save that has never certified one.
                GenesisManifest manifest = WorldGenesisService.read(saveRoot)
                        .map(dev.nodera.storage.CertifiedWorldGenesis::manifest)
                        .orElseGet(() -> EntityLaneBootstrap.genesis(worldSeed, new HashService()));
                // An empty local binding set is NOT a reason to stop: this node simply owns none
                // of the planned regions (every region fell to another player's view). The plan
                // must still reach the clients below — skipping the broadcast would leave them
                // deriving ownership from a stale payload, so two players could each believe they
                // primary the same region.
                // Bind the local companion's validation lane to THIS world's seed before it can be
                // seated. The seed drives the deterministic RNG the region engine re-executes with,
                // so a worker still on its boot-time placeholder would compute roots nobody else
                // computes and vote against every batch — a committee that never reaches quorum,
                // with nothing in any log to say why.
                try {
                    CompanionClient companion = CompanionLink.client();
                    String hostRoute = NoderaPeerService.get().hostRoute();
                    if (companion != null && hostRoute != null && !hostRoute.isEmpty()) {
                        companion.mesh(hostRoute, worldSeed).ifPresent(err -> LOG.warn(
                                "Nodera: could not bind the companion to this world: {}", err));
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Nodera: companion world bind failed: {}", e.toString());
                }
                // Hand every resident its committee seats BEFORE the local lane opens: the seat is
                // what makes an always-on peer re-execute the world, and a validator that has not
                // activated the region drops the primary's very first proposal on the floor.
                int seats = assignResidentSeats(host, plan, residents);
                if (!bindings.isEmpty()) {
                    List<LiveEntityLaneSession.CommitteePeer> peers = new ArrayList<>();
                    for (PlayerNodeRegistry.PlayerNode node : memberNodes.values()) {
                        peers.add(new LiveEntityLaneSession.CommitteePeer(
                                dev.nodera.transport.PeerAddress.of(node.nodeId(), node.route()),
                                node.publicKey()));
                    }
                    // Residents are committee peers too — without their address+key here the local
                    // lane could not send them proposals or verify the votes they send back.
                    for (dev.nodera.protocol.membership.PeerEntry entry : residents.values()) {
                        peers.add(new LiveEntityLaneSession.CommitteePeer(
                                dev.nodera.transport.PeerAddress.of(entry.nodeId(), entry.route()),
                                entry.publicKey()));
                    }
                    activateEntityLane(server, manifest, bindings, peers);
                    LOG.info("Nodera: entity lane live on {} region(s) across {} member node(s) "
                                    + "+ {} resident peer(s) holding {} committee seat(s) (genesis {})",
                            bindings.size(), views.size(), residents.size(), seats,
                            manifest.genesisRoot().toShortHex(4));
                } else {
                    LOG.info("Nodera: no regions fall to this node in the new plan "
                                    + "({} member node(s), {} resident peer(s)) — broadcasting it "
                                    + "for the owners", views.size(), residents.size());
                }
                // The re-plan swap ends here, where the outcome is actually known: a lane that
                // activated replaces the held ownership, one that did not drops it.
                dev.nodera.mod.server.entity.LiveRegionOwnershipProvider
                        .endSwap(!bindings.isEmpty());
                // Broadcast the plan inputs so every player's client derives the identical plan
                // and activates its own regions (the shared-computation ownership model).
                NoderaLanePlanPayload payload = new NoderaLanePlanPayload(
                        manifest.worldSeed(), manifest.rulesVersion(),
                        manifest.registryFingerprint(),
                        java.util.Base64.getEncoder().encodeToString(
                                manifest.genesisRoot().hash().toArray()),
                        java.util.Base64.getEncoder().encodeToString(
                                host.identity().publicKeyBytes().toArray()),
                        gameTime, NoderaConstants.QUORUM_MVP_SIZE, members);
                server.execute(() -> {
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, payload);
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.endSwap(false);
                LOG.warn("Nodera: entity lane bootstrap failed: {}", e.toString());
            }
        });
        return true;
    }

    /** Guards concurrent re-plans (a burst of node announces re-plans once at a time). */
    private static final java.util.concurrent.atomic.AtomicBoolean REPLANNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** The member set the current lane was planned for — an identical set never re-plans. */
    private static volatile String lastPlanKey = "";

    /**
     * Stable key over (player, node, <b>region</b>) triples; a re-plan is warranted when this
     * changes.
     *
     * <p>The region coordinate is load-bearing: ownership is a function of where each player's
     * field-of-view disc sits, so a key over membership alone would freeze the plan at the
     * positions held when the last player joined or left. Players would then walk out of the
     * regions they own and read {@code FOREIGN} (owned by whoever was nearest at join time) or
     * {@code UNASSIGNED} (never claimed) for the ground under their feet. Keying on the region
     * makes the owned footprint follow the player — it re-plans exactly when someone crosses a
     * region boundary, not on every step.
     */
    private static String planKey(MinecraftServer server) {
        java.util.TreeSet<String> entries = new java.util.TreeSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(p.getUUID());
            dev.nodera.core.region.RegionId region =
                    dev.nodera.mod.server.entity.MinecraftEntityAdapters.region(p);
            entries.add(p.getUUID() + "→" + (node == null ? "host" : node.nodeId().value())
                    + "@" + region.dimension() + ":" + region.regionX() + "," + region.regionZ());
        }
        return String.join(",", entries);
    }

    /** Ticks between movement checks — the FOV plan cannot go stale faster than a player walks. */
    private static final int OWNERSHIP_TICK_INTERVAL = 20;

    /**
     * Minimum ticks between two movement-triggered re-plans. A re-plan closes and reopens the
     * lane, so a player jittering across a region boundary must not churn it every second;
     * membership changes (join/leave) bypass this and re-plan immediately.
     */
    private static final int MOVEMENT_REPLAN_COOLDOWN_TICKS = 100;

    /**
     * Tick of the last movement-triggered re-plan. Seeded one full cooldown in the past so the
     * FIRST movement re-plans immediately — never {@code Long.MIN_VALUE}, whose subtraction from
     * a small positive tick count overflows to a negative value and suppresses every re-plan.
     */
    private static volatile long lastMovementReplanTick = -MOVEMENT_REPLAN_COOLDOWN_TICKS;

    /**
     * Re-plan region ownership when a player's field of view has moved to a new region — the
     * "ownership follows the player" half of the FOV model. Cheap: runs once a second, compares
     * the plan key, and defers to {@link #replanEntityLane} which no-ops on an unchanged key.
     *
     * @param server the session server.
     * @Thread-context server thread (tick).
     */
    public static void tickOwnership(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick % OWNERSHIP_TICK_INTERVAL != 0) {
            return;
        }
        if (!NoderaConfig.ENTITY_LANE_AUTO.get() || !NoderaPeerService.get().isHosting()
                || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (planKey(server).equals(lastPlanKey)) {
            return; // nobody crossed a region boundary
        }
        if (tick - lastMovementReplanTick < MOVEMENT_REPLAN_COOLDOWN_TICKS) {
            return; // a boundary-straddling player must not churn the lane
        }
        lastMovementReplanTick = tick;
        LOG.debug("Nodera: player view moved to a new region — re-planning ownership");
        replanEntityLane(server);
    }

    /**
     * Recompute region ownership from the CURRENT player set (a node announced, a player left)
     * and re-open the live lane on the new plan. Runs the heavy close+reopen off-thread; cheap
     * no-op when the lane is disabled or the world is not shared.
     *
     * @param server the session server.
     * @Thread-context server thread.
     */
    public static void replanEntityLane(MinecraftServer server) {
        if (!NoderaConfig.ENTITY_LANE_AUTO.get() || !NoderaPeerService.get().isHosting()
                || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        // Same members, lane already up → nothing to re-plan (announce storms and duplicate
        // login events must not churn the lane through close/reopen cycles).
        String key = planKey(server);
        if (key.equals(lastPlanKey) && entityLaneActive()) {
            return;
        }
        if (!REPLANNING.compareAndSet(false, true)) {
            return; // a re-plan is already in flight; the announce that raced it re-triggers
        }
        lastPlanKey = key;
        Thread.ofPlatform().name("nodera-entity-lane-replan").daemon().start(() -> {
            // A re-plan is a swap, not a teardown: hold the outgoing ownership on the diagnostics
            // surfaces so the panels never blink to "no delegated regions" / UNASSIGNED mid-swap.
            dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.beginSwap();
            try {
                synchronized (NoderaHost.class) {
                    closeEntityLane();
                }
                server.execute(() -> {
                    boolean planning = false;
                    try {
                        planning = activateEntityLaneFromWorld(server);
                    } finally {
                        // When a plan is being computed its boot thread ends the swap once the
                        // outcome is known; when it never started, end it here.
                        if (!planning) {
                            dev.nodera.mod.server.entity.LiveRegionOwnershipProvider
                                    .endSwap(false);
                        }
                        REPLANNING.set(false);
                    }
                });
            } catch (RuntimeException e) {
                dev.nodera.mod.server.entity.LiveRegionOwnershipProvider.endSwap(false);
                REPLANNING.set(false);
                LOG.warn("Nodera: entity lane re-plan failed: {}", e.toString());
            }
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Install live entity validation once Task-5b supplies certified genesis and region bindings. */
    public static synchronized void activateEntityLane(
            MinecraftServer server,
            GenesisManifest genesis,
            java.util.List<LiveEntityLaneSession.RegionBinding> regions,
            java.util.List<LiveEntityLaneSession.CommitteePeer> peers) {
        NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
        if (host == null) {
            throw new IllegalStateException("host peer must be running before entity lane activation");
        }
        closeEntityLane();
        entityLane = LiveEntityLaneSession.open(
                server, genesis, regions, peers,
                server.getWorldPath(LevelResource.ROOT).resolve("nodera/entity-lane"), host);
        // Per-joiner identities (L-50): each connected player's actor is admissible under its
        // OWN node's key on the server lane too — the member node key IS the actor's signer
        // identity; the interim session signer stays co-registered by the lazy submit() path
        // while the vanilla-session capture point remains (T16 retires it).
        LiveEntityLaneSession session = entityLane;
        if (session != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(p.getUUID());
                if (node != null) {
                    session.registerActorKey(
                            new NodeId(p.getUUID()), node.publicKey());
                }
            }
        }
        // The bootstrap may run off-thread (activateEntityLaneFromWorld): if the server stopped
        // while the session was opening, onServerStopping already ran — close instead of leaking.
        if (!server.isRunning()) {
            closeEntityLane();
        }
    }

    /** Whether a live entity-lane session is currently installed. */
    public static synchronized boolean entityLaneActive() {
        return entityLane != null;
    }

    /**
     * The live lane's per-peer relay metrics (who processes whose events, and how long), or null
     * without an active lane. Read by {@code /nodera debug relay} + the verbose console stream.
     */
    public static synchronized dev.nodera.diagnostics.metric.RelayMetrics entityLaneRelayMetrics() {
        return entityLane == null ? null : entityLane.runtime().validation().relayMetrics();
    }

    /** Stop local validation resources without changing the world's shared flag. */
    public static synchronized void onServerStopping(MinecraftServer server) {
        // The game endpoint dies with the game. Tell the worker so the world stays LISTED on the
        // network (the worker keeps announcing) but stops advertising a joinable game server.
        if (NoderaPeerService.get().isHosting() && CompanionLink.isPresent()) {
            Path saveRoot = server.getWorldPath(LevelResource.ROOT);
            NoderaWorldStore.read(saveRoot).ifPresent(id -> {
                ShareOptions opts = NoderaPeerService.get().hostOptions();
                notifyWorker(server, id.worldId(), server.getWorldData().getLevelName(),
                        opts == null ? ShareOptions.playerDefault() : opts, null);
            });
        }
        publishedGamePort = -1;
        closeEntityLane();
        // The gate belongs to the hosted game, not to the process: a stale armed gate would keep
        // challenging joiners of whatever world is opened next (L-52).
        HostJoinGate.get().disarm();
        NoderaPeerService.get().stopHosting();
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
            CompanionLink.client().mintWorldIdentity(identitySeed(saveRoot, world), id.createdAtEpoch(), false,
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
        closeEntityLane();
        HostJoinGate.get().disarm();
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

    /**
     * The seed an existing identity was minted with: the interim name-derived id when the persisted
     * worldId matches that derivation (pre-30c records), else the certified genesis root. Re-minting
     * with any other seed silently changes the worldId and breaks tracker/joiner continuity.
     */
    private static Bytes identitySeed(Path saveRoot, String worldName) {
        Bytes interim = worldId(worldName);
        Optional<WorldIdentity> existing = NoderaWorldStore.read(saveRoot);
        if (existing.isPresent()) {
            WorldIdentity id = existing.get();
            if (WorldIdentity.deriveWorldId(interim, id.authorPublicKey(), id.createdAtEpoch())
                    .equals(id.worldId())) {
                return interim;
            }
        }
        return WorldGenesisService.read(saveRoot)
                .map(g -> g.manifest().genesisRoot().hash())
                .orElse(interim);
    }

    private static void closeEntityLane() {
        if (entityLane != null) {
            entityLane.close();
            entityLane = null;
        }
    }
}
