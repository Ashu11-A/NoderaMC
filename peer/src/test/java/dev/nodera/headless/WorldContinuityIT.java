package dev.nodera.headless;

import dev.nodera.testkit.harness.SpawnedService;
import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.storage.fs.FsContentStore;
import dev.nodera.transport.rendezvous.RendezvousClient;
import dev.nodera.transport.rendezvous.RendezvousEndpoint;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The <b>world-continuity acceptance</b>, Minecraft-free — the headless rehearsal of the scripted
 * test series ({@code scripts/e2e-continuity.sh}): one player shares a world on the Nodera network,
 * a second player joins through the real tracker + rendezvous services, the world's data enters the
 * peer system, and then the host goes away — first the game (mc route dropped), then the host's
 * entire worker process. The test fails exactly when the user-visible test would: if the second
 * peer cannot still produce the complete world after the host is gone.
 *
 * <p>Real pieces everywhere the harness allows: the standalone Rust {@code nodera-tracker} and
 * {@code nodera-rendezvous} binaries (skipped, not failed, when not built), real TCP
 * {@link SocketPeerTransport}s, the real worker control verbs ({@code NODERA-HOST/SEED/ARCHIVE})
 * driven over a real loopback socket exactly as the mod drives them, and {@link FsContentStore}
 * blob tiers on disk.
 *
 * <p>Thread-context: single test thread; services run in child processes / daemon threads.
 */
final class WorldContinuityIT {

    private static final HashService HASHES = new HashService();

    private final List<SpawnedService> services = new ArrayList<>();
    private final List<WorkerNode> workers = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (WorkerNode worker : workers) {
            worker.closeQuietly();
        }
        for (SpawnedService service : services) {
            service.close();
        }
    }

    @Test
    void worldSurvivesHostDisconnectAndHostWorkerDeath(@TempDir Path tmp) throws Exception {
        TrackerClient.Endpoint tracker = startTracker(binary("nodera-tracker"));
        RendezvousEndpoint rendezvous = startRendezvous(binary("nodera-rendezvous"));

        // Two players, each with their own always-on peer worker.
        WorkerNode host = WorkerNode.start("host", tmp.resolve("host"), tracker, rendezvous);
        WorkerNode joiner = WorkerNode.start("joiner", tmp.resolve("joiner"), tracker, rendezvous);
        workers.add(host);
        workers.add(joiner);

        // --- stage 1: the host player shares a world ------------------------------------------
        byte[] worldBlob = worldArchiveFixture();
        Bytes worldId = HASHES.sha256(worldBlob);
        String worldIdHex = worldId.toHex();
        String nameB64 = Base64.getEncoder().encodeToString(
                "Continuity World".getBytes(StandardCharsets.UTF_8));

        String hostAck = host.control(ControlProtocol.HOST + " 2 " + worldIdHex + " " + nameB64
                + " {\"listed\":true,\"mc\":\"127.0.0.1:25599\",\"players\":1}");
        assertThat(hostAck).isEqualTo(ControlProtocol.OK);

        // The mod's share path packs the save and hands the worker the archive file (NODERA-SEED).
        Path archiveFile = tmp.resolve("spool.nar");
        Files.write(archiveFile, worldBlob);
        String seedReply = host.control(ControlProtocol.SEED + " 2 " + worldIdHex + " "
                + b64(archiveFile));
        assertThat(seedReply).startsWith(ControlProtocol.OK + " ");
        assertThat(host.stateJson()).contains("\"maintained_pieces\":"
                + WorldArchive.manifestFor(1, worldBlob).pieceCount());

        // --- stage 2: the network can find the world (tracker + rendezvous) -------------------
        TrackerResponse listed = awaitListed(joiner, tracker, worldId);
        assertThat(listed.worldName()).isEqualTo("Continuity World");
        assertThat(listed.peers()).isNotEmpty();
        assertThat(listed.seeders()).as("archive manifest advertised to the tracker").isNotEmpty();

        // …and the host KNOWS it can be found. An announce that was sent is not an announce that
        // was registered: a tracker that refuses one (or hangs up on an announce body it cannot
        // decode) leaves the host describing the world as hosted and joinable while no directory
        // carries it, and the failure then surfaces only on the joining player's screen as a world
        // with no seeder. This is the host-side half of the assertion above.
        assertThat(host.stateJson())
                .as("the host reports the listing the tracker just proved")
                .contains("\"listed_on_trackers\":1")
                .contains("\"announced_to_trackers\":1");

        var discovered = new RendezvousClient(joiner.identity, Duration.ofSeconds(3),
                Duration.ofSeconds(5)).discover(rendezvous,
                UUID.nameUUIDFromBytes(worldId.toArray()), worldId, 0, 10);
        assertThat(discovered.records())
                .as("host registered with the rendezvous service").isNotEmpty();

        // --- stage 3: the second player pulls the world's data over the P2P lane --------------
        Path fetched1 = tmp.resolve("joiner-fetch-1.nar");
        String fetchReply = joiner.control(ControlProtocol.ARCHIVE + " 2 " + worldIdHex + " "
                + b64(fetched1) + " 30");
        assertThat(fetchReply).startsWith(ControlProtocol.OK + " ");
        assertThat(Files.readAllBytes(fetched1))
                .as("the world archive crossed the peer network byte-exactly")
                .isEqualTo(worldBlob);

        // --- stage 4: the host player closes the game (world stays, endpoint gone) ------------
        String refresh = host.control(ControlProtocol.HOST + " 2 " + worldIdHex + " " + nameB64
                + " {\"listed\":true,\"players\":0}");
        assertThat(refresh).isEqualTo(ControlProtocol.OK);
        assertThat(host.stateJson()).contains("\"mc_route\":\"\"");
        assertThat(query(joiner, tracker, worldId)).isPresent();

        // --- stage 5: the host's machine goes away entirely -----------------------------------
        host.closeQuietly();

        // The joiner's worker still holds and serves the full archive: the world's data survived
        // its author. (The user-facing fail condition — "the second player also disconnects" —
        // is precisely a failure to reproduce the world here.)
        Path fetched2 = tmp.resolve("joiner-fetch-2.nar");
        String refetch = joiner.control(ControlProtocol.ARCHIVE + " 2 " + worldIdHex + " "
                + b64(fetched2) + " 30");
        assertThat(refetch).startsWith(ControlProtocol.OK + " ");
        assertThat(Files.readAllBytes(fetched2)).isEqualTo(worldBlob);

        // And the unpacked save is a well-formed world folder a client can re-open (the mod's
        // rehost path does exactly this unpack).
        Path restored = tmp.resolve("restored-world");
        WorldArchive.unpackInto(Files.readAllBytes(fetched2), restored);
        assertThat(Files.exists(restored.resolve("level.dat"))).isTrue();
        assertThat(Files.exists(restored.resolve("region/r.0.0.mca"))).isTrue();
    }

    // --- fixtures ------------------------------------------------------------------------------

    /** A miniature but structurally-real save folder, packed with the production codec. */
    private static byte[] worldArchiveFixture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] mca = new byte[3 * WorldArchive.ARCHIVE_PIECE_BYTES + 4321];
        for (int i = 0; i < mca.length; i++) {
            mca[i] = (byte) (i * 131 + 7);
        }
        files.put("level.dat", "level-data".getBytes(StandardCharsets.UTF_8));
        files.put("region/r.0.0.mca", mca);
        files.put("nodera-world.dat", "world-identity".getBytes(StandardCharsets.UTF_8));
        return WorldArchive.pack(files);
    }

    // --- the embedded worker node (HeadlessPeerMain's composition, test-owned) ------------------

    private static final class WorkerNode {
        final NodeIdentity identity;
        final SocketPeerTransport transport;
        final PeerRuntime runtime;
        final WorldHostingService hosting;
        final WorldArchiveService archive;
        final ControlServer control;

        private WorkerNode(NodeIdentity identity, SocketPeerTransport transport,
                           PeerRuntime runtime, WorldHostingService hosting,
                           WorldArchiveService archive, ControlServer control) {
            this.identity = identity;
            this.transport = transport;
            this.runtime = runtime;
            this.hosting = hosting;
            this.archive = archive;
            this.control = control;
        }

        static WorkerNode start(String name, Path dataDir, TrackerClient.Endpoint tracker,
                                RendezvousEndpoint rendezvous) throws IOException {
            NodeIdentity identity = NodeIdentity.generate();
            NodeCapabilities caps = NodeCapabilities.initial().withRoles(
                    EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP));
            SocketPeerTransport transport = new SocketPeerTransport(
                    identity.nodeId(), "127.0.0.1", 0, "127.0.0.1");
            PeerRuntime runtime = PeerRuntime.bootstrap(identity, caps, transport,
                    transport::listenRoute, PeerRuntimeConfig.defaults(), null);
            WorldArchiveService archive = new WorldArchiveService(identity, transport,
                    new FsContentStore(dataDir, HASHES), List.of(tracker));
            WorldHostingService hosting = new WorldHostingService(identity, caps,
                    runtime::selfRoute, List.of(tracker), List.of(rendezvous),
                    archive::holdingsFor);
            runtime.onApplicationMessage(archive::onMessage);
            WorkerControlHandler handler = new WorkerControlHandler("test-" + name, identity,
                    caps, runtime, new dev.nodera.diagnostics.metric.TrafficMeter(), hosting,
                    null, archive);
            ControlServer control = new ControlServer("127.0.0.1", 0, handler);
            control.start();
            return new WorkerNode(identity, transport, runtime, hosting, archive, control);
        }

        /** Drive one control verb over a real loopback socket, exactly as the mod does. */
        String control(String requestLine) throws IOException {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
                socket.setSoTimeout(60_000);
                OutputStream out = socket.getOutputStream();
                out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                return in.readLine();
            }
        }

        String stateJson() throws IOException {
            return control(ControlProtocol.STATE + " 2");
        }

        void closeQuietly() {
            try {
                hosting.close();
            } catch (RuntimeException ignored) {
                // teardown
            }
            control.close();
            archive.close();
            try {
                runtime.stop();
            } catch (RuntimeException ignored) {
                // teardown
            }
            transport.stop();
        }
    }

    // --- service spawning -----------------------------------------------------------------------

    /** The binary, or an assumption failure describing how to build it. */
    private static Path binary(String name) {
        Optional<Path> found = SpawnedService.binary(name);
        assumeThat(found).as(SpawnedService.buildHint(name)).isPresent();
        return found.orElseThrow();
    }

    private TrackerClient.Endpoint startTracker(Path binary) throws IOException {
        return TrackerClient.Endpoint.parse(spawn(binary, """
                bind_addr = "127.0.0.1:0"
                announce_interval_seconds = 1
                peer_ttl_seconds = 30
                healthy_seeder_floor = 1
                sample_size = 10
                seeder_floor = 5
                """));
    }

    private RendezvousEndpoint startRendezvous(Path binary) throws IOException {
        return RendezvousEndpoint.parse(spawn(binary, """
                bind_addr = "127.0.0.1:0"
                registration_ttl_seconds = 60
                refresh_interval_seconds = 30
                reservation_max_bytes = 1048576
                per_ip_request_quota = 0
                reservation_hmac_key_hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
                """));
    }

    private String spawn(Path binary, String configToml) throws IOException {
        SpawnedService service = SpawnedService.start(binary, configToml);
        services.add(service);
        return service.endpoint();
    }

    // --- tracker helpers ------------------------------------------------------------------------

    private static Optional<TrackerResponse> query(WorkerNode from, TrackerClient.Endpoint endpoint,
                                                   Bytes worldId) {
        try (TrackerClient client = new TrackerClient(List.of(endpoint), from.identity)) {
            return client.query(worldId);
        }
    }

    /** Await the world's listing to include a manifest-seeder row (announce cadence is 1 s). */
    private static TrackerResponse awaitListed(WorkerNode from, TrackerClient.Endpoint endpoint,
                                               Bytes worldId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        TrackerResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            Optional<TrackerResponse> response = query(from, endpoint, worldId);
            if (response.isPresent()) {
                last = response.get();
                if (!last.seeders().isEmpty()) {
                    return last;
                }
            }
            Thread.sleep(200);
        }
        assertThat(last).as("world listed by the tracker").isNotNull();
        return last;
    }

    private static String b64(Path path) {
        return Base64.getEncoder().encodeToString(
                path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }
}
