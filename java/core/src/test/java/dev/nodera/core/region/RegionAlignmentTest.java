package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ALIGN-1, the invariant the whole server category rests on: one Nodera region, one Folia region
 * thread. The exhaustive test is the point — this is arithmetic that decides whether two threads
 * can end up writing one authority unit, and "it looked right" is not a standard for that.
 */
class RegionAlignmentTest {

    @Test
    @DisplayName("a region nests cleanly from grid exponent 3 upward, and never below")
    void theThresholdIsThree() {
        assertThat(RegionAlignment.nestsCleanly(0)).isFalse();
        assertThat(RegionAlignment.nestsCleanly(1)).isFalse();
        assertThat(RegionAlignment.nestsCleanly(2)).isFalse();
        assertThat(RegionAlignment.nestsCleanly(3)).isTrue();
        assertThat(RegionAlignment.nestsCleanly(RegionAlignment.DEFAULT_GRID_EXPONENT)).isTrue();
        assertThat(RegionAlignment.nestsCleanly(10)).isTrue();
    }

    @Test
    @DisplayName("at Folia's default a section holds four whole regions")
    void theDefaultExponentGivesFourRegionsPerSection() {
        int perAxis = RegionAlignment.regionsPerSectionAxis(RegionAlignment.DEFAULT_GRID_EXPONENT);

        assertThat(perAxis).isEqualTo(2);
        assertThat(perAxis * perAxis).as("four regions per section, never a partial one").isEqualTo(4);
        assertThat(RegionAlignment.sectionChunks(RegionAlignment.DEFAULT_GRID_EXPONENT))
                .isEqualTo(16);
        assertThat(RegionAlignment.regionsPerSectionAxis(3)).isEqualTo(1);
    }

    @Test
    @DisplayName("every region in a wide grid nests in exactly one section, on both signs")
    void nestingHoldsExhaustivelyIncludingNegativeCoordinates() {
        for (int exponent = 3; exponent <= 6; exponent++) {
            for (int regionX = -64; regionX <= 64; regionX++) {
                for (int regionZ = -64; regionZ <= 64; regionZ++) {
                    assertThat(RegionAlignment.regionNestsInOneSection(regionX, regionZ, exponent))
                            .as("region (%d,%d) at exponent %d", regionX, regionZ, exponent)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("below the threshold a region genuinely straddles two sections")
    void theRefusalIsNotSuperstition() {
        // grid exponent 2 → 4-chunk sections, and an 8-chunk region covers two of them. This is
        // the concrete fact the refusal exists for, asserted rather than asserted-about.
        int exponent = 2;
        assertThat(RegionAlignment.sectionChunks(exponent))
                .isLessThan(NoderaConstants.REGION_SIZE_CHUNKS);
        assertThat(RegionAlignment.sectionOf(0, exponent))
                .isNotEqualTo(RegionAlignment.sectionOf(NoderaConstants.REGION_SIZE_CHUNKS - 1,
                        exponent));
        assertThat(RegionAlignment.regionNestsInOneSection(0, 0, exponent)).isFalse();
        assertThatThrownBy(() -> RegionAlignment.regionsPerSectionAxis(exponent))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the preflight tells the operator what to change, not just that it refused")
    void theRefusalMessageNamesTheSettingAndTheFix() {
        assertThat(RegionAlignment.preflight(RegionAlignment.DEFAULT_GRID_EXPONENT)).isEmpty();
        assertThat(RegionAlignment.preflight(3)).isEmpty();

        String refusal = RegionAlignment.preflight(2);
        assertThat(refusal)
                .contains("threaded-regions.grid-exponent")
                .contains("is 2")
                .contains("at least 3")
                .contains("config/paper-global.yml");
    }

    @Test
    @DisplayName("an absurd exponent is rejected rather than shifted into nonsense")
    void outOfRangeExponentsAreRefused() {
        assertThatThrownBy(() -> RegionAlignment.sectionChunks(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegionAlignment.sectionChunks(31))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
