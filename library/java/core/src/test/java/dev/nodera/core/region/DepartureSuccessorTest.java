package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who inherits a region when the player standing in it leaves.
 *
 * <p>The distinction these tests exist to hold is between a region somebody else can see and a
 * region nobody can. Re-planning handles the first perfectly well and cannot help with the second:
 * the plan simply stops mentioning it, the chunks stop being held, and whatever was built there
 * since the last seed is gone. A {@code null} successor is how the departure path learns it has to
 * seed the region to the network before letting go of it.
 */
final class DepartureSuccessorTest {

    private static final DimensionKey OVERWORLD = DimensionKey.of("minecraft", "overworld");

    private static PlayerView viewAt(int blockX, int blockZ) {
        return PlayerView.fromBlock(OVERWORLD, blockX, blockZ, 8);
    }

    @Test
    void aRegionAnotherPlayerAlsoSeesPassesToThatPlayer() {
        NodeId leaving = NodeId.random();
        NodeId staying = NodeId.random();
        // Standing close together, so their view discs overlap and both cover the same regions.
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                Map.of(leaving, viewAt(0, 0), staying, viewAt(16, 16)), 3);

        Map<RegionId, NodeId> successors = ViewOwnershipPlanner.successorsFor(plan, leaving);

        assertThat(successors).isNotEmpty();
        assertThat(successors.values())
                .as("every vacated region here is covered by the player who stayed")
                .containsOnly(staying);
    }

    @Test
    void aRegionNobodyElseSeesHasNoSuccessor() {
        NodeId leaving = NodeId.random();
        NodeId elsewhere = NodeId.random();
        // Far enough apart that no region is covered by both.
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                Map.of(leaving, viewAt(0, 0), elsewhere, viewAt(100_000, 100_000)), 3);

        Map<RegionId, NodeId> successors = ViewOwnershipPlanner.successorsFor(plan, leaving);

        assertThat(successors).isNotEmpty();
        assertThat(successors.values())
                .as("nobody is nearby, so these have to go to the network before the chunks drop")
                .containsOnlyNulls();
    }

    @Test
    void regionsTheDepartingPlayerDidNotOwnAreNotMentioned() {
        NodeId leaving = NodeId.random();
        NodeId owner = NodeId.random();
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                Map.of(leaving, viewAt(0, 0), owner, viewAt(100_000, 100_000)), 3);

        Map<RegionId, NodeId> successors = ViewOwnershipPlanner.successorsFor(plan, leaving);

        for (RegionId region : successors.keySet()) {
            assertThat(plan.get(region).primary())
                    .as("only the departing node's own regions are being vacated")
                    .isEqualTo(leaving);
        }
    }

    @Test
    void anAlwaysOnResidentCanInheritWhenNoPlayerCan() {
        NodeId leaving = NodeId.random();
        NodeId resident = NodeId.random();
        // A headless peer holds no view, so it can never be primary — but it can hold the region
        // rather than have it dropped, which is the difference between a world that survives its
        // last player logging off and one that does not.
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(
                Map.of(leaving, viewAt(0, 0)), 3, List.of(resident));

        Map<RegionId, NodeId> successors = ViewOwnershipPlanner.successorsFor(plan, leaving);

        assertThat(successors.values()).containsOnly(resident);
    }

    @Test
    void aNodeThatOwnsNothingVacatesNothing() {
        NodeId leaving = NodeId.random();
        NodeId owner = NodeId.random();
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(Map.of(owner, viewAt(0, 0)), 3);

        assertThat(ViewOwnershipPlanner.successorsFor(plan, leaving)).isEmpty();
    }

    @Test
    void nullArgumentsAnswerEmptyRatherThanThrowing() {
        // This runs inside a logout handler, and NeoForge's event bus rethrows: an exception here
        // takes the integrated server down with the player mid-disconnect.
        assertThat(ViewOwnershipPlanner.successorsFor(null, NodeId.random())).isEmpty();
        assertThat(ViewOwnershipPlanner.successorsFor(Map.of(), null)).isEmpty();
    }
}
