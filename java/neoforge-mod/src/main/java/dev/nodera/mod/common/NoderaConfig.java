package dev.nodera.mod.common;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Nodera configuration specs — exposes the Task 0 §5 {@code NoderaConstants} defaults as
 * NeoForge config values (SERVER + CLIENT). Unused until Task 5+; registered now so the
 * specs exist and the files materialise on first run.
 *
 * <p>Thread context: registration runs on the mod-loading thread; value reads later happen on
 * whichever thread asks NeoForge's config API (server main thread for the SERVER spec).
 */
public final class NoderaConfig {

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    // Region / batch sizing (Task 0 §5).
    public static final ModConfigSpec.IntValue REGION_SIZE_CHUNKS =
            SERVER_BUILDER.defineInRange("region.regionSizeChunks", 8, 1, 64);
    public static final ModConfigSpec.IntValue BATCH_TICKS =
            SERVER_BUILDER.defineInRange("execution.batchTicks", 2, 1, 20);
    public static final ModConfigSpec.IntValue LEASE_LENGTH_TICKS =
            SERVER_BUILDER.defineInRange("coordinator.leaseLengthTicks", 200, 1, 60_000);

    // Quorum (Task 6–8 MVP gate).
    public static final ModConfigSpec.IntValue REQUIRED_VALIDATORS =
            SERVER_BUILDER.defineInRange("committee.requiredValidators", 3, 1, 16);

    // Client worker capacity (Task 5).
    public static final ModConfigSpec.IntValue CLIENT_MAX_PRIMARY =
            CLIENT_BUILDER.defineInRange("worker.maxPrimary", 1, 0, 64);
    public static final ModConfigSpec.IntValue CLIENT_MAX_REPLICA =
            CLIENT_BUILDER.defineInRange("worker.maxReplica", 4, 0, 64);

    // Host auto-share (Task 30). A DEDICATED server may put its world on the network automatically at
    // startup (an always-on FULL_ARCHIVE seeder — the classic "bootstrap server", now just a peer).
    // An INTEGRATED (singleplayer/LAN) server ignores this: it stays private until the player uses
    // the pause-menu "Share" action, so this flag can never auto-broadcast a private world.
    public static final ModConfigSpec.BooleanValue HOST_AUTO_SHARE =
            SERVER_BUILDER.define("host.autoShare", true);

    // Task 12 live entity lane. When true, sharing a world also bootstraps the validated entity
    // lane over the host player's field of view (EntityLaneBootstrap plan; DelegabilityPolicy still
    // gates every region). Off by default until the lane is soak-proven on real worlds.
    public static final ModConfigSpec.BooleanValue ENTITY_LANE_AUTO =
            SERVER_BUILDER.define("entity.laneAutoActivate", false);

    // Task 12b ghost lane. Empty by default: dimensions opt in explicitly until soak-proven.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>>
            MOB_CAPTURE_DIMENSIONS = SERVER_BUILDER.defineListAllowEmpty(
            "entity.mobCaptureDimensions", java.util.List.of(), NoderaConfig::isDimensionId);

    // P2P direct-transport endpoint (Phase 6 continuity). A host peer (dedicated server or a player's
    // integrated server that pressed "Share") listens here; joiners dial the advertised route and
    // keep a direct mesh that outlives the host. Advertise host "auto" picks the best local
    // site-local IPv4.
    public static final ModConfigSpec.ConfigValue<String> P2P_BIND_HOST =
            SERVER_BUILDER.define("p2p.bindHost", "0.0.0.0");
    public static final ModConfigSpec.IntValue P2P_PORT =
            SERVER_BUILDER.defineInRange("p2p.port", 25566, 1, 65535);

    // The Minecraft game port "Open to Nodera" publishes the integrated server on, so joiners can
    // actually connect (the analogue of vanilla LAN's random port). 0 = pick a free port.
    public static final ModConfigSpec.IntValue GAME_PORT =
            SERVER_BUILDER.defineInRange("host.gamePort", 0, 0, 65535);
    // Whether the published game server verifies joiners against Mojang session auth. Default ON
    // (never silently weaken a real host). The scripted dev/e2e runs turn it off — dev offline
    // accounts cannot pass session auth, and the Nodera lane's own identity/permission model
    // (Task 33) rides the worker identities, not Mojang sessions.
    public static final ModConfigSpec.BooleanValue HOST_ONLINE_AUTH =
            SERVER_BUILDER.define("host.onlineAuth", true);
    public static final ModConfigSpec.ConfigValue<String> P2P_ADVERTISE_HOST =
            SERVER_BUILDER.define("p2p.advertiseHost", "auto");
    public static final ModConfigSpec.ConfigValue<String> CLIENT_P2P_ADVERTISE_HOST =
            CLIENT_BUILDER.define("p2p.advertiseHost", "auto");

    // Embedded default infrastructure endpoints (Task 30). The build ships with a KNOWN network of
    // tracker + rendezvous services so a fresh install is functional out of the box rather than
    // announcing into nothing. For localhost development these point at the embedded services that
    // `scripts/dev.sh` runs (ports 25600 / 25601); a release build replaces these constants with the
    // known public network. A user can still override or clear the lists in the generated config.
    public static final java.util.List<String> DEFAULT_TRACKER_ENDPOINTS =
            java.util.List.of("127.0.0.1:25600");
    public static final java.util.List<String> DEFAULT_RENDEZVOUS_ENDPOINTS =
            java.util.List.of("127.0.0.1:25601");

    // Tracker endpoints (Task 28). Each entry is a `host:port` route of a standalone
    // `nodera-tracker` service. Defaults to the embedded dev network (above) so the host announces
    // its world and a client queries the same endpoints to populate its multiplayer world list.
    // Both sides carry the list — a host announces as the world's FULL_ARCHIVE peer.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> TRACKER_ENDPOINTS =
            SERVER_BUILDER.defineListAllowEmpty("tracker.endpoints", DEFAULT_TRACKER_ENDPOINTS,
                    NoderaConfig::isTrackerEndpoint);
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CLIENT_TRACKER_ENDPOINTS =
            CLIENT_BUILDER.defineListAllowEmpty("tracker.endpoints", DEFAULT_TRACKER_ENDPOINTS,
                    NoderaConfig::isTrackerEndpoint);

    // Rendezvous endpoints (Task 29). Each entry is a `host:port` route of a standalone
    // `nodera-rendezvous` service. Defaults to the embedded dev network (above): the host registers
    // a signed record so peers can discover + reach it (NAT hole-punch / relay fallback), and either
    // side reserves a relay slot when it cannot accept direct inbound connections. Both sides carry
    // the list.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> RENDEZVOUS_ENDPOINTS =
            SERVER_BUILDER.defineListAllowEmpty("rendezvous.endpoints", DEFAULT_RENDEZVOUS_ENDPOINTS,
                    NoderaConfig::isRendezvousEndpoint);
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CLIENT_RENDEZVOUS_ENDPOINTS =
            CLIENT_BUILDER.defineListAllowEmpty("rendezvous.endpoints", DEFAULT_RENDEZVOUS_ENDPOINTS,
                    NoderaConfig::isRendezvousEndpoint);

    /**
     * Validate one configured rendezvous route (same {@code host:port} grammar as a tracker route).
     *
     * @param raw the configured value.
     * @return whether it parses as a {@code host:port} route.
     * @Thread-context config-loading thread.
     */
    private static boolean isRendezvousEndpoint(Object raw) {
        return isTrackerEndpoint(raw);
    }

    private static boolean isDimensionId(Object raw) {
        return raw instanceof String id
                && net.minecraft.resources.ResourceLocation.tryParse(id) != null;
    }

    /** Whether vanilla-authoritative ghost capture is enabled for this dimension. */
    public static boolean mobCapture(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        String id = dimension.location().toString();
        return MOB_CAPTURE_DIMENSIONS.get().stream().anyMatch(id::equals);
    }

    /**
     * Validate one configured tracker route.
     *
     * <p>Rejecting a malformed route at config-load time beats discovering it as a silently dead
     * endpoint hours later: the peer would look connected while announcing into nothing.
     *
     * @param raw the configured value.
     * @return whether it parses as a {@code host:port} route.
     * @Thread-context config-loading thread.
     */
    private static boolean isTrackerEndpoint(Object raw) {
        if (!(raw instanceof String route)) {
            return false;
        }
        try {
            dev.nodera.peer.discovery.TrackerClient.Endpoint.parse(route);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // World-continuity lane. `archive.seedOnShare`: a shared world's save is packed into the
    // canonical world archive and seeded to the always-on worker at share time and again on server
    // stop (the final flush), so the world's bytes live on the peer network, not only on the host's
    // disk. `continuity.autoRehost` (client): when the connection to a Nodera-joined world's host
    // dies, fetch the world's archive from the network through the local worker, re-open it locally,
    // and re-share — the joiner becomes the world's next host instead of being thrown to the title
    // screen. This is the gateway-migration UX interim (L-17 allows the brief reconnect).
    public static final ModConfigSpec.BooleanValue ARCHIVE_SEED_ON_SHARE =
            SERVER_BUILDER.define("archive.seedOnShare", true);
    public static final ModConfigSpec.BooleanValue CONTINUITY_AUTO_REHOST =
            CLIENT_BUILDER.define("continuity.autoRehost", true);
    public static final ModConfigSpec.IntValue CONTINUITY_FETCH_TIMEOUT_SECONDS =
            CLIENT_BUILDER.defineInRange("continuity.fetchTimeoutSeconds", 120, 5, 3600);

    // Live-test drive (docs/Testing.Live.md, Test 1): with two players online and the entity lane
    // active, teleport each player to a region its own node owns, then send player 2 into player
    // 1's region — with the region enter/leave log as the evidence stream. Never on by default.
    public static final ModConfigSpec.BooleanValue DEBUG_REGION_DRIVE =
            SERVER_BUILDER.define("debug.regionDrive", false);

    // Companion app / headless-peer worker (Task 32). The Nodera peer node runs in a separate,
    // always-on process (the `nodera-headless` worker, supervised by the Tauri companion app) so a
    // node stays on the network even with Minecraft closed. `companion.controlEndpoint` is the
    // loopback address the mod probes at startup; `companion.required` is the presence gate: when
    // true, the mod ABORTS NeoForge startup if the worker is absent, guaranteeing the player is a
    // network node whenever Minecraft runs. It defaults to TRUE — the worker is started by
    // `scripts/dev.sh` / the companion app; if you launch without it, install/start it from
    // https://github.com/Ashu11-A/NoderaMC (or set this false to run the mod without the network node).
    public static final ModConfigSpec.ConfigValue<String> COMPANION_CONTROL_ENDPOINT =
            CLIENT_BUILDER.define("companion.controlEndpoint", "127.0.0.1:25610");
    public static final ModConfigSpec.BooleanValue COMPANION_REQUIRED =
            CLIENT_BUILDER.define("companion.required", true);
    public static final ModConfigSpec.ConfigValue<String> SERVER_COMPANION_CONTROL_ENDPOINT =
            SERVER_BUILDER.define("companion.controlEndpoint", "127.0.0.1:25610");
    public static final ModConfigSpec.BooleanValue SERVER_COMPANION_REQUIRED =
            SERVER_BUILDER.define("companion.required", true);

    private static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
    private static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    private NoderaConfig() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} on the mod-loading thread. */
    @ApiStatus.Internal
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }
}
