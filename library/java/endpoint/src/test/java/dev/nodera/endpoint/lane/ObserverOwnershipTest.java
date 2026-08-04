package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.state.OwnershipState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The observer's ownership index (L-80). Two properties matter.
 *
 * <p>The original one: a node owning nothing still knows where every captured action has to go.
 *
 * <p>The one this grew to carry: it answers for <b>every</b> node in the plan, not only for the one
 * asking. The index used to hold primaries alone, which is all the forwarding path needed — and the
 * boss bar, which is drawn on the host once per online player, had nothing else to consult and so
 * classified every player against the <i>host's</i> seats. A joiner standing on ground its own node
 * owned therefore read {@code FOREIGN} for as long as it stood there, and two players teleported
 * together both read {@code OWNED}: one answer printed on two screens.
 */
class ObserverOwnershipTest {

    private static final DimensionKey OW = DimensionKey.of("minecraft", "overworld");

    private static RegionId region(int x, int z) {
        return new RegionId(OW, x, z);
    }

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }

    private static ObserverOwnership.Seats seats(NodeId primary, NodeId... validators) {
        return new ObserverOwnership.Seats(primary, List.of(validators));
    }

    @AfterEach
    void tearDown() {
        ObserverOwnership.clear();
    }

    @Test
    @DisplayName("the plan answers who owns a region this node does not hold")
    void publishedPlanIsQueryable() {
        Map<RegionId, ObserverOwnership.Seats> plan = new LinkedHashMap<>();
        plan.put(region(0, 0), seats(node(1)));
        plan.put(region(1, 0), seats(node(2)));

        ObserverOwnership.publish(plan);

        assertThat(ObserverOwnership.primaryOf(region(0, 0))).isEqualTo(node(1));
        assertThat(ObserverOwnership.primaryOf(region(1, 0))).isEqualTo(node(2));
        assertThat(ObserverOwnership.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("each node gets its own answer for the same region")
    void oneRegionAnswersDifferentlyPerNode() {
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1), node(2))));

        assertThat(ObserverOwnership.stateFor(region(0, 0), node(1)))
                .isEqualTo(OwnershipState.OWNED);
        assertThat(ObserverOwnership.stateFor(region(0, 0), node(2)))
                .isEqualTo(OwnershipState.VALIDATING);
        assertThat(ObserverOwnership.stateFor(region(0, 0), node(3)))
                .isEqualTo(OwnershipState.FOREIGN);
    }

    @Test
    @DisplayName("two players on one region cannot both be told they own it")
    void onlyOneNodeCanOwnARegion() {
        // The `/tp` case: both players end up in the same region. Exactly one is its primary, and
        // the index says so per node instead of once for the machine drawing the bar.
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1), node(2))));

        assertThat(ObserverOwnership.stateFor(region(0, 0), node(1)))
                .isEqualTo(OwnershipState.OWNED);
        assertThat(ObserverOwnership.stateFor(region(0, 0), node(2)))
                .isNotEqualTo(OwnershipState.OWNED);
    }

    @Test
    @DisplayName("a region outside the plan is unassigned, not foreign")
    void anUnplannedRegionAnswersNull() {
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1))));

        assertThat(ObserverOwnership.primaryOf(region(9, 9))).isNull();
        assertThat(ObserverOwnership.primaryOf(null)).isNull();
        // "Nothing is delegated there" is a different statement from "somebody else has it", and
        // the two read differently on the bar.
        assertThat(ObserverOwnership.stateFor(region(9, 9), node(1)))
                .isEqualTo(OwnershipState.UNASSIGNED);
    }

    @Test
    @DisplayName("with no plan published the index says so rather than guessing")
    void anEmptyIndexIsDetectable() {
        assertThat(ObserverOwnership.hasPlan()).isFalse();

        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1))));

        assertThat(ObserverOwnership.hasPlan()).isTrue();
    }

    @Test
    @DisplayName("publishing replaces the plan rather than merging into it")
    void republishingReplaces() {
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1))));
        ObserverOwnership.publish(Map.of(region(5, 5), seats(node(2))));

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
        Map<RegionId, ObserverOwnership.Seats> plan = new LinkedHashMap<>();
        plan.put(region(0, 0), null);
        plan.put(region(1, 1), seats(null));
        plan.put(null, seats(node(3)));
        plan.put(region(2, 2), seats(node(4)));

        ObserverOwnership.publish(plan);

        assertThat(ObserverOwnership.size()).isEqualTo(1);
        assertThat(ObserverOwnership.primaryOf(region(2, 2))).isEqualTo(node(4));
    }

    @Test
    @DisplayName("a player with no node is never told it owns anything")
    void aNodelessPlayerIsForeign() {
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1))));

        assertThat(ObserverOwnership.stateFor(region(0, 0), null))
                .isEqualTo(OwnershipState.FOREIGN);
    }

    @Test
    @DisplayName("the plan dies with the session that computed it")
    void clearEmptiesTheIndex() {
        ObserverOwnership.publish(Map.of(region(0, 0), seats(node(1))));
        ObserverOwnership.clear();

        assertThat(ObserverOwnership.size()).isZero();
        assertThat(ObserverOwnership.primaryOf(region(0, 0))).isNull();
        ObserverOwnership.publish(null);
        assertThat(ObserverOwnership.size()).isZero();
    }
}
