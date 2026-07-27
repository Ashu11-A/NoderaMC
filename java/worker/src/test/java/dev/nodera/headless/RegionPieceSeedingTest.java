package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker L-41's second half: the worker seeds the validated lane's <b>region pieces</b>, not only
 * the whole-save archive, and keeps them advertised on its own announce.
 *
 * <p>What these tests are really pinning is that the two lanes stay separate books. Both produce a
 * {@link PieceManifest} with a {@code version} field, but an archive version means "this world's
 * save, later" while a region version means "this region's state, later" — and the archive's
 * retention, supersede and newest-wins rules all read the first meaning. Filing region snapshots
 * into the archive ladder would let a region at version 9 evict the world's actual bytes at
 * version 8, so the separation is the correctness property, not an implementation detail.
 */
final class RegionPieceSeedingTest {

    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;

    private final HashService hashes = new HashService();
    private WorldArchiveService service;
    private LoopbackTransport transport;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private WorldArchiveService worker() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        service = new WorldArchiveService(
                identity, transport, new InMemoryContentStore(hashes), List.of());
        return service;
    }

    private static RegionId region(int x, int z) {
        return new RegionId(DimensionKey.overworld(), x, z);
    }

    /** A snapshot whose bytes differ per version, so two versions are two distinct blobs. */
    private static RegionSnapshot snapshot(RegionId region, long version) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] sections = new int[SECTION_COUNT];
                sections[0] = (int) (version * 31 + dx * 7 + dz);
                chunks.add(new ChunkColumnState(region.originChunkX() + dx,
                        region.originChunkZ() + dz, sections, MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, new SnapshotVersion(version), version, chunks);
    }

    private static String worldId(String label) {
        return new HashService().sha256(label.getBytes()).toHex();
    }

    @Test
    @DisplayName("a committed region is split, published and advertised on the world's announce")
    void aRegionIsSeededAndAdvertised() {
        WorldArchiveService archive = worker();
        String world = worldId("l41-seeded");

        PieceManifest manifest = archive.seedRegion(world, snapshot(region(0, 0), 1));

        assertThat(manifest.region()).isEqualTo(region(0, 0));
        assertThat(archive.heldRegions(world)).containsExactly(region(0, 0));
        assertThat(archive.newestRegionManifest(world, region(0, 0))).contains(manifest);
        // The announce is the whole point: a region seeded but not advertised is a region nobody
        // can discover, which is the same as not seeding it.
        assertThat(archive.holdingsFor(world))
                .extracting(ManifestHolding::manifestRoot)
                .contains(manifest.manifestRoot());
        assertThat(archive.maintainedPieces()).isEqualTo(manifest.pieceCount());
    }

    @Test
    @DisplayName("region versions never touch the archive ladder")
    void theTwoLanesKeepSeparateBooks() {
        WorldArchiveService archive = worker();
        String world = worldId("l41-separate");

        PieceManifest save = archive.seedArchive(world, "a world save".getBytes());
        // A region at a far higher version than the archive's: if the ladders were shared, the
        // archive's newest-wins and supersede rules would treat this as a newer world and evict
        // the save.
        archive.seedRegion(world, snapshot(region(0, 0), 99));

        assertThat(archive.heldVersions(world))
                .as("the archive ladder holds saves and nothing else")
                .containsExactly(save);
        assertThat(archive.newestManifest(world)).contains(save);
        assertThat(archive.supersedeOlderVersions(world))
                .as("one archive version cannot be superseded by a region")
                .isZero();
        assertThat(archive.newestRegionManifest(world, region(0, 0))).isPresent();
        // Both lanes are advertised together — the announce says "I have this world and these
        // regions of it" in one record.
        assertThat(archive.holdingsFor(world)).hasSize(2);
    }

    @Test
    @DisplayName("re-seeding a version already held is idempotent, not a fork")
    void reseedingTheSameVersionChangesNothing() {
        WorldArchiveService archive = worker();
        String world = worldId("l41-idempotent");
        RegionSnapshot committed = snapshot(region(1, 2), 7);

        PieceManifest first = archive.seedRegion(world, committed);
        PieceManifest again = archive.seedRegion(world, committed);

        assertThat(again).isSameAs(first);
        assertThat(archive.holdingsFor(world)).hasSize(1);
    }

    @Test
    @DisplayName("the region window bounds a long session, keeping the newest snapshots fetchable")
    void oldRegionVersionsAreEvicted() {
        WorldArchiveService archive = worker();
        archive.setRetainedRegionVersions(2);
        String world = worldId("l41-window");
        RegionId region = region(3, 4);

        List<PieceManifest> streamed = new ArrayList<>();
        for (long version = 1; version <= 12; version++) {
            streamed.add(archive.seedRegion(world, snapshot(region, version)));
        }

        assertThat(archive.newestRegionManifest(world, region)).contains(streamed.get(11));
        // Exactly the window survives on disk: twelve versions were seeded, two are still held.
        assertThat(archive.maintainedPieces())
                .as("an evicted version releases its pieces — that is what bounds the store")
                .isEqualTo(streamed.get(10).pieceCount() + streamed.get(11).pieceCount());
        // The announce carries the NEWEST snapshot per region, not every retained one: the older
        // version exists so a joiner mid-fetch keeps its bytes, not so a new joiner starts on it.
        assertThat(archive.holdingsFor(world))
                .extracting(ManifestHolding::manifestRoot)
                .containsExactly(streamed.get(11).manifestRoot());
    }

    @Test
    @DisplayName("the window is at least one: the newest snapshot IS the region")
    void theWindowNeverEmptiesTheRegion() {
        WorldArchiveService archive = worker();
        archive.setRetainedRegionVersions(0);
        assertThat(archive.retainedRegionVersions()).isEqualTo(1);

        String world = worldId("l41-floor");
        archive.seedRegion(world, snapshot(region(0, 0), 1));
        PieceManifest newest = archive.seedRegion(world, snapshot(region(0, 0), 2));

        assertThat(archive.newestRegionManifest(world, region(0, 0))).contains(newest);
        assertThat(archive.holdingsFor(world)).hasSize(1);
    }

    @Test
    @DisplayName("regions are advertised in a canonical order, capped so an announce stays bounded")
    void theAdvertisedRegionsAreCappedAndOrdered() {
        WorldArchiveService archive = worker();
        String world = worldId("l41-cap");
        int seeded = WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS + 10;
        // Seeded out of order on purpose: the cap must cut the same prefix however they arrived,
        // or two announces from this node would describe two different worlds.
        for (int i = seeded - 1; i >= 0; i--) {
            archive.seedRegion(world, snapshot(region(i, 0), 1));
        }

        assertThat(archive.heldRegions(world)).hasSize(seeded);
        List<ManifestHolding> holdings = archive.holdingsFor(world);
        assertThat(holdings).hasSize(WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS);
        List<Bytes> expected = new ArrayList<>();
        for (int i = 0; i < WorldArchiveService.MAX_ADVERTISED_REGION_HOLDINGS; i++) {
            expected.add(archive.newestRegionManifest(world, region(i, 0)).orElseThrow()
                    .manifestRoot());
        }
        assertThat(holdings).extracting(ManifestHolding::manifestRoot)
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("a region manifest is filed by its own region, and the archive's by ARCHIVE_REGION")
    void manifestsAreFiledByTheRegionTheyCarry() {
        WorldArchiveService archive = worker();
        String world = worldId("l41-filing");

        PieceManifest save = archive.seedArchive(world, "bytes".getBytes());
        PieceManifest committed = archive.seedRegion(world, snapshot(region(5, 5), 3));

        // This is what makes the two lanes separable with no new message on the wire: which lane a
        // manifest belongs to is already written on the manifest.
        assertThat(save.region()).isEqualTo(WorldArchive.ARCHIVE_REGION);
        assertThat(committed.region()).isEqualTo(region(5, 5));
    }
}
