package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.SessionDelegation;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.storage.WorldAdminProof;
import dev.nodera.storage.WorldIdentity;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.storage.fs.FsContentStore;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control socket's verbs, driven the way the mod drives them: a line in, a line out.
 *
 * <p>Six sibling classes over one subject — the worker's control protocol. Every one of them opens
 * a real socket to a real {@code ControlServer} and speaks the product's own wire, so no assertion
 * here can pass on a state the product cannot report.
 */
final class ControlVerbsIT {

    /**
     * The claim this suite exists to test is not "the verb parses" — it is <b>a setting actually
     * changed worker behaviour</b>.
     *
     * <p>So it stands up the real thing: a loopback control endpoint over a real
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
    @Nested
    final class ConfigVerbIT {

        private final PeerTestHarness harness = PeerTestHarness.create();
        private final HashService hashes = harness.hashes();
        private final RecordingTransport contentTransport = new RecordingTransport();

        private WorkerNode worker;
        private FsContentStore diskStore;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        /** Boot the worker's real lanes behind a real control endpoint. */
        private void worker() throws java.io.IOException {
            worker(null);
        }

        /**
         * @param archiveDir where the archive's blobs live, or {@code null} for an in-memory store —
         *                   the relocation test needs a directory to move content out of.
         */
        private void worker(Path archiveDir) throws java.io.IOException {
            diskStore = archiveDir == null ? null : new FsContentStore(archiveDir, hashes);
            PeerTestHarness.WorkerNodeBuilder builder = harness.workerNode("config-test")
                    .roles(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP)
                    .sharedTracker()
                    .contentTransport(contentTransport)
                    .withReplication()
                    .withConfigSeams();
            worker = (diskStore == null ? builder.inMemoryArchive() : builder.archive(diskStore))
                    .build();
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
            PieceManifest manifest = worker.archive().seedArchive(worldIdHex, blob);
            assertTrue(manifest.pieceCount() > 0);

            PeerAddress requester = PeerAddress.of(NodeId.random(), "peer:1");
            ContentRequest request = ContentRequest.of(manifest.manifestRoot(), 0);

            // Baseline: unconfigured, the node serves. (If it did not, the pause assertion below would
            // be vacuous.)
            worker.archive().onMessage(requester, request);
            assertEquals(1, contentTransport.chunks().size(), "an unpaused node must serve the piece");
            contentTransport.clear();

            // --- push the setting over the real wire ------------------------------------------------
            String reply = config("{\"behavior.transfers_paused\":true}");
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertTrue(reply.contains("\"applied\":[\"behavior.transfers_paused\"]"), reply);

            // --- the actual claim -------------------------------------------------------------------
            worker.archive().onMessage(requester, request);
            assertTrue(contentTransport.chunks().isEmpty(),
                    "a paused worker must emit no ContentChunk, got: " + contentTransport.chunks());

            // …and the pause is visible to the dashboard, so it never looks like a fault.
            assertTrue(worker.state().contains("\"transfers_paused\":true"));

            // --- and back ---------------------------------------------------------------------------
            String resumed = config("{\"behavior.transfers_paused\":false}");
            assertTrue(resumed.contains("\"applied\":[\"behavior.transfers_paused\"]"), resumed);
            worker.archive().onMessage(requester, request);
            assertEquals(1, contentTransport.chunks().size(),
                    "resuming must make the very same request serve again");
            assertTrue(worker.state().contains("\"transfers_paused\":false"));
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
            assertEquals(65536L, worker.archive().content().serveBandwidthBudget());
            assertEquals(600, worker.replication().sweepSeconds());

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
         * {@code app/src/config.rs::default_settings_serialise_to_the_agreed_json}, which is what the
         * app actually puts on the wire for a default settings document. Every key in it must be one
         * this worker recognises.
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
        void changingTheArchiveDirectoryRelocatesWhatThisNodeIsSeeding(@TempDir Path tmp)
                throws Exception {
            // L-58: this key used to be reported as restart_required, which quietly stranded the
            // node's seeding obligations — the world would stay listed while every piece request
            // missed. The content has to move with the setting.
            Path oldDir = tmp.resolve("old");
            Path newDir = tmp.resolve("new");
            worker(oldDir);

            String worldIdHex = hashes.sha256("relocating-world".getBytes(StandardCharsets.UTF_8))
                    .toHex();
            byte[] blob = new byte[128_000];
            new java.util.Random(11L).nextBytes(blob);
            var manifest = worker.archive().seedArchive(worldIdHex, blob);
            assertTrue(diskStore.size() > 0, "the node is actually holding something to strand");

            String reply = config("{\"storage.peer_worlds_dir\":\"" + newDir + "\"}");
            assertNotNull(reply);
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertTrue(reply.contains("\"applied\":[\"storage.peer_worlds_dir\"]"),
                    "the directory change must be APPLIED, not deferred to a restart: " + reply);

            assertTrue(diskStore.contentRoot().startsWith(newDir), diskStore.contentRoot().toString());
            assertTrue(diskStore.has(manifest.blob()),
                    "the seeded blob survived the move, so the swarm's requests still hit");
            assertFalse(worker.archive().holdingsFor(worldIdHex).isEmpty(),
                    "and the node still advertises what it holds");
        }

        /**
         * frontend M-1 — <b>the folder the user picked on Android becomes the archive, and a world
         * archive is written into it.</b>
         *
         * <p>The app has always been able to open the system folder picker and persist the grant.
         * What it could not do was hand the result to the peer: Android 11+ withholds raw file
         * access to shared storage regardless of the grant, so what comes back is a
         * {@code content://} tree and the worker writes with {@code java.nio.file}. Pushing that URI
         * as {@code storage.peer_worlds_dir} used to reach the same path-containment guard the seed
         * and fetch verbs use, which correctly refused a string that is not a path — leaving the
         * setting {@code rejected} and the chosen folder decorative.
         *
         * <p>This drives the real control socket with the real verb and then asserts the thing the
         * limitation's exit clause actually names: <b>the world's archive bytes are in the picked
         * folder</b>, and the swarm can still be served from there. The document tree is a fake one
         * standing in for {@code DocumentsContract}; the physical exit test on a handset is the
         * clause this cannot reach, and it stays named in the register.
         */
        @Test
        void aFolderPickedWithAndroidsFileManagerBecomesTheArchive(@TempDir Path tmp)
                throws Exception {
            System.setProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY,
                    FakeDocumentTree.class.getName());
            FakeDocumentTree.reset();
            try {
                worker(tmp.resolve("app-private"));

                String worldIdHex = hashes.sha256("picked-folder-world"
                        .getBytes(StandardCharsets.UTF_8)).toHex();
                byte[] blob = new byte[128_000];
                new java.util.Random(23L).nextBytes(blob);
                var manifest = worker.archive().seedArchive(worldIdHex, blob);
                assertTrue(diskStore.size() > 0, "the node is holding a world to move");

                String tree = "content://com.android.externalstorage.documents"
                        + "/tree/primary%3ADocuments";
                String reply = config("{\"storage.peer_worlds_dir\":\"" + tree + "\"}");

                assertNotNull(reply);
                assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
                assertTrue(reply.contains("\"applied\":[\"storage.peer_worlds_dir\"]"),
                        "a folder the user picked must be APPLIED, not rejected as a bad path: "
                                + reply);
                assertEquals(tree, diskStore.contentLocation());
                assertNull(diskStore.contentRoot(), "a document tree has no filesystem path");
                assertTrue(FakeDocumentTree.blobCount() > 0,
                        "the world archive's bytes are in the folder the user chose");
                assertTrue(diskStore.has(manifest.blob()),
                        "and the swarm's piece requests still hit");
                assertFalse(worker.archive().holdingsFor(worldIdHex).isEmpty(),
                        "the node still advertises what it holds");
            } finally {
                System.clearProperty(SafBlobDirectory.BRIDGE_CLASS_PROPERTY);
                FakeDocumentTree.reset();
            }
        }

        @Test
        void anEmptyArchiveDirectoryIsRefusedRatherThanTreatedAsALocation() throws Exception {
            worker();
            String reply = config("{\"storage.peer_worlds_dir\":\"\"}");
            assertNotNull(reply);
            assertFalse(reply.contains("unknown setting"), reply);
            assertTrue(reply.contains("rejected"), reply);
        }

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

        /**
         * The app's DEFAULT settings must not switch seeding off.
         *
         * <p>Live, 2026-07-27: two players, one world, both workers running under the companion app.
         * The joiner's recovery failed with {@code archive fetch stalled at 0/75 piece(s) after 120s
         * with no progress, from 2 seeder(s)} — from two seeders that held all 75 pieces. Both had been
         * told, by the app, at connect, {@code network.max_upload_bytes_per_sec: 0}. The app documents
         * that as "unlimited"; {@code setServeBounds} reads it as "serve nothing". Nothing logged,
         * nothing failed, and the wire has no way to answer "I am refusing" — so the download simply
         * received silence until its deadline.
         *
         * <p>Asserted on behaviour rather than on the stored bound, because the bound is exactly what
         * both sides already agreed on and still disagreed about.
         */
        @Test
        void theAppsDefaultConfigurationLeavesTheNodeSeeding() throws Exception {
            worker();

            String worldIdHex = hashes.sha256("default-config-world".getBytes(StandardCharsets.UTF_8))
                    .toHex();
            // Three whole archive pieces, so "served everything asked for" and "served some of it" are
            // distinguishable counts rather than one chunk either way.
            byte[] blob = new byte[3 * WorldArchive.ARCHIVE_PIECE_BYTES];
            new java.util.Random(29L).nextBytes(blob);
            PieceManifest manifest = worker.archive().seedArchive(worldIdHex, blob);
            assertEquals(3, manifest.pieceCount());

            String reply = config("{\"network.max_upload_bytes_per_sec\":0,"
                    + "\"network.max_upload_slots_per_world\":0}");
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertTrue(reply.contains("network.max_upload_bytes_per_sec"), reply);

            PeerAddress requester = PeerAddress.of(NodeId.random(), "peer:1");
            ContentRequest wholeWorld =
                    new ContentRequest(manifest.manifestRoot(), List.of(0, 1, 2));
            worker.archive().onMessage(requester, wholeWorld);
            assertEquals(3, contentTransport.chunks().size(),
                    "0 means 'no limit' to the app, so an unconfigured cap must not stop the upload");

            // And the read-back speaks the app's dialect: an unlimited bound reports as 0, not as the
            // saturation value the enforcer happens to use.
            String effective = worker.request(ControlProtocol.CONFIG + " 2");
            assertTrue(effective.contains("\"network.max_upload_bytes_per_sec\":0"), effective);
            assertTrue(effective.contains("\"network.max_upload_slots_per_world\":0"), effective);

            // A real cap is still a real cap — "0 is unlimited" must not become "every value is".
            assertFalse(config("{\"network.max_upload_bytes_per_sec\":65536}")
                    .startsWith(ControlProtocol.ERR));
            assertEquals(65536L, worker.archive().content().serveBandwidthBudget());
            contentTransport.clear();
            // A fresh window, so the throttle below is the new budget refusing the piece and not the
            // bytes the unlimited run above already spent.
            worker.archive().content().resetServeWindow();
            worker.archive().onMessage(requester, wholeWorld);
            assertTrue(contentTransport.chunks().isEmpty(),
                    "a 64 KiB/s budget cannot pass a 256 KiB piece, got "
                            + contentTransport.chunks().size());
        }

        /**
         * The app's default storage settings must not switch replication off.
         *
         * <p>`app/src/settings.rs` documents `replication_budget_bytes: 0` as "the worker's default"
         * and ships it as the DEFAULT value, pushed on every connect.
         * {@link WorldReplicationService#start()} reads a zero budget as "hold nothing for anybody" and
         * schedules no sweep at all — so every worker running under the app quietly stopped adopting
         * worlds, and a peer supporting somebody else's world received not one piece of it. The live
         * symptom was a world row reading "Supporting for the network · v0 · 0 B · no manifest yet"
         * indefinitely, on a peer that was online, meshed, and doing nothing about it.
         */
        @Test
        void theAppsDefaultStorageSettingsLeaveReplicationRunning() throws Exception {
            worker();

            String reply = config("{\"storage.replication_budget_bytes\":0,"
                    + "\"storage.replication_sweep_seconds\":0}");
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertTrue(reply.contains("storage.replication_budget_bytes"), reply);

            assertEquals(WorldReplicationService.DEFAULT_BUDGET_BYTES, worker.replication().budgetBytes(),
                    "0 means 'your default' to the app; a zero budget here disables the lane entirely");
            assertEquals(WorldReplicationService.DEFAULT_SWEEP_SECONDS,
                    worker.replication().sweepSeconds());

            // A real budget is still a real budget — "0 is the default" must not become "any value is".
            assertFalse(config("{\"storage.replication_budget_bytes\":65536}")
                    .startsWith(ControlProtocol.ERR));
            assertEquals(65536L, worker.replication().budgetBytes());
        }

        @Test
        void configReadsBackTheWorkersOwnEffectiveValuesNotTheLastPush() throws Exception {
            worker();

            // 5 seconds is below WorldReplicationService's 30 s floor: the worker clamps it, and the
            // read-back must show what it enforces rather than what it was asked for — otherwise the
            // settings screen is a second facade over the first one.
            config("{\"storage.replication_sweep_seconds\":5,\"network.max_download_bytes_per_sec\":2048}");

            String effective = worker.request(ControlProtocol.CONFIG + " 2");
            assertFalse(effective.startsWith(ControlProtocol.ERR), effective);
            assertTrue(effective.contains("\"storage.replication_sweep_seconds\":30"), effective);
            assertTrue(effective.contains("\"network.max_download_bytes_per_sec\":2048"), effective);
            assertTrue(effective.contains("\"behavior.transfers_paused\":false"), effective);
            assertEquals(30, worker.replication().sweepSeconds());
        }

        /**
         * A sweep interval too large for an {@code int} saturates instead of truncating.
         *
         * <p>The guard that rejects a non-positive interval reads the value as a {@code long}, so a
         * narrowing cast after it handed back exactly what the guard had just refused: 2^32 truncates to
         * {@code 0} and 2^31 to {@link Integer#MIN_VALUE}. Nothing crashed, because
         * {@link WorldReplicationService#reconfigure} floors the interval at 30 s — which is what made
         * this worth a test rather than a one-line note. The corruption is silent and it *inverts* the
         * request: an operator asking for the longest sweep the field can express got the shortest one
         * the lane allows, and every read-back agreed with them that it had been applied.
         */
        @Test
        void aSweepIntervalBeyondAnIntSaturatesRatherThanTruncatingToTheFloor() throws Exception {
            worker();

            for (long huge : new long[] {1L << 31, 1L << 32, Long.MAX_VALUE}) {
                String reply = config("{\"storage.replication_sweep_seconds\":" + huge + "}");
                assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
                assertEquals(Integer.MAX_VALUE, worker.replication().sweepSeconds(),
                        huge + " must saturate, not wrap round to the 30 s floor");
            }
        }

        @Test
        void malformedBase64IsAnErrorRatherThanAnEmptyConfig() throws Exception {
            worker();
            worker.archive().content().setTransfersPaused(true);

            String reply = worker.request(ControlProtocol.CONFIG + " 2 !!!!not-base64!!!!");
            assertTrue(reply.startsWith(ControlProtocol.ERR), reply);
            // The pre-existing setting survived: an unreadable payload must never be read as "unset
            // everything", which would silently undo the user's configuration.
            assertTrue(worker.archive().content().transfersPaused());
        }

        /**
         * M-NET-1. A tracker store added in the companion app has to reach the worker that is
         * <i>already running</i>.
         *
         * <p>The mobile limitation read "the synchronised services list is read once, at worker boot"
         * — true of {@code SyncedServices.load}, which is the boot seed, and irrelevant to how a store
         * actually takes effect. The app merges every store's trackers into
         * {@code network.default_trackers} and pushes them over {@code NODERA-CONFIG}
         * ({@code config.rs::a_stores_trackers_are_pushed_to_a_running_worker}); this is the other half
         * — that the running worker changes which trackers it dials when it arrives. One shared
         * {@code TrackerClient} is what makes that reach every lane at once.
         *
         * <p>Nothing here is desktop-specific: on Android the same push crosses the same control
         * endpoint into a worker living in the app's own process.
         */
        @Test
        void aStoresTrackersChangeWhichTrackerARunningWorkerDials() throws Exception {
            worker();
            assertTrue(worker.tracker().endpoints().isEmpty(),
                    "the worker starts with no tracker configured");

            String reply = config("{\"network.default_trackers\":"
                    + "[\"tcp://store-one.example:25600\",\"tcp://store-two.example:25601\"]}");
            assertNotNull(reply);
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertFalse(reply.contains("unknown setting"), reply);
            assertTrue(reply.contains("\"rejected\":{}"), reply);
            assertTrue(reply.contains("\"applied\":[\"network.default_trackers\"]"), reply);

            List<String> dialled = worker.tracker().endpoints().stream()
                    .map(e -> e.host() + ":" + e.port())
                    .toList();
            assertEquals(List.of("store-one.example:25600", "store-two.example:25601"), dialled,
                    "the store's trackers must be live, not queued behind a restart");

            // And a later store REPLACES the list rather than appending to it — otherwise removing a
            // store in the app would leave the worker dialling it forever.
            assertFalse(config("{\"network.default_trackers\":[\"tcp://store-three.example:25602\"]}")
                    .startsWith(ControlProtocol.ERR));
            assertEquals(List.of("store-three.example:25602"), worker.tracker().endpoints().stream()
                    .map(e -> e.host() + ":" + e.port()).toList());
        }

        // --- helpers ---------------------------------------------------------------------------------

        private String config(String json) {
            return worker.request(ControlProtocol.CONFIG + " 2 " + WorkerNode.b64(json));
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
                sent.add(WireCodec.decode(frame));
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

    /**
     * {@code NODERA-DELEGATE} over the real control endpoint — the verb that ends a world's author being
     * treated as a stranger in their own world.
     *
     * <p>The game generates a throwaway keypair per session and announces it. Permissions are anchored
     * to the worker's persistent key. Those two facts alone meant every player was evaluated as a
     * member, forever. This verb is the bridge: the worker signs that the session key speaks for it.
     */
    @Nested
    final class DelegateVerbIT {

        private static final String WORLD = "00112233445566778899aabbccddeeff";

        @TempDir
        Path dir;

        private final PeerTestHarness harness = PeerTestHarness.create();
        private WorkerNode worker;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private void bootWorker() throws Exception {
            worker = harness.workerNode("delegate-test").stateDir(dir).build();
        }

        @Test
        @DisplayName("the worker vouches for a session key, and the delegation names the worker")
        void aDelegationBindsTheSessionKeyToTheWorkerIdentity() throws Exception {
            bootWorker();
            NodeIdentity session = NodeIdentity.generate();

            SessionDelegation delegation = ask(WORLD, session, 3600);

            assertThat(delegation.workerNodeId())
                    .as("permissions resolve against this, not the session key")
                    .isEqualTo(worker.nodeId());
            assertThat(delegation.workerPublicKey()).isEqualTo(worker.identity().publicKeyBytes());
            assertThat(delegation.isValidFor(session.publicKeyBytes(), Bytes.fromHex(WORLD),
                    System.currentTimeMillis())).isTrue();
        }

        @Test
        @DisplayName("a delegation is inert for any other session key")
        void itDoesNotVouchForAKeyItWasNotAskedAbout() throws Exception {
            bootWorker();
            NodeIdentity session = NodeIdentity.generate();
            NodeIdentity attacker = NodeIdentity.generate();

            SessionDelegation delegation = ask(WORLD, session, 3600);

            assertThat(delegation.isValidFor(attacker.publicKeyBytes(), Bytes.fromHex(WORLD),
                    System.currentTimeMillis())).isFalse();
        }

        @Test
        @DisplayName("a delegation is inert in any other world")
        void itIsScopedToTheWorldItWasMintedFor() throws Exception {
            bootWorker();
            NodeIdentity session = NodeIdentity.generate();

            SessionDelegation delegation = ask(WORLD, session, 3600);

            assertThat(delegation.isValidFor(session.publicKeyBytes(),
                    Bytes.fromHex("ffeeddccbbaa99887766554433221100"), System.currentTimeMillis()))
                    .isFalse();
        }

        @Test
        @DisplayName("an oversized lifetime is clamped rather than honoured")
        void theWorkerDecidesHowLongItVouchesFor() throws Exception {
            bootWorker();
            NodeIdentity session = NodeIdentity.generate();

            // A session asking for a decade gets the worker's own ceiling. The expiry is the only thing
            // limiting a delegation that has been copied off a disk, so the asker does not set it.
            SessionDelegation delegation = ask(WORLD, session, 10L * 365 * 24 * 3600);

            assertThat(delegation.notAfterEpochMillis())
                    .isLessThanOrEqualTo(System.currentTimeMillis()
                            + SessionDelegation.DEFAULT_TTL_MILLIS);
        }

        @Test
        @DisplayName("an empty session key is refused rather than signed")
        void anEmptySessionKeyIsRefused() throws Exception {
            bootWorker();

            // No key argument at all. Signing here would produce a statement naming no session, which
            // is a credential for whoever holds the bytes.
            String reply = worker.request(ControlProtocol.DELEGATE + " 2 " + WORLD);

            assertThat(reply).startsWith(ControlProtocol.ERR);
        }

        private SessionDelegation ask(String worldIdHex, NodeIdentity session, long ttlSeconds) {
            String payload = WorkerNode.okPayload(worker.request(ControlProtocol.DELEGATE + " 2 "
                    + worldIdHex + " " + WorkerNode.b64(session.publicKeyBytes())
                    + " " + ttlSeconds));
            return SessionDelegation.decode(new CanonicalReader(
                    Bytes.unsafeWrap(Base64.getDecoder().decode(payload))));
        }
    }

    /**
     * Issue #37 / L-51: the {@code NODERA-REKEY} control verb wires the full password re-key pipeline —
     * re-pack is the mod's job, but here the worker re-encrypts the archive under a fresh Argon2id salt
     * (new key → new ciphertext → new {@code manifestRoot} + bumped version), re-signs the
     * {@link WorldIdentity} with the new {@code manifestRef}, and returns it. This IT drives the verb
     * over a real loopback control endpoint and proves the crypto + identity round trip — no Minecraft
     * types. Authorship is enforced by the signature (a wrong-author identity is rejected).
     */
    @Nested
    final class RekeyVerbIT {

        /**
         * A minute, not the harness default of thirty seconds: this verb derives an Argon2id key on the
         * request thread, and it is the only verb in the tree that can legitimately take that long.
         */
        private static final Duration REKEY_TIMEOUT = Duration.ofSeconds(60);

        private final PeerTestHarness harness = PeerTestHarness.create();
        private final InMemoryContentStore store = new InMemoryContentStore(harness.hashes());
        private WorkerNode worker;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private NodeIdentity author() throws java.io.IOException {
            worker = harness.workerNode("rekey-test")
                    .roles(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP)
                    .archive(store)
                    .controlTimeout(REKEY_TIMEOUT)
                    .build();
            return worker.identity();
        }

        /**
         * The identity half of an {@code NODERA-OK <identityB64> <version>} payload. The version token
         * is what an encrypted refresh records as the save's seeded version (L-59) — SEED's reply is no
         * longer the only place a version comes from.
         */
        private static Bytes b64ToBytes(String b64) {
            return Bytes.unsafeWrap(Base64.getDecoder().decode(b64.trim().split("\\s+")[0]));
        }

        /** The version token of an {@code NODERA-OK <identityB64> <version>} payload. */
        private static long replyVersion(String reply) {
            String[] parts = WorkerNode.okPayload(reply).split("\\s+");
            assertEquals(2, parts.length, "the reply must carry the seeded version: " + reply);
            return Long.parseLong(parts[1]);
        }

        private static Bytes encodeIdentity(WorldIdentity id) {
            CanonicalWriter w = new CanonicalWriter();
            id.encode(w);
            return w.toBytes();
        }

        /** The re-key verb line: world id, the packed archive's path, the new password, the identity. */
        private String rekey(String worldIdHex, Path archiveFile, String password, WorldIdentity id) {
            return worker.request(ControlProtocol.REKEY + " 2 " + worldIdHex
                    + " " + WorkerNode.b64(archiveFile.toString())
                    + " " + WorkerNode.b64(password)
                    + " " + WorkerNode.b64(encodeIdentity(id)));
        }

        private static WorldIdentity decodeIdentity(String reply) {
            return WorldIdentity.decode(new CanonicalReader(b64ToBytes(WorkerNode.okPayload(reply))));
        }

        @Test
        void rekeyEncryptsUnderNewPasswordAndResignsIdentity(@TempDir Path tmp) throws Exception {
            NodeIdentity authorIdentity = author();

            // A freshly-packed plaintext blob (the mod's job) + the current signed identity.
            byte[] blob = new byte[300_000];
            new java.util.Random(42L).nextBytes(blob);
            Path archiveFile = tmp.resolve("packed.nar");
            Files.write(archiveFile, blob);

            Bytes genesisRoot = harness.hashes().sha256("genesis".getBytes());
            WorldIdentity current = WorldIdentity.create(authorIdentity, genesisRoot, 1L,
                    true, true, false, Bytes.empty());
            String worldIdHex = current.worldId().toHex();

            String pwd = "new-password-#37";
            String reply = rekey(worldIdHex, archiveFile, pwd, current);

            assertTrue(reply.startsWith(ControlProtocol.OK + " "), "expected OK, got: " + reply);
            assertTrue(replyVersion(reply) > 0,
                    "the reply reports the archive version now seeded: " + reply);
            WorldIdentity reSigned = decodeIdentity(reply);

            // worldId is stable; encrypted flag flips; manifestRef is the new manifestRoot; signature verifies.
            assertEquals(current.worldId(), reSigned.worldId());
            assertTrue(reSigned.encrypted());
            assertTrue(reSigned.verifySignature());
            assertFalse(reSigned.manifestRef().isEmpty());

            PieceManifest newest = worker.archive().newestManifest(worldIdHex).orElseThrow();
            assertTrue(newest.encrypted());
            assertEquals(reSigned.manifestRef(), newest.manifestRoot());
            assertEquals(1, newest.version().value()); // first seed → v1

            // The published ciphertext blob (keyed by its ContentId hash) decrypts to the original under
            // the NEW password only — proving the key actually changed.
            byte[] cipherBlob = store.get(newest.blob()).orElseThrow();
            assertArrayEquals(blob, WorldArchive.decryptArchive(newest, cipherBlob,
                    pwd.toCharArray()).orElseThrow());
            assertTrue(WorldArchive.decryptArchive(newest, cipherBlob,
                    "wrong".toCharArray()).isEmpty());
        }

        @Test
        void aSecondRekeySupersedesTheOldCiphertextSoTheOldPasswordStopsWorking(@TempDir Path tmp)
                throws Exception {
            // L-55: a re-key used to APPEND a manifest version and leave the previous one seeded. The
            // superseded blob is still decryptable with the OLD password, so changing the password
            // revoked nothing on this node — a holder of the old password kept reading the pre-re-key
            // world from it. Superseding evicts the old version: manifest table, tracker holdings, and
            // the content store itself.
            NodeIdentity authorIdentity = author();

            byte[] blob = new byte[120_000];
            new java.util.Random(7L).nextBytes(blob);
            Path archiveFile = tmp.resolve("packed.nar");
            Files.write(archiveFile, blob);

            Bytes genesisRoot = harness.hashes().sha256("genesis-l55".getBytes());
            WorldIdentity current = WorldIdentity.create(authorIdentity, genesisRoot, 1L,
                    true, true, false, Bytes.empty());
            String worldIdHex = current.worldId().toHex();

            String firstPassword = "first-password";
            String firstReply = rekey(worldIdHex, archiveFile, firstPassword, current);
            assertTrue(firstReply.startsWith(ControlProtocol.OK + " "), firstReply);
            WorldIdentity afterFirst = decodeIdentity(firstReply);
            PieceManifest firstManifest = worker.archive().newestManifest(worldIdHex).orElseThrow();
            byte[] firstCipher = store.get(firstManifest.blob()).orElseThrow();
            assertArrayEquals(blob, WorldArchive.decryptArchive(firstManifest, firstCipher,
                    firstPassword.toCharArray()).orElseThrow());

            // The author changes the password again.
            String secondPassword = "second-password";
            String secondReply = rekey(worldIdHex, archiveFile, secondPassword, afterFirst);
            assertTrue(secondReply.startsWith(ControlProtocol.OK + " "), secondReply);

            PieceManifest newest = worker.archive().newestManifest(worldIdHex).orElseThrow();
            assertEquals(2, newest.version().value());
            assertFalse(newest.manifestRoot().equals(firstManifest.manifestRoot()),
                    "a re-key must mint a new manifest root");

            // The superseded version is gone: not in the manifest table, not advertised, not stored.
            assertEquals(1, worker.archive().heldVersions(worldIdHex).size(),
                    "only the newest version survives a re-key");
            assertEquals(newest.manifestRoot(),
                    worker.archive().heldVersions(worldIdHex).get(0).manifestRoot());
            assertTrue(worker.archive().holdingsFor(worldIdHex).stream()
                            .noneMatch(h -> h.manifestRoot().equals(firstManifest.manifestRoot())),
                    "the next announce must not advertise the superseded manifest");
            assertTrue(worker.archive().content().heldPieces(firstManifest.manifestRoot()).isEmpty(),
                    "no piece of the superseded manifest is held any more");
            assertFalse(store.has(firstManifest.blob()),
                    "the old ciphertext is evicted from the content store, "
                            + "so the OLD password no longer reads anything from this node");

            // And the surviving ciphertext answers to the new password only.
            byte[] secondCipher = store.get(newest.blob()).orElseThrow();
            assertArrayEquals(blob, WorldArchive.decryptArchive(newest, secondCipher,
                    secondPassword.toCharArray()).orElseThrow());
            assertTrue(WorldArchive.decryptArchive(newest, secondCipher,
                    firstPassword.toCharArray()).isEmpty());
        }

        @Test
        void rekeyRejectsAWrongAuthorIdentity(@TempDir Path tmp) throws Exception {
            author(); // the worker's identity

            byte[] blob = new byte[64_000];
            Path archiveFile = tmp.resolve("packed.nar");
            Files.write(archiveFile, blob);

            // An identity minted by a DIFFERENT identity than the worker — resign must reject it.
            NodeIdentity stranger = NodeIdentity.generate();
            Bytes genesisRoot = harness.hashes().sha256("genesis2".getBytes());
            WorldIdentity notOurs = WorldIdentity.create(stranger, genesisRoot, 1L,
                    true, true, false, Bytes.empty());
            // The world id passed in the verb must match the identity's derived worldId for the
            // defence-in-depth check to pass and reach the author gate; use the identity's own.
            String hex = notOurs.worldId().toHex();

            String reply = rekey(hex, archiveFile, "whatever", notOurs);

            assertNotNull(reply);
            assertTrue(reply.startsWith(ControlProtocol.ERR), "expected ERR, got: " + reply);
            assertTrue(reply.contains("not the author"));
            // No manifest was seeded for this world.
            assertTrue(worker.archive().newestManifest(hex).isEmpty());
        }
    }

    /**
     * Worker L-41 over the real control socket: {@code NODERA-SEED-REGION} hands the always-on worker a
     * committed region snapshot, and the worker seeds and advertises it.
     *
     * <p>The verb exists because of who holds what. Under field-of-view ownership the seats live on the
     * players' nodes, so the process that commits a region is usually a game client — the one process
     * guaranteed to go away. Pushing the snapshot across the loopback control channel is what puts the
     * region on a process that does not.
     */
    @Nested
    final class SeedRegionVerbIT {

        private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 2, -3);

        private final PeerTestHarness harness = PeerTestHarness.create();
        private WorkerNode worker;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private void startWorker() throws java.io.IOException {
            worker = harness.workerNode("seed-region-test")
                    .roles(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP)
                    .inMemoryArchive()
                    .build();
        }

        private static RegionSnapshot snapshot(long version) {
            List<ChunkColumnState> chunks = new ArrayList<>();
            for (int dx = 0; dx < 8; dx++) {
                for (int dz = 0; dz < 8; dz++) {
                    int[] sections = new int[24];
                    sections[0] = (int) version * 17 + dx;
                    chunks.add(new ChunkColumnState(REGION.originChunkX() + dx,
                            REGION.originChunkZ() + dz, sections, -64, 24));
                }
            }
            return new RegionSnapshot(REGION, new SnapshotVersion(version), version, chunks);
        }

        private static Path write(Path dir, RegionSnapshot snapshot) throws Exception {
            CanonicalWriter w = new CanonicalWriter();
            snapshot.encode(w);
            Path file = dir.resolve("region-" + snapshot.version().value() + ".bin");
            Files.write(file, w.toBytes().toArray());
            return file;
        }

        private static String b64(Path path) {
            return WorkerNode.b64(path.toAbsolutePath().toString());
        }

        @Test
        @DisplayName("the verb seeds a committed region and the world's announce advertises it")
        void theVerbSeedsAndAdvertises(@TempDir Path dir) throws Exception {
            startWorker();
            String world = harness.hashes().sha256("l41-verb".getBytes()).toHex();
            worker.hosting().host(world, "A World", "{}");

            String reply = worker.request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                    + b64(write(dir, snapshot(4))));

            assertThat(reply).startsWith(ControlProtocol.OK);
            String[] parts = reply.split(" ");
            assertThat(parts[2]).as("the snapshot's own version, not a counter the worker invents")
                    .isEqualTo("4");
            assertThat(worker.archive().heldRegions(world)).containsExactly(REGION);
            assertThat(worker.archive().newestRegionManifest(world, REGION).orElseThrow()
                    .manifestRoot().toHex()).isEqualTo(parts[1]);
            assertThat(worker.archive().holdingsFor(world)).hasSize(1);
        }

        @Test
        @DisplayName("STATE reports the regions this node validates, not only the bytes it stores")
        void stateReportsRegionsValidatedHere(@TempDir Path dir) throws Exception {
            startWorker();
            String world = harness.hashes().sha256("l41-state".getBytes()).toHex();
            worker.hosting().host(world, "A World", "{}");

            // Storing a world and running part of one are different contributions, and the app could
            // only report the first. Two players in one world therefore both read as "supporting the
            // network" no matter how the ownership plan had actually divided the world between them.
            assertThat(worker.state()).contains("\"regions_held\":0");

            assertThat(worker.request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                    + b64(write(dir, snapshot(7))))).startsWith(ControlProtocol.OK);

            assertThat(worker.state()).contains("\"regions_held\":1");
        }

        @Test
        @DisplayName("the world stays seeded after the pushing process is gone — the whole of L-41")
        void theRegionOutlivesItsCommitter(@TempDir Path dir) throws Exception {
            startWorker();
            String world = harness.hashes().sha256("l41-outlives".getBytes()).toHex();
            worker.hosting().host(world, "A World", "{}");
            worker.archive().seedArchive(world, "the save's bytes".getBytes());

            // One control connection per push, each closed on the way out — the caller is transient by
            // construction, exactly like the game client this models.
            for (long version = 1; version <= 3; version++) {
                assertThat(worker.request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                        + b64(write(dir, snapshot(version))))).startsWith(ControlProtocol.OK);
            }

            // Nothing is connected now. Both lanes are still held and still advertised, which is the
            // row's exit clause: whole-save archive AND validated-lane region pieces, on the worker's
            // own timer, after the driving process disconnected.
            assertThat(worker.archive().newestManifest(world)).isPresent();
            assertThat(worker.archive().newestRegionManifest(world, REGION).orElseThrow()
                    .version().value()).isEqualTo(3);
            assertThat(worker.archive().holdingsFor(world))
                    .as("one announce carries the save and the region alike")
                    .hasSize(2);
            assertThat(worker.archive().maintainedPieces()).isGreaterThan(1);
        }

        @Test
        @DisplayName("a file that is not a region snapshot is refused, not seeded")
        void garbageIsRefused(@TempDir Path dir) throws Exception {
            startWorker();
            String world = harness.hashes().sha256("l41-garbage".getBytes()).toHex();
            Path junk = dir.resolve("not-a-snapshot.bin");
            Files.write(junk, "definitely not canonical".getBytes(StandardCharsets.UTF_8));

            String reply = worker.request(ControlProtocol.SEED_REGION + " 2 " + world + " " + b64(junk));

            // Seeding it blind would advertise content no fetcher could ever use.
            assertThat(reply).startsWith(ControlProtocol.ERR);
            assertThat(worker.archive().heldRegions(world)).isEmpty();
        }

        @Test
        @DisplayName("a missing file and a missing world id are refused with a reason")
        void argumentsAreChecked(@TempDir Path dir) throws Exception {
            startWorker();
            String world = harness.hashes().sha256("l41-args".getBytes()).toHex();

            assertThat(worker.request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                    + b64(dir.resolve("absent.bin")))).startsWith(ControlProtocol.ERR);
            assertThat(worker.request(ControlProtocol.SEED_REGION + " 2  "
                    + b64(write(dir, snapshot(1))))).startsWith(ControlProtocol.ERR);
        }
    }

    /**
     * Deleting a world across two real peers, over the control endpoint the app and the mod use.
     *
     * <p>The claim under test is the whole feature in one sentence: <b>the peer that created a world
     * can make the network forget it, and nobody else can</b>. So the owner deletes through
     * {@code NODERA-DELETE}, a second peer that is merely supporting the world applies the deletion
     * because the record verifies — not because of who sent it — and a third scenario shows the
     * supporter cannot originate one.
     *
     * <p>Both peers run over a shared loopback transport, so the deletion travels as encoded frames
     * exactly as it would on a socket. Nothing here reaches the network.
     */
    @Nested
    final class WorldDeletionVerbIT {

        @TempDir
        Path dir;

        private final PeerTestHarness harness = PeerTestHarness.create();

        @AfterEach
        void tearDown() {
            harness.close();
        }

        /**
         * Boot one worker with its own state directory.
         *
         * @param name    the directory to keep this worker's state in.
         * @param members the peers its deletion lane relays to.
         */
        private WorkerNode boot(String name, List<PeerEntry> members) throws Exception {
            return harness.workerNode("deletion-test")
                    .stateDir(dir.resolve(name))
                    .withDeletion(() -> members)
                    .build();
        }

        /** Create a world on a worker through the same verb the mod uses, and return its id. */
        private String createWorld(WorkerNode worker, String genesisSeed) {
            String payload = WorkerNode.okPayload(worker.request(ControlProtocol.WORLDID + " 2 "
                    + WorkerNode.b64(genesisSeed) + " 1000 1 1 0 "));
            WorldIdentity world = WorldIdentity.decode(new CanonicalReader(
                    Bytes.unsafeWrap(Base64.getDecoder().decode(payload))));
            return world.worldId().toHex();
        }

        @Test
        @DisplayName("the owner deletes a world and the peer supporting it forgets too")
        void aDeletionReachesTheSupportingPeer() throws Exception {
            WorkerNode supporter = boot("supporter", List.of());
            WorkerNode owner = boot("owner", List.of(member(supporter)));
            String worldId = createWorld(owner, "a world worth deleting");
            // The supporter keeps the world alive for the owner: it holds the bytes and none of the
            // authority, which is exactly the peer a deletion has to convince.
            assertThat(supporter.hosting().seed(worldId, "Their World")).isNull();

            String reply = owner.request(ControlProtocol.DELETE + " 2 " + worldId + " "
                    + WorkerNode.b64("finished with it"));

            assertThat(reply).startsWith(ControlProtocol.OK);
            assertThat(owner.hosting().hostedWorlds()).isEmpty();
            // Relay is a send; application happens on the receiver's own state thread. Asserting
            // immediately would be testing the scheduler, and would pass or fail depending on the machine.
            Await.quietly(5_000, () -> supporter.deletions().isDeleted(worldId));
            assertThat(supporter.deletions().isDeleted(worldId))
                    .as("the supporter verified the record itself and acted on it")
                    .isTrue();
            assertThat(supporter.hosting().hostedWorlds()).isEmpty();
        }

        @Test
        @DisplayName("a peer cannot delete a world it merely supports")
        void aSupporterCannotDeleteSomebodyElsesWorld() throws Exception {
            WorkerNode owner = boot("owner", List.of());
            WorkerNode supporter = boot("supporter", List.of());
            String worldId = createWorld(owner, "not the supporter's to delete");
            assertThat(supporter.hosting().seed(worldId, "Their World")).isNull();

            String reply = supporter.request(ControlProtocol.DELETE + " 2 " + worldId);

            // No key, no deletion. The refusal is the security property, not a UI nicety: without it,
            // hosting somebody's world would be the power to destroy it.
            assertThat(reply).startsWith(ControlProtocol.ERR);
            assertThat(reply).contains("does not administer");
            assertThat(supporter.hosting().hostedWorlds()).hasSize(1);
            assertThat(supporter.deletions().isDeleted(worldId)).isFalse();
        }

        @Test
        @DisplayName("a deleted world cannot be re-hosted, before or after a restart")
        void aDeletedWorldStaysDeleted() throws Exception {
            WorkerNode owner = boot("owner", List.of());
            String worldId = createWorld(owner, "gone for good");
            assertThat(owner.request(ControlProtocol.DELETE + " 2 " + worldId))
                    .startsWith(ControlProtocol.OK);

            assertThat(owner.hosting().host(worldId, "Back Again", "{}"))
                    .isEqualTo("this world was deleted by its owner");

            // A restart is a new service over the same directory; the deletion is on disk, so the
            // world does not come back with it.
            WorldDeletionService restarted = new WorldDeletionService(owner.nodeId(),
                    harness.transport(NodeIdentity.generate()), List::of, owner.hosting(),
                    null, null, null, null);
            restarted.attachStore(new WorldTombstoneStore(dir.resolve("owner").resolve("deleted")));
            assertThat(restarted.isDeleted(worldId)).isTrue();
            assertThat(restarted.tombstone(worldId))
                    .hasValueSatisfying(t -> assertThat(t.verify()).isTrue());
        }

        @Test
        @DisplayName("deleting an unknown world is refused rather than half-done")
        void anUnknownWorldIsRefused() throws Exception {
            WorkerNode owner = boot("owner", List.of());
            String strangersWorld = harness.hashes()
                    .sha256("never seen".getBytes(StandardCharsets.UTF_8)).toHex();

            String reply = owner.request(ControlProtocol.DELETE + " 2 " + strangersWorld);

            assertThat(reply).startsWith(ControlProtocol.ERR);
            assertThat(owner.deletions().isDeleted(strangersWorld))
                    .as("a refused deletion must not leave a tombstone behind")
                    .isFalse();
        }

        private static PeerEntry member(WorkerNode worker) {
            return new PeerEntry(worker.nodeId(), MeshNode.ROUTE, NodeCapabilities.initial(), false);
        }
    }

    /**
     * World ownership over the real control endpoint — the path the companion app and the mod use.
     *
     * <p>The claim under test is not "the verbs parse". It is that <b>creating a world makes this peer
     * its provable administrator</b>: minting a world identity mints the world's key pair, the app can
     * see which worlds are administered here, and a challenge from anyone gets a signature that only
     * the holder of that world's private key could produce — verifiable with nothing but the world's
     * public key.
     */
    @Nested
    final class WorldOwnershipVerbIT {

        /** The mod's "open this world to Nodera" call, with a fixed genesis so the id is stable. */
        private static final String CREATE_WORLD =
                ControlProtocol.WORLDID + " 2 " + WorkerNode.b64("genesis-root") + " 1000 1 1 0 ";

        @TempDir
        Path dir;

        private final PeerTestHarness harness = PeerTestHarness.create();
        private WorkerNode worker;

        @AfterEach
        void tearDown() {
            harness.close();
        }

        private void bootWorker() throws Exception {
            worker = harness.workerNode("ownership-test").stateDir(dir).build();
        }

        /** Create the world through the verb and decode the identity it answers with. */
        private WorldIdentity createWorld() {
            return WorldIdentity.decode(new CanonicalReader(Bytes.unsafeWrap(
                    Base64.getDecoder().decode(WorkerNode.okPayload(worker.request(CREATE_WORLD))))));
        }

        @Test
        @DisplayName("creating a world makes this peer its administrator, provably")
        void mintingAWorldIdentityMintsItsKeyAndAClaim() throws Exception {
            bootWorker();

            WorldIdentity world = createWorld();
            String worldIdHex = world.worldId().toHex();

            // The world now has a key of its own, held here and nowhere else.
            assertThat(worker.keys().administers(worldIdHex)).isTrue();

            // …and the app can see it, without the game running and without a mesh.
            String worlds = worker.request(ControlProtocol.WORLDS + " 2");
            assertThat(worlds).contains("\"world_id\":\"" + worldIdHex + "\"");
            assertThat(worlds).contains("\"owned\":true");
            assertThat(worlds).doesNotContain("\"world_public_key\":\"\"");
        }

        @Test
        @DisplayName("the proof verifies under the world's public key, and only for its own challenge")
        void aProofIsVerifiableAndSingleUse() throws Exception {
            bootWorker();
            WorldIdentity world = createWorld();
            String worldIdHex = world.worldId().toHex();
            Bytes worldPublicKey = worker.keys().load(worldIdHex).orElseThrow().x509Public();

            Bytes challenge = harness.hashes()
                    .sha256("a verifier's nonce".getBytes(StandardCharsets.UTF_8));
            String proofB64 = WorkerNode.okPayload(worker.request(ControlProtocol.PROVE + " 2 "
                    + worldIdHex + " " + WorkerNode.b64(challenge)));
            WorldAdminProof proof = WorldAdminProof.decode(
                    new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(proofB64))));

            assertThat(proof.verify(worldPublicKey, challenge, worker.nodeId()))
                    .as("the verifier needs nothing but the world's public key")
                    .isTrue();
            assertThat(proof.verify(worldPublicKey,
                    harness.hashes().sha256("a different nonce".getBytes(StandardCharsets.UTF_8)),
                    worker.nodeId()))
                    .as("a captured proof does not answer the next challenge")
                    .isFalse();
        }

        @Test
        @DisplayName("a peer refuses to prove administration of a world it did not create")
        void anUnownedWorldCannotBeProved() throws Exception {
            bootWorker();
            // A world this node merely serves for somebody else.
            String someoneElses = harness.hashes()
                    .sha256("not mine".getBytes(StandardCharsets.UTF_8)).toHex();
            worker.hosting().seed(someoneElses, "Their World");

            String reply = worker.request(ControlProtocol.PROVE + " 2 " + someoneElses + " "
                    + WorkerNode.b64("nonce"));

            assertThat(reply).startsWith(ControlProtocol.ERR);
            assertThat(reply).contains("does not administer");
            assertThat(worker.request(ControlProtocol.WORLDS + " 2"))
                    .as("it is listed as supported, and honestly not owned")
                    .contains("\"role\":\"supported\"")
                    .contains("\"owned\":false");
        }

        @Test
        @DisplayName("an empty challenge is refused rather than signed")
        void anEmptyChallengeIsRefused() throws Exception {
            bootWorker();
            WorldIdentity world = createWorld();

            String reply = worker.request(ControlProtocol.PROVE + " 2 " + world.worldId().toHex() + " ");

            assertThat(reply).startsWith(ControlProtocol.ERR);
            assertThat(reply).contains("challenge");
        }

        @Test
        @DisplayName("the claim the app shows is the one a peer would verify off the wire")
        void theStoredClaimIsSelfAuthenticating() throws Exception {
            bootWorker();
            WorldIdentity world = createWorld();

            Bytes stored = worker.hosting().hostedWorlds().iterator().next().ownershipRecord();
            WorldOwnership claim = WorldOwnership.decode(new CanonicalReader(stored));

            assertThat(claim.verify()).isTrue();
            assertThat(claim.worldId()).isEqualTo(world.worldId());
            assertThat(claim.isOwner(worker.nodeId())).isTrue();
        }

        @Test
        @DisplayName("re-creating the same world does not mint it a second key")
        void theKeyIsMintedOnce() throws Exception {
            bootWorker();
            WorldIdentity world = createWorld();
            Bytes keyBefore = worker.keys().load(world.worldId().toHex()).orElseThrow().x509Public();

            // The same genesis root and creation time derive the same world id — a re-share, not a new
            // world. A second key here would silently hand the world to a different administrator.
            createWorld();

            assertThat(worker.keys().load(world.worldId().toHex()).orElseThrow().x509Public())
                    .isEqualTo(keyBefore);
        }
    }
}
