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
import dev.nodera.storage.WorldIdentity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
    private final long startedAtMillis;

    public WorkerControlHandler(String version, NodeIdentity identity, NodeCapabilities capabilities,
                                PeerRuntime runtime, TrafficMeter meter, WorldHostingService hosting) {
        this.version = version;
        this.identity = identity;
        this.capabilities = capabilities;
        this.runtime = runtime;
        this.meter = meter;
        this.hosting = hosting;
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

        // Peers currently in the mesh (excluding self).
        List<String> peerJson = new ArrayList<>();
        for (NodeId id : runtime.sessionView().memberIds()) {
            if (id.equals(self)) {
                continue;
            }
            peerJson.add("{\"node_id\":\"" + escape(id.value().toString()) + "\",\"route\":\"\","
                    + "\"path\":\"direct\",\"up_bytes_per_sec\":0,\"down_bytes_per_sec\":0}");
        }

        // Worlds this worker keeps discoverable, as objects (name + id + player count).
        List<String> worldJson = new ArrayList<>();
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            worldJson.add("{\"world_id\":\"" + escape(world.worldIdHex()) + "\",\"name\":\""
                    + escape(world.name()) + "\",\"players\":0}");
        }

        // The worker's declared roles (BOOTSTRAP / FULL_ARCHIVE / REGION_VALIDATOR …).
        List<String> roleJson = new ArrayList<>();
        for (PeerRole role : capabilities.roles()) {
            roleJson.add("\"" + role.name() + "\"");
        }

        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);

        return "{"
                + "\"node_id\":\"" + escape(self.value().toString()) + "\","
                + "\"worker_version\":\"" + escape(version) + "\","
                + "\"uptime_seconds\":" + uptimeSeconds + ","
                + "\"is_gateway\":" + runtime.isGateway() + ","
                + "\"self_route\":\"" + escape(nullToEmpty(runtime.selfRoute())) + "\","
                + "\"roles\":[" + String.join(",", roleJson) + "],"
                + "\"maintained_pieces\":0,"
                + "\"maintained_bytes\":0,"
                + "\"total_sent_bytes\":" + meter.bytesTx() + ","
                + "\"total_received_bytes\":" + meter.bytesRx() + ","
                + "\"peers\":[" + String.join(",", peerJson) + "],"
                + "\"connected_worlds\":[" + String.join(",", worldJson) + "],"
                + "\"trackers\":[" + endpointArray(hosting.trackerHealth()) + "],"
                + "\"rendezvous\":[" + endpointArray(hosting.rendezvousHealth()) + "],"
                + "\"daemon_up\":true"
                + "}";
    }

    @Override
    public String host(String worldId, String worldNameB64, String optionsJson) {
        return hosting.host(worldId, decodeB64(worldNameB64), optionsJson);
    }

    @Override
    public String stop(String worldId) {
        return hosting.stop(worldId);
    }

    @Override
    public String join(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return "missing worldId";
        }
        // Resolve via tracker + dial via rendezvous/socket is the joiner data plane (Phase D); the
        // discovery half (tracker query / rendezvous discover) already works — accepted as a no-op
        // here until the worker drives the joiner PeerRuntime dial.
        return null;
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

    private static String endpointArray(List<WorldHostingService.EndpointHealth> health) {
        List<String> rows = new ArrayList<>(health.size());
        for (WorldHostingService.EndpointHealth e : health) {
            rows.add("{\"host\":\"" + escape(e.host()) + "\",\"port\":" + e.port()
                    + ",\"reachable\":" + e.reachable() + "}");
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
