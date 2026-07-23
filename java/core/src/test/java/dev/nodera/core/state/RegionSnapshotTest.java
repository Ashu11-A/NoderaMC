package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link RegionSnapshot} canonicalisation and byte-stability checks (Task 2). The chunks list is
 * sorted by {@code (chunkX, chunkZ)} so equivalent state encodes to identical bytes regardless of
 * collection order.
 */
final class RegionSnapshotTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    @Test
    void chunksSortedByChunkXThenChunkZ() {
        ChunkColumnState a = chunk(2, 5);
        ChunkColumnState b = chunk(2, 1);
        ChunkColumnState c = chunk(-1, 9);

        RegionSnapshot snap = new RegionSnapshot(
                REGION, new SnapshotVersion(1L), 100L, java.util.List.of(a, b, c));

        assertThat(snap.chunks()).extracting(ChunkColumnState::chunkX, ChunkColumnState::chunkZ)
                .containsExactly(tuple(-1, 9), tuple(2, 1), tuple(2, 5));
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionSnapshot snap = new RegionSnapshot(
                REGION, new SnapshotVersion(3L), 42L,
                java.util.List.of(chunk(1, 1), chunk(1, 2)));

        RegionSnapshot decoded = RegionSnapshot.decode(new CanonicalReader(encode(snap)));
        assertThat(decoded).isEqualTo(snap);
    }

    @Test
    void byteStableAcrossInputOrder() {
        ChunkColumnState a = chunk(2, 5);
        ChunkColumnState b = chunk(2, 1);
        RegionSnapshot s1 = new RegionSnapshot(REGION, new SnapshotVersion(1L), 100L, java.util.List.of(a, b));
        RegionSnapshot s2 = new RegionSnapshot(REGION, new SnapshotVersion(1L), 100L, java.util.List.of(b, a));

        assertThat(encode(s1)).isEqualTo(encode(s2));
    }

    @Test
    void entitiesSortedByNetworkId() {
        RegionSnapshot snapshot = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(),
                List.of(EntityMutationTest.entity(9, 1), EntityMutationTest.entity(-2, 1)));
        assertThat(snapshot.entities()).extracting(e -> e.id().value()).containsExactly(-2L, 9L);
    }

    @Test
    void entityInputOrderDoesNotChangeBytes() {
        PersistedEntityState a = EntityMutationTest.entity(1, 1);
        PersistedEntityState b = EntityMutationTest.entity(2, 1);
        RegionSnapshot first = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(), List.of(a, b));
        RegionSnapshot second = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(), List.of(b, a));
        assertThat(encode(first)).isEqualTo(encode(second));
    }

    @Test
    void entityTableChangesCanonicalBytes() {
        RegionSnapshot empty = new RegionSnapshot(REGION, SnapshotVersion.INITIAL, 0, List.of());
        RegionSnapshot populated = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(),
                List.of(EntityMutationTest.entity(1, 1)));
        assertThat(encode(populated)).isNotEqualTo(encode(empty));
    }

    @Test
    void versionOneSnapshotDecodesWithEmptyEntityTable() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(dev.nodera.core.crypto.TypeTags.REGION_SNAPSHOT).writeU16(1);
        REGION.encode(w);
        SnapshotVersion.INITIAL.encode(w);
        w.writeU64(7);
        w.writeList(List.of(chunk(0, 0)), CanonicalWriter::writeEncodable);
        byte[] versionOne = w.toByteArray();
        RegionSnapshot decoded = RegionSnapshot.decode(new CanonicalReader(versionOne));
        assertThat(decoded.tick()).isEqualTo(7);
        assertThat(decoded.entities()).isEmpty();
        assertThat(decoded.bodyVersion()).isEqualTo(1);
        assertThat(encode(decoded)).isEqualTo(versionOne);
    }

    @Test
    void rejectsDuplicateEntityIds() {
        assertThatThrownBy(() -> new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(),
                List.of(EntityMutationTest.entity(1, 1), EntityMutationTest.entity(1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate entity id");
    }

    @Test
    void rejectsUnsupportedBodyVersion() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(dev.nodera.core.crypto.TypeTags.REGION_SNAPSHOT).writeU16(3);
        assertThatThrownBy(() -> RegionSnapshot.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REGION_SNAPSHOT encoding version");
    }

    private static ChunkColumnState chunk(int x, int z) {
        return new ChunkColumnState(x, z, new int[]{1, 2}, -64, 2);
    }

    private static byte[] encode(RegionSnapshot snap) {
        CanonicalWriter w = new CanonicalWriter();
        snap.encode(w);
        return w.toByteArray();
    }
}
