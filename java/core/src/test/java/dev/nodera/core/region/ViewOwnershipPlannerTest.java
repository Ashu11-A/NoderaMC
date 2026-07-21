package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decentralized ownership planning: overlapping player views → per-region primary + validator
 * committees, with no coordinator. Asserts closest-player-owns, overlap-forms-committee, the
 * committee cap, solo ownership, and determinism.
 */
final class ViewOwnershipPlannerTest {

    private static final DimensionKey OW = DimensionKey.overworld();

    private static NodeId node(long id) {
        return new NodeId(new UUID(0, id));
    }

    private static PlayerView viewAtChunk(int cx, int cz, int r) {
        return PlayerView.fromBlock(OW, cx * 16 + 8, cz * 16 + 8, r);
    }

    @Test
    void soloPlayerOwnsEveryRegionAloneWithNoValidators() {
        NodeId alice = node(1);
        PlayerView view = viewAtChunk(0, 0, 6);
        Map<NodeId, PlayerView> views = Map.of(alice, view);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE);

        assertThat(plan).isNotEmpty();
        assertThat(plan).containsKeys(view.centerRegion());
        assertThat(plan.values()).allSatisfy(claim -> {
            assertThat(claim.primary()).isEqualTo(alice);
            assertThat(claim.validators()).isEmpty();
            assertThat(claim.coverCount()).isEqualTo(1);
            assertThat(claim.isSoloOwned()).isTrue();
        });
    }

    @Test
    void overlappingViewsFormACommitteeClosestPlayerIsPrimary() {
        NodeId alice = node(1); // stands ON the shared region
        NodeId bob = node(2);   // far away but his disc still reaches the shared region
        RegionId shared = new RegionId(OW, 0, 0);

        // Alice centred in region (0,0); Bob centred east but with a disc that reaches back to (0,0).
        PlayerView aliceView = viewAtChunk(3, 3, 6);      // inside region (0,0)
        PlayerView bobView = viewAtChunk(20, 3, 16);      // region (2,0); disc reaches chunk 7 of (0,0)
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        views.put(bob, bobView);   // insertion order deliberately bob-first
        views.put(alice, aliceView);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE);

        RegionClaim claim = plan.get(shared);
        assertThat(claim).isNotNull();
        assertThat(claim.primary()).isEqualTo(alice);         // Alice is closest → primary
        assertThat(claim.validators()).containsExactly(bob);  // Bob overlaps → validator
        assertThat(claim.coverCount()).isEqualTo(2);
        assertThat(claim.committee()).containsExactly(alice, bob);
    }

    @Test
    void committeeIsCappedAtMaxSizeButCoverCountReflectsAll() {
        // Five players all standing in the same region → committee capped at 3, coverCount == 5.
        RegionId shared = new RegionId(OW, 0, 0);
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            views.put(node(i), viewAtChunk(3, 3, 4));
        }
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE);

        RegionClaim claim = plan.get(shared);
        assertThat(claim.committee()).hasSize(NoderaConstants.QUORUM_MVP_SIZE); // 3
        assertThat(claim.validators()).hasSize(NoderaConstants.QUORUM_MVP_SIZE - 1);
        assertThat(claim.coverCount()).isEqualTo(5);
    }

    @Test
    void disjointPlayersEachOwnTheirOwnRegionsAlone() {
        NodeId alice = node(1);
        NodeId bob = node(2);
        PlayerView aliceView = viewAtChunk(0, 0, 4);
        PlayerView bobView = viewAtChunk(1000, 1000, 4); // nowhere near Alice
        Map<NodeId, PlayerView> views = Map.of(alice, aliceView, bob, bobView);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE);

        assertThat(plan.get(aliceView.centerRegion()).primary()).isEqualTo(alice);
        assertThat(plan.get(aliceView.centerRegion()).validators()).isEmpty();
        assertThat(plan.get(bobView.centerRegion()).primary()).isEqualTo(bob);
        assertThat(plan.get(bobView.centerRegion()).validators()).isEmpty();
    }

    @Test
    void planIsDeterministicRegardlessOfInputOrder() {
        PlayerView a = viewAtChunk(0, 0, 8);
        PlayerView b = viewAtChunk(2, 2, 8);
        Map<NodeId, PlayerView> order1 = new LinkedHashMap<>();
        order1.put(node(1), a);
        order1.put(node(2), b);
        Map<NodeId, PlayerView> order2 = new LinkedHashMap<>();
        order2.put(node(2), b);
        order2.put(node(1), a);

        assertThat(ViewOwnershipPlanner.plan(order1, NoderaConstants.QUORUM_MVP_SIZE))
                .isEqualTo(ViewOwnershipPlanner.plan(order2, NoderaConstants.QUORUM_MVP_SIZE));
    }

    @Test
    void emptyViewsProduceEmptyPlan() {
        assertThat(ViewOwnershipPlanner.plan(Map.of(), NoderaConstants.QUORUM_MVP_SIZE)).isEmpty();
    }
}
