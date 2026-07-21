package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.control.ControlHandler;
import dev.nodera.storage.WorldIdentity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 32/33: the worker's {@link ControlHandler} — answers the mod's / companion app's control verbs
 * from the live {@link PeerRuntime}, {@link TrafficMeter}, and the set of worlds this worker is
 * hosting. This is what turns the dashboard from placeholder zeros into real data, exposes the
 * worker's identity (the world-author identity), and is the delegation point for host/join/password.
 *
 * @Thread-context every method is called on a per-connection worker thread; state is held in
 *                 concurrent structures.
 */
public final class WorkerControlHandler implements ControlHandler {

    private final String version;
    private final NodeIdentity identity;
    private final PeerRuntime runtime;
    private final TrafficMeter meter;

    /** worldId → display name of worlds this worker is hosting. */
    private final Map<String, String> hostedWorlds = new ConcurrentHashMap<>();

    public WorkerControlHandler(String version, NodeIdentity identity, PeerRuntime runtime,
                                TrafficMeter meter) {
        this.version = version;
        this.identity = identity;
        this.runtime = runtime;
        this.meter = meter;
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
        // Peers currently in the mesh (excluding self).
        NodeId self = runtime.nodeId();
        List<String> peerJson = new ArrayList<>();
        for (NodeId id : runtime.sessionView().memberIds()) {
            if (id.equals(self)) {
                continue;
            }
            peerJson.add("{\"node_id\":\"" + id.value() + "\",\"route\":\"\","
                    + "\"path\":\"direct\",\"up_bytes_per_sec\":0,\"down_bytes_per_sec\":0}");
        }
        List<String> worldJson = new ArrayList<>();
        for (String name : hostedWorlds.values()) {
            worldJson.add("\"" + escape(name) + "\"");
        }
        // maintained pieces/bytes are 0 until the worker seeds content (Phase D data plane);
        // sent/received are the real metered totals; daemon_up is true (we are answering).
        return "{"
                + "\"maintained_pieces\":0,"
                + "\"maintained_bytes\":0,"
                + "\"total_sent_bytes\":" + meter.bytesTx() + ","
                + "\"total_received_bytes\":" + meter.bytesRx() + ","
                + "\"peers\":[" + String.join(",", peerJson) + "],"
                + "\"connected_worlds\":[" + String.join(",", worldJson) + "],"
                + "\"daemon_up\":true"
                + "}";
    }

    @Override
    public String host(String worldId, String worldNameB64, String optionsJson) {
        if (worldId == null || worldId.isBlank()) {
            return "missing worldId";
        }
        String name = decodeB64(worldNameB64);
        hostedWorlds.put(worldId, name.isBlank() ? worldId : name);
        // Rendezvous registration + tracker announce + content seeding is wired in Phase D; recording
        // the world here already surfaces it on the dashboard's "connected worlds" panel.
        return null;
    }

    @Override
    public String stop(String worldId) {
        hostedWorlds.remove(worldId);
        return null;
    }

    @Override
    public String join(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return "missing worldId";
        }
        // Resolve via tracker + dial via rendezvous/socket is Phase D; accepted as a no-op for now.
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

    /** @return the worlds this worker currently hosts (worldId → name), for tests/diagnostics. */
    public Map<String, String> hostedWorlds() {
        return Map.copyOf(hostedWorlds);
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
