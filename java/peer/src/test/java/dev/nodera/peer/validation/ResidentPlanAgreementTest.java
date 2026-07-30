package dev.nodera.peer.validation;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan is a shared computation, and a shared computation needs shared inputs (network L-30).
 *
 * <p>{@code EntityLaneBootstrap.plan} is pure, so two members derive identical leases from identical
 * inputs — that is the property the whole host-free ownership model rests on. The live defect these
 * tests pin is that the two sides were <b>not</b> given identical inputs: the host planned with
 * {@code residents.keySet()}, the joining client planned with the four-argument overload and
 * therefore with no residents at all, because the resident pool was never in the broadcast payload.
 *
 * <p>The consequence is not a crash but silence. The client primaries a region and computes a
 * committee of players only; the resident worker was seated by the host under a lease that names it
 * a validator, re-executes the batch and votes — and the vote arrives from a node the client's own
 * lease does not list, so it is dropped. Every symptom L-30 records follows from that: seats that
 * exist on one side, {@code votes_received=0} on the other, and no two comparable roots.
 *
 * <p>These are the assertions that make the divergence a test failure rather than a live mystery.
 * The fix is the {@code residents} field on {@code NoderaLanePlanPayload} plus the client passing it
 * to the five-argument overload; {@code ClientValidationLaneResidentPoolTest} covers the filter that
 * turns the broadcast pool into plan input.
 */
final class ResidentPlanAgreementTest {

    private static final int COMMITTEE = 3;
    private static final long TICK = 1_000L;

    private static NodeId node(long id) {
        return new NodeId(new UUID(0, id));
    }

    private static PlayerView view(int blockX, int blockZ) {
        return PlayerView.fromBlock(DimensionKey.overworld(), blockX, blockZ, 8);
    }

    /** One player, one always-on worker: the topology every ordinary shared world starts in. */
    private static final Map<NodeId, PlayerView> ONE_PLAYER = Map.of(node(1), view(0, 0));

    @Test
    @DisplayName("same views, same residents, two members: byte-identical leases")
    void identicalInputsAgree() {
        List<NodeId> residents = List.of(node(90), node(91));

        List<EntityLaneBootstrap.PlannedRegion> host =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, residents);
        List<EntityLaneBootstrap.PlannedRegion> joiner =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(90), TICK, COMMITTEE, residents);

        assertThat(host).hasSameSizeAs(joiner);
        for (int i = 0; i < host.size(); i++) {
            assertThat(host.get(i).lease())
                    .as("the lease is the agreement; only locallyPrimary may differ by perspective")
                    .isEqualTo(joiner.get(i).lease());
        }
    }

    @Test
    @DisplayName("dropping the resident pool changes the committees — the L-30 divergence")
    void aMemberPlanningWithoutResidentsComputesDifferentLeases() {
        List<NodeId> residents = List.of(node(90), node(91));

        List<EntityLaneBootstrap.PlannedRegion> withPool =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, residents);
        // Exactly what the client used to do: the four-argument overload, which passes List.of().
        List<EntityLaneBootstrap.PlannedRegion> withoutPool =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

        assertThat(withPool).hasSameSizeAs(withoutPool);
        assertThat(withPool)
                .as("if these agreed there would have been no bug to fix")
                .isNotEqualTo(withoutPool);
        for (int i = 0; i < withPool.size(); i++) {
            assertThat(withoutPool.get(i).lease().validators())
                    .as("the residents the host seated are absent from the lease the client held")
                    .doesNotContain(node(90), node(91));
            assertThat(withPool.get(i).lease().validators())
                    .as("and present in the one the host actually planned")
                    .contains(node(90), node(91));
        }
    }

    @Test
    @DisplayName("the dropped vote: a seated resident is not a validator in the poolless lease")
    void theResidentsVoteWouldBeRejected() {
        NodeId resident = node(90);
        List<EntityLaneBootstrap.PlannedRegion> hostPlan =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(resident));
        List<EntityLaneBootstrap.PlannedRegion> clientPlan =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

        // The host seats the resident on a region…
        EntityLaneBootstrap.PlannedRegion seated = hostPlan.stream()
                .filter(p -> p.lease().validators().contains(resident))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the host seated no resident at all"));

        // …and the lease the client holds for that same region does not know it.
        EntityLaneBootstrap.PlannedRegion sameRegion = clientPlan.stream()
                .filter(p -> p.region().equals(seated.region()))
                .findFirst()
                .orElseThrow();
        assertThat(sameRegion.lease().validators())
                .as("this is why the worker's vote was dropped as coming from outside the committee")
                .doesNotContain(resident);
    }

    @Test
    @DisplayName("an empty pool is still agreement — a world with no worker is not broken")
    void noResidentsIsNotADivergence() {
        List<EntityLaneBootstrap.PlannedRegion> host =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE, List.of());
        List<EntityLaneBootstrap.PlannedRegion> joiner =
                EntityLaneBootstrap.plan(ONE_PLAYER, node(1), TICK, COMMITTEE);

        assertThat(host)
                .as("the five-argument overload with an empty pool IS the four-argument one")
                .isEqualTo(joiner);
    }

    @Test
    @DisplayName("pool order is part of the plan: two members must not sort it differently")
    void poolOrderIsAnInput() {
        List<EntityLaneBootstrap.PlannedRegion> forward = EntityLaneBootstrap.plan(
                ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(node(90), node(91)));
        List<EntityLaneBootstrap.PlannedRegion> reversed = EntityLaneBootstrap.plan(
                ONE_PLAYER, node(1), TICK, COMMITTEE, List.of(node(91), node(90)));

        // Documents which way it is, so the client-side filter can be held to it. RegionLease sorts
        // its validators canonically, so a two-resident pool that fills two seats lands identically
        // whichever order it arrived in; the property that matters is that BOTH members answer the
        // same, and this test is what would fail if the planner ever became order-sensitive without
        // the broadcast order being made authoritative.
        assertThat(forward).isEqualTo(reversed);
    }
}
