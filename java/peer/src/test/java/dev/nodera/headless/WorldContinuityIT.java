package dev.nodera.headless;

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
import dev.nodera.storage.rocksdb.FsContentStore;
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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    private final List<Process> services = new ArrayList<>();
    private final List<Path> configFiles = new ArrayList<>();
    private final List<WorkerNode> workers = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (WorkerNode worker : workers) {
            worker.closeQuietly();
        }
        for (Process p : services) {
            p.destroy();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
        for (Path f : configFiles) {
            Files.deleteIfExists(f);
        }
    }

    @Test
    void worldSurvivesHostDisconnectAndHostWorkerDeath(@TempDir Path tmp) throws Exception {
        Optional<Path> trackerBin = binary("nodera-tracker");
        Optional<Path> rendezvousBin = binary("nodera-rendezvous");
        assumeThat(trackerBin)
                .as("nodera-tracker binary (run: cd rust && cargo build --release)").isPresent();
        assumeThat(rendezvousBin)
                .as("nodera-rendezvous binary (run: cd rust && cargo build --release)").isPresent();

        TrackerClient.Endpoint tracker = startTracker(trackerBin.get());
        RendezvousEndpoint rendezvous = startRendezvous(rendezvousBin.get());

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

    // --- service spawning (the TrackerServiceIT / RendezvousRelayIT pattern) --------------------

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found");
        }
        return dir;
    }

    private static Optional<Path> binary(String name) {
        Path root = repoRoot().resolve("rust").resolve("target");
        for (String profile : new String[] {"release", "debug"}) {
            Path candidate = root.resolve(profile).resolve(name);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private TrackerClient.Endpoint startTracker(Path binary) throws IOException {
        Path config = Files.createTempFile("nodera-tracker-continuity", ".toml");
        configFiles.add(config);
        Files.writeString(config, """
                bind_addr = "127.0.0.1:0"
                announce_interval_seconds = 1
                peer_ttl_seconds = 30
                healthy_seeder_floor = 1
                sample_size = 10
                seeder_floor = 5
                """, StandardCharsets.UTF_8);
        return TrackerClient.Endpoint.parse(spawn(binary, config));
    }

    private RendezvousEndpoint startRendezvous(Path binary) throws IOException {
        Path config = Files.createTempFile("nodera-rendezvous-continuity", ".toml");
        configFiles.add(config);
        Files.writeString(config, """
                bind_addr = "127.0.0.1:0"
                registration_ttl_seconds = 60
                refresh_interval_seconds = 30
                reservation_max_bytes = 1048576
                per_ip_request_quota = 0
                reservation_hmac_key_hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
                """, StandardCharsets.UTF_8);
        return RendezvousEndpoint.parse(spawn(binary, config));
    }

    /** Start a service binary, wait for its "listening on" line, and drain its output. */
    private String spawn(Path binary, Path config) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(binary.toString(), "--config", config.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        services.add(process);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            int idx = line.indexOf("listening on ");
            if (idx >= 0) {
                String addr = line.substring(idx + "listening on ".length()).trim();
                Thread drain = new Thread(() -> {
                    try {
                        while (reader.readLine() != null) {
                            // service log output is irrelevant to the test
                        }
                    } catch (IOException ignored) {
                        // process exiting
                    }
                }, "service-drain");
                drain.setDaemon(true);
                drain.start();
                return addr;
            }
        }
        throw new IOException(binary.getFileName() + " did not report a listening address");
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
