package dev.nodera.coordinator;

import dev.nodera.core.state.EntityKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityDelegabilityRulesTest {

    @Test
    void emptyAndItemOnlyRegionsAreDelegable() {
        assertThat(EntityDelegabilityRules.allows(List.of(), false)).isTrue();
        assertThat(EntityDelegabilityRules.allows(List.of(EntityKind.ITEM), false)).isTrue();
    }

    @Test
    void ghostBlocksDelegationWhenMobCaptureIsOff() {
        assertThat(EntityDelegabilityRules.allows(List.of(EntityKind.ITEM, EntityKind.GHOST), false))
                .isFalse();
    }

    @Test
    void ghostIsAllowedWhenMobCaptureIsOn() {
        assertThat(EntityDelegabilityRules.allows(List.of(EntityKind.ITEM, EntityKind.GHOST), true))
                .isTrue();
    }

    @Test
    void blockingValueFeedsLegacyPolicyInput() {
        assertThat(EntityDelegabilityRules.hasBlockingEntity(List.of(EntityKind.GHOST), false)).isTrue();
        assertThat(EntityDelegabilityRules.hasBlockingEntity(List.of(EntityKind.GHOST), true)).isFalse();
    }

    @Test
    void nullCollectionOrKindFailsClosed() {
        assertThatThrownBy(() -> EntityDelegabilityRules.allows(null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EntityDelegabilityRules.allows(java.util.Arrays.asList(EntityKind.ITEM, null), true))
                .isFalse();
    }
}
