package dev.nodera.distribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A world is stored and sent as one blob per region file, and every blob knows which region it is.
 *
 * <p>The property that matters is the last test here: a player exploring one corner of the world
 * leaves every other region's bytes <b>identical</b>, so a peer holding them has nothing to
 * re-fetch. Under the single-blob archive the opposite was true — any change anywhere produced a
 * new 54 MiB blob with a new root, and every peer downloaded all of it again.
 */
final class RegionSplitArchiveTest {

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> save() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("level.dat", bytes("world settings"));
        files.put("region/r.0.0.mca", bytes("spawn blocks"));
        files.put("entities/r.0.0.mca", bytes("spawn entities"));
        files.put("poi/r.0.0.mca", bytes("spawn poi"));
        files.put("region/r.-1.0.mca", bytes("west blocks"));
        files.put("DIM-1/region/r.0.0.mca", bytes("nether blocks"));
        files.put("playerdata/steve.dat", bytes("inventory"));
        return files;
    }

    @Test
    @DisplayName("every file lands in exactly one region, and the world reassembles from them")
    void splitThenMergeIsIdentity() {
        Map<String, byte[]> original = save();
        var split = RegionSplitArchive.split(original);

        assertThat(split.keySet()).containsExactly(
                SaveRegion.WORLD,
                new SaveRegion("", -1, 0),
                new SaveRegion("", 0, 0),
                new SaveRegion("DIM-1", 0, 0));

        var merged = RegionSplitArchive.merge(split);
        assertThat(merged.keySet()).containsExactlyInAnyOrderElementsOf(original.keySet());
        for (var entry : original.entrySet()) {
            assertThat(merged.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("one region's blob carries one region's ground — blocks, entities and poi")
    void aRegionBlobIsSelfContained() {
        var split = RegionSplitArchive.split(save());
        assertThat(WorldArchive.entryPaths(split.get(new SaveRegion("", 0, 0))))
                .containsExactly("entities/r.0.0.mca", "poi/r.0.0.mca", "region/r.0.0.mca");
        assertThat(WorldArchive.entryPaths(split.get(SaveRegion.WORLD)))
                .containsExactly("level.dat", "playerdata/steve.dat");
    }

    @Test
    @DisplayName("a peer can hold part of a world: merging a subset yields that subset")
    void aPartialHoldingIsAPartialWorld() {
        var split = RegionSplitArchive.split(save());
        Map<SaveRegion, byte[]> held = Map.of(
                SaveRegion.WORLD, split.get(SaveRegion.WORLD),
                new SaveRegion("", 0, 0), split.get(new SaveRegion("", 0, 0)));

        assertThat(RegionSplitArchive.merge(held).keySet())
                .containsExactlyInAnyOrder("level.dat", "playerdata/steve.dat",
                        "entities/r.0.0.mca", "poi/r.0.0.mca", "region/r.0.0.mca");
    }

    @Test
    @DisplayName("a blob cannot smuggle in another region's files")
    void aMisfiledBlobIsRefused() {
        var split = RegionSplitArchive.split(save());
        Map<SaveRegion, byte[]> lying = Map.of(
                new SaveRegion("", 9, 9), split.get(new SaveRegion("", 0, 0)));

        assertThatThrownBy(() -> RegionSplitArchive.merge(lying))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("belongs to");
    }

    @Test
    @DisplayName("a save directory splits by region, world bucket always present")
    void directoriesSplit(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("region"));
        Files.write(dir.resolve("level.dat"), bytes("settings"));
        Files.write(dir.resolve("region/r.2.3.mca"), bytes("far terrain"));

        var split = RegionSplitArchive.splitSaveDirectory(dir);
        assertThat(split.keySet()).containsExactly(SaveRegion.WORLD, new SaveRegion("", 2, 3));

        var empty = Files.createDirectories(dir.resolve("empty"));
        assertThat(RegionSplitArchive.splitSaveDirectory(empty))
                .containsOnlyKeys(SaveRegion.WORLD);
    }

    /**
     * The index is what makes a region-addressed world discoverable at all: the manifest query
     * answers a bounded prefix, so a world of hundreds of regions can only be enumerated from
     * content the fetcher already verified.
     */
    @Test
    @DisplayName("the region index round-trips, carrying a fetchable manifest per region")
    void theIndexRoundTrips() {
        var split = RegionSplitArchive.split(save());
        java.util.SequencedMap<SaveRegion, PieceManifest> index = new java.util.LinkedHashMap<>();
        split.forEach((region, blob) ->
                index.put(region, WorldArchive.manifestFor(region.toRegionId(), 7L, blob)));

        var decoded = RegionSplitArchive.decodeIndex(RegionSplitArchive.encodeIndex(index));

        assertThat(decoded.keySet()).containsExactlyElementsOf(index.keySet());
        index.forEach((region, manifest) -> assertThat(decoded.get(region).manifestRoot())
                .as(region.toString()).isEqualTo(manifest.manifestRoot()));

        // And it is readable straight out of a fetched world bucket.
        Map<String, byte[]> world = new LinkedHashMap<>(WorldArchive.unpack(
                split.get(SaveRegion.WORLD)));
        world.put(RegionSplitArchive.INDEX_ENTRY, RegionSplitArchive.encodeIndex(index));
        assertThat(RegionSplitArchive.indexOf(WorldArchive.pack(world)))
                .get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .hasSize(index.size());
        assertThat(RegionSplitArchive.indexOf(split.get(SaveRegion.WORLD)))
                .as("a world bucket from a peer on the whole-world format carries no index")
                .isEmpty();
    }

    /**
     * The whole point of addressing content by region: exploring one corner of the world must not
     * make every other corner's bytes new.
     */
    @Test
    @DisplayName("changing one region leaves every other region byte-identical")
    void anUnchangedRegionKeepsItsBytes() {
        var before = RegionSplitArchive.split(save());

        Map<String, byte[]> after = save();
        after.put("region/r.-1.0.mca", bytes("west blocks, now with a house"));
        after.put("region/r.7.7.mca", bytes("terrain a player just walked into"));
        var split = RegionSplitArchive.split(after);

        assertThat(split.get(new SaveRegion("", 0, 0)))
                .as("the spawn region did not change, so a peer holding it re-fetches nothing")
                .isEqualTo(before.get(new SaveRegion("", 0, 0)));
        assertThat(split.get(new SaveRegion("DIM-1", 0, 0)))
                .isEqualTo(before.get(new SaveRegion("DIM-1", 0, 0)));
        assertThat(split.get(new SaveRegion("", -1, 0)))
                .isNotEqualTo(before.get(new SaveRegion("", -1, 0)));
        assertThat(split).containsKey(new SaveRegion("", 7, 7));
    }
}
