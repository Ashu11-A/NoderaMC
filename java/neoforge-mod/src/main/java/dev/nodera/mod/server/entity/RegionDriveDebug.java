package dev.nodera.mod.server.entity;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionId;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.NoderaPeerService;
import dev.nodera.mod.common.PlayerNodeRegistry;
import dev.nodera.peer.validation.EntityLaneBootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The scripted region-ownership drive (live Test 1) plus the always-on region enter/leave log.
 *
 * <p><b>Enter/leave log</b> (cheap, every {@value #TRACK_INTERVAL_TICKS} ticks): whenever a player
 * crosses a region boundary, one line names the region left, the region entered, and — when the
 * ownership plan knows it — which player's node owns the entered region. This is the evidence
 * stream the live tests grep.
 *
 * <p><b>Drive</b> ({@code debug.regionDrive=true}): once two players and the entity lane are
 * live, the drive teleports each player to the centre of a region <i>their own node owns</i>,
 * then sends the second player into the first player's region — logging every step. This is
 * Test 1's "who is responsible for what" assertion made scriptable without GUI input; the
 * ownership map is recomputed with the same pure planner every member uses, so the log speaks
 * for the real plan.
 *
 * <p>Thread-context: all methods on the server main thread (tick handler).
 */
public final class RegionDriveDebug {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaRegionDrive");

    private static final int TRACK_INTERVAL_TICKS = 10;
    /** Ticks after the second player appears before the drive starts (world settle time). */
    private static final int DRIVE_DELAY_TICKS = 200;
    /** Ticks between drive steps. */
    private static final int STEP_DELAY_TICKS = 200;

    private static final Map<UUID, RegionId> lastRegion = new HashMap<>();
    private static int driveCountdown = -1;
    private static int driveStep;
    /** The drive runs ONCE per server session — a rejoining player must never be re-teleported. */
    private static boolean driveDone;

    private RegionDriveDebug() {
    }

    /** Tick hook (registered by {@code ServerBootstrap}); no-ops when nothing to do. */
    public static void onServerTick(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick % TRACK_INTERVAL_TICKS == 0) {
            trackRegions(server);
        }
        if (!NoderaConfig.DEBUG_REGION_DRIVE.get()) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (driveDone || players.size() < 2) {
            driveCountdown = -1;
            return;
        }
        if (driveCountdown < 0) {
            driveCountdown = DRIVE_DELAY_TICKS;
            driveStep = 0;
            LOG.info("DRIVE armed: {} players online", players.size());
        }
        if (--driveCountdown > 0) {
            return;
        }
        // The lane re-plans (close + reopen) on membership churn; a momentarily-inactive lane
        // defers the step instead of disarming the whole drive.
        if (!NoderaHost.entityLaneActive()) {
            driveCountdown = 100;
            return;
        }
        driveCountdown = STEP_DELAY_TICKS;
        runDriveStep(server, players);
    }

    // --- enter/leave tracking ------------------------------------------------------------------

    private static void trackRegions(MinecraftServer server) {
        Map<RegionId, String> owners = ownersByRegion(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            RegionId now = MinecraftEntityAdapters.region(p);
            RegionId before = lastRegion.put(p.getUUID(), now);
            if (before != null && !before.equals(now)) {
                LOG.info("REGION: {} left {} → entered {}{}",
                        p.getGameProfile().getName(), before, now,
                        owners.containsKey(now) ? " (owner: " + owners.get(now) + ")" : "");
            }
        }
        lastRegion.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);
    }

    // --- the drive -----------------------------------------------------------------------------

    private static void runDriveStep(MinecraftServer server, List<ServerPlayer> players) {
        Map<UUID, RegionId> owned = ownedRegionPerPlayer(server);
        ServerPlayer p1 = players.get(0);
        ServerPlayer p2 = players.get(1);
        RegionId r1 = owned.get(p1.getUUID());
        RegionId r2 = owned.get(p2.getUUID());
        switch (driveStep++) {
            case 0 -> {
                if (r1 == null || r2 == null) {
                    LOG.warn("DRIVE step 1 skipped: ownership incomplete (p1={}, p2={})", r1, r2);
                    driveStep = 0;
                    return;
                }
                LOG.info("DRIVE step 1: {} → its own region {}; {} → its own region {}",
                        p1.getGameProfile().getName(), r1, p2.getGameProfile().getName(), r2);
                teleportToRegion(p1, r1);
                teleportToRegion(p2, r2);
            }
            case 1 -> {
                if (r1 == null) {
                    LOG.warn("DRIVE step 2 skipped: p1 owns no region");
                    return;
                }
                LOG.info("DRIVE step 2: {} → {}'s region {} (cross-owner visit)",
                        p2.getGameProfile().getName(), p1.getGameProfile().getName(), r1);
                teleportToRegion(p2, r1);
            }
            default -> {
                driveDone = true;
                LOG.info("DRIVE complete");
            }
        }
    }

    /** Teleport a player to the surface centre of a region (logged by the enter/leave tracker). */
    private static void teleportToRegion(ServerPlayer player, RegionId region) {
        ServerLevel level = player.serverLevel();
        int blockX = region.originChunkX() * 16 + NoderaConstants.REGION_SIZE_CHUNKS * 8;
        int blockZ = region.originChunkZ() * 16 + NoderaConstants.REGION_SIZE_CHUNKS * 8;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ) + 1;
        player.teleportTo(level, blockX + 0.5, y, blockZ + 0.5,
                player.getYRot(), player.getXRot());
    }

    /** Recompute the pure ownership plan and pick, per player, one region its node primaries. */
    private static Map<UUID, RegionId> ownedRegionPerPlayer(MinecraftServer server) {
        Map<UUID, RegionId> out = new LinkedHashMap<>();
        NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
        if (host == null) {
            return out;
        }
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        Map<NodeId, UUID> nodePlayers = new LinkedHashMap<>();
        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(p.getUUID());
            NodeId member = node != null ? node.nodeId() : host.identity().nodeId();
            views.putIfAbsent(member, PlayerView.fromBlock(
                    MinecraftEntityAdapters.dimension(p.serverLevel()),
                    p.blockPosition().getX(), p.blockPosition().getZ(), viewDistance));
            nodePlayers.putIfAbsent(member, p.getUUID());
        }
        long tick = server.overworld().getGameTime();
        for (EntityLaneBootstrap.PlannedRegion planned : EntityLaneBootstrap.plan(
                views, host.identity().nodeId(), tick, NoderaConstants.QUORUM_MVP_SIZE)) {
            UUID owner = nodePlayers.get(planned.lease().primary());
            if (owner != null) {
                out.putIfAbsent(owner, planned.region());
            }
        }
        return out;
    }

    /** region → owning player's name, from the same pure plan (for the enter/leave log). */
    private static Map<RegionId, String> ownersByRegion(MinecraftServer server) {
        Map<RegionId, String> out = new HashMap<>();
        NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
        if (host == null || !NoderaHost.entityLaneActive()) {
            return out;
        }
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        Map<NodeId, String> names = new LinkedHashMap<>();
        int viewDistance = server.getPlayerList().getViewDistance();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(p.getUUID());
            NodeId member = node != null ? node.nodeId() : host.identity().nodeId();
            views.putIfAbsent(member, PlayerView.fromBlock(
                    MinecraftEntityAdapters.dimension(p.serverLevel()),
                    p.blockPosition().getX(), p.blockPosition().getZ(), viewDistance));
            names.putIfAbsent(member, p.getGameProfile().getName());
        }
        long tick = server.overworld().getGameTime();
        for (EntityLaneBootstrap.PlannedRegion planned : EntityLaneBootstrap.plan(
                views, host.identity().nodeId(), tick, NoderaConstants.QUORUM_MVP_SIZE)) {
            String owner = names.get(planned.lease().primary());
            if (owner != null) {
                out.put(planned.region(), owner);
            }
        }
        return out;
    }
}
