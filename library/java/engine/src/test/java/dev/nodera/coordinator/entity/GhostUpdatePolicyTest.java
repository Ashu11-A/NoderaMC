package dev.nodera.coordinator.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class GhostUpdatePolicyTest {

    @Test
    void unchangedOrSubIntervalMotionIsCoalesced() {
        PersistedEntityState initial = ghost(0, FixedVec3.ofBlock(1, 2, 3), Bytes.fromHex("01"));

        assertThat(GhostUpdatePolicy.shouldEmit(initial, initial)).isFalse();
        assertThat(GhostUpdatePolicy.shouldEmit(
                initial, ghost(4, FixedVec3.ofBlock(2, 2, 3), Bytes.fromHex("01"))))
                .isFalse();
        assertThat(GhostUpdatePolicy.shouldEmit(
                initial, ghost(5, FixedVec3.ofBlock(2, 2, 3), Bytes.fromHex("01"))))
                .isTrue();
    }

    @Test
    void healthOrPosePayloadChangeEmitsImmediately() {
        PersistedEntityState initial = ghost(10, FixedVec3.ZERO, Bytes.fromHex("01"));
        PersistedEntityState changed = ghost(11, FixedVec3.ZERO, Bytes.fromHex("02"));

        assertThat(GhostUpdatePolicy.shouldEmit(initial, changed)).isTrue();
    }

    private static PersistedEntityState ghost(int age, FixedVec3 position, Bytes payload) {
        return new PersistedEntityState(
                new NetworkEntityId(1), EntityKind.GHOST, 54, position, FixedVec3.ZERO,
                age, PersistedEntityState.NEVER_DESPAWN, payload);
    }
}
