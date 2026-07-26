package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    // ---- resident validators: the always-on peers that staff committees ----------------------

    @Test
    void residentValidatorsStaffASoloPlayersCommitteeWithoutEverBecomingPrimary() {
        NodeId alice = node(1);
        NodeId workerA = node(50);
        NodeId workerB = node(51);
        PlayerView view = viewAtChunk(0, 0, 6);
        Map<NodeId, PlayerView> views = Map.of(alice, view);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of(workerA, workerB));

        assertThat(plan).isNotEmpty();
        assertThat(plan.values()).allSatisfy(claim -> {
            // Primacy stays geometric: the only player present owns everything it sees.
            assertThat(claim.primary()).isEqualTo(alice);
            // ...but the seats player geometry left empty are now staffed.
            assertThat(claim.validators()).containsExactly(workerA, workerB);
            assertThat(claim.committee()).hasSize(NoderaConstants.QUORUM_MVP_SIZE);
            // A resident witnesses the region; it does not SEE it.
            assertThat(claim.coverCount()).isEqualTo(1);
            assertThat(claim.isSoloOwned()).isTrue();
        });
    }

    @Test
    void playersAlwaysOutrankResidentsForTheRemainingSeats() {
        NodeId alice = node(1);
        NodeId bob = node(2);
        NodeId worker = node(50);
        PlayerView aliceView = viewAtChunk(3, 3, 6);
        PlayerView bobView = viewAtChunk(20, 3, 16);
        Map<NodeId, PlayerView> views = new LinkedHashMap<>();
        views.put(alice, aliceView);
        views.put(bob, bobView);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                views, 2, List.of(worker));

        // Committee cap of 2 = primary + ONE validator, and the covering player takes it.
        RegionClaim shared = plan.get(new RegionId(OW, 0, 0));
        assertThat(shared).isNotNull();
        assertThat(shared.primary()).isEqualTo(alice);
        assertThat(shared.validators()).containsExactly(bob);
        assertThat(shared.validators()).doesNotContain(worker);
    }

    @Test
    void aResidentThatIsAlsoAPlayerIsNotSeatedTwice() {
        NodeId alice = node(1);
        PlayerView view = viewAtChunk(0, 0, 6);
        Map<NodeId, PlayerView> views = Map.of(alice, view);

        // alice is passed as a resident too — she must not appear as her own validator.
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of(alice, node(50)));

        assertThat(plan.values()).allSatisfy(claim -> {
            assertThat(claim.primary()).isEqualTo(alice);
            assertThat(claim.validators()).containsExactly(node(50));
        });
    }

    @Test
    void residentStaffingIsDeterministicRegardlessOfInputOrder() {
        NodeId alice = node(1);
        Map<NodeId, PlayerView> views = Map.of(alice, viewAtChunk(0, 0, 6));

        Map<RegionId, RegionClaim> first = ViewOwnershipPlanner.plan(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of(node(51), node(50)));
        Map<RegionId, RegionClaim> second = ViewOwnershipPlanner.plan(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of(node(50), node(51)));

        // Same views + same resident SET → byte-identical plan, which is what lets every peer
        // derive the committee independently instead of being told.
        assertThat(first).isEqualTo(second);
        assertThat(first.values()).allSatisfy(c ->
                assertThat(c.validators()).containsExactly(node(50), node(51)));
    }

    @Test
    void noResidentsLeavesThePureGeometryPlanUntouched() {
        NodeId alice = node(1);
        Map<NodeId, PlayerView> views = Map.of(alice, viewAtChunk(0, 0, 6));

        assertThat(ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE, List.of()))
                .isEqualTo(ViewOwnershipPlanner.plan(views, NoderaConstants.QUORUM_MVP_SIZE));
    }
    // --- L-63: a node may hold many views (an endpoint with tenants) --------------------------

    @Test
    void anEndpointsSecondTenantIsVisibleToThePlan() {
        // The bug L-63 names: one view per node meant an endpoint with forty players contributed
        // one disc, and the regions its other thirty-nine stood in went unowned.
        NodeId endpoint = node(10);
        PlayerView near = viewAtChunk(0, 0, 4);
        PlayerView far = viewAtChunk(100, 100, 4);

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.planMultiView(
                Map.of(endpoint, List.of(near, far)), NoderaConstants.QUORUM_MVP_SIZE, List.of());

        assertThat(plan).containsKeys(near.centerRegion(), far.centerRegion());
        assertThat(plan.get(far.centerRegion()).primary())
                .as("the second tenant's region belongs to the node that can see it")
                .isEqualTo(endpoint);
    }

    @Test
    void aNodeRanksByItsNearestView() {
        // The min-distance rule: a crowd elsewhere on the same endpoint must not outrank a player
        // standing on the block, and one tenant standing on the block must win for its whole node.
        NodeId endpoint = node(11);
        NodeId lonePlayer = node(12);
        // A region is 8×8 chunks, so its centre is around chunk (3.5, 3.5): the endpoint's near
        // tenant stands on the centre, the lone player only clips the corner from outside.
        PlayerView onTheRegion = viewAtChunk(4, 4, 4);
        PlayerView elsewhere = viewAtChunk(60, 60, 4);
        PlayerView twoRegionsOver = viewAtChunk(-1, -1, 8);

        Map<NodeId, List<PlayerView>> views = new LinkedHashMap<>();
        views.put(endpoint, List.of(elsewhere, onTheRegion));
        views.put(lonePlayer, List.of(twoRegionsOver));

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.planMultiView(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of());

        RegionClaim claim = plan.get(onTheRegion.centerRegion());
        assertThat(claim).isNotNull();
        assertThat(claim.primary())
                .as("the endpoint's nearest view decides its rank, not its farthest")
                .isEqualTo(endpoint);
    }

    @Test
    void aNodeTakesOneSeatHoweverManyOfItsTenantsAreLooking() {
        // Twenty tenants on one endpoint are still one re-execution: a node must not fill a
        // committee with itself, or "three independent validators" would mean one process.
        NodeId endpoint = node(13);
        NodeId neighbour = node(14);
        List<PlayerView> tenants = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tenants.add(viewAtChunk(i % 3, i % 2, 6));
        }
        Map<NodeId, List<PlayerView>> views = new LinkedHashMap<>();
        views.put(endpoint, tenants);
        views.put(neighbour, List.of(viewAtChunk(1, 1, 6)));

        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.planMultiView(
                views, NoderaConstants.QUORUM_MVP_SIZE, List.of());

        for (RegionClaim claim : plan.values()) {
            assertThat(claim.validators())
                    .as("no node appears twice in a committee")
                    .doesNotHaveDuplicates()
                    .doesNotContain(claim.primary());
        }
    }

    @Test
    void coverCountStillCountsPlayersRatherThanNodes() {
        // isSoloOwned() answers "is only one player here", which is a question about people. Two
        // tenants of one endpoint standing in a region make it not solo.
        NodeId endpoint = node(15);
        PlayerView first = viewAtChunk(0, 0, 4);
        PlayerView second = viewAtChunk(0, 0, 4);

        Map<RegionId, RegionClaim> twoTenants = ViewOwnershipPlanner.planMultiView(
                Map.of(endpoint, List.of(first, second)), NoderaConstants.QUORUM_MVP_SIZE, List.of());
        Map<RegionId, RegionClaim> oneTenant = ViewOwnershipPlanner.planMultiView(
                Map.of(endpoint, List.of(first)), NoderaConstants.QUORUM_MVP_SIZE, List.of());

        assertThat(twoTenants.get(first.centerRegion()).coverCount()).isEqualTo(2);
        assertThat(oneTenant.get(first.centerRegion()).coverCount()).isEqualTo(1);
    }

    @Test
    void twoPeersDeriveTheIdenticalPlanForATwentyTenantEndpoint() {
        // The property the whole design rests on: no coordination. Two peers holding the same
        // facts in different orders must compute byte-identical plans, or they disagree about who
        // owns what and every commit after that is a fight.
        NodeId endpoint = node(20);
        NodeId playerA = node(21);
        NodeId playerB = node(22);
        List<PlayerView> tenants = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tenants.add(viewAtChunk((i * 7) % 11, (i * 5) % 9, 5));
        }

        Map<NodeId, List<PlayerView>> asSeenByOne = new LinkedHashMap<>();
        asSeenByOne.put(endpoint, tenants);
        asSeenByOne.put(playerA, List.of(viewAtChunk(2, 2, 7)));
        asSeenByOne.put(playerB, List.of(viewAtChunk(8, 4, 7)));

        // The same facts, arrived in a different order — a different gossip path, a different map.
        Map<NodeId, List<PlayerView>> asSeenByTheOther = new java.util.TreeMap<>(
                java.util.Comparator.comparing((NodeId n) -> n.value()).reversed());
        asSeenByTheOther.put(playerB, List.of(viewAtChunk(8, 4, 7)));
        asSeenByTheOther.put(playerA, List.of(viewAtChunk(2, 2, 7)));
        List<PlayerView> shuffled = new java.util.ArrayList<>(tenants);
        java.util.Collections.reverse(shuffled);
        asSeenByTheOther.put(endpoint, shuffled);

        Map<RegionId, RegionClaim> first = ViewOwnershipPlanner.planMultiView(
                asSeenByOne, NoderaConstants.QUORUM_MVP_SIZE, List.of());
        Map<RegionId, RegionClaim> second = ViewOwnershipPlanner.planMultiView(
                asSeenByTheOther, NoderaConstants.QUORUM_MVP_SIZE, List.of());

        assertThat(second).isEqualTo(first);
        assertThat(second.keySet()).containsExactlyElementsOf(first.keySet());
        assertThat(first).isNotEmpty();
    }

    @Test
    void theSingleViewApiStillMeansWhatItMeant() {
        NodeId alice = node(30);
        NodeId bob = node(31);
        Map<NodeId, PlayerView> single = new LinkedHashMap<>();
        single.put(alice, viewAtChunk(0, 0, 6));
        single.put(bob, viewAtChunk(1, 0, 6));

        Map<NodeId, List<PlayerView>> asLists = new LinkedHashMap<>();
        single.forEach((n, v) -> asLists.put(n, List.of(v)));

        assertThat(ViewOwnershipPlanner.plan(single, NoderaConstants.QUORUM_MVP_SIZE))
                .isEqualTo(ViewOwnershipPlanner.planMultiView(
                        asLists, NoderaConstants.QUORUM_MVP_SIZE, List.of()));
    }
}
