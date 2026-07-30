package dev.nodera.simulation.border;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 increment 9 (L-26): the contraption-migration decision core. Pure-function rules —
 * DEMOTE when the group touches the vanilla lane, MIGRATE to one shared primary when
 * primaries differ, INTERNAL when already single-primary; the group is the flood-fill closure
 * of border signals; idle groups decay after {@code decayTicks}.
 */
final class ContraptionMigratorTest {

    private static final DimensionKey DIM = new DimensionKey("minecraft", "overworld");
    private static final RegionId A = new RegionId(DIM, 0, 0);   // owns x,z in [0,128)
    private static final RegionId B = new RegionId(DIM, 1, 0);   // owns x in [128,256)
    private static final RegionId C = new RegionId(DIM, 2, 0);   // owns x in [256,384)
    private static final NodeId P1 = new NodeId(new UUID(0, 1));
    private static final NodeId P2 = new NodeId(new UUID(0, 2));

    /** A signal from the east edge of {@code sourceRegionX} into the next region east. */
    private static BorderSignal eastward(int sourceRegionX) {
        int lastOwnedX = sourceRegionX * 128 + 127;
        return new BorderSignal(BorderSignal.Kind.WIRE,
                new NBlockPos(lastOwnedX, 64, 0), new NBlockPos(lastOwnedX + 1, 64, 0), 7L);
    }

    @Test
    void targetRegionResolvesTheNeighborAcrossTheBorder() {
        assertThat(ContraptionMigrator.targetRegion(A, eastward(0))).isEqualTo(B);
    }

    @Test
    void touchingAVanillaRegionDemotesTheWholeGroup() {
        ContraptionMigrator.Decision decision = ContraptionMigrator.decide(
                A, Map.of(A, List.of(eastward(0))),
                region -> region.equals(A) ? Optional.of(P1) : Optional.empty());
        assertThat(decision).isInstanceOf(ContraptionMigrator.Decision.Demote.class);
        assertThat(decision.group()).containsExactly(A, B);
        assertThat(((ContraptionMigrator.Decision.Demote) decision).vanillaRegions())
                .containsExactly(B);
    }

    @Test
    void differingPrimariesMigrateTheGroupUnderTheSourcePrimary() {
        ContraptionMigrator.Decision decision = ContraptionMigrator.decide(
                A, Map.of(A, List.of(eastward(0))),
                region -> Optional.of(region.equals(A) ? P1 : P2));
        assertThat(decision).isInstanceOf(ContraptionMigrator.Decision.Migrate.class);
        assertThat(((ContraptionMigrator.Decision.Migrate) decision).newPrimary()).isEqualTo(P1);
        assertThat(decision.group()).containsExactly(A, B);
    }

    @Test
    void sharedPrimaryIsInternal() {
        ContraptionMigrator.Decision decision = ContraptionMigrator.decide(
                A, Map.of(A, List.of(eastward(0))), region -> Optional.of(P1));
        assertThat(decision).isInstanceOf(ContraptionMigrator.Decision.Internal.class);
    }

    @Test
    void groupIsTheFloodFillClosureNotJustDirectNeighbors() {
        // A signals B, B signals C: one contraption spanning three regions even though A
        // never signals C directly.
        ContraptionMigrator.Decision decision = ContraptionMigrator.decide(
                A, Map.of(A, List.of(eastward(0)), B, List.of(eastward(1))),
                region -> Optional.of(region.equals(C) ? P2 : P1));
        assertThat(decision.group()).containsExactly(A, B, C);
        assertThat(decision).isInstanceOf(ContraptionMigrator.Decision.Migrate.class);
    }

    @Test
    void idleGroupsDecayAfterTheWindowAndActiveOnesSurvive() {
        ContraptionMigrator.Groups groups = new ContraptionMigrator.Groups(1200);
        Set<RegionId> group = Set.of(A, B);
        groups.touch(group, 100L);
        assertThat(groups.isActive(group, 1299L)).isTrue();
        assertThat(groups.isActive(group, 1300L)).isFalse();

        groups.touch(Set.of(B, C), 1250L);
        Set<Set<RegionId>> expired = groups.expire(1300L);
        assertThat(expired).containsExactly(Set.of(A, B));
        assertThat(groups.activeCount()).isEqualTo(1);
        assertThat(groups.isActive(Set.of(B, C), 1300L)).isTrue();
    }
}
