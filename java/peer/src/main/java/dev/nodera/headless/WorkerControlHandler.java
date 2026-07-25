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
import java.util.List;
import java.util.Map;

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

    private final String version;
    private final NodeIdentity identity;
    private final NodeCapabilities capabilities;
    private final PeerRuntime runtime;
    private final TrafficMeter meter;
    private final WorldHostingService hosting;
    private final dev.nodera.peer.validation.WorkerValidationService validation;
    private final WorldArchiveService archive;
    private final PeerTrafficMeter peerMeter; // nullable — per-peer rows fall back to zeros
    private final dev.nodera.peer.discovery.PeerDiscoveryService discovery; // nullable
    private final ConfigSeams config; // nullable — no config plane wired
    private final WorldGrantGossipService grants; // nullable — no permission lane wired
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
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            String mc = world.mcRoute();
            WorldArchiveService.PieceReport report =
                    archive == null ? null : archive.pieceReport(world.worldIdHex());
            if (report != null) {
                totalPieces += report.pieceCount();
                totalHeldPieces += report.heldCount();
            }
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
                    + ",\"seeders\":" + (report == null ? 0 : report.holders().size())
                    + ",\"seeding\":" + world.seeding()
                    + "}");
        }

        // The worker's declared roles (BOOTSTRAP / FULL_ARCHIVE / REGION_VALIDATOR …).
        List<String> roleJson = new ArrayList<>();
        for (PeerRole role : capabilities.roles()) {
            roleJson.add("\"" + role.name() + "\"");
        }

        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        long sent = meter.bytesTx();
        long received = meter.bytesRx();

        return "{"
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
                + "\"daemon_up\":true"
                + "}";
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
     * Every activated region's head root, keyed by region (L-30). The counters above say validation
     * happened; only the roots say the peers agree about WHAT happened — which is the claim a live
     * mesh has to support, and the one thing no reply carried before.
     *
     * @return the {@code "region":"roothex"} pairs, comma separated (possibly empty).
     */
    private String regionRootsJson() {
        List<String> rows = new ArrayList<>();
        for (dev.nodera.core.region.RegionId region : validation.activeRegionIds()) {
            validation.headRoot(region).ifPresent(root -> rows.add(
                    "\"" + escape(regionKey(region)) + "\":\"" + root.hash().toHex() + "\""));
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

    @Override
    public String join(String worldId) {
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
        String error = hosting.seed(id.toHex(), "");
        if (error != null) {
            return error;
        }
        discovery.sweepNow();
        return null;
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
        byte[] blob;
        try {
            blob = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path));
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
    public String fetchArchive(String worldId, String destPathB64, long timeoutSeconds) {
        if (archive == null) {
            return null;
        }
        String path = decodeB64(destPathB64);
        if (worldId == null || worldId.isBlank() || path.isBlank()) {
            throw new IllegalArgumentException("missing worldId/destination path");
        }
        long seconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        byte[] blob = archive.fetchArchive(worldId, java.time.Duration.ofSeconds(seconds));
        long version = archive.newestManifest(worldId)
                .map(m -> m.version().value()).orElse(0L);
        try {
            java.nio.file.Path dest = java.nio.file.Path.of(path);
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
                                    boolean listed, boolean encrypted, String manifestRefB64) {
        Bytes genesisRoot = decodeBytes(genesisRootB64);
        Bytes manifestRef = decodeBytes(manifestRefB64);
        WorldIdentity id = WorldIdentity.create(identity, genesisRoot, createdAtEpoch, shared, listed,
                encrypted, manifestRef);
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        return Base64.getEncoder().encodeToString(w.toBytes().toArray());
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
        java.nio.file.Path archiveFile = java.nio.file.Path.of(decodeB64(archivePathB64));
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
        private final dev.nodera.storage.rocksdb.FsContentStore contentStore;

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
                           dev.nodera.storage.rocksdb.FsContentStore contentStore) {
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
                config.content.setServeBounds(config.content.serveMaxInflight(), asLong(raw));
                return null;
            }
            case K_SERVE_INFLIGHT -> {
                if (config.content == null) {
                    return "this worker has no content plane";
                }
                config.content.setServeBounds((int) asLong(raw),
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
                config.replication.reconfigure(asLong(raw), config.replication.sweepSeconds());
                return null;
            }
            case K_REPL_SWEEP -> {
                if (config.replication == null) {
                    return "this worker has no replication lane";
                }
                config.replication.reconfigure(config.replication.budgetBytes(), (int) asLong(raw));
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
                config.contentStore.relocateTo(java.nio.file.Path.of(path));
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
            fields.add("\"" + K_UPLOAD + "\":" + config.content.serveBandwidthBudget());
            fields.add("\"" + K_DOWNLOAD + "\":" + config.content.downloadBandwidthBudget());
            fields.add("\"" + K_SERVE_INFLIGHT + "\":" + config.content.serveMaxInflight());
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
        java.util.regex.Matcher m = FLAT_FIELD.matcher(json);
        while (m.find()) {
            out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    private static final java.util.regex.Pattern FLAT_FIELD = java.util.regex.Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\[[^\\]]*\\]|\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)");

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
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(v);
            while (m.find()) {
                String item = m.group(1).trim();
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
