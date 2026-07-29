package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.entity.PlayerRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that keeps captured movement honest (L-12): a step is only proposed once the committee
 * holds the presence it would move. Without it every captured step is rejected
 * {@code ENTITY_NOT_FOUND} on every replica — committee traffic that can never commit.
 */
final class PlayerLanePresenceTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final NodeId MOVER =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
    private static final NodeId SOMEBODY_ELSE =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000bb"));

    private static Optional<RegionSnapshot> snapshotHolding(NodeId... owners) {
        List<PersistedEntityState> players = new java.util.ArrayList<>();
        long id = 1;
        for (NodeId owner : owners) {
            players.add(new PersistedEntityState(
                    NetworkEntityId.allocate(REGION, SnapshotVersion.INITIAL, id++),
                    EntityKind.PLAYER, PlayerRules.PLAYER_TYPE_ID,
                    FixedVec3.fromExternal(64.5, 64.0, 64.5), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN,
                    PlayerRules.emptyInventoryPayload(owner)));
        }
        return Optional.of(new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0L, List.of(), players));
    }

    @Test
    void aRegisteredPresenceIsMovable() {
        assertThat(PlayerLanePresence.registered(snapshotHolding(MOVER), MOVER)).isTrue();
    }

    @Test
    void aPlayerWithNoCommittedPresenceProposesNothing() {
        assertThat(PlayerLanePresence.registered(snapshotHolding(SOMEBODY_ELSE), MOVER)).isFalse();
        assertThat(PlayerLanePresence.registered(snapshotHolding(), MOVER)).isFalse();
    }

    @Test
    void noSnapshotAndNoActorFailClosed() {
        assertThat(PlayerLanePresence.registered(Optional.empty(), MOVER)).isFalse();
        assertThat(PlayerLanePresence.registered(null, MOVER)).isFalse();
        assertThat(PlayerLanePresence.registered(snapshotHolding(MOVER), null)).isFalse();
    }
}
