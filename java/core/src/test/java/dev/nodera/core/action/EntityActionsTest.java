package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DropItemAction}/{@link PickupItemAction} (Task 12a): canonical round-trip via the
 * polymorphic {@link GameAction#decode} dispatch (the sealed hierarchy is self-describing on the
 * wire), and the determinism-critical invariant that the player carries NO entity id — the engine
 * allocates it from batch context.
 */
final class EntityActionsTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    @Test
    void dropItemRoundTripsThroughPolymorphicDispatch() {
        DropItemAction drop = new DropItemAction(0x4242, 64,
                new FixedVec3(FixedVec3.ONE, 70 * FixedVec3.ONE, 2 * FixedVec3.ONE));
        CanonicalWriter w = new CanonicalWriter();
        drop.encode(w);
        GameAction decoded = GameAction.decode(new CanonicalReader(w.toBytes().toArray()));
        assertThat(decoded).isEqualTo(drop);
    }

    @Test
    void pickupItemRoundTripsThroughPolymorphicDispatch() {
        PickupItemAction pickup = new PickupItemAction(
                NetworkEntityId.allocate(REGION, new SnapshotVersion(5), 3));
        CanonicalWriter w = new CanonicalWriter();
        pickup.encode(w);
        assertThat(GameAction.decode(new CanonicalReader(w.toBytes().toArray()))).isEqualTo(pickup);
    }

    @Test
    void dropRejectsNonPositiveCountAndNullOrigin() {
        assertThatThrownBy(() -> new DropItemAction(1, 0, FixedVec3.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DropItemAction(1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pickupRejectsNullId() {
        assertThatThrownBy(() -> new PickupItemAction(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allActionKindsDispatchWithoutCollision() {
        // Every GameAction permit opens with its own tag, so a mixed stream decodes each to its
        // concrete type — no tag serves two permits.
        GameAction[] actions = {
                new PlaceBlockAction(new dev.nodera.core.state.NBlockPos(1, 2, 3), 5, 1),
                new BreakBlockAction(new dev.nodera.core.state.NBlockPos(1, 2, 3)),
                new DropItemAction(9, 1, FixedVec3.ZERO),
                new PickupItemAction(NetworkEntityId.allocate(REGION, SnapshotVersion.INITIAL, 0)),
        };
        for (GameAction action : actions) {
            CanonicalWriter w = new CanonicalWriter();
            action.encode(w);
            GameAction decoded = GameAction.decode(new CanonicalReader(w.toBytes().toArray()));
            assertThat(decoded).isEqualTo(action);
            assertThat(decoded.getClass()).isEqualTo(action.getClass());
        }
    }
}
