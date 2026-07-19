package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NetworkEntityId} determinism + {@link PersistedEntityState} canonical round-trip (Task 12a).
 * The id allocator is a pure function (same region/version/seq ⇒ same id on every replica — never a
 * random UUID), and the persisted state — the unit that enters the entity table and the root —
 * round-trips over both entity kinds.
 */
final class EntityLaneTypesTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId REGION_B = new RegionId(DimensionKey.overworld(), 7, 0);

    @Test
    void idAllocationIsDeterministicAndCollisionFree() {
        SnapshotVersion v = new SnapshotVersion(12);
        // Same inputs ⇒ identical id on every replica (the determinism rule).
        assertThat(NetworkEntityId.allocate(REGION, v, 0))
                .isEqualTo(NetworkEntityId.allocate(REGION, v, 0));
        // Distinct seq ⇒ distinct id; distinct region ⇒ distinct id.
        assertThat(NetworkEntityId.allocate(REGION, v, 0))
                .isNotEqualTo(NetworkEntityId.allocate(REGION, v, 1));
        assertThat(NetworkEntityId.allocate(REGION, v, 0))
                .isNotEqualTo(NetworkEntityId.allocate(REGION_B, v, 0));
        // Ids are Comparable (the entity table's canonical iteration order); ordering is by the
        // raw hash, not by seq, so we only assert distinctness, not a direction.
        assertThat(NetworkEntityId.allocate(REGION, v, 0).compareTo(
                NetworkEntityId.allocate(REGION, v, 1000))).isNotEqualTo(0);
    }

    @Test
    void networkEntityIdRoundTrips() {
        NetworkEntityId id = NetworkEntityId.allocate(REGION, new SnapshotVersion(3), 7);
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        assertThat(NetworkEntityId.decode(new CanonicalReader(w.toBytes().toArray()))).isEqualTo(id);
    }

    @Test
    void persistedStateRoundTripsForBothKinds() {
        for (EntityKind kind : EntityKind.values()) {
            PersistedEntityState state = new PersistedEntityState(
                    NetworkEntityId.allocate(REGION, new SnapshotVersion(1), 2),
                    kind, 0x1234,
                    new FixedVec3(FixedVec3.ONE, -FixedVec3.ONE, 3 * FixedVec3.ONE),
                    FixedVec3.ZERO,
                    42, 6000, Bytes.fromHex("cafe"));
            CanonicalWriter w = new CanonicalWriter();
            state.encode(w);
            assertThat(PersistedEntityState.decode(new CanonicalReader(w.toBytes().toArray())))
                    .isEqualTo(state);
        }
    }

    @Test
    void tickAdvancesAgeAndDespawnBoundaryIsInclusive() {
        PersistedEntityState state = new PersistedEntityState(
                NetworkEntityId.allocate(REGION, new SnapshotVersion(1), 0),
                EntityKind.ITEM, 1, FixedVec3.ZERO, FixedVec3.ZERO,
                5999, 6000, Bytes.empty());
        assertThat(state.shouldDespawn()).isFalse();
        assertThat(state.tick().shouldDespawn()).isTrue();
    }

    @Test
    void neverDespawnSentinel() {
        PersistedEntityState immortal = new PersistedEntityState(
                NetworkEntityId.allocate(REGION, new SnapshotVersion(1), 0),
                EntityKind.GHOST, 1, FixedVec3.ZERO, FixedVec3.ZERO,
                1_000_000, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
        assertThat(immortal.shouldDespawn()).isFalse();
    }
}
