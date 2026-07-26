package dev.nodera.telemetry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-79's exit test. The row's claim is not "we do not send names" — nobody was going to — it is the
 * stronger one: an endpoint's aggregate must not vary with <i>which</i> tenants are connected, and
 * below a floor it must not be sent at all.
 */
class TenantBoundaryTest {

    @Test
    @DisplayName("two endpoints with the same number of different people emit identical attributes")
    void noAttributeVariesWithWhichTenantsAreConnected() {
        List<UUID> mondayCrowd = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<String> tuesdayCrowd = List.of("alice", "bob", "carol", "dave", "erin", "frank");

        Map<String, Long> monday = TenantBoundary.endpointAttributes(
                TenantBoundary.populationOf(mondayCrowd), 42_000_000L, 19.4);
        Map<String, Long> tuesday = TenantBoundary.endpointAttributes(
                TenantBoundary.populationOf(tuesdayCrowd), 42_000_000L, 19.4);

        assertThat(tuesday).isEqualTo(monday);
        // And structurally: the only way to influence the output is the population size. There is
        // no parameter that could carry an identity, which is why this property is not a promise
        // that has to be re-checked at every call site.
        assertThat(monday).containsOnlyKeys(
                TenantBoundary.TPS, TenantBoundary.TENANTS, TenantBoundary.TRAFFIC_MB);
    }

    @Test
    @DisplayName("below the floor, tenant-derived attributes are absent — not zero")
    void theFloorOmitsRatherThanZeroes() {
        Map<String, Long> tiny = TenantBoundary.endpointAttributes(2, 900_000_000L, 20.0);

        assertThat(tiny).doesNotContainKeys(TenantBoundary.TENANTS, TenantBoundary.TRAFFIC_MB);
        // A zero would say "measured, and it was nothing", which on a two-player server is a
        // statement about two identifiable people. An absent key says "not reported".
        assertThat(tiny).containsOnlyKeys(TenantBoundary.TPS);
        assertThat(TenantBoundary.tenantCount(2, TenantBoundary.DEFAULT_FLOOR)).isEmpty();
    }

    @Test
    @DisplayName("tick health is not tenant-derived and survives the floor")
    void theMachinesOwnReadingIsStillReported() {
        Map<String, Long> tiny = TenantBoundary.endpointAttributes(1, 5_000L, 12.7);

        assertThat(tiny).containsEntry(TenantBoundary.TPS, 13L);
    }

    @Test
    @DisplayName("at the floor the numbers appear, and they are buckets")
    void atTheFloorTheReportBegins() {
        Map<String, Long> atFloor = TenantBoundary.endpointAttributes(
                TenantBoundary.DEFAULT_FLOOR, 3L * 1024 * 1024, 20.0);

        assertThat(atFloor).containsKeys(TenantBoundary.TENANTS, TenantBoundary.TRAFFIC_MB);
        assertThat(atFloor.get(TenantBoundary.TRAFFIC_MB)).isEqualTo(3L);
        // The count is a magnitude, not the exact population: 40 tenants and 41 must not be
        // distinguishable, or the number is a fingerprint of the server rather than a statistic.
        assertThat(TenantBoundary.endpointAttributes(40, 0, 20.0).get(TenantBoundary.TENANTS))
                .isEqualTo(TenantBoundary.endpointAttributes(41, 0, 20.0)
                        .get(TenantBoundary.TENANTS));
    }

    @Test
    @DisplayName("a floor of zero does not silently disable the boundary")
    void theFloorCannotBeConfiguredAway() {
        assertThat(TenantBoundary.reports(0, 0)).isFalse();
        assertThat(TenantBoundary.reports(1, 0)).isTrue();
        assertThat(TenantBoundary.reports(1, -5)).isTrue();
        assertThat(TenantBoundary.endpointAttributes(0, 1_000_000L, 20.0, 0))
                .containsOnlyKeys(TenantBoundary.TPS);
    }

    @Test
    @DisplayName("an operator may raise the floor, and raising it removes attributes")
    void aHigherFloorIsHonoured() {
        assertThat(TenantBoundary.endpointAttributes(10, 1_000_000L, 20.0, 20))
                .containsOnlyKeys(TenantBoundary.TPS);
        assertThat(TenantBoundary.endpointAttributes(10, 1_000_000L, 20.0, 5))
                .containsKeys(TenantBoundary.TENANTS, TenantBoundary.TRAFFIC_MB);
    }

    @Test
    @DisplayName("population is read as a size and nothing else")
    void populationOfTakesOnlyTheSize() {
        assertThat(TenantBoundary.populationOf(null)).isZero();
        assertThat(TenantBoundary.populationOf(List.of())).isZero();
        assertThat(TenantBoundary.populationOf(List.of("a", "b"))).isEqualTo(2);
    }
}
