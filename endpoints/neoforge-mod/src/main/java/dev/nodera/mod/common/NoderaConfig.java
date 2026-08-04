package dev.nodera.mod.common;

import dev.nodera.endpoint.config.NoderaSettings;
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

    // The auto-shared world's password (L-52). A dedicated server has no Share screen at all, so
    // without this its operator has no way to protect a world: empty (the default) is plaintext
    // hosting exactly as before, and a non-empty value both encrypts the archived content and gates
    // the LIVE game — joiners must supply it in the configuration phase or the connection is refused.
    // Server-side config, so it lives with the world rather than with any player's client.
    public static final ModConfigSpec.ConfigValue<String> HOST_SHARE_PASSWORD =
            SERVER_BUILDER.define("host.sharePassword", "");

    /**
     * The operator's switch for the validated entity lane, <b>underneath</b> the release-level root
     * switch in {@link ValidationLane}.
     *
     * <p>Inert while deterministic validation is off for the release: the root switch is checked
     * first, so setting this to {@code true} enables nothing on its own. It stays defined, and
     * stays {@code true}, so that the day the lane comes back an install is not silently opted out
     * of it by a config file written today.
     *
     * <p>Which regions are delegated was never this switch's decision anyway — that is per world,
     * in {@link ShareOptions#delegateRegions()}, chosen in the Share screen by the player who
     * shared it, and then per region by the delegability policy.
     */
    public static final ModConfigSpec.BooleanValue ENTITY_LANE_AUTO =
            SERVER_BUILDER.define("entity.laneAutoActivate", true);

    // Task 12b ghost lane. Empty by default: a dimension opts in to capturing EVERY species in it.
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>>
            MOB_CAPTURE_DIMENSIONS = SERVER_BUILDER.defineListAllowEmpty(
            "entity.mobCaptureDimensions", java.util.List.of(), NoderaConfig::isResourceId);

    /**
     * Species captured wherever they are found, no dimension opt-in required (L-24).
     *
     * <p>The ghost lane shipped default-off because nothing had been proven. That is no longer
     * true species-by-species: the zombie's behaviour originates in the validated engine —
     * {@code MobAiRules}' seeded wander over the replicated root and {@code MobCombatRules}'
     * validated vitals — so its capture is proven, not hoped for. This list is where that proof is
     * cashed in, and it grows as each further species' behaviour moves into the engine.
     *
     * <p>Deliberately separate from the dimension list rather than replacing it: the dimension
     * switch means "capture everything here", which is what a soak wants, while this one means
     * "this species is understood anywhere", which is what a default install wants.
     */
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>>
            MOB_CAPTURE_SPECIES = SERVER_BUILDER.defineListAllowEmpty(
            "entity.mobCaptureSpecies", java.util.List.of("minecraft:zombie"),
            NoderaConfig::isResourceId);

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
    //
    // The VALUES live in `NoderaSettings.defaults()` and are read from there. They used to be
    // declared here and hardcoded a second time in the headless node, with a comment promising the
    // two agreed — a comment is not a mechanism.
    public static final java.util.List<String> DEFAULT_TRACKER_ENDPOINTS =
            NoderaSettings.defaults().trackerEndpoints();
    public static final java.util.List<String> DEFAULT_RENDEZVOUS_ENDPOINTS =
            NoderaSettings.defaults().rendezvousEndpoints();

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
    /**
     * Validate one configured rendezvous route.
     *
     * <p>Deliberately <b>not</b> delegated to {@link #isTrackerEndpoint} any more: tracker routes
     * now accept a {@code tcp://} / {@code udp://} scheme, but a rendezvous relay circuit is a
     * long-lived stream and has no UDP surface. Accepting {@code udp://} here would validate a
     * route that can never connect.
     *
     * @param raw the configured value.
     * @return whether it parses as a bare {@code host:port} rendezvous route.
     * @Thread-context config-loading thread.
     */
    private static boolean isRendezvousEndpoint(Object raw) {
        if (!(raw instanceof String route)) {
            return false;
        }
        try {
            dev.nodera.transport.rendezvous.RendezvousEndpoint.parse(route);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Both the dimension list and the species list are ResourceLocation ids; one validator. */
    private static boolean isResourceId(Object raw) {
        return raw instanceof String id
                && net.minecraft.resources.ResourceLocation.tryParse(id) != null;
    }

    /** Whether vanilla-authoritative ghost capture is enabled for this dimension. */
    public static boolean mobCapture(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        String id = dimension.location().toString();
        return MOB_CAPTURE_DIMENSIONS.get().stream().anyMatch(id::equals);
    }

    /**
     * Whether this entity may be ghost-captured (L-24).
     *
     * <p>Either its dimension opted in to capturing everything, or its species is one the engine
     * owns. An entity that is neither cannot live in a validated region — the lane would be
     * mirroring behaviour it does not model — which is what makes its region refusable.
     *
     * @param dimension the entity's dimension.
     * @param speciesId the entity type id, e.g. {@code minecraft:zombie}.
     */
    public static boolean mobCapture(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            String speciesId) {
        return mobCapture(dimension)
                || (speciesId != null
                    && MOB_CAPTURE_SPECIES.get().stream().anyMatch(speciesId::equals));
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
    // Issue #43 continuous streaming: while a world is hosted, re-seed its archive to the worker
    // every N server ticks (default 2400 = 2 min — autosave-like cadence) so the network copy is
    // never more than one interval behind and a crash/exit cannot revert the world. 0 disables.
    public static final ModConfigSpec.IntValue ARCHIVE_STREAM_INTERVAL_TICKS =
            SERVER_BUILDER.defineInRange("archive.streamIntervalTicks", 2400, 0, 24_000 * 60);
    // Issue #43 bounded final flush: the server-stopped seed waits at most this long before
    // abandoning (the streaming lane already holds a copy ≤ one interval old) — a hung worker
    // can no longer wedge the "Saving World" screen.
    public static final ModConfigSpec.IntValue ARCHIVE_FINAL_FLUSH_TIMEOUT_SECONDS =
            SERVER_BUILDER.defineInRange("archive.finalFlushTimeoutSeconds", 20, 1, 600);
    public static final ModConfigSpec.BooleanValue CONTINUITY_AUTO_REHOST =
            CLIENT_BUILDER.define("continuity.autoRehost", true);
    public static final ModConfigSpec.IntValue CONTINUITY_FETCH_TIMEOUT_SECONDS =
            CLIENT_BUILDER.defineInRange("continuity.fetchTimeoutSeconds", 120, 5, 3600);

    // L-52 joiner side: a password offered to any world that challenges this client, when the
    // player has not typed one for that world in this session. Empty by default — passwords a
    // player types stay in memory and are never written anywhere. Setting this is an explicit,
    // local choice (the same one every launcher's "save server password" is), and it is what makes
    // the gate drivable headlessly: a scripted client has no keyboard to type into a prompt.
    public static final ModConfigSpec.ConfigValue<String> JOIN_PASSWORD =
            CLIENT_BUILDER.define("join.password", "");

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

    /**
     * The worker this game process should talk to, with the environment winning over the config.
     *
     * <h2>Why the environment has to win</h2>
     *
     * <p>The config default is {@code 127.0.0.1:25610} for both the client and the server spec, and
     * the config file lives in the game directory. Two Minecraft clients launched on one machine —
     * which is how this project is tested, and how a player runs a second account — therefore
     * pointed at the <b>same worker</b>, and each also defaulted to the same {@code ~/.nodera} state
     * directory. One node then wore two hats: one identity, one world registry, one set of world
     * keys, and two players who each believed they were its author. Every ownership question after
     * that point had a plausible-looking wrong answer.
     *
     * <p>{@code NODERA_CONTROL_PORT} is the same variable the worker itself reads to choose the port
     * it listens on, and {@code NODERA_STATE_DIR} the same one it reads to choose its store, so a
     * second instance is launched by setting the pair once and nothing has to agree about anything
     * else. A launcher that sets neither behaves exactly as before.
     *
     * @param configured the endpoint from the config spec.
     * @return the endpoint to dial.
     * @Thread-context any thread.
     */
    public static String resolveControlEndpoint(String configured) {
        String endpoint = System.getenv("NODERA_CONTROL_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.trim();
        }
        String port = System.getenv("NODERA_CONTROL_PORT");
        if (port != null && !port.isBlank()) {
            return "127.0.0.1:" + port.trim();
        }
        return configured;
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
        // Hand the Minecraft-free half of the codebase a way to read these without a NeoForge type
        // on its path. Every value is read through the spec at CALL time, not captured here, so a
        // config reload reaches the endpoint logic the same tick it reaches the mod.
        NoderaSettings.install(SPEC_BACKED);
    }

    /** Reads the specs above. The only place a {@code ModConfigSpec} value crosses into the library. */
    private static final NoderaSettings SPEC_BACKED = new NoderaSettings() {
        @Override
        public java.util.List<String> trackerEndpoints() {
            return java.util.List.copyOf(TRACKER_ENDPOINTS.get());
        }

        @Override
        public java.util.List<String> rendezvousEndpoints() {
            return java.util.List.copyOf(RENDEZVOUS_ENDPOINTS.get());
        }

        @Override
        public java.util.List<String> clientTrackerEndpoints() {
            return java.util.List.copyOf(CLIENT_TRACKER_ENDPOINTS.get());
        }

        @Override
        public java.util.List<String> clientRendezvousEndpoints() {
            return java.util.List.copyOf(CLIENT_RENDEZVOUS_ENDPOINTS.get());
        }

        @Override
        public String joinPassword() {
            return JOIN_PASSWORD.get();
        }
    };
}
