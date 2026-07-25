package dev.nodera.headless;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim this suite exists to test is not "the verb parses" — it is <b>a setting actually
 * changed worker behaviour</b>.
 *
 * <p>So it stands up the real thing: a loopback {@link ControlServer} over a real
 * {@link WorkerControlHandler} over a real {@link WorldArchiveService}, seeds an archive so the
 * node genuinely has bytes to serve, then pushes {@code behavior.transfers_paused:true} through
 * {@code NODERA-CONFIG} and drives a real {@link ContentRequest} into the archive lane. A paused
 * node must emit <b>no {@link ContentChunk} at all</b>; flipping the flag back must make chunks
 * flow again over the same wire, from the same request. Anything less would be testing the JSON
 * round trip and calling it a feature.
 *
 * <p>No Minecraft types are involved, and the transport is a recorder rather than a socket, so the
 * assertion is on what the node <i>tried to send</i> — which is exactly the thing a pause has to
 * suppress.
 */
final class ConfigVerbIT {

    private final HashService hashes = new HashService();

    private ControlServer control;
    private WorldArchiveService archive;
    private WorldHostingService hosting;
    private WorldReplicationService replication;
    private TrackerClient tracker;
    private PeerRuntime runtime;
    private LoopbackTransport meshTransport;
    private RecordingTransport contentTransport;

    @AfterEach
    void tearDown() {
        closeQuietly(control::close);
        closeQuietly(() -> {
            if (replication != null) {
                replication.close();
            }
        });
        closeQuietly(() -> {
            if (archive != null) {
                archive.close();
            }
        });
        closeQuietly(() -> {
            if (hosting != null) {
                hosting.close();
            }
        });
        closeQuietly(() -> {
            if (tracker != null) {
                tracker.close();
            }
        });
        closeQuietly(() -> {
            if (runtime != null) {
                runtime.stop();
            }
        });
        closeQuietly(() -> {
            if (meshTransport != null) {
                meshTransport.stop();
            }
        });
    }

    private static void closeQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // teardown is best-effort; a failure here must not mask the test's own failure.
        }
    }

    /** Boot the worker's real lanes behind a real control endpoint. */
    private NodeIdentity worker() {
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial()
                .withRoles(EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP));

        meshTransport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        meshTransport.start();
        runtime = PeerRuntime.bootstrap(identity, caps, meshTransport,
                () -> "loopback", PeerRuntimeConfig.defaults(), null);

        contentTransport = new RecordingTransport();
        tracker = new TrackerClient(List.of(), identity);
        archive = new WorldArchiveService(identity, contentTransport,
                new InMemoryContentStore(hashes), tracker);
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute, tracker,
                List.of(), archive::holdingsFor);
        replication = new WorldReplicationService(identity.nodeId(), tracker, archive, hosting,
                WorldReplicationService.DEFAULT_BUDGET_BYTES, 300);

        WorkerControlHandler handler = new WorkerControlHandler("config-test", identity, caps,
                runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, archive,
                null, null,
                new WorkerControlHandler.ConfigSeams(archive.content(), replication, null, tracker));
        control = new ControlServer("127.0.0.1", 0, handler);
        try {
            control.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("control server did not bind", e);
        }
        return identity;
    }

    @Test
    void pausingTransfersStopsTheWorkerServingPiecesAndResumingRestartsIt() throws Exception {
        worker();

        // A world this node genuinely holds — without real content the "no chunk" assertion would
        // pass for the wrong reason.
        String worldIdHex = hashes.sha256("config-verb-world".getBytes(StandardCharsets.UTF_8))
                .toHex();
        byte[] blob = new byte[300_000];
        new java.util.Random(7L).nextBytes(blob);
        PieceManifest manifest = archive.seedArchive(worldIdHex, blob);
        assertTrue(manifest.pieceCount() > 0);

        PeerAddress requester = PeerAddress.of(NodeId.random(), "peer:1");
        ContentRequest request = ContentRequest.of(manifest.manifestRoot(), 0);

        // Baseline: unconfigured, the node serves. (If it did not, the pause assertion below would
        // be vacuous.)
        archive.onMessage(requester, request);
        assertEquals(1, contentTransport.chunks().size(), "an unpaused node must serve the piece");
        contentTransport.clear();

        // --- push the setting over the real wire ------------------------------------------------
        String reply = config("{\"behavior.transfers_paused\":true}");
        assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
        assertTrue(reply.contains("\"applied\":[\"behavior.transfers_paused\"]"), reply);

        // --- the actual claim -------------------------------------------------------------------
        archive.onMessage(requester, request);
        assertTrue(contentTransport.chunks().isEmpty(),
                "a paused worker must emit no ContentChunk, got: " + contentTransport.chunks());

        // …and the pause is visible to the dashboard, so it never looks like a fault.
        assertTrue(request(ControlProtocol.STATE + " 2").contains("\"transfers_paused\":true"));

        // --- and back ---------------------------------------------------------------------------
        String resumed = config("{\"behavior.transfers_paused\":false}");
        assertTrue(resumed.contains("\"applied\":[\"behavior.transfers_paused\"]"), resumed);
        archive.onMessage(requester, request);
        assertEquals(1, contentTransport.chunks().size(),
                "resuming must make the very same request serve again");
        assertTrue(request(ControlProtocol.STATE + " 2").contains("\"transfers_paused\":false"));
    }

    @Test
    void appliedRestartRequiredAndRejectedAreReportedSeparately() throws Exception {
        worker();

        String reply = config("{"
                + "\"network.max_upload_bytes_per_sec\":65536,"
                + "\"storage.replication_sweep_seconds\":600,"
                + "\"network.port_range\":\"25620-25624\","
                + "\"network.max_connections_per_world\":8,"
                + "\"network.max_connections\":32,"
                + "\"network.not_a_setting\":1}");
        assertNotNull(reply);
        assertFalse(reply.startsWith(ControlProtocol.ERR), reply);

        // Applied: the two keys backed by a live seam — and they really moved.
        assertTrue(reply.contains("network.max_upload_bytes_per_sec"), reply);
        assertEquals(65536L, archive.content().serveBandwidthBudget());
        assertEquals(600, replication.sweepSeconds());

        // Restart-required: read once at spawn, so the app offers a restart instead of a fib.
        assertTrue(reply.contains("\"restart_required\":[\"network.port_range\"]"), reply);

        // Rejected, each with a reason the UI can show. max_connections has no transport seam
        // wired in this embedding, which is a rejection — never a silent success.
        assertTrue(reply.contains("\"network.max_connections_per_world\":\"the transport has no "
                + "world dimension; a socket is not owned by a world\""), reply);
        assertTrue(reply.contains("\"network.not_a_setting\":\"unknown setting\""), reply);
        assertTrue(reply.contains("\"network.max_connections\":\""), reply);

        // The truthfulness invariant B6's badges rest on: nothing rejected or restart-required may
        // also be claimed as applied. Read the "applied" array alone, not the whole reply.
        String appliedArray = reply.substring(reply.indexOf("\"applied\":[") + "\"applied\":[".length(),
                reply.indexOf(']'));
        assertFalse(appliedArray.contains("max_connections_per_world"), reply);
        assertFalse(appliedArray.contains("max_connections"), reply);
        assertFalse(appliedArray.contains("not_a_setting"), reply);
        assertFalse(appliedArray.contains("port_range"), reply);
    }

    /**
     * The cross-language key contract, pinned against the companion app's own golden string.
     *
     * <p>The JSON below is copied verbatim from
     * {@code rust/nodera-app/src/config.rs::default_settings_serialise_to_the_agreed_json}, which is
     * what the app actually puts on the wire for a default settings document. Every key in it must
     * be one this worker recognises.
     *
     * <p>This test exists because the two sides were written independently and <b>did</b> drift:
     * the worker originally expected its own internal names ({@code network.tracker_endpoints},
     * {@code storage.archive_dir}, {@code network.serve_max_inflight}) while the app sent its
     * settings-document names. Nothing crashed — the keys simply came back under {@code rejected}
     * as "unknown setting", so three controls would have sat there looking saved and done nothing.
     * A rename on either side reintroduces exactly that, and only a test that feeds one side's real
     * output into the other can catch it.
     */
    @Test
    void everyKeyTheCompanionAppSendsIsOneThisWorkerRecognises() throws Exception {
        worker();

        String appGolden = "{\"behavior.transfers_paused\":false,"
                + "\"network.default_trackers\":[\"tcp://127.0.0.1:25600\"],"
                + "\"network.unlimited_connections_only\":false,"
                + "\"network.max_connections\":200,"
                + "\"network.max_connections_per_world\":50,"
                + "\"network.max_upload_slots_per_world\":4,"
                + "\"network.max_upload_bytes_per_sec\":0,"
                + "\"network.max_download_bytes_per_sec\":0,"
                + "\"network.port_range\":\"random\","
                + "\"storage.peer_worlds_dir\":\"\"}";

        String reply = config(appGolden);
        assertNotNull(reply);
        assertFalse(reply.startsWith(ControlProtocol.ERR), reply);

        // "unknown setting" is the signature of a key-name mismatch. The two structurally-impossible
        // controls are still rejected here — but for their own documented reasons, never this one.
        assertFalse(reply.contains("unknown setting"),
                "the app sent a key this worker does not know — the key tables have drifted: " + reply);
    }

    @Test
    void configReadsBackTheWorkersOwnEffectiveValuesNotTheLastPush() throws Exception {
        worker();

        // 5 seconds is below WorldReplicationService's 30 s floor: the worker clamps it, and the
        // read-back must show what it enforces rather than what it was asked for — otherwise the
        // settings screen is a second facade over the first one.
        config("{\"storage.replication_sweep_seconds\":5,\"network.max_download_bytes_per_sec\":2048}");

        String effective = request(ControlProtocol.CONFIG + " 2");
        assertFalse(effective.startsWith(ControlProtocol.ERR), effective);
        assertTrue(effective.contains("\"storage.replication_sweep_seconds\":30"), effective);
        assertTrue(effective.contains("\"network.max_download_bytes_per_sec\":2048"), effective);
        assertTrue(effective.contains("\"behavior.transfers_paused\":false"), effective);
        assertEquals(30, replication.sweepSeconds());
    }

    @Test
    void malformedBase64IsAnErrorRatherThanAnEmptyConfig() throws Exception {
        worker();
        archive.content().setTransfersPaused(true);

        String reply = request(ControlProtocol.CONFIG + " 2 !!!!not-base64!!!!");
        assertTrue(reply.startsWith(ControlProtocol.ERR), reply);
        // The pre-existing setting survived: an unreadable payload must never be read as "unset
        // everything", which would silently undo the user's configuration.
        assertTrue(archive.content().transfersPaused());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String config(String json) throws Exception {
        return request(ControlProtocol.CONFIG + " 2 " + Base64.getEncoder()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** Send one control line, return the single reply line. */
    private String request(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    /**
     * A {@link PeerTransport} that records what the node <i>tried to send</i>. That is the right
     * observation point for a pause: suppression happens before the frame reaches any socket, so a
     * real network would only add flakiness without adding evidence.
     */
    private static final class RecordingTransport implements PeerTransport {

        private final List<NoderaMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void start() {
            // nothing to bind
        }

        @Override
        public void stop() {
            // nothing to unbind
        }

        @Override
        public void send(PeerAddress to, byte[] frame) {
            sent.add(MessageCodec.decode(frame));
        }

        @Override
        public void sendStream(PeerAddress to, long streamId, byte[] payload) {
            throw new UnsupportedOperationException("not used by the archive lane");
        }

        @Override
        public void setHandler(MessageHandler handler) {
            // the archive lane is driven directly in this test
        }

        @Override
        public String listenRoute() {
            return "recording";
        }

        List<ContentChunk> chunks() {
            List<ContentChunk> out = new ArrayList<>();
            for (NoderaMessage m : sent) {
                if (m instanceof ContentChunk chunk) {
                    out.add(chunk);
                }
            }
            return out;
        }

        void clear() {
            sent.clear();
        }
    }
}
