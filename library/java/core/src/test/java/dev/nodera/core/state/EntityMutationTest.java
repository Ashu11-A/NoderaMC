package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityMutationTest {

    @Test
    void createRoundTrips() {
        PersistedEntityState entity = entity(1, 3);
        EntityMutation mutation = new EntityMutation(entity.id(), null, entity);
        assertThat(roundTrip(mutation)).isEqualTo(mutation);
        assertThat(mutation.isCreate()).isTrue();
        assertThat(mutation.isUpdate()).isFalse();
        assertThat(mutation.isRemove()).isFalse();
    }

    @Test
    void updateRoundTrips() {
        PersistedEntityState before = entity(2, 3);
        PersistedEntityState after = new PersistedEntityState(
                before.id(), before.kind(), before.typeId(), before.pos(), before.vel(),
                1, before.despawnTick(), before.payload());
        EntityMutation mutation = new EntityMutation(before.id(), before, after);
        assertThat(roundTrip(mutation)).isEqualTo(mutation);
        assertThat(mutation.isUpdate()).isTrue();
    }

    @Test
    void removeRoundTrips() {
        PersistedEntityState entity = entity(3, 3);
        EntityMutation mutation = new EntityMutation(entity.id(), entity, null);
        assertThat(roundTrip(mutation)).isEqualTo(mutation);
        assertThat(mutation.isRemove()).isTrue();
    }

    @Test
    void rejectsMutationWithNeitherSide() {
        assertThatThrownBy(() -> new EntityMutation(new NetworkEntityId(1), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected or new");
    }

    @Test
    void rejectsNullId() {
        assertThatThrownBy(() -> new EntityMutation(null, null, entity(1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpectedStateWithDifferentId() {
        assertThatThrownBy(() -> new EntityMutation(new NetworkEntityId(9), entity(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedPrevious id");
    }

    @Test
    void rejectsNewStateWithDifferentId() {
        assertThatThrownBy(() -> new EntityMutation(new NetworkEntityId(9), null, entity(1, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newState id");
    }

    @Test
    void rejectsWrongTag() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(999).writeU16(1);
        assertThatThrownBy(() -> EntityMutation.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENTITY_MUTATION");
    }

    private static EntityMutation roundTrip(EntityMutation mutation) {
        CanonicalWriter w = new CanonicalWriter();
        mutation.encode(w);
        return EntityMutation.decode(new CanonicalReader(w.toByteArray()));
    }

    static PersistedEntityState entity(long id, int count) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(1, 5, 1), FixedVec3.ZERO,
                0, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 42, (byte) count}));
    }
}
