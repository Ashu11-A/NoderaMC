package dev.nodera.mod.client.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.mod.common.NoderaLanePlanPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The joiner's half of the resident plan input (network L-30).
 *
 * <p>The host builds its resident pool from its session view under four rules — not self, not a node
 * that already carries a player view, routable, and key-bearing — and plans with it. The client has
 * to arrive at the same pool from the broadcast, because the pool is a plan input and a pool that
 * disagrees produces leases that disagree; {@code ResidentPlanAgreementTest} pins what that costs.
 *
 * <p>So these tests are about a filter agreeing with a filter in another class, which is the kind of
 * coupling worth a test rather than a comment.
 */
final class ClientValidationLaneResidentPoolTest {

    private static final String KEY = "AAAA";

    private static NodeId node(long id) {
        return new NodeId(new UUID(0, id));
    }

    private static NoderaLanePlanPayload.Resident resident(long id, String route) {
        return new NoderaLanePlanPayload.Resident(new UUID(0, id).toString(), KEY, route);
    }

    @Test
    @DisplayName("an ordinary broadcast pool becomes plan input in broadcast order")
    void theOrdinaryCaseIsPassedThrough() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(resident(90, "10.0.0.5:25610"), resident(91, "10.0.0.6:25610")),
                node(1), Set.of(node(1), node(2)));

        assertThat(pool).containsExactly(node(90), node(91));
    }

    @Test
    @DisplayName("this node is never seated as a resident — it is ranked by its own view")
    void selfIsDropped() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(resident(1, "10.0.0.5:25610"), resident(90, "10.0.0.6:25610")),
                node(1), Set.of(node(1)));

        assertThat(pool).containsExactly(node(90));
    }

    @Test
    @DisplayName("a node that also holds a field of view is a player, not a resident")
    void aPlayerNodeIsDropped() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(resident(2, "10.0.0.5:25610"), resident(90, "10.0.0.6:25610")),
                node(1), Set.of(node(1), node(2)));

        assertThat(pool)
                .as("seating it twice would give one node two ranks in the same committee")
                .containsExactly(node(90));
    }

    @Test
    @DisplayName("a routeless resident is not seated: a proposal that cannot arrive is not a seat")
    void anUnroutableResidentIsDropped() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(resident(90, ""), resident(91, "  "), resident(92, "10.0.0.6:25610")),
                node(1), Set.of(node(1)));

        assertThat(pool).containsExactly(node(92));
    }

    @Test
    @DisplayName("a keyless resident is not seated: a vote that cannot be verified cannot count")
    void anUnverifiableResidentIsDropped() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(new NoderaLanePlanPayload.Resident(
                                new UUID(0, 90).toString(), "", "10.0.0.5:25610"),
                        resident(91, "10.0.0.6:25610")),
                node(1), Set.of(node(1)));

        assertThat(pool).containsExactly(node(91));
    }

    @Test
    @DisplayName("a repeated resident is seated once")
    void duplicatesCollapse() {
        Set<NodeId> pool = ClientValidationLane.residentSeatPool(
                List.of(resident(90, "10.0.0.5:25610"), resident(90, "10.0.0.9:25610")),
                node(1), Set.of(node(1)));

        assertThat(pool)
                .as("the host's pool is a map keyed by node id, so the client's must dedupe too")
                .containsExactly(node(90));
    }

    @Test
    @DisplayName("no residents broadcast means no residents planned — an empty pool is valid")
    void anEmptyPoolIsValid() {
        assertThat(ClientValidationLane.residentSeatPool(List.of(), node(1), Set.of(node(1))))
                .isEmpty();
    }
}
