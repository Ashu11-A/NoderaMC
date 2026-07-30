package dev.nodera.endpoint.lane;

import dev.nodera.endpoint.lane.ObserverOwnership;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observer's ownership index (L-80). Small on purpose: the interesting property is that a node
 * owning nothing still knows where every captured action has to go.
 */
class ObserverOwnershipTest {

    private static final DimensionKey OW = DimensionKey.of("minecraft", "overworld");

    private static RegionId region(int x, int z) {
        return new RegionId(OW, x, z);
    }

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }

    @AfterEach
    void tearDown() {
        ObserverOwnership.clear();
    }

    @Test
    @DisplayName("the plan answers who owns a region this node does not hold")
    void publishedPlanIsQueryable() {
        Map<RegionId, NodeId> plan = new LinkedHashMap<>();
        plan.put(region(0, 0), node(1));
        plan.put(region(1, 0), node(2));

        ObserverOwnership.publish(plan);

        assertThat(ObserverOwnership.primaryOf(region(0, 0))).isEqualTo(node(1));
        assertThat(ObserverOwnership.primaryOf(region(1, 0))).isEqualTo(node(2));
        assertThat(ObserverOwnership.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a region outside the plan has no owner — and no guess is offered")
    void anUnplannedRegionAnswersNull() {
        ObserverOwnership.publish(Map.of(region(0, 0), node(1)));

        assertThat(ObserverOwnership.primaryOf(region(9, 9))).isNull();
        assertThat(ObserverOwnership.primaryOf(null)).isNull();
    }

    @Test
    @DisplayName("publishing replaces the plan rather than merging into it")
    void republishingReplaces() {
        ObserverOwnership.publish(Map.of(region(0, 0), node(1)));
        ObserverOwnership.publish(Map.of(region(5, 5), node(2)));

        // A player walking away takes their regions out of the plan. A merge would leave the
        // observer forwarding to a node that no longer owns anything there — every action lost,
        // and nothing in any log to say why.
        assertThat(ObserverOwnership.primaryOf(region(0, 0))).isNull();
        assertThat(ObserverOwnership.primaryOf(region(5, 5))).isEqualTo(node(2));
        assertThat(ObserverOwnership.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("null entries never enter the index")
    void malformedPlanEntriesAreDropped() {
        Map<RegionId, NodeId> plan = new LinkedHashMap<>();
        plan.put(region(0, 0), null);
        plan.put(null, node(3));
        plan.put(region(2, 2), node(4));

        ObserverOwnership.publish(plan);

        assertThat(ObserverOwnership.size()).isEqualTo(1);
        assertThat(ObserverOwnership.primaryOf(region(2, 2))).isEqualTo(node(4));
    }

    @Test
    @DisplayName("the plan dies with the session that computed it")
    void clearEmptiesTheIndex() {
        ObserverOwnership.publish(Map.of(region(0, 0), node(1)));
        ObserverOwnership.clear();

        assertThat(ObserverOwnership.size()).isZero();
        assertThat(ObserverOwnership.primaryOf(region(0, 0))).isNull();
        ObserverOwnership.publish(null);
        assertThat(ObserverOwnership.size()).isZero();
    }
}
