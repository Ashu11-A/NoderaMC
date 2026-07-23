package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityStoreTest {

    @Test
    void entitiesAreAlwaysIdSorted() {
        EntityStore store = new EntityStore(List.of(entity(7, 0), entity(-2, 0)));
        assertThat(store.entities()).extracting(e -> e.id().value()).containsExactly(-2L, 7L);
    }

    @Test
    void createProducesCreateMutation() {
        EntityStore store = new EntityStore(List.of());
        store.create(entity(1, 0));
        assertThat(store.mutations()).singleElement().satisfies(mutation -> {
            assertThat(mutation.isCreate()).isTrue();
            assertThat(mutation.id()).isEqualTo(new NetworkEntityId(1));
        });
    }

    @Test
    void updateProducesOneUpdateMutation() {
        PersistedEntityState before = entity(1, 0);
        PersistedEntityState after = entity(1, 1);
        EntityStore store = new EntityStore(List.of(before));
        store.update(after);
        assertThat(store.mutations()).singleElement().satisfies(mutation -> {
            assertThat(mutation.expectedPrevious()).isEqualTo(before);
            assertThat(mutation.newState()).isEqualTo(after);
        });
    }

    @Test
    void removeProducesRemoveMutation() {
        PersistedEntityState before = entity(1, 0);
        EntityStore store = new EntityStore(List.of(before));
        assertThat(store.remove(before.id())).isEqualTo(before);
        assertThat(store.mutations()).singleElement().satisfies(mutation ->
                assertThat(mutation.isRemove()).isTrue());
    }

    @Test
    void createThenRemoveProducesNoStateMutation() {
        EntityStore store = new EntityStore(List.of());
        PersistedEntityState created = entity(1, 0);
        store.create(created);
        store.remove(created.id());
        assertThat(store.mutations()).isEmpty();
    }

    @Test
    void createThenUpdateCoalescesToOneCreateAtFinalState() {
        EntityStore store = new EntityStore(List.of());
        store.create(entity(1, 0));
        store.update(entity(1, 5));
        assertThat(store.mutations()).singleElement().satisfies(mutation -> {
            assertThat(mutation.expectedPrevious()).isNull();
            assertThat(mutation.newState().ageTicks()).isEqualTo(5);
        });
    }

    @Test
    void creditsRetainEveryRequestedOneWayEffect() {
        EntityStore store = new EntityStore(List.of());
        InventoryCredit credit = new InventoryCredit(
                new NodeId(new UUID(1, 2)), new NetworkEntityId(3), 4, 5);
        store.credit(credit);
        assertThat(store.credits()).containsExactly(credit);
    }

    @Test
    void duplicateCreateFails() {
        EntityStore store = new EntityStore(List.of(entity(1, 0)));
        assertThatThrownBy(() -> store.create(entity(1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void updateAndRemoveOfMissingEntityFail() {
        EntityStore store = new EntityStore(List.of());
        assertThatThrownBy(() -> store.update(entity(1, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.remove(new NetworkEntityId(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PersistedEntityState entity(long id, int age) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.ITEM, 4,
                FixedVec3.ofBlock(1, 5, 1), FixedVec3.ZERO,
                age, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 4, 1}));
    }
}
