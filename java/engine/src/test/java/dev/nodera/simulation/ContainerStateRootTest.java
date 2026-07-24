package dev.nodera.simulation;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ContainerEntry;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.shadow.SnapshotDeltaApplier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16 / L-10 (container-lane increment 1): container contents are REGION STATE — they live in
 * the hashed root (snapshot body v4), ride deltas with replace semantics (delta body v5), and
 * reproduce byte-exactly on the applier. Pre-container transitions keep their exact bytes.
 */
final class ContainerStateRootTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);

    private static ContainerEntry chest(int x, int y, int z, int itemId, int count) {
        List<ItemSlot> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            slots.add(ItemSlot.EMPTY);
        }
        slots.set(0, new ItemSlot(itemId, count));
        return new ContainerEntry(new NBlockPos(x, y, z), slots);
    }

    @Test
    void containerSnapshotRoundTripsWithCanonicalOrder() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, 0);
        // Deliberately unsorted input: canonicalisation must order by (y, z, x).
        RegionSnapshot v4 = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(),
                List.of(), List.of(),
                List.of(chest(70, 65, 70, 42, 3), chest(64, 64, 64, 7, 1)),
                RegionSnapshot.CONTAINER_ENCODING_VERSION);
        assertThat(v4.containers().get(0).pos()).isEqualTo(new NBlockPos(64, 64, 64));

        CanonicalWriter w = new CanonicalWriter();
        v4.encode(w);
        RegionSnapshot decoded = RegionSnapshot.decode(new CanonicalReader(w.toBytes().toArray()));
        assertThat(decoded).isEqualTo(v4);
        assertThat(decoded.containers()).hasSize(2);
    }

    @Test
    void preContainerSnapshotsKeepTheirExactBytes() {
        RegionSnapshot v2 = TestFixtures.fullUniformSnapshot(region, 0);
        CanonicalWriter w = new CanonicalWriter();
        v2.encode(w);
        RegionSnapshot decoded = RegionSnapshot.decode(new CanonicalReader(w.toBytes().toArray()));
        assertThat(decoded).isEqualTo(v2);
        assertThat(decoded.bodyVersion())
                .as("no containers ⇒ the body version (and thus every stored root) is unchanged")
                .isLessThan(RegionSnapshot.CONTAINER_ENCODING_VERSION);
    }

    @Test
    void containerSurvivesTheDeltaBoundaryByteExactly() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        MutableRegionState state = new MutableRegionState(base, RegionBounds.of(region));
        state.putContainer(chest(64, 64, 64, 42, 5));

        RegionSnapshot after = state.toSnapshot(SnapshotVersion.INITIAL.next(), 1L);
        assertThat(after.bodyVersion()).isEqualTo(RegionSnapshot.CONTAINER_ENCODING_VERSION);
        StateRoot root = StateRoot.of(hashes.hash(after));
        RegionDelta delta = state.toDelta(base.version(), after.version(), root);
        assertThat(delta.bodyVersion()).isEqualTo(RegionDelta.CONTAINER_ENCODING_VERSION);

        RegionSnapshot applied = SnapshotDeltaApplier.apply(base, delta, 1L);
        assertThat(StateRoot.of(hashes.hash(applied)))
                .as("the applier reproduces the container-bearing root byte-for-byte")
                .isEqualTo(root);
        assertThat(applied.containers()).hasSize(1);
        assertThat(applied.containers().get(0).slots().get(0))
                .isEqualTo(new ItemSlot(42, 5));
    }

    @Test
    void emptyingTheLastContainerStillShipsTheReplacingDelta() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(), List.of(), List.of(),
                List.of(chest(64, 64, 64, 42, 5)),
                RegionSnapshot.CONTAINER_ENCODING_VERSION);
        MutableRegionState state = new MutableRegionState(base, RegionBounds.of(region));
        state.removeContainer(new NBlockPos(64, 64, 64));

        RegionSnapshot after = state.toSnapshot(base.version().next(), 1L);
        StateRoot root = StateRoot.of(hashes.hash(after));
        RegionDelta delta = state.toDelta(base.version(), after.version(), root);
        assertThat(delta.bodyVersion())
                .as("base had container state ⇒ the clearing delta must still be v5")
                .isEqualTo(RegionDelta.CONTAINER_ENCODING_VERSION);

        RegionSnapshot applied = SnapshotDeltaApplier.apply(base, delta, 1L);
        assertThat(applied.containers())
                .as("the applier clears the table instead of carrying the stale chest forward")
                .isEmpty();
        assertThat(StateRoot.of(hashes.hash(applied))).isEqualTo(root);
    }

    @Test
    void malformedContainersAreRejected() {
        assertThatThrownBy(() -> new ItemSlot(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemSlot(3, 256))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContainerEntry(new NBlockPos(0, 0, 0), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, 0);
        assertThatThrownBy(() -> new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(), List.of(), List.of(),
                List.of(chest(1, 1, 1, 2, 2), chest(1, 1, 1, 3, 3)),
                RegionSnapshot.CONTAINER_ENCODING_VERSION))
                .as("duplicate container positions are structurally invalid")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
