package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.peer.control.ControlProtocol;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
