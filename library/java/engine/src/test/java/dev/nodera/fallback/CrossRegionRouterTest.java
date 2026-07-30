package dev.nodera.fallback;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRegionRouterTest {

    private final CrossRegionRouter router = new CrossRegionRouter();
    private final RegionId region = FbFixtures.region(0, 0);

    private ActionEnvelope inRegion() {
        return FbFixtures.place(region, 1, 5, 70, 5, 1); // x=5,z=5 owned by region (0,0)
    }

    private ActionEnvelope crossRegion() {
        // env.region = (0,0) but the target x=200 lands in region (1,0) → cross-region.
        return FbFixtures.place(region, 2, 200, 70, 5, 1);
    }

    @Test
    void crossRegionAlwaysFallsBackEvenWhenDelegated() {
        RoutingDecision d = router.classify(crossRegion(), CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY);
        assertThat(d.reason()).isEqualTo(RoutingReason.CROSS_REGION);
        assertThat(d.isFallback()).isTrue();
    }

    @Test
    void unassignedRegionFallsBack() {
        RoutingDecision d = router.classify(inRegion(), CrossRegionRouter.RegionStatus.UNASSIGNED);
        assertThat(d.reason()).isEqualTo(RoutingReason.UNASSIGNED);
        assertThat(d.isFallback()).isTrue();
    }

    @Test
    void delegatedHealthyGoesToCommittee() {
        RoutingDecision d = router.classify(inRegion(), CrossRegionRouter.RegionStatus.DELEGATED_HEALTHY);
        assertThat(d.reason()).isEqualTo(RoutingReason.DELEGATED_COMMITTEE);
        assertThat(d.isCommittee()).isTrue();
    }

    @Test
    void disputedAndCollapsedFallBack() {
        assertThat(router.classify(inRegion(), CrossRegionRouter.RegionStatus.DELEGATED_DISPUTED).reason())
                .isEqualTo(RoutingReason.DISPUTED);
        assertThat(router.classify(inRegion(), CrossRegionRouter.RegionStatus.COMMITTEE_COLLAPSED).reason())
                .isEqualTo(RoutingReason.COMMITTEE_COLLAPSED);
    }
}
