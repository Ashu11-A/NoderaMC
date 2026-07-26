package dev.nodera.core.region;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why the live mesh has never been observed carrying validated state (network L-30).
 *
 * <p>A live seat-count diagnostic reported {@code resident peer(s) holding 0 committee seat(s)} on
 * 2026-07-26, and the first explanation offered was that the suites run too many players: residents
 * fill each committee's <b>leftover</b> validator seats, so with three players and a committee of
 * three there would be nothing left to give.
 *
 * <p><b>These tests were written to prove that and disproved it.</b> Players' view discs overlap
 * only partially, so a three-player world still leaves plenty of regions seen by a single player,
 * and the planner seats residents in all of them. The population is not the cause.
 *
 * <p>What they pin instead is the planner's actual contract, which is worth having either way — and
 * they narrow L-30 to the live path rather than the plan: to what {@code residents} contains by the
 * time {@code NoderaHost.assignResidentSeats} reads it, and whether those ids are the same ones the
 * plan put in its committees.
 */
final class ResidentSeatingTopologyTest {

    private static final int COMMITTEE = 3;

    private static NodeId node(long id) {
        return new NodeId(new UUID(0, id));
    }

    /** A player standing at the origin, seeing a small disc of regions. */
    private static PlayerView view(int blockX, int blockZ) {
        return PlayerView.fromBlock(DimensionKey.overworld(), blockX, blockZ, 8);
    }

    @Test
    @DisplayName("even three players leave leftovers, so residents ARE seated — the planner is fine")
    void evenAFullWorldSeatsResidents() {
        Map<NodeId, PlayerView> views = Map.of(
                node(1), view(0, 0), node(2), view(16, 0), node(3), view(0, 16));
        List<NodeId> residents = List.of(node(90), node(91));

        Map<RegionId, RegionClaim> plan =
                ViewOwnershipPlanner.plan(views, COMMITTEE, residents);

        assertThat(plan).isNotEmpty();
        long seated = plan.values().stream()
                .flatMap(claim -> claim.validators().stream())
                .filter(residents::contains)
                .count();

        // Written first as `isZero()` on the theory that three players fill every committee. That
        // was wrong, and this is the assertion that corrected it: players' view discs overlap only
        // partially, so plenty of regions are seen by ONE player and have room behind them. The
        // planner seats residents even in a three-player world.
        //
        // Which means a live run reporting `resident peer(s) holding 0 committee seat(s)` is NOT
        // explained by the population, and network L-30's cause lies further down the live path —
        // in what `residents` actually contains when `assignResidentSeats` reads it.
        assertThat(seated)
                .as("a partially-overlapping world always leaves seats for the always-on peers")
                .isPositive();
    }

    @Test
    @DisplayName("one player leaves leftovers, and residents take them — L-30's missing topology")
    void aQuietWorldSeatsItsResidents() {
        Map<NodeId, PlayerView> views = Map.of(node(1), view(0, 0));
        List<NodeId> residents = List.of(node(90), node(91));

        Map<RegionId, RegionClaim> plan =
                ViewOwnershipPlanner.plan(views, COMMITTEE, residents);

        assertThat(plan).isNotEmpty();
        for (RegionClaim claim : plan.values()) {
            assertThat(claim.validators())
                    .as("the sole player primaries; both residents fill the committee behind them")
                    .contains(node(90), node(91));
        }

        // The consequence L-30 is waiting for: more than one node holds a replica of the same
        // region, so two independently-computed roots exist and can be compared.
        long regionsWithTwoOrMoreHolders = plan.values().stream()
                .filter(claim -> claim.validators().size() >= 2)
                .count();
        assertThat(regionsWithTwoOrMoreHolders).isEqualTo(plan.size());
    }

    @Test
    @DisplayName("two players still leave one seat each — the mesh soak's own topology")
    void twoPlayersLeaveOneSeat() {
        Map<NodeId, PlayerView> views = Map.of(node(1), view(0, 0), node(2), view(4096, 4096));
        List<NodeId> residents = List.of(node(90));

        Map<RegionId, RegionClaim> plan =
                ViewOwnershipPlanner.plan(views, COMMITTEE, residents);

        // The players are far apart, so no region is contested and each committee has room. A
        // resident SHOULD be seated here — which makes the mesh soak's zero worker replicas a
        // question about the live path rather than about the planner.
        long seated = plan.values().stream()
                .flatMap(claim -> claim.validators().stream())
                .filter(node(90)::equals)
                .count();
        assertThat(seated).isEqualTo(plan.size());
    }

    @Test
    @DisplayName("no residents offered means no residents seated, whatever the population")
    void withoutResidentsNothingChanges() {
        Map<NodeId, PlayerView> views = Map.of(node(1), view(0, 0));
        Map<RegionId, RegionClaim> plan = ViewOwnershipPlanner.plan(views, COMMITTEE, List.of());

        assertThat(plan).isNotEmpty();
        for (RegionClaim claim : plan.values()) {
            assertThat(claim.validators()).doesNotContain(node(90), node(91));
        }
    }
}
