package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.PeerRuntimeConfig;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.peer.control.ControlServer;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker L-41 over the real control socket: {@code NODERA-SEED-REGION} hands the always-on worker a
 * committed region snapshot, and the worker seeds and advertises it.
 *
 * <p>The verb exists because of who holds what. Under field-of-view ownership the seats live on the
 * players' nodes, so the process that commits a region is usually a game client — the one process
 * guaranteed to go away. Pushing the snapshot across the loopback control channel is what puts the
 * region on a process that does not.
 */
final class SeedRegionVerbIT {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 2, -3);

    private final HashService hashes = new HashService();
    private ControlServer control;
    private WorldArchiveService archive;
    private WorldHostingService hosting;
    private PeerRuntime runtime;
    private LoopbackTransport transport;

    @AfterEach
    void tearDown() {
        if (control != null) {
            control.close();
        }
        if (archive != null) {
            archive.close();
        }
        if (hosting != null) {
            try {
                hosting.close();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (runtime != null) {
            try {
                runtime.stop();
            } catch (RuntimeException ignored) {
                // teardown
            }
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private void startWorker() throws java.io.IOException {
        NodeIdentity identity = NodeIdentity.generate();
        NodeCapabilities caps = NodeCapabilities.initial()
                .withRoles(EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP));
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        runtime = PeerRuntime.bootstrap(identity, caps, transport,
                () -> "loopback", PeerRuntimeConfig.defaults(), null);
        archive = new WorldArchiveService(
                identity, transport, new InMemoryContentStore(hashes), List.of());
        hosting = new WorldHostingService(identity, caps, runtime::selfRoute,
                List.of(), List.of(), archive::holdingsFor);
        control = new ControlServer("127.0.0.1", 0, new WorkerControlHandler(
                "seed-region-test", identity, caps, runtime,
                new dev.nodera.diagnostics.metric.TrafficMeter(), hosting, null, archive));
        control.start();
    }

    private String request(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", control.boundPort()), 2000);
            s.setSoTimeout(30_000);
            OutputStream out = s.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
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
        return Base64.getEncoder().encodeToString(
                path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the verb seeds a committed region and the world's announce advertises it")
    void theVerbSeedsAndAdvertises(@TempDir Path dir) throws Exception {
        startWorker();
        String world = hashes.sha256("l41-verb".getBytes()).toHex();
        hosting.host(world, "A World", "{}");

        String reply = request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                + b64(write(dir, snapshot(4))));

        assertThat(reply).startsWith(ControlProtocol.OK);
        String[] parts = reply.split(" ");
        assertThat(parts[2]).as("the snapshot's own version, not a counter the worker invents")
                .isEqualTo("4");
        assertThat(archive.heldRegions(world)).containsExactly(REGION);
        assertThat(archive.newestRegionManifest(world, REGION).orElseThrow().manifestRoot().toHex())
                .isEqualTo(parts[1]);
        assertThat(archive.holdingsFor(world)).hasSize(1);
    }

    @Test
    @DisplayName("the world stays seeded after the pushing process is gone — the whole of L-41")
    void theRegionOutlivesItsCommitter(@TempDir Path dir) throws Exception {
        startWorker();
        String world = hashes.sha256("l41-outlives".getBytes()).toHex();
        hosting.host(world, "A World", "{}");
        archive.seedArchive(world, "the save's bytes".getBytes());

        // One control connection per push, each closed on the way out — the caller is transient by
        // construction, exactly like the game client this models.
        for (long version = 1; version <= 3; version++) {
            assertThat(request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                    + b64(write(dir, snapshot(version))))).startsWith(ControlProtocol.OK);
        }

        // Nothing is connected now. Both lanes are still held and still advertised, which is the
        // row's exit clause: whole-save archive AND validated-lane region pieces, on the worker's
        // own timer, after the driving process disconnected.
        assertThat(archive.newestManifest(world)).isPresent();
        assertThat(archive.newestRegionManifest(world, REGION).orElseThrow().version().value())
                .isEqualTo(3);
        assertThat(archive.holdingsFor(world))
                .as("one announce carries the save and the region alike")
                .hasSize(2);
        assertThat(archive.maintainedPieces()).isGreaterThan(1);
    }

    @Test
    @DisplayName("a file that is not a region snapshot is refused, not seeded")
    void garbageIsRefused(@TempDir Path dir) throws Exception {
        startWorker();
        String world = hashes.sha256("l41-garbage".getBytes()).toHex();
        Path junk = dir.resolve("not-a-snapshot.bin");
        Files.write(junk, "definitely not canonical".getBytes(StandardCharsets.UTF_8));

        String reply = request(ControlProtocol.SEED_REGION + " 2 " + world + " " + b64(junk));

        // Seeding it blind would advertise content no fetcher could ever use.
        assertThat(reply).startsWith(ControlProtocol.ERR);
        assertThat(archive.heldRegions(world)).isEmpty();
    }

    @Test
    @DisplayName("a missing file and a missing world id are refused with a reason")
    void argumentsAreChecked(@TempDir Path dir) throws Exception {
        startWorker();
        String world = hashes.sha256("l41-args".getBytes()).toHex();

        assertThat(request(ControlProtocol.SEED_REGION + " 2 " + world + " "
                + b64(dir.resolve("absent.bin")))).startsWith(ControlProtocol.ERR);
        assertThat(request(ControlProtocol.SEED_REGION + " 2  "
                + b64(write(dir, snapshot(1))))).startsWith(ControlProtocol.ERR);
    }
}
