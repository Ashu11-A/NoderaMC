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

    // P2P direct-transport endpoint (Phase 6 continuity). A host peer (dedicated server or a player's
    // integrated server that pressed "Share") listens here; joiners dial the advertised route and
    // keep a direct mesh that outlives the host. Advertise host "auto" picks the best local
    // site-local IPv4.
    public static final ModConfigSpec.ConfigValue<String> P2P_BIND_HOST =
            SERVER_BUILDER.define("p2p.bindHost", "0.0.0.0");
    public static final ModConfigSpec.IntValue P2P_PORT =
            SERVER_BUILDER.defineInRange("p2p.port", 25566, 1, 65535);
    public static final ModConfigSpec.ConfigValue<String> P2P_ADVERTISE_HOST =
            SERVER_BUILDER.define("p2p.advertiseHost", "auto");
    public static final ModConfigSpec.ConfigValue<String> CLIENT_P2P_ADVERTISE_HOST =
            CLIENT_BUILDER.define("p2p.advertiseHost", "auto");

    // Tracker endpoints (Task 28). Each entry is a `host:port` route of a standalone
    // `nodera-tracker` service. Empty by default: a LAN game needs no tracker, and a peer with no
    // endpoints simply announces nowhere and discovers through the Task 20 bootstrap mechanisms.
    // Both sides carry the list — a dedicated server announces as the world's FULL_ARCHIVE host,
    // and a client queries the same endpoints to populate its multiplayer world list.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> TRACKER_ENDPOINTS =
            SERVER_BUILDER.defineListAllowEmpty("tracker.endpoints", java.util.List.of(),
                    NoderaConfig::isTrackerEndpoint);
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CLIENT_TRACKER_ENDPOINTS =
            CLIENT_BUILDER.defineListAllowEmpty("tracker.endpoints", java.util.List.of(),
                    NoderaConfig::isTrackerEndpoint);

    // Rendezvous endpoints (Task 29). Each entry is a `host:port` route of a standalone
    // `nodera-rendezvous` service. Empty by default: a LAN game reaches peers directly, so the
    // NAT-traversal + relay-fallback transport is only engaged when endpoints are configured. Both
    // sides carry the list — a peer registers its candidates and discovers others, and either side
    // reserves a relay slot when it cannot accept direct inbound connections.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> RENDEZVOUS_ENDPOINTS =
            SERVER_BUILDER.defineListAllowEmpty("rendezvous.endpoints", java.util.List.of(),
                    NoderaConfig::isRendezvousEndpoint);
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CLIENT_RENDEZVOUS_ENDPOINTS =
            CLIENT_BUILDER.defineListAllowEmpty("rendezvous.endpoints", java.util.List.of(),
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
