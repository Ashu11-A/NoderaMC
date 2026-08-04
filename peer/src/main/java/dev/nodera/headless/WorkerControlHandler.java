package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.control.ControlHandler;
import dev.nodera.peer.metric.PeerTrafficMeter;
import dev.nodera.storage.WorldIdentity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 32/33: the worker's {@link ControlHandler} — answers the mod's / companion app's control verbs
 * from the live {@link PeerRuntime}, {@link TrafficMeter}, and the {@link WorldHostingService}. This is
 * what turns the dashboard from placeholder zeros into real data, exposes the worker's identity (the
 * world-author identity), and is the delegation point for host/join/password.
 *
 * <p>The {@code NODERA-STATE} JSON is the single wire contract the Rust companion parses
 * ({@code rust/nodera-app/src/metrics.rs}) — every field here maps 1:1 to a field there; keep them in
 * lockstep.
 *
 * @Thread-context every method is called on a per-connection worker thread; the delegate state is held
 *                 in concurrent structures.
 */
public final class WorkerControlHandler implements ControlHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaWorker");

    private final String version;
    private final NodeIdentity identity;
    private final NodeCapabilities capabilities;
    private final PeerRuntime runtime;
    private final TrafficMeter meter;
    private final WorldHostingService hosting;
    private final dev.nodera.peer.validation.WorkerValidationService validation;
    /**
     * Where this endpoint will read and write on a client's say-so. Every client-supplied path goes
     * through it — see {@link ControlPaths} for why an unguarded one made the loopback endpoint an
     * arbitrary file read/write primitive for any local process.
     */
    private final ControlPaths controlPaths = ControlPaths.fromEnvironment();

    private final WorldArchiveService archive;
    private final PeerTrafficMeter peerMeter; // nullable — per-peer rows fall back to zeros
    private final dev.nodera.peer.discovery.PeerDiscoveryService discovery; // nullable
    private final ConfigSeams config; // nullable — no config plane wired
    private final WorldGrantGossipService grants; // nullable — no permission lane wired
    /**
     * The private keys of the worlds this node created, or {@code null} when this embedding keeps
     * none. Without it a world can still be hosted and seeded; what is missing is the ability to
     * <em>prove</em> who administers it, so the ownership verbs decline rather than improvise.
     */
    private final WorldKeyStore keys; // nullable — no world-key plane wired
    /**
     * The lanes that make an unmodified Minecraft usable on this network: LAN detection, the
     * connection tunnel, and the discovery client a browser reads. {@code null} when this embedding
     * has none, and every verb behind them then declines explicitly.
     */
    private final LiveLanes lanes; // nullable

    /** The deletion lane; null until {@link #attachDeletion}, and then the only way to delete. */
    private volatile WorldDeletionService deletion;
    /**
     * What this node announces to local clients, or {@code null} when it announces nothing.
     *
     * <p>Set after construction like the telemetry emitter: the bus is created before the services
     * that publish to it, and threading it through five constructor overloads for one optional lane
     * would cost more than it explains.
     */
    private volatile dev.nodera.peer.control.WorkerEventBus events;

    /** The integration-run mode, or null on a normally started worker. See {@link TestMode}. */
    private volatile TestMode testMode;

    /** Publishes this node's ownership claims to the mesh; {@code null} when no lane is wired. */
    private volatile WorldOwnershipService ownership;
    /**
     * The node's single telemetry emitter, or {@code null} when this embedding has none — the
     * telemetry verb then declines with {@code NODERA-ERR unsupported}, so an app talking to a
     * worker without an emitter cannot show a consent toggle with nothing behind it.
     */
    private volatile WorkerTelemetryService telemetry;
    private final long startedAtMillis;

    /**
     * A worker with an archive lane but no per-peer metering, discovery, configuration plane, or
     * permission gossip — the shape the control-verb integration tests embed. Peer rows report
     * zero throughput and the optional verbs decline honestly rather than pretending.
     */
    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting,
                                dev.nodera.peer.validation.WorkerValidationService validation,
                                WorldArchiveService archive) {
        this(version, identity, capabilities, runtime, meter, hosting, validation, archive,
                null, null, null, null);
    }

    /**
     * A worker with the runtime-configuration seams behind
     * {@link dev.nodera.peer.control.ControlProtocol#CONFIG} but no permission gossip.
     *
     * @param config the live objects a configuration push may re-bound, or {@code null} when this
     *               embedding has no configuration plane — the verb then declines with
     *               {@code NODERA-ERR unsupported} rather than reporting a success it did not
     *               perform.
     */
    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting,
                                dev.nodera.peer.validation.WorkerValidationService validation,
                                WorldArchiveService archive, PeerTrafficMeter peerMeter,
                                dev.nodera.peer.discovery.PeerDiscoveryService discovery,
                                ConfigSeams config) {
        this(version, identity, capabilities, runtime, meter, hosting, validation, archive,
                peerMeter, discovery, config, null);
    }

    /**
     * Full constructor, including the permission-gossip lane (issue #36 / L-54).
     *
     * @param grants the lane that relays world-permission grants to co-hosting peers, or
     *               {@code null} when this embedding has none — a grant is then minted and returned
     *               to the caller exactly as before, but reaches no other peer.
     */
    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting,
                                dev.nodera.peer.validation.WorkerValidationService validation,
                                WorldArchiveService archive, PeerTrafficMeter peerMeter,
                                dev.nodera.peer.discovery.PeerDiscoveryService discovery,
                                ConfigSeams config, WorldGrantGossipService grants) {
        this(version, identity, capabilities, runtime, meter, hosting, validation, archive, peerMeter,
                discovery, config, grants, null);
    }

    /**
     * Full constructor including the world-key plane — the half of world ownership that lives on
     * this machine only.
     *
     * @param keys the store holding the private key of every world this node created, or
     *             {@code null} when this embedding has none. A worker without it can host and seed
     *             worlds but cannot mint an ownership claim or answer
     *             {@link dev.nodera.peer.control.ControlProtocol#PROVE}; both decline explicitly
     *             rather than reporting an authority that does not exist.
     */
    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting,
                                dev.nodera.peer.validation.WorkerValidationService validation,
                                WorldArchiveService archive, PeerTrafficMeter peerMeter,
                                dev.nodera.peer.discovery.PeerDiscoveryService discovery,
                                ConfigSeams config, WorldGrantGossipService grants,
                                WorldKeyStore keys) {
        this(version, identity, capabilities, runtime, meter, hosting, validation, archive, peerMeter,
                discovery, config, grants, keys, null);
    }

    /**
     * Full constructor including the LAN/tunnel/discovery lanes.
     *
     * @param lanes the live-connection lanes, or {@code null} when this embedding has none — the
     *              LAN, directory, connect and share-link verbs then decline rather than answering
     *              emptily, because "no LAN lane on this machine" and "no worlds open" are different
     *              things and only one of them is the user's fault.
     */
    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting,
                                dev.nodera.peer.validation.WorkerValidationService validation,
                                WorldArchiveService archive, PeerTrafficMeter peerMeter,
                                dev.nodera.peer.discovery.PeerDiscoveryService discovery,
                                ConfigSeams config, WorldGrantGossipService grants,
                                WorldKeyStore keys, LiveLanes lanes) {
        this.lanes = lanes;
        this.keys = keys;
        this.grants = grants;
        this.config = config;
        this.version = version;
        this.identity = identity;
        this.capabilities = capabilities;
        this.runtime = runtime;
        this.meter = meter;
        this.hosting = hosting;
        this.validation = validation;
        this.archive = archive;
        this.peerMeter = peerMeter;
        this.discovery = discovery;
        this.startedAtMillis = System.currentTimeMillis();
    }

    /**
     * Attach the telemetry emitter.
     *
     * <p>Set after construction rather than injected: the emitter's snapshot supplier reads the same
     * runtime this handler serves, and threading it through every constructor overload would add a
     * twelfth parameter to four of them for an optional lane.
     */
    public void attachTelemetry(WorkerTelemetryService service) {
        this.telemetry = service;
    }

    /**
     * Attach the lane that publishes this node's ownership claims to the mesh.
     *
     * <p>Set after construction for the same reason as the telemetry emitter: the service needs the
     * runtime this handler already serves, and an ownership claim is minted here (in
     * {@link #mintWorldIdentity}) but has to reach the peers that co-host the world.
     */
    public void attachOwnership(WorldOwnershipService service) {
        this.ownership = service;
    }

    /** Attach the lane that applies and floods verified world deletions ({@code NODERA-DELETE}). */
    public void attachDeletion(WorldDeletionService service) {
        this.deletion = service;
    }

    /** Attach the event bus this worker announces on ({@code NODERA-EVENTS}). */
    public void attachEvents(dev.nodera.peer.control.WorkerEventBus bus) {
        this.events = bus;
    }

    @Override
    public dev.nodera.peer.control.WorkerEventBus events() {
        return events;
    }

    /**
     * Attach the integration-run mode ({@code --test-mode --role …}).
     *
     * <p>Set once at startup by {@link PeerNode} and never otherwise: a worker that was not
     * started with the flag leaves this null, {@link #testMode(String, String)} returns null, and
     * the control server answers every test verb with "unsupported" — the same answer it gives a
     * verb that does not exist. That is the whole security property of the mode, so this stays a
     * startup-only attachment rather than something a verb can switch on.
     */
    public void attachTestMode(TestMode mode) {
        this.testMode = mode;
    }

    @Override
    public String testMode(String action, String rest) {
        TestMode mode = testMode;
        return mode == null ? null : mode.handle(action, rest);
    }

    @Override
    public String workerVersion() {
        return version;
    }

    @Override
    public String identityLine() {
        String pub = Base64.getEncoder().encodeToString(identity.publicKeyBytes().toArray());
        return identity.nodeId().value() + " " + pub;
    }

    @Override
    public String stateJson() {
        NodeId self = runtime.nodeId();

        // Peers currently in the mesh (excluding self), with real routes, real client agents, and
        // real per-peer throughput. The membership view supplies identity/route/agent; the
        // per-peer meter supplies the bytes actually moved with each of them.
        List<String> peerJson = new ArrayList<>();
        for (dev.nodera.protocol.membership.PeerEntry member : runtime.sessionView().members()) {
            if (member.nodeId().equals(self)) {
                continue;
            }
            PeerTrafficMeter.PeerTraffic traffic =
                    peerMeter == null ? null : peerMeter.forNode(member.nodeId());
            // The route the peer publishes is where we dial it; the meter's route is where bytes
            // actually crossed. Prefer the published one and fall back to the observed one.
            String route = member.route();
            if ((route == null || route.isBlank()) && traffic != null) {
                route = traffic.route();
            }
            peerJson.add("{\"node_id\":\"" + escape(member.nodeId().value().toString()) + "\","
                    + "\"route\":\"" + escape(nullToEmpty(route)) + "\","
                    + "\"path\":\"" + escape(pathOf(route)) + "\","
                    + "\"client\":\"" + escape(clientOf(member)) + "\","
                    + "\"up_bytes_per_sec\":" + (traffic == null ? 0 : traffic.txBytesPerSec()) + ","
                    + "\"down_bytes_per_sec\":" + (traffic == null ? 0 : traffic.rxBytesPerSec()) + ","
                    + "\"total_up_bytes\":" + (traffic == null ? 0 : traffic.totalTxBytes()) + ","
                    + "\"total_down_bytes\":" + (traffic == null ? 0 : traffic.totalRxBytes()) + "}");
        }

        // Worlds this worker keeps discoverable. Beyond the identity fields the multiplayer UI
        // needs, each row now carries what an Info tab asks of a torrent: how big it is, the
        // checksum that identifies those exact bytes, when it entered the network, and when its
        // content last changed. "mc_route" is present while the hosting player's game is open —
        // the joinability signal.
        List<String> worldJson = new ArrayList<>();
        long totalPieces = 0;
        long totalHeldPieces = 0;
        Set<String> hostedIds = new HashSet<>();
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            hostedIds.add(world.worldIdHex());
            String mc = world.mcRoute();
            WorldArchiveService.PieceReport report =
                    archive == null ? null : archive.pieceReport(world.worldIdHex());
            if (report != null) {
                totalPieces += report.pieceCount();
                totalHeldPieces += report.heldCount();
            }
            // Who else holds this world, asked SEPARATELY from the piece report.
            //
            // `pieceReport` returns null for a world this node has no manifest of, and the holder
            // count used to be read off that report — so a world this node knows about but has not
            // downloaded reported "0 peers holding it" and "0 of 1 full copy · 100% chance nobody
            // has it", about a world two other peers were serving in full. The one state where a
            // player most needs to see that help exists is the one where the report is null, so
            // the question has to be asked of the tracker directly.
            int holders = archive == null ? 0 : archive.holdersFor(world.worldIdHex()).size();
            worldJson.add("{\"world_id\":\"" + escape(world.worldIdHex()) + "\",\"name\":\""
                    + escape(world.name()) + "\",\"players\":" + world.players()
                    + ",\"mc_route\":\"" + (mc == null ? "" : escape(mc)) + "\""
                    + ",\"added_at\":" + world.addedAtEpochMillis()
                    + ",\"updated_at\":" + world.updatedAtEpochMillis()
                    + ",\"total_bytes\":" + (report == null ? 0 : report.totalBytes())
                    + ",\"checksum\":\""
                    + (report == null ? "" : escape(report.manifestRoot().toHex())) + "\""
                    + ",\"version\":" + (report == null ? 0 : report.version())
                    + ",\"piece_count\":" + (report == null ? 0 : report.pieceCount())
                    + ",\"pieces_held\":" + (report == null ? 0 : report.heldCount())
                    + ",\"seeders\":" + holders
                    // The backup picture, as arithmetic rather than as a feeling. `copies` counts
                    // this node's own full copy plus every peer known to hold pieces; `wanted` is
                    // what ReplicationTarget asks of a network this size — capped by that size, so
                    // a small swarm is told to put a full copy on every peer; and the risk is
                    // (1-availability)^copies, which is what makes the target arguable on screen
                    // instead of asserted.
                    + ",\"backup_copies\":" + backupCopies(report, holders)
                    + ",\"backup_copies_wanted\":" + BACKUP_TARGET.replicasFor(holders + 1)
                    + ",\"loss_risk_permille\":"
                    + BACKUP_TARGET.lossRiskPermille(backupCopies(report, holders))
                    // Whether any tracker actually took this world's last announce. A world can be
                    // hosted, seeded and complete here and still be invisible to everyone else;
                    // without these two numbers the app can only report what this node INTENDED.
                    + ",\"listed_on_trackers\":" + world.listedOnTrackers()
                    + ",\"announced_to_trackers\":" + world.announcedToTrackers()
                    + ",\"seeding\":" + world.seeding()
                    // The third, independent fact: is somebody on THIS machine playing in it right
                    // now. Neither "seeding" nor "owned" can answer that — a player joins worlds
                    // they neither run nor authored, which is the ordinary case and was the one the
                    // companion app had no way to show.
                    + ",\"connected\":" + world.connected()
                    // Ownership. "seeding" says what this node DOES with the world; "owned" says
                    // whether it may speak for it. They are independent: a node can host a world it
                    // did not create (it fetched and re-shared it), and can administer a world it is
                    // currently only seeding. The app renders them as two different lists, so
                    // collapsing them into one flag here would make that impossible.
                    + ",\"owned\":" + world.owned()
                    // What this node PROCESSES, as distinct from what it stores. The archive
                    // counters above describe a copy of somebody's save; this counts the regions
                    // of the live world whose validated snapshots are committed and seeded here —
                    // the chunks this machine is actually answerable for.
                    //
                    // Without it the app could only ever describe a node as a file host, so two
                    // players sharing a world both read as "Supporting for the network" whatever
                    // the ownership plan had actually given them, and "is anybody but the host
                    // doing any work?" had no answer anywhere on screen.
                    + ",\"regions_held\":"
                    + (archive == null ? 0 : archive.heldRegions(world.worldIdHex()).size())
                    + ",\"world_public_key\":\"" + base64(world.worldPublicKey()) + "\""
                    + "}");
        }

        // Archive-only worlds are replicated for the network, not hosted or joinable here.
        if (archive != null) {
            for (String worldIdHex : archive.knownWorldIds()) {
                if (hostedIds.contains(worldIdHex)) {
                    continue;
                }
                WorldArchiveService.PieceReport report = archive.pieceReport(worldIdHex);
                if (report == null) {
                    continue;
                }
                if (report.heldCount() == 0) {
                    continue;
                }
                totalPieces += report.pieceCount();
                totalHeldPieces += report.heldCount();
                int holders = archive.holdersFor(worldIdHex).size();
                worldJson.add("{\"world_id\":\"" + escape(worldIdHex) + "\",\"name\":\""
                        + escape(worldIdHex) + "\""
                        // -1 is the worker's "nobody in that world has reported to me", which is
                        // always true here: a replication peer has no game in the world.
                        + ",\"players\":-1"
                        + ",\"mc_route\":\"\""
                        + ",\"added_at\":0"
                        + ",\"updated_at\":0"
                        + ",\"total_bytes\":" + report.totalBytes()
                        + ",\"checksum\":\"" + escape(report.manifestRoot().toHex()) + "\""
                        + ",\"version\":" + report.version()
                        + ",\"piece_count\":" + report.pieceCount()
                        + ",\"pieces_held\":" + report.heldCount()
                        + ",\"seeders\":" + holders
                        + ",\"backup_copies\":" + backupCopies(report, holders)
                        + ",\"backup_copies_wanted\":" + BACKUP_TARGET.replicasFor(holders + 1)
                        + ",\"loss_risk_permille\":"
                        + BACKUP_TARGET.lossRiskPermille(backupCopies(report, holders))
                        // This node never announced it, so it can say nothing about whether a
                        // tracker took an announce for it.
                        + ",\"listed_on_trackers\":0"
                        + ",\"announced_to_trackers\":0"
                        + ",\"seeding\":true"
                        + ",\"connected\":false"
                        + ",\"owned\":false"
                        + ",\"regions_held\":" + archive.heldRegions(worldIdHex).size()
                        + ",\"world_public_key\":\"\""
                        + "}");
            }
        }

        // The worker's declared roles (BOOTSTRAP / FULL_ARCHIVE / REGION_VALIDATOR …).
        List<String> roleJson = new ArrayList<>();
        for (PeerRole role : capabilities.roles()) {
            roleJson.add("\"" + role.name() + "\"");
        }

        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        long sent = meter.bytesTx();
        long received = meter.bytesRx();

        // The integration role, when this worker is part of a run. Absent on every normally
        // started worker: the dashboard and the mod must never be able to tell the difference
        // between "no role" and "not in a run", because there is none.
        TestMode mode = testMode;
        String roleField = mode == null || !mode.enabled() ? "" : mode.stateFragment() + ",";

        return "{"
                + roleField
                + "\"node_id\":\"" + escape(self.value().toString()) + "\","
                + "\"worker_version\":\"" + escape(version) + "\","
                + "\"client\":\"" + escape(dev.nodera.core.NoderaConstants.CLIENT_AGENT) + "\","
                + "\"uptime_seconds\":" + uptimeSeconds + ","
                + "\"is_gateway\":" + runtime.isGateway() + ","
                + "\"self_route\":\"" + escape(nullToEmpty(runtime.selfRoute())) + "\","
                + "\"roles\":[" + String.join(",", roleJson) + "],"
                + "\"maintained_pieces\":" + (archive == null ? 0 : archive.maintainedPieces()) + ","
                + "\"maintained_bytes\":" + (archive == null ? 0 : archive.maintainedBytes()) + ","
                + "\"total_sent_bytes\":" + sent + ","
                + "\"total_received_bytes\":" + received + ","
                + "\"total_chunks\":" + totalPieces + ","
                // Ratio and availability are permille integers so every consumer renders the same
                // number — no float formatting drift between the Rust UI and the Minecraft HUD.
                + "\"share_ratio_permille\":" + shareRatioPermille(sent, received) + ","
                + "\"availability_permille\":" + availabilityPermille(totalHeldPieces, totalPieces) + ","
                + "\"peers\":[" + String.join(",", peerJson) + "],"
                + "\"connected_worlds\":[" + String.join(",", worldJson) + "],"
                + "\"trackers\":[" + endpointArray(hosting.trackerHealth()) + "],"
                + "\"rendezvous\":[" + endpointArray(hosting.rendezvousHealth()) + "],"
                + validationJson()
                // Whether this node is currently moving content bytes at all. Without it a paused
                // node — battery rule, tray toggle, manual pause — looks exactly like a broken one:
                // zero throughput and no explanation. The app renders it as a "Paused" pill.
                + "\"transfers_paused\":" + transfersPaused() + ","
                // Inbound sockets turned away by the connection cap. A climbing value is the only
                // signal that distinguishes "my cap is too low" from "nobody is connecting".
                + "\"refused_connections\":" + refusedConnections() + ","
                // The LAN block rides STATE so the companion app learns about a world being opened
                // on the same push that carries everything else — a modal that needed its own poll
                // would appear seconds after the player pressed the button.
                + "\"lan\":" + lanBlock() + ","
                + "\"daemon_up\":true,"
                // The consent state travels with the metrics so the app and the mod both read one
                // source of truth for it. A worker with no emitter reports "unanswered" rather than
                // omitting the block — an absent field would be indistinguishable from a denial.
                + "\"telemetry\":" + telemetryJson()
                + "}";
    }

    /**
     * The {@code lan} block of the state JSON.
     *
     * <p>{@code supported:false} is not the same as an empty session list, and both are reported:
     * a machine whose network stack would not let the worker join the multicast group can never see
     * a LAN world, and telling the user that is more useful than showing them an empty list forever.
     */
    private String lanBlock() {
        String json = lanJson();
        return json == null
                ? "{\"supported\":false,\"reason\":\"" + escape(lanUnavailableReason())
                        + "\",\"sessions\":[]}"
                : json;
    }

    /** Why this worker is not watching for LAN worlds. Empty is not an answer, so there is a default. */
    private String lanUnavailableReason() {
        if (lanes != null && !lanes.lanUnavailable().isEmpty()) {
            return lanes.lanUnavailable();
        }
        return "this worker was built or started without the LAN lane";
    }

    /** The {@code telemetry} block of the state JSON. */
    private String telemetryJson() {
        WorkerTelemetryService service = telemetry;
        return service == null
                ? "{\"consent\":\"unanswered\",\"endpoint\":\"\",\"queued\":0,\"dropped\":0,"
                        + "\"sent\":0,\"last_attempt\":0,\"last_error\":\"\"}"
                : service.stateJson();
    }

    @Override
    public String telemetryStatus() {
        WorkerTelemetryService service = telemetry;
        return service == null ? null : service.statusJson();
    }

    @Override
    public String setTelemetryConsent(String decision) {
        WorkerTelemetryService service = telemetry;
        if (service == null) {
            return "unsupported";
        }
        dev.nodera.telemetry.TelemetryConsent value =
                dev.nodera.telemetry.TelemetryConsent.parse(decision);
        // UNANSWERED is not settable: it is the absence of a decision, and accepting it as one
        // would let a caller un-ask a question the user already answered.
        if (value == dev.nodera.telemetry.TelemetryConsent.UNANSWERED) {
            return "decision must be granted or denied";
        }
        try {
            service.setConsent(value);
            return null;
        } catch (java.io.IOException e) {
            return "could not record the decision: " + e.getMessage();
        }
    }

    @Override
    public String recordTelemetryEvent(String eventJsonB64) {
        WorkerTelemetryService service = telemetry;
        if (service == null) {
            return "unsupported";
        }
        if (eventJsonB64 == null || eventJsonB64.isBlank()) {
            return "empty event";
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(eventJsonB64),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "event payload is not base64";
        }
        dev.nodera.telemetry.TelemetryEvent event = dev.nodera.telemetry.TelemetrySpool.parse(json);
        // An event whose name is not in the registry is refused HERE as well as at the receiver:
        // the caller is a local process we can give a real error to, and a silent accept would let
        // a mod build up a spool of events the service will only ever reject.
        if (event == null) {
            return "event is not a declared telemetry event";
        }
        service.record(event);
        return null;
    }

    /** @return whether content transfers are suspended; {@code false} when there is no content plane. */
    private boolean transfersPaused() {
        if (config != null && config.content != null) {
            return config.content.transfersPaused();
        }
        return archive != null && archive.content().transfersPaused();
    }

    /** @return inbound connections refused by the cap; {@code 0} when no transport seam is wired. */
    private long refusedConnections() {
        return config == null || config.transport == null ? 0 : config.transport.refusedConnections();
    }

    /**
     * Upload ÷ download as a permille. A node that has downloaded nothing but uploaded something
     * is reported as {@code 0} rather than infinity — "ratio" is undefined with no denominator,
     * and a UI showing ∞ on a fresh seeder is noise, not information.
     */
    static long shareRatioPermille(long sentBytes, long receivedBytes) {
        return receivedBytes <= 0 ? 0 : sentBytes * 1000L / receivedBytes;
    }

    /** Locally-verified pieces as a permille of all pieces this node tracks; 1000 when none. */
    static long availabilityPermille(long heldPieces, long totalPieces) {
        return totalPieces <= 0 ? 1000 : heldPieces * 1000L / totalPieces;
    }

    /**
     * How a peer is reached, as the Peers tab's "connection" column. The relay transport routes by
     * the literal route {@code "relay"}; everything else is a direct socket dial. This reports the
     * route's shape rather than guessing — it is never used to make a routing decision.
     */
    private static String pathOf(String route) {
        if (route == null || route.isBlank()) {
            return "unknown";
        }
        return "relay".equals(route) ? "relayed" : "direct";
    }

    /** The peer's self-declared client agent, or a neutral placeholder when it publishes none. */
    private static String clientOf(dev.nodera.protocol.membership.PeerEntry member) {
        String agent = member.clientVersion();
        return agent == null || agent.isBlank()
                ? dev.nodera.core.NoderaConstants.PRODUCT_NAME + " (unknown)" : agent;
    }

    @Override
    public String piecesJson(String worldId) {
        if (archive == null || worldId == null || worldId.isBlank()) {
            return null;
        }
        WorldArchiveService.PieceReport report = archive.pieceReport(worldId.trim());
        if (report == null) {
            return null;
        }
        List<String> holderJson = new ArrayList<>();
        for (NodeId holder : report.holders()) {
            holderJson.add("\"" + escape(holder.value().toString()) + "\"");
        }
        return "{"
                + "\"world_id\":\"" + escape(report.worldIdHex()) + "\","
                + "\"manifest_root\":\"" + escape(report.manifestRoot().toHex()) + "\","
                + "\"version\":" + report.version() + ","
                + "\"piece_count\":" + report.pieceCount() + ","
                + "\"held_count\":" + report.heldCount() + ","
                + "\"total_bytes\":" + report.totalBytes() + ","
                + "\"held_bitmap\":\""
                + Base64.getEncoder().encodeToString(report.held().toByteArray()) + "\","
                + "\"holders\":[" + String.join(",", holderJson) + "]"
                + "}";
    }

    /**
     * The validation-lane counters as an additive STATE fragment (empty when the lane is not
     * wired). Additive fields are safe: the Tauri parser deserializes with serde defaults.
     */
    private String validationJson() {
        if (validation == null) {
            return "";
        }
        var s = validation.snapshot();
        return "\"validation\":{"
                + "\"active_regions\":" + s.activeRegions() + ","
                + "\"proposals_sent\":" + s.proposalsSent() + ","
                + "\"votes_cast\":" + s.votesCast() + ","
                + "\"votes_received\":" + s.votesReceived() + ","
                + "\"committee_commits\":" + s.committeeCommits() + ","
                + "\"fallback_commits\":" + s.fallbackCommits() + ","
                // Issue #5's Phase-1 gate is a NUMBER, and this is where a scripted soak reads it.
                + "\"divergences\":" + s.divergences() + ","
                + "\"region_roots\":{" + regionRootsJson() + "}"
                + "},";
    }

    /**
     * Every activated region's head root <b>and the version it is the root of</b>, keyed by region
     * (L-30). The counters above say validation happened; only the roots say the peers agree about
     * WHAT happened — which is the claim a live mesh has to support.
     *
     * <p><b>The version is not decoration.</b> A root without one cannot support the claim it is
     * meant to: two peers holding the same region at different points in the same history report
     * different roots and are in perfect agreement, while two peers at the <em>same</em> version
     * reporting different roots have forked. Emitting the root alone makes those two cases
     * indistinguishable, so a reader either calls a normal mid-commit skew a divergence or has to
     * ignore real ones. Anything comparing these must pair them by version first.
     *
     * @return the {@code "region":{"root":"hex","version":n}} pairs, comma separated (possibly
     *         empty).
     */
    private String regionRootsJson() {
        List<String> rows = new ArrayList<>();
        for (dev.nodera.core.region.RegionId region : validation.activeRegionIds()) {
            validation.headRoot(region).ifPresent(root -> {
                long version = validation.currentSnapshot(region)
                        .map(snapshot -> snapshot.version().value())
                        .orElse(-1L);
                rows.add("\"" + escape(regionKey(region)) + "\":{\"root\":\""
                        + root.hash().toHex() + "\",\"version\":" + version + "}");
            });
        }
        return String.join(",", rows);
    }

    /** {@code dimension@x,z} — stable across peers, so two workers' replies are comparable. */
    private static String regionKey(dev.nodera.core.region.RegionId region) {
        return region.dimension() + "@" + region.regionX() + "," + region.regionZ();
    }

    @Override
    public String host(String worldId, String worldNameB64, String optionsJson) {
        return hosting.host(worldId, decodeB64(worldNameB64), optionsJson);
    }

    @Override
    public String stop(String worldId) {
        return hosting.stop(worldId);
    }

    /**
     * Join the hosting world's live membership session (ControlProtocol.MESH). The mod hands over
     * the game server's advertised P2P route; the runtime dials it and, from the reply, becomes an
     * ordinary member — counted in session health, eligible for committee seats, and eligible to
     * win the gateway election if the hosting game exits. An empty route detaches.
     */
    @Override
    public String mesh(String bootstrapRoute, String worldSeed) {
        if (bootstrapRoute == null || bootstrapRoute.isBlank()) {
            runtime.joinSession(null);
            return null;
        }
        String route = bootstrapRoute.trim();
        if (route.equals(runtime.selfRoute())) {
            return "refusing to dial self";
        }
        // Bind the validation lane to the world BEFORE joining: a seat can arrive as soon as the
        // membership reply lands, and a replica activated on the wrong seed re-executes to roots
        // nobody else computes.
        if (worldSeed != null && !worldSeed.isBlank() && validation != null) {
            long seed;
            try {
                seed = Long.parseLong(worldSeed.trim());
            } catch (NumberFormatException malformed) {
                return "malformed worldSeed '" + worldSeed + "'";
            }
            if (!validation.bindWorld(seed)) {
                return "cannot rebind world seed while regions are active";
            }
        }
        try {
            // Routed by host:port; the node id is learned from the membership reply.
            runtime.joinSession(dev.nodera.transport.PeerAddress.of(null, route));
        } catch (RuntimeException e) {
            return "cannot join session at " + route + ": " + e.getMessage();
        }
        return null;
    }

    /** The durability model the backup figures in {@code NODERA-STATE} are derived from. */
    private static final dev.nodera.peer.archival.ReplicationTarget BACKUP_TARGET =
            dev.nodera.peer.archival.ReplicationTarget.standard();

    /**
     * Full copies of a world believed to exist, including this node's own when it has one.
     *
     * <p>A partial copy is not a backup: it cannot serve the world on its own, and counting it
     * would let a swarm of half-downloads report itself safe. This node contributes 1 only when
     * every piece is verified present here.
     */
    private static int backupCopies(WorldArchiveService.PieceReport report, int holders) {
        boolean complete = report != null && report.pieceCount() > 0
                && report.heldCount() == report.pieceCount();
        return holders + (complete ? 1 : 0);
    }

    /**
     * How long one {@code NODERA-JOIN} vouches for "a player here is in this world".
     *
     * <p>Three times the mod's renewal cadence, so a single dropped renewal — a stalled tick, a
     * busy control socket — does not make a live session blink out of the companion app.
     */
    private static final long JOIN_LEASE_SECONDS = 90;

    @Override
    public String join(String worldId, String worldNameB64, String leaseSeconds,
                       String playersInWorld) {
        if (worldId == null || worldId.isBlank()) {
            return "missing worldId";
        }
        if (discovery == null) {
            return "discovery lane unavailable";
        }
        Bytes id;
        try {
            id = Bytes.fromHex(worldId.trim());
        } catch (RuntimeException e) {
            return "malformed worldId";
        }
        // Join a world's swarm without hosting it: register the world with the hosting service so
        // it enters the discovery sweep set, then sweep immediately rather than waiting out the
        // cadence. Every routable peer the trackers and rendezvous services report for the world is
        // announced to, and the ordinary membership reply meshes us.
        //
        // (This verb used to accept and do nothing — reporting success for a join that never
        // happened, which is worse than an error because nothing upstream could tell.)
        //
        // The name rides along because this is often the FIRST thing this node learns about the
        // world: a joiner had no row for it at all, so its companion app showed "Nothing is on the
        // network until you share it" to a player who was standing in the world at the time. A row
        // keyed by a 64-character id would have been only a smaller version of the same problem.
        String error = hosting.seed(id.toHex(), decodeB64(worldNameB64));
        if (error != null) {
            return error;
        }
        // Renewed on every call, not set on the first: this is a lease, and the game repeating the
        // verb is what keeps it true. See ControlProtocol.JOIN.
        long lease = JOIN_LEASE_SECONDS;
        if (leaseSeconds != null && !leaseSeconds.isBlank()) {
            try {
                lease = Long.parseLong(leaseSeconds.trim());
            } catch (NumberFormatException ignored) {
                // An unreadable lease is not a reason to refuse the join — the world still belongs
                // in the sweep set. Fall back to the default rather than dropping the whole verb.
                lease = JOIN_LEASE_SECONDS;
            }
        }
        hosting.markConnected(id.toHex(), lease);
        // The caller is IN the world, so its game can see who else is — and that is the only place
        // this number can honestly come from. A peer that merely holds the world's bytes never
        // reaches here, which is what stops it publishing the zero it can see.
        //
        // Tied to the same lease as the connection: a count is only as good as the session that
        // observed it, and a game that stops renewing stops vouching for either.
        hosting.markPlayers(id.toHex(), parsePlayerCount(playersInWorld), lease);
        discovery.sweepNow();
        // Start PULLING the world, not just announcing interest in it. Joining is the moment this
        // node most obviously needs the content — the player is standing in it — and without this
        // the only thing that would ever fetch it is the background replication sweep, minutes
        // later. Live symptom: a peer's world row read "Supporting for the network · v0 · 0 B, no
        // manifest yet" for as long as anybody watched it.
        if (config != null && config.replication != null) {
            config.replication.sweepNow();
        }
        return null;
    }

    /**
     * Read a reported player count, or {@link WorldHostingService#PLAYERS_UNKNOWN}.
     *
     * <p>Everything unreadable maps to unknown rather than to zero, because the two were the same
     * value for the whole life of this field and that is exactly why a world with people in it
     * reported nobody on every node except the one hosting the game.
     */
    private static long parsePlayerCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return WorldHostingService.PLAYERS_UNKNOWN;
        }
        try {
            return Math.max(WorldHostingService.PLAYERS_UNKNOWN, Long.parseLong(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return WorldHostingService.PLAYERS_UNKNOWN;
        }
    }

    @Override
    public String seedArchive(String worldId, String archivePathB64) {
        if (archive == null) {
            return null;
        }
        String path = decodeB64(archivePathB64);
        if (worldId == null || worldId.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("missing worldId/archive path");
        }
        java.nio.file.Path archiveFile = controlPaths.resolve(path, "archive path");
        byte[] blob;
        try {
            blob = java.nio.file.Files.readAllBytes(archiveFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read archive file: " + e.getMessage());
        }
        var manifest = archive.seedArchive(worldId, blob);
        // Advertise immediately: a host that quits right after the final flush must not leave a
        // listing without holdings until the next heartbeat.
        hosting.refreshNow(worldId);
        return manifest.manifestRoot().toHex() + " " + manifest.version().value()
                + " " + manifest.pieceCount();
    }

    @Override
    public String seedRegion(String worldId, String snapshotPathB64) {
        if (archive == null) {
            return null;
        }
        String path = decodeB64(snapshotPathB64);
        if (worldId == null || worldId.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("missing worldId/snapshot path");
        }
        java.nio.file.Path snapshotFile = controlPaths.resolve(path, "region snapshot path");
        byte[] encoded;
        try {
            encoded = java.nio.file.Files.readAllBytes(snapshotFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read region snapshot file: " + e.getMessage());
        }
        dev.nodera.core.state.RegionSnapshot snapshot;
        try {
            // Decoding here rather than storing the bytes blind is the point: a snapshot that does
            // not decode is not a region, and seeding it would advertise content no fetcher could
            // use. The failure belongs to the caller that wrote the file.
            snapshot = dev.nodera.core.state.RegionSnapshot.decode(
                    new dev.nodera.core.crypto.CanonicalReader(encoded));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a region snapshot: " + e.getMessage());
        }
        var manifest = archive.seedRegion(worldId, snapshot);
        // Same reason as the archive seed: a host that quits right after committing must not leave
        // the region unadvertised until the next heartbeat.
        hosting.refreshNow(worldId);
        return manifest.manifestRoot().toHex() + " " + manifest.version().value()
                + " " + manifest.pieceCount();
    }

    @Override
    public String fetchArchive(String worldId, String destPathB64, long timeoutSeconds,
                               ArchiveProgress progress) {
        if (archive == null) {
            return null;
        }
        String path = decodeB64(destPathB64);
        if (worldId == null || worldId.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("missing worldId/destination path");
        }
        long seconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        byte[] blob = archive.fetchArchive(worldId, java.time.Duration.ofSeconds(seconds),
                progress::at);
        long version = archive.newestManifest(worldId)
                .map(m -> m.version().value()).orElse(0L);
        try {
            java.nio.file.Path dest = controlPaths.resolve(path, "archive destination");
            if (dest.getParent() != null) {
                java.nio.file.Files.createDirectories(dest.getParent());
            }
            java.nio.file.Files.write(dest, blob);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot write archive file: " + e.getMessage());
        }
        return blob.length + " " + version;
    }

    @Override
    public String mintWorldIdentity(String genesisRootB64, long createdAtEpoch, boolean shared,
                                    boolean listed, boolean encrypted, String manifestRefB64,
                                    String pinnedWorldIdHex) {
        Bytes genesisRoot = decodeBytes(genesisRootB64);
        Bytes manifestRef = decodeBytes(manifestRefB64);
        // A world id the save already carries wins over deriving a new one. Deriving binds the
        // genesis root, and that root is not as stable as the derivation assumes: it is
        // re-certified from whichever chunks happen to be loaded when the genesis file is missing.
        // Every drift used to mint a second id, and both ids stayed announced — one world, two
        // entries on the network, two administrator keys.
        Bytes pinned = parseWorldId(pinnedWorldIdHex);
        WorldIdentity id = pinned != null
                ? WorldIdentity.createPinned(identity, pinned, createdAtEpoch, shared, listed,
                        encrypted, manifestRef)
                : WorldIdentity.create(identity, genesisRoot, createdAtEpoch, shared, listed,
                        encrypted, manifestRef);
        // Minting a world identity IS the moment of authorship — the derivation binds this node's
        // public key into the world id, and no other node can produce the same one. So this is
        // exactly where the world's own key pair is created: the peer that authored the world takes
        // the private half, and the ownership claim binding the two is persisted and published.
        //
        // Doing it here rather than behind a separate verb means a node cannot be talked into
        // claiming a world it did not author: there is no code path that mints a key for a world id
        // that arrived from outside.
        mintOwnership(id.worldId(), createdAtEpoch);
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray());
    }

    /**
     * Read a pinned world id off the wire, or {@code null} when there is none to honour.
     *
     * <p>Anything that is not exactly 32 bytes of hex is treated as absent rather than as an error:
     * the argument is optional and additive, so a caller that sends nothing, sends a placeholder, or
     * sends something malformed all mean the same thing — derive the id as before.
     */
    private static Bytes parseWorldId(String hex) {
        if (hex == null) {
            return null;
        }
        String trimmed = hex.trim();
        if (trimmed.length() != 64) {
            return null;
        }
        try {
            return Bytes.fromHex(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Create (or reuse) this world's key pair and record the ownership claim it supports.
     *
     * <p>Reuse matters: a world re-shared after a re-key, a restart or a reinstall must keep the
     * same administrative key, or every peer that already learned the world's public key would see
     * the world change hands.
     *
     * @param worldId        the world just authored.
     * @param createdAtEpoch the world's creation time, so the claim and the identity agree.
     */
    private void mintOwnership(Bytes worldId, long createdAtEpoch) {
        if (keys == null) {
            return;
        }
        String hex = worldId.toHex();
        try {
            dev.nodera.storage.PersistedWorldKey worldKey = keys.loadOrGenerate(hex);
            dev.nodera.storage.WorldOwnership claim =
                    dev.nodera.storage.WorldOwnership.create(identity, worldKey, createdAtEpoch);
            CanonicalWriter w = new CanonicalWriter();
            claim.encode(w);
            hosting.bindOwnership(hex, w.toBytes());
            WorldOwnershipService lane = ownership;
            if (lane != null) {
                lane.publish(claim);
            }
        } catch (RuntimeException e) {
            // A world that cannot be keyed is still a world: the identity is already signed and the
            // save is usable. Losing the admin badge is a smaller failure than losing the share, so
            // this is reported and not rethrown.
            LOG.warn("Could not mint the administrator key for world {}: {}", hex, e.getMessage());
        }
    }

    @Override
    public String worldsJson() {
        List<String> rows = new ArrayList<>();
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            rows.add("{\"world_id\":\"" + escape(world.worldIdHex()) + "\","
                    + "\"name\":\"" + escape(world.name()) + "\","
                    + "\"role\":\"" + (world.seeding() ? "supported" : "shared") + "\","
                    + "\"owned\":" + world.owned() + ","
                    + "\"world_public_key\":\"" + base64(world.worldPublicKey()) + "\","
                    + "\"added_at\":" + world.addedAtEpochMillis() + ","
                    + "\"updated_at\":" + world.updatedAtEpochMillis() + "}");
        }
        return "{\"worlds\":[" + String.join(",", rows) + "]}";
    }

    @Override
    public String deleteWorld(String worldIdHex, String reasonB64) {
        WorldDeletionService lane = deletion;
        if (lane == null) {
            return null; // no deletion lane at all — the dispatch says so rather than pretending
        }
        if (worldIdHex == null || worldIdHex.isBlank()) {
            throw new IllegalArgumentException("missing worldId");
        }
        String hex = worldIdHex.trim().toLowerCase(java.util.Locale.ROOT);
        if (lane.isDeleted(hex)) {
            return "0"; // already deleted here; saying so beats minting a second tombstone
        }
        if (keys == null) {
            throw new IllegalStateException("this worker administers no worlds");
        }
        // Authority is the world's private key and nothing else. No key, no deletion — not even for
        // a world this node is currently hosting, because hosting somebody else's world must never
        // become the power to destroy it.
        dev.nodera.storage.PersistedWorldKey worldKey = keys.load(hex).orElseThrow(
                () -> new IllegalStateException("this node does not administer that world"));
        // The stored claim is preferred so the tombstone quotes the world's real founding date, but
        // its absence is not a blocker: holding both keys is what a claim proves, and this node
        // holds both, so it can restate it. Requiring the stored copy would mean a world could only
        // be deleted while it was still being shared.
        dev.nodera.storage.WorldOwnership claim = ownershipClaimFor(hex).orElseGet(
                () -> dev.nodera.storage.WorldOwnership.create(identity, worldKey,
                        System.currentTimeMillis()));
        String reason = new String(decodeBytes(reasonB64).toArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        dev.nodera.storage.WorldTombstone tombstone = dev.nodera.storage.WorldTombstone.create(
                identity, worldKey, claim, reason, System.currentTimeMillis());
        WorldDeletionService.Outcome outcome = lane.publish(tombstone);
        if (outcome.error() != null) {
            throw new IllegalStateException(outcome.error());
        }
        return Integer.toString(outcome.peersNotified());
    }

    /** The signed ownership claim this node already holds for a world, if it is still hosting it. */
    private java.util.Optional<dev.nodera.storage.WorldOwnership> ownershipClaimFor(String hex) {
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            if (world.worldIdHex().equalsIgnoreCase(hex) && world.owned()) {
                return decodeOwnership(world.ownershipRecord());
            }
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<dev.nodera.storage.WorldOwnership> decodeOwnership(
            Bytes record) {
        if (record == null || record.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(dev.nodera.storage.WorldOwnership.decode(
                    new dev.nodera.core.crypto.CanonicalReader(record)));
        } catch (RuntimeException malformed) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public String proveAdmin(String worldIdHex, String challengeB64) {
        if (keys == null || worldIdHex == null || worldIdHex.isBlank()) {
            return null;
        }
        Bytes challenge = decodeBytes(challengeB64);
        if (challenge.isEmpty()) {
            // Refused rather than signed: a proof over an empty challenge is the same bytes every
            // time, so anyone who saw one could replay it forever.
            throw new IllegalArgumentException("a proof needs a challenge");
        }
        return keys.load(worldIdHex.trim())
                .map(worldKey -> {
                    dev.nodera.storage.WorldAdminProof proof =
                            dev.nodera.storage.WorldAdminProof.create(worldKey, identity.nodeId(),
                                    challenge, System.currentTimeMillis());
                    CanonicalWriter w = new CanonicalWriter();
                    proof.encode(w);
                    return Base64.getEncoder().encodeToString(w.toBytes().toArray());
                })
                .orElse(null);
    }

    @Override
    public String grantRole(String worldIdHex, String subjectNodeId, String subjectPublicKeyB64,
                            int roleOrdinal, long grantVersion) {
        // The worker signs as the world author; the recipient key is bound into the grant (F2).
        // Authority to grant is re-verified when the grant is applied on every peer.
        Bytes worldId = Bytes.fromHex(worldIdHex);
        NodeId subject = new NodeId(java.util.UUID.fromString(subjectNodeId));
        Bytes subjectPublicKey = decodeBytes(subjectPublicKeyB64);
        dev.nodera.core.identity.WorldRole role =
                dev.nodera.core.identity.WorldRole.fromOrdinal(roleOrdinal);
        dev.nodera.storage.WorldPermissionGrant grant =
                dev.nodera.storage.WorldPermissionGrant.create(identity, worldId, subject,
                        subjectPublicKey, role, grantVersion);
        // L-54: a grant that only ever reaches the author's own disk is not a decision the mesh
        // has taken. Publishing applies it here and relays it to every co-hosting peer, each of
        // which re-verifies it against the world's author key before accepting.
        if (grants != null) {
            grants.track(worldIdHex, identity.nodeId(), identity.publicKeyBytes());
            grants.publish(worldIdHex, grant);
        }
        CanonicalWriter w = new CanonicalWriter();
        grant.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray());
    }

    @Override
    public String delegateSession(String worldIdHex, String sessionPublicKeyB64, long ttlSeconds) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return null;
        }
        Bytes sessionKey = decodeBytes(sessionPublicKeyB64);
        if (sessionKey.isEmpty()) {
            // Refused rather than signed. A delegation over an empty key would name no session at
            // all, which is a credential for whoever holds the bytes.
            throw new IllegalArgumentException("a delegation needs the session's public key");
        }
        Bytes worldId = Bytes.fromHex(worldIdHex.trim());
        // The requested lifetime is a hint. A session that asks for a year gets the default, because
        // the point of the expiry is that a delegation copied off a disk stops working.
        long ttlMillis = ttlSeconds <= 0 ? dev.nodera.core.identity.SessionDelegation.DEFAULT_TTL_MILLIS
                : Math.min(ttlSeconds * 1000L,
                        dev.nodera.core.identity.SessionDelegation.DEFAULT_TTL_MILLIS);
        dev.nodera.core.identity.SessionDelegation delegation =
                dev.nodera.core.identity.SessionDelegation.create(identity, sessionKey, worldId,
                        System.currentTimeMillis() + ttlMillis);
        CanonicalWriter w = new CanonicalWriter();
        delegation.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray());
    }

    @Override
    public String rekey(String worldIdHex, String archivePathB64, String newPasswordB64,
                        String currentWorldIdentityB64) {
        // Issue #37 / L-51: the actual password re-key. The mod hands a freshly-packed plaintext
        // archive (the author's own machine — loopback trust boundary) + the new password + the
        // current signed identity. We re-encrypt under a fresh Argon2id salt (new key → new
        // ciphertext → new manifestRoot + bumped version), re-sign the identity with the new
        // manifestRef, re-announce, and return the re-signed identity. No old password is needed
        // (none is escrowed) — the plaintext save is the source. Authorship is enforced by resign.
        // Returns null only when the archive lane is absent (older worker → "unknown verb" /
        // "archive lane unavailable"); validation failures throw → the dispatch surfaces them as
        // NODERA-ERR (mirrors SEED/ARCHIVE/WORLDID — never silent success).
        if (archive == null) {
            return null;
        }
        if (worldIdHex == null || worldIdHex.isBlank()) {
            throw new IllegalArgumentException("missing worldId");
        }
        if (archivePathB64 == null || archivePathB64.isBlank()) {
            throw new IllegalArgumentException("missing archive path");
        }
        if (newPasswordB64 == null || newPasswordB64.isBlank()) {
            throw new IllegalArgumentException("missing new password");
        }
        if (currentWorldIdentityB64 == null || currentWorldIdentityB64.isBlank()) {
            throw new IllegalArgumentException("missing current world identity");
        }
        // 1. read the freshly-packed plaintext blob.
        java.nio.file.Path archiveFile =
                controlPaths.resolve(decodeB64(archivePathB64), "archive path");
        byte[] blob;
        try {
            blob = java.nio.file.Files.readAllBytes(archiveFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read archive file: " + e.getMessage(), e);
        }
        // 2. decode the current signed identity the mod read from nodera-world.dat.
        WorldIdentity current = WorldIdentity.decode(new dev.nodera.core.crypto.CanonicalReader(
                decodeBytes(currentWorldIdentityB64)));
        if (!current.worldId().toHex().equalsIgnoreCase(worldIdHex)) {
            throw new IllegalArgumentException("worldId mismatch");
        }
        // 3. honest author pre-check (the signature is the real authority; resign enforces it too).
        if (!identity.nodeId().equals(current.authorNodeId())) {
            throw new IllegalArgumentException("not the author of this world");
        }
        // 4. re-key: seedEncryptedArchive mints a fresh salt, bumps the version, publishes ciphertext.
        char[] pwd = new String(Base64.getDecoder().decode(newPasswordB64),
                java.nio.charset.StandardCharsets.UTF_8).toCharArray();
        dev.nodera.distribution.PieceManifest manifest;
        try {
            manifest = archive.seedEncryptedArchive(worldIdHex, blob, pwd);
        } finally {
            java.util.Arrays.fill(pwd, '\0');
        }
        // 4b. evict the superseded ciphertext (L-55). The old blob decrypts under the OLD password,
        // so keeping it seeded would mean a password change revoked nothing on this node.
        archive.supersedeOlderVersions(worldIdHex);
        // 5. re-sign the identity with the new manifestRef (encrypted=true; password is non-blank).
        WorldIdentity reSigned = current.resign(identity, current.shared(),
                current.listedOnTracker(), true, manifest.manifestRoot());
        // 6. re-announce so the new manifestRoot appears in the tracker holdings immediately.
        hosting.refreshNow(worldIdHex);
        // 7. return the re-signed identity (B64 canonical bytes), mirroring mintWorldIdentity, plus
        //    the version now seeded — the caller records it as the save's seeded version, which is
        //    what keeps the continuity freshness guard honest for an encrypted world (its refreshes
        //    come through here, not through SEED, so SEED's reply can no longer be the only source).
        CanonicalWriter w = new CanonicalWriter();
        reSigned.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray())
                + " " + manifest.version().value();
    }


    // --- the live-connection lanes: LAN, tunnel, directory, share links -------------------------

    /**
     * The services behind the verbs that let an <b>unmodified</b> Minecraft use this network.
     *
     * <p>Bundled rather than passed individually because they only make sense together: LAN
     * detection with no tunnel produces offers nobody can accept, and a tunnel with no discovery
     * client produces sessions nobody can find. A embedding either has this lane or does not.
     *
     * @param lan        watches for worlds opened to LAN and holds the player's decision.
     * @param tunnel     carries the connections.
     * @param tracker    the discovery client a browser reads and a joiner resolves through.
     * @param rendezvous the configured rendezvous routes, for minting share links.
     * @param lanUnavailable why {@code lan} is absent, in words a person can act on — or empty when
     *                       it is present. "Switched off" and "this machine's network stack would
     *                       not let me listen" are different situations and only one of them is the
     *                       user's to fix; a bare {@code supported:false} makes them identical.
     * @Thread-context immutable holder; the services behind it are individually thread-safe.
     */
    public record LiveLanes(LanSessionService lan,
                            dev.nodera.peer.tunnel.TunnelService tunnel,
                            dev.nodera.peer.discovery.TrackerClient tracker,
                            List<String> rendezvous,
                            String lanUnavailable) {
        public LiveLanes {
            rendezvous = List.copyOf(rendezvous == null ? List.of() : rendezvous);
            lanUnavailable = lanUnavailable == null ? "" : lanUnavailable;
        }

        /** Backwards-compatible constructor for an embedding with a working LAN lane. */
        public LiveLanes(LanSessionService lan, dev.nodera.peer.tunnel.TunnelService tunnel,
                         dev.nodera.peer.discovery.TrackerClient tracker, List<String> rendezvous) {
            this(lan, tunnel, tracker, rendezvous, "");
        }
    }

    @Override
    public String lanJson() {
        if (lanes == null || lanes.lan() == null) {
            return null; // → "this worker cannot watch for LAN worlds"
        }
        List<String> rows = new ArrayList<>();
        for (LanSessionService.Session session : lanes.lan().sessions()) {
            rows.add("{\"session_id\":\"" + escape(session.sessionIdHex()) + "\","
                    + "\"name\":\"" + escape(session.name()) + "\","
                    + "\"port\":" + session.port() + ","
                    + "\"state\":\"" + session.state().name().toLowerCase(java.util.Locale.ROOT) + "\","
                    + "\"detected_at\":" + session.detectedAtEpochMillis() + "}");
        }
        return "{\"supported\":true,\"reason\":\"\",\"sessions\":[" + String.join(",", rows) + "]}";
    }

    @Override
    public String lanAction(String action, int port) {
        if (lanes == null || lanes.lan() == null) {
            return "this worker cannot watch for LAN worlds";
        }
        return switch (action == null ? "" : action.toUpperCase(java.util.Locale.ROOT)) {
            case "SHARE" -> lanes.lan().share(port);
            case "DECLINE" -> lanes.lan().decline(port);
            case "STOP" -> lanes.lan().stop(port);
            default -> "unknown LAN action '" + action + "'";
        };
    }

    @Override
    public String directoryJson(int limit) {
        if (lanes == null || lanes.tracker() == null) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        for (var entry : lanes.tracker().catalog(limit)) {
            String worldId = entry.genesisHash().toHex();
            // A world this node is itself hosting is still listed: a player with two machines is a
            // real case, and hiding your own worlds from the browser would look like a bug.
            rows.add("{\"session_id\":\"" + escape(worldId) + "\","
                    + "\"name\":\"" + escape(entry.worldName()) + "\","
                    // The tracker counts live ANNOUNCING PEERS for a swarm; it never learns who
                    // is inside a world. Sent under its real name so no screen can render it as a
                    // player count again — which is what "3 players" over three seeders of an empty
                    // world was.
                    + "\"peers\":" + entry.worldPlayerCount() + ","
                    + "\"pieces\":" + entry.storedChunks() + ","
                    + "\"health\":\"" + entry.health().name().toLowerCase(java.util.Locale.ROOT) + "\","
                    + "\"mine\":" + hosting.hostedWorlds().stream()
                            .anyMatch(w -> w.worldIdHex().equalsIgnoreCase(worldId))
                    + "}");
        }
        return "{\"worlds\":[" + String.join(",", rows) + "]}";
    }

    @Override
    public String connectSession(String sessionIdHex) {
        if (lanes == null || lanes.tunnel() == null || lanes.tracker() == null) {
            return null;
        }
        if (sessionIdHex == null || sessionIdHex.isBlank()) {
            throw new IllegalArgumentException("missing session id");
        }
        String session = sessionIdHex.trim();
        Bytes worldId;
        try {
            worldId = Bytes.fromHex(session);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("malformed session id");
        }
        // Resolve who is hosting it. The tracker's routes answer is per-peer, and a peer publishes
        // both its P2P route and (for a LAN session) an "mc/" claim — only the former can be dialled
        // by this node's transport, so the mc/ claims are filtered out rather than tried and failed.
        dev.nodera.transport.PeerAddress host = null;
        for (var peer : lanes.tracker().routes(worldId).peers()) {
            if (peer.peer().equals(runtime.nodeId())) {
                continue; // ourselves: tunnelling to our own loopback would be a loop, not a join
            }
            for (String route : peer.routes()) {
                if (route != null && !route.isBlank()
                        && !route.startsWith(WorldHostingService.MC_ROUTE_PREFIX)) {
                    host = dev.nodera.transport.PeerAddress.of(peer.peer(), route);
                    break;
                }
            }
            if (host != null) {
                break;
            }
        }
        if (host == null) {
            throw new IllegalStateException(
                    "nobody reachable is hosting that world right now");
        }
        try {
            return lanes.tunnel().open(session, host).address();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not open a local port: " + e.getMessage(), e);
        }
    }

    @Override
    public String disconnectSession(String sessionIdHex) {
        if (lanes == null || lanes.tunnel() == null) {
            return "this worker cannot tunnel connections";
        }
        return lanes.tunnel().closeLocal(sessionIdHex) ? null : "not connected to that world";
    }

    @Override
    public String shareLink(String worldIdHex) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return null;
        }
        String id = worldIdHex.trim();
        WorldHostingService.HostedWorld world = hosting.hostedWorlds().stream()
                .filter(w -> w.worldIdHex().equalsIgnoreCase(id))
                .findFirst()
                .orElse(null);
        if (world == null) {
            return null; // → "this node does not know that world"
        }
        List<String> trackers = new ArrayList<>();
        if (lanes != null && lanes.tracker() != null) {
            for (var endpoint : lanes.tracker().endpoints()) {
                trackers.add(endpoint.toString());
            }
        }
        // A LAN session is an invitation to a live connection; a hosted world is an invitation to
        // content. The link says which, because they behave completely differently on arrival: one
        // is joinable only while the host's game is open, the other survives it.
        boolean lan = lanes != null && lanes.lan() != null
                && lanes.lan().byId(id).isPresent();
        dev.nodera.storage.WorldShareLink link = new dev.nodera.storage.WorldShareLink(
                Bytes.fromHex(id),
                world.name(),
                lan ? dev.nodera.storage.WorldShareLink.KIND_LAN
                        : dev.nodera.storage.WorldShareLink.KIND_WORLD,
                trackers,
                lanes == null ? List.of() : lanes.rendezvous(),
                world.worldPublicKey(),
                identity.nodeId().value().toString(),
                System.currentTimeMillis());
        return link.toUri();
    }

    // --- NODERA-CONFIG: the settings screen's other end ---------------------------------------

    /**
     * The live objects a configuration push is allowed to re-bound.
     *
     * <p>Deliberately a bundle of the <b>real</b> services rather than a copy of their values: the
     * whole point of the verb is that a setting changes what the running node does, so there is no
     * intermediate "config state" to drift out of sync with the objects. A seam left {@code null}
     * makes every key that depends on it {@code rejected} with a reason — never silently dropped.
     *
     * @Thread-context immutable holder; the services behind it are individually thread-safe.
     */
    public static final class ConfigSeams {

        private final dev.nodera.distribution.ContentTransferService content;
        private final WorldReplicationService replication;
        private final dev.nodera.transport.socket.SocketPeerTransport transport;
        private final dev.nodera.peer.discovery.TrackerClient tracker;
        private final dev.nodera.storage.fs.FsContentStore contentStore;

        /**
         * @param content     the piece plane (upload/download bounds, the pause flag); nullable.
         * @param replication the replication lane (byte budget, sweep cadence); nullable.
         * @param transport   the socket transport (connection cap); nullable.
         * @param tracker     the node's shared tracker client (endpoint list); nullable.
         */
        public ConfigSeams(dev.nodera.distribution.ContentTransferService content,
                           WorldReplicationService replication,
                           dev.nodera.transport.socket.SocketPeerTransport transport,
                           dev.nodera.peer.discovery.TrackerClient tracker) {
            this(content, replication, transport, tracker, null);
        }

        /**
         * As above, plus the relocatable blob store behind {@code storage.peer_worlds_dir} (L-58).
         *
         * @param contentStore the on-disk content store, or {@code null} when this embedding has
         *                     none — the archive-directory key is then {@code rejected} with a
         *                     reason rather than silently reported as applied.
         */
        public ConfigSeams(dev.nodera.distribution.ContentTransferService content,
                           WorldReplicationService replication,
                           dev.nodera.transport.socket.SocketPeerTransport transport,
                           dev.nodera.peer.discovery.TrackerClient tracker,
                           dev.nodera.storage.fs.FsContentStore contentStore) {
            this.content = content;
            this.replication = replication;
            this.transport = transport;
            this.tracker = tracker;
            this.contentStore = contentStore;
        }
    }

    // Config keys.
    //
    // These are the companion app's OWN settings-document field names, not this worker's internal
    // ones — `network.default_trackers`, not `network.tracker_endpoints`; `storage.peer_worlds_dir`,
    // not `storage.archive_dir`. That identity is the whole mechanism behind the app's honesty
    // badges: it decides whether a control is "live" purely by looking for that control's key in
    // the `applied` list this handler returns. A worker-internal name would need a translation
    // table on the app side, and the day the two drifted the app would quietly badge a setting as
    // enforced because a *differently-named* key came back.
    //
    // The strings are therefore wire contract. `rust/nodera-app/src/config.rs` has a golden-string
    // test pinning what it emits; these must equal it.
    static final String K_UPLOAD = "network.max_upload_bytes_per_sec";
    static final String K_DOWNLOAD = "network.max_download_bytes_per_sec";
    /** The UI calls this "upload slots per world"; it lands on the per-manifest in-flight serve cap. */
    static final String K_SERVE_INFLIGHT = "network.max_upload_slots_per_world";
    static final String K_MAX_CONNECTIONS = "network.max_connections";
    static final String K_TRACKERS = "network.default_trackers";
    static final String K_PAUSED = "behavior.transfers_paused";
    static final String K_REPL_BUDGET = "storage.replication_budget_bytes";
    static final String K_REPL_SWEEP = "storage.replication_sweep_seconds";
    static final String K_ARCHIVE_DIR = "storage.peer_worlds_dir";

    /**
     * Keys the worker understands but cannot change without being restarted — they are read once
     * at startup from the spawn environment and rebuilding their owner would drop live state
     * (an open listener, an open content store). Reported as {@code restart_required} so the app
     * can offer the restart button instead of pretending the value took effect.
     */
    private static final List<String> RESTART_REQUIRED_KEYS = List.of(
            "network.p2p_port", "network.port_range", "network.rendezvous_endpoints");

    /**
     * Keys this worker will <b>never</b> honour, each with the reason the app shows in a tooltip.
     * These are not "not yet": they are unimplementable against the wire as it exists, and saying
     * so once here is what stops the UI from carrying a permanent lie.
     */
    private static final Map<String, String> NEVER_KEYS = Map.of(
            "network.max_connections_per_world",
            "the transport has no world dimension; a socket is not owned by a world",
            "network.unlimited_connections_only",
            "no peer advertises a connection cap on the wire, so there is nothing to filter on");

    @Override
    public String applyConfig(String configJsonB64) {
        if (config == null) {
            return null; // → NODERA-ERR unsupported
        }
        if (configJsonB64 == null || configJsonB64.isBlank()) {
            // The dispatch routes an empty payload to readConfig, so reaching here means a direct
            // caller handed us nothing. "Nothing" is not "unset everything".
            throw new IllegalArgumentException("missing config payload");
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(configJsonB64.trim()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // Thrown out so the dispatch turns it into NODERA-ERR: a payload we could not read is
            // NOT an empty config, and applying "nothing" would silently unset the user's settings.
            throw new IllegalArgumentException("malformed base64 config payload");
        }
        Map<String, String> fields = parseFlatJson(json);
        if (fields.isEmpty() && !json.trim().equals("{}")) {
            throw new IllegalArgumentException("config payload is not a flat JSON object");
        }

        List<String> applied = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        Map<String, String> rejected = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            String raw = entry.getValue();
            String never = NEVER_KEYS.get(key);
            if (never != null) {
                rejected.put(key, never);
                continue;
            }
            if (RESTART_REQUIRED_KEYS.contains(key)) {
                restart.add(key);
                continue;
            }
            try {
                String failure = applyOne(key, raw);
                if (failure == null) {
                    applied.add(key);
                } else {
                    rejected.put(key, failure);
                }
            } catch (RuntimeException e) {
                // A bad value for one key must not lose the other keys in the same push.
                rejected.put(key, e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage());
            }
        }
        return outcomeJson(applied, restart, rejected);
    }

    /**
     * Apply one key. Returns {@code null} when it was genuinely applied, or the reason it was not —
     * an unknown key included, because a key we drop on the floor would otherwise be indistinguishable
     * to the app from one we honoured.
     */
    private String applyOne(String key, String raw) {
        switch (key) {
            case K_UPLOAD -> {
                if (config.content == null) {
                    return "this worker has no content plane";
                }
                config.content.setServeBounds(config.content.serveMaxInflight(),
                        unlimitedIfZero(asLong(raw)));
                return null;
            }
            case K_SERVE_INFLIGHT -> {
                if (config.content == null) {
                    return "this worker has no content plane";
                }
                config.content.setServeBounds(
                        (int) Math.min(Integer.MAX_VALUE, unlimitedIfZero(asLong(raw))),
                        config.content.serveBandwidthBudget());
                return null;
            }
            case K_DOWNLOAD -> {
                if (config.content == null) {
                    return "this worker has no content plane";
                }
                config.content.setDownloadBandwidthBudget(asLong(raw));
                return null;
            }
            case K_PAUSED -> {
                if (config.content == null) {
                    return "this worker has no content plane";
                }
                config.content.setTransfersPaused(asBool(raw));
                return null;
            }
            case K_MAX_CONNECTIONS -> {
                if (config.transport == null) {
                    return "this worker's transport does not expose a connection cap";
                }
                long limit = asLong(raw);
                // 0 is the app's "unlimited"; the transport's unbounded value is MAX_VALUE.
                config.transport.setMaxConnections(
                        limit <= 0 ? Integer.MAX_VALUE : (int) Math.min(limit, Integer.MAX_VALUE));
                return null;
            }
            case K_TRACKERS -> {
                if (config.tracker == null) {
                    return "this worker has no tracker client";
                }
                List<dev.nodera.peer.discovery.TrackerClient.Endpoint> parsed = new ArrayList<>();
                for (String route : asStringList(raw)) {
                    parsed.add(dev.nodera.peer.discovery.TrackerClient.Endpoint.parse(route));
                }
                config.tracker.setEndpoints(parsed);
                return null;
            }
            case K_REPL_BUDGET -> {
                if (config.replication == null) {
                    return "this worker has no replication lane";
                }
                // 0 is the app's "use the worker's default", documented as such in
                // `rust/nodera-app/src/settings.rs` and shipped as the DEFAULT value. The
                // replication lane reads 0 as "hold nothing for anybody" and refuses to schedule a
                // sweep at all — so every worker running under the app silently stopped adopting
                // worlds, and a peer supporting a world never received a single piece of it. Same
                // shape of disagreement as the upload cap; same translation.
                long budget = asLong(raw);
                config.replication.reconfigure(
                        budget <= 0 ? WorldReplicationService.DEFAULT_BUDGET_BYTES : budget,
                        config.replication.sweepSeconds());
                return null;
            }
            case K_REPL_SWEEP -> {
                if (config.replication == null) {
                    return "this worker has no replication lane";
                }
                long sweep = asLong(raw);
                // Clamped, not cast. The `<= 0` guard reads the long, so a bare `(int)` afterwards
                // let a value the guard had just approved come out the other side as zero or
                // negative: 2^31 truncates to Integer.MIN_VALUE, 2^32 to 0. Either one reaches
                // `scheduleWithFixedDelay` as a non-positive period. Same idiom as the two caps
                // above — saturate at Integer.MAX_VALUE and let the lane's own bounds take it from
                // there.
                config.replication.reconfigure(config.replication.budgetBytes(),
                        sweep <= 0 ? WorldReplicationService.DEFAULT_SWEEP_SECONDS
                                : (int) Math.min(Integer.MAX_VALUE, sweep));
                return null;
            }
            case K_ARCHIVE_DIR -> {
                // L-58: this used to be restart_required, which quietly stranded whatever the node
                // was already seeding — the blobs a node holds ARE its seeding obligations, so
                // re-pointing the path without moving them leaves every piece request missing.
                // The content moves first; the store re-points only after.
                if (config.contentStore == null) {
                    return "this worker has no relocatable content store";
                }
                String path = raw == null ? "" : unquote(raw).trim();
                if (path.isEmpty()) {
                    return "an empty archive directory is not a location";
                }
                // Same guard as the seed/fetch verbs: this one MOVES every blob this node holds,
                // so an unconstrained destination is a write primitive with the node's whole
                // content store behind it.
                config.contentStore.relocateTo(
                        controlPaths.resolve(path, "archive directory"));
                return null;
            }
            default -> {
                return "unknown setting";
            }
        }
    }

    @Override
    public String readConfig() {
        if (config == null) {
            return null; // → NODERA-ERR unsupported
        }
        // The worker's own effective view, not an echo of the last push: a value the worker clamped
        // (a sweep cadence below its 30 s floor, a connection cap of 0) must read back at what it
        // actually enforces, or the settings screen becomes a second facade over the first.
        List<String> fields = new ArrayList<>();
        if (config.content != null) {
            fields.add("\"" + K_UPLOAD + "\":" + zeroIfUnlimited(
                    config.content.serveBandwidthBudget(), Long.MAX_VALUE));
            fields.add("\"" + K_DOWNLOAD + "\":" + config.content.downloadBandwidthBudget());
            fields.add("\"" + K_SERVE_INFLIGHT + "\":" + zeroIfUnlimited(
                    config.content.serveMaxInflight(), Integer.MAX_VALUE));
            fields.add("\"" + K_PAUSED + "\":" + config.content.transfersPaused());
        }
        if (config.transport != null) {
            int cap = config.transport.maxConnections();
            fields.add("\"" + K_MAX_CONNECTIONS + "\":" + (cap == Integer.MAX_VALUE ? 0 : cap));
        }
        if (config.replication != null) {
            fields.add("\"" + K_REPL_BUDGET + "\":" + config.replication.budgetBytes());
            fields.add("\"" + K_REPL_SWEEP + "\":" + config.replication.sweepSeconds());
        }
        if (config.tracker != null) {
            List<String> routes = new ArrayList<>();
            for (var e : config.tracker.endpoints()) {
                routes.add("\"" + escape(e.toString()) + "\"");
            }
            fields.add("\"" + K_TRACKERS + "\":[" + String.join(",", routes) + "]");
        }
        return "{" + String.join(",", fields) + "}";
    }

    /**
     * Translate the app's {@code 0 = unlimited} into the bound the content plane enforces.
     *
     * <p>The two sides disagreed about what zero means, and the disagreement was silent and total.
     * {@code rust/nodera-app/src/settings.rs} documents "0 means unlimited, for every field below"
     * and ships {@code max_upload_bytes_per_sec: 0} as the DEFAULT, pushed to the worker the moment
     * the companion app connects. {@code ContentTransferService.setServeBounds} reads zero as
     * "serve nothing" — a legitimate internal state, since that is how an upload cap dragged to
     * zero is expressed. So every worker running under the app stopped answering piece requests
     * entirely, while still announcing the world, answering manifest queries and looking healthy.
     *
     * <p>What that cost, live: a joiner whose host closed its game sat on "Migrating world…" and
     * failed with {@code archive fetch stalled at 0/75 piece(s) after 120s with no progress, from
     * 2 seeder(s)} — from two seeders that held every piece and were refusing to serve any of them
     * because the app had told them to. Silence is the wire's only answer to an unserved request,
     * so nothing at either end said what was wrong.
     *
     * <p>{@code K_MAX_CONNECTIONS} already carried this translation. It belongs on every capped
     * field the app owns, which is what this makes explicit.
     *
     * @param value the app's value.
     * @return the same value, or {@link Long#MAX_VALUE} when it asked for "no limit".
     */
    private static long unlimitedIfZero(long value) {
        return value <= 0 ? Long.MAX_VALUE : value;
    }

    /**
     * The inverse, so a read-back speaks the app's dialect rather than the enforcer's.
     *
     * <p>The sentinel is passed in rather than assumed, because the two bounds saturate at
     * different values — a {@code long} budget at {@link Long#MAX_VALUE}, an {@code int} slot count
     * at {@link Integer#MAX_VALUE} — and treating the smaller one as "unlimited" for both would
     * report a real 2 GB/s upload cap as "no cap".
     *
     * @param enforced the bound the content plane actually enforces.
     * @param unlimited the value that means "no bound" for this field.
     * @return {@code 0} when unbounded, else the bound itself.
     */
    private static long zeroIfUnlimited(long enforced, long unlimited) {
        return enforced == unlimited ? 0 : enforced;
    }

    /** Render the outcome the app badges its settings from. Empty collections are still emitted. */
    private static String outcomeJson(List<String> applied, List<String> restartRequired,
                                      Map<String, String> rejected) {
        List<String> appliedJson = new ArrayList<>(applied.size());
        for (String key : applied) {
            appliedJson.add("\"" + escape(key) + "\"");
        }
        List<String> restartJson = new ArrayList<>(restartRequired.size());
        for (String key : restartRequired) {
            restartJson.add("\"" + escape(key) + "\"");
        }
        List<String> rejectedJson = new ArrayList<>(rejected.size());
        for (Map.Entry<String, String> e : rejected.entrySet()) {
            rejectedJson.add("\"" + escape(e.getKey()) + "\":\"" + escape(e.getValue()) + "\"");
        }
        return "{\"applied\":[" + String.join(",", appliedJson) + "],"
                + "\"restart_required\":[" + String.join(",", restartJson) + "],"
                + "\"rejected\":{" + String.join(",", rejectedJson) + "}}";
    }

    /**
     * Split a <b>flat</b> JSON object into key → raw-value text, preserving order.
     *
     * <p>Hand-rolled on purpose: the worker's classpath carries no JSON library, and adding one for
     * a dozen scalar settings would be the largest dependency in the module. The grammar accepted is
     * exactly what the config payload is defined to be — one object, string keys, values that are
     * numbers, booleans, quoted strings, or flat arrays of quoted strings. A nested object is not
     * accepted, which is why the key namespace is dotted rather than hierarchical.
     */
    static Map<String, String> parseFlatJson(String json) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        // Scanned in one pass rather than matched with a regex. The natural pattern for a JSON
        // string — `"((?:[^"\\]|\\.)*)"` — backtracks POLYNOMIALLY: the two alternatives both
        // match an ordinary character, so an unterminated run costs O(n^2) to fail. This input
        // arrives from the control endpoint, so that is a denial of service reachable by anything
        // that can open the socket. A scanner has no such shape and is easier to read besides.
        int i = 0;
        int n = json.length();
        while (i < n) {
            if (json.charAt(i) != '"') {
                i++;
                continue;
            }
            int keyEnd = endOfQuoted(json, i);
            if (keyEnd < 0) {
                break;                               // unterminated string: nothing further parses
            }
            String key = unescape(json, i + 1, keyEnd);
            i = keyEnd + 1;
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= n || json.charAt(i) != ':') {
                continue;                            // a string that was not a key; keep scanning
            }
            i++;
            while (i < n && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int valueStart = i;
            char first = json.charAt(i);
            if (first == '"') {
                int end = endOfQuoted(json, i);
                if (end < 0) {
                    break;
                }
                i = end + 1;
            } else if (first == '[') {
                int end = json.indexOf(']', i);
                if (end < 0) {
                    break;
                }
                i = end + 1;
            } else {
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}'
                        && !Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
            }
            out.put(key, json.substring(valueStart, i).trim());
        }
        return out;
    }

    /**
     * Index of the closing quote of the string starting at {@code open}, or -1 if unterminated.
     *
     * <p>One pass, honouring backslash escapes. This replaces the regex both parsers used to share.
     */
    private static int endOfQuoted(String s, int open) {
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;                                 // skip whatever the backslash escapes
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /** The contents of a quoted range, with JSON backslash escapes resolved. */
    private static String unescape(String s, int from, int toExclusive) {
        StringBuilder out = new StringBuilder(toExclusive - from);
        for (int i = from; i < toExclusive; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < toExclusive) {
                out.append(s.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Coerce a raw JSON scalar to a long, tolerating a quoted number (the app may send either). */
    private static long asLong(String raw) {
        String v = unquote(raw);
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number, got '" + raw + "'");
        }
    }

    /** Coerce a raw JSON scalar to a boolean; anything but true/false is an error, never "false". */
    private static boolean asBool(String raw) {
        String v = unquote(raw).trim();
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new IllegalArgumentException("expected true/false, got '" + raw + "'");
    }

    /** Coerce a raw JSON array of strings (or a single comma-separated string) to a list. */
    private static List<String> asStringList(String raw) {
        List<String> out = new ArrayList<>();
        String v = raw.trim();
        if (v.startsWith("[")) {
            // Scanned, not matched: same polynomial-backtracking shape as parseFlatJson, same
            // reachability from the control endpoint.
            int i = 0;
            while (i < v.length()) {
                if (v.charAt(i) != '"') {
                    i++;
                    continue;
                }
                int end = endOfQuoted(v, i);
                if (end < 0) {
                    break;
                }
                String item = unescape(v, i + 1, end).trim();
                i = end + 1;
                if (!item.isEmpty()) {
                    out.add(item);
                }
            }
            return out;
        }
        for (String item : unquote(v).split(",")) {
            if (!item.trim().isEmpty()) {
                out.add(item.trim());
            }
        }
        return out;
    }

    private static String unquote(String raw) {
        String v = raw.trim();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1) : v;
    }

    private static String endpointArray(List<WorldHostingService.EndpointHealth> health) {
        List<String> rows = new ArrayList<>(health.size());
        for (WorldHostingService.EndpointHealth e : health) {
            rows.add("{\"host\":\"" + escape(e.host()) + "\",\"port\":" + e.port()
                    + ",\"scheme\":\"" + escape(e.scheme()) + "\""
                    + ",\"reachable\":" + e.reachable()
                    + ",\"latency_ms\":" + e.latencyMillis() + "}");
        }
        return String.join(",", rows);
    }

    private static Bytes decodeBytes(String b64) {
        if (b64 == null || b64.isBlank()) {
            return Bytes.empty();
        }
        try {
            return Bytes.unsafeWrap(Base64.getDecoder().decode(b64));
        } catch (IllegalArgumentException e) {
            return Bytes.empty();
        }
    }

    private static String decodeB64(String b64) {
        if (b64 == null || b64.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return b64; // tolerate a plain name
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Base64 for a JSON string field; empty bytes render as {@code ""}, never as a placeholder. */
    private static String base64(Bytes bytes) {
        return bytes == null || bytes.isEmpty()
                ? "" : Base64.getEncoder().encodeToString(bytes.toArray());
    }

    /** Minimal JSON string escaping for the hand-written state payload. */
    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
