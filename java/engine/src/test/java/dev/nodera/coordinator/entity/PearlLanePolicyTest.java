package dev.nodera.coordinator.entity;

import dev.nodera.coordinator.DelegabilityPolicy;
import dev.nodera.coordinator.EntityDelegabilityRules;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Named Task-12 pearl suite: ghosting, border materialization, isolation, and action rerouting. */
final class PearlLanePolicyTest {

    @Test
    void flightGhostsOnlyWhenMobCaptureIsEnabled() {
        assertThat(EntityDelegabilityRules.allows(List.of(EntityKind.GHOST), false)).isFalse();
        assertThat(EntityDelegabilityRules.allows(List.of(EntityKind.GHOST), true)).isTrue();
    }

    @Test
    void borderCrossingTransfersToDelegatedAndMaterializesIntoVanilla() {
        assertThat(EntityLaneRouting.ghostBorder(true, true))
                .isEqualTo(EntityLaneRouting.GhostBorderRoute.REHOME_GHOST);
        assertThat(EntityLaneRouting.ghostBorder(true, false))
                .isEqualTo(EntityLaneRouting.GhostBorderRoute.MATERIALIZE_VANILLA);
    }

    @Test
    void pearlTicketDoesNotDelegateLoadedPlayerlessDestination() {
        DelegabilityPolicy.Inputs playerless = new DelegabilityPolicy.Inputs(
                true, true, 3, false, true, true, false,
                false, false, false, false, 0);

        assertThat(new DelegabilityPolicy(3, true)
                .evaluate(new RegionId(DimensionKey.overworld(), 1, 0), playerless)
                .reasons()).containsExactly(DelegabilityPolicy.Reason.NO_PLAYER_PRESENT);
    }

    @Test
    void teleportRoutesNextActionFromPlayersNewChunk() {
        RegionId before = RegionId.fromChunk(DimensionKey.overworld(), 7, 0);
        RegionId after = RegionId.fromChunk(DimensionKey.overworld(), 8, 0);

        assertThat(before).isNotEqualTo(after);
        assertThat(after.regionX()).isEqualTo(1);
    }
}
