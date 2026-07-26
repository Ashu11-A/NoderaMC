package dev.nodera.telemetry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tenant-privacy boundary for an endpoint's aggregate telemetry (server L-79,
 * {@code docs/plans/Plan.6.md} §4).
 *
 * <p>A Paper or Folia endpoint's operator can consent for the endpoint. The people playing on it
 * cannot: they were never asked, and on a small server the "aggregate" is thin cover — two tenants'
 * combined traffic is one person's session with a rounding error. Two mechanisms, both here so the
 * rule is one thing rather than a habit spread across call sites:
 *
 * <ul>
 *   <li><b>Nothing varies with <i>which</i> tenants are connected.</b> The inputs are a count and
 *       totals; no identity, name, address or per-tenant value can reach an attribute because none
 *       is accepted. Two endpoints with the same number of different people emit identical
 *       attributes — which is what makes the word "aggregate" true rather than aspirational.</li>
 *   <li><b>A reporting floor.</b> Below {@link #DEFAULT_FLOOR} tenants, tenant-derived attributes
 *       are <b>omitted entirely</b> rather than bucketed to zero. An omitted attribute says "not
 *       reported"; a zero says "measured, and it was nothing", and on a two-player server the
 *       second sentence is a statement about two identifiable people.</li>
 * </ul>
 *
 * <p>Attributes that do not derive from tenants — server tick health, which describes the machine —
 * are unaffected by the floor. Suppressing those would cost the project the one reading a small
 * server is uniquely able to give without telling anyone anything about its players.
 *
 * @Thread-context stateless static helpers; safe from any thread.
 */
public final class TenantBoundary {

    /**
     * The default reporting floor. Five is chosen so a report is never a description of a household:
     * below it, a count and a traffic total are close enough to individual behaviour that the
     * project would rather have no number at all.
     */
    public static final int DEFAULT_FLOOR = 5;

    /** Attribute key: bucketed tenant count, present only at or above the floor. */
    public static final String TENANTS = "tenants";

    /** Attribute key: bucketed traffic in megabytes, present only at or above the floor. */
    public static final String TRAFFIC_MB = "traffic_mb";

    /** Attribute key: server tick health. Not tenant-derived, so never suppressed. */
    public static final String TPS = "tps";

    private TenantBoundary() {
    }

    /**
     * Build the attributes an endpoint may emit for one reporting window.
     *
     * @param tenantCount   how many tenants were connected. Only the count is accepted — there is
     *                      deliberately no parameter that could carry who they were.
     * @param trafficBytes  total traffic across the window.
     * @param ticksPerSecond the endpoint's tick health.
     * @param floor         the reporting floor; values below 1 are treated as 1, because a floor of
     *                      zero would silently disable the boundary.
     * @return the emittable attributes, in a stable order. Tenant-derived keys are absent below the
     *         floor rather than present-and-zero.
     */
    public static Map<String, Long> endpointAttributes(
            int tenantCount, long trafficBytes, double ticksPerSecond, int floor) {
        Map<String, Long> attributes = new LinkedHashMap<>();
        // Tick health describes the machine, not the people on it.
        attributes.put(TPS, Buckets.tps(ticksPerSecond));
        if (reports(tenantCount, floor)) {
            attributes.put(TENANTS, Buckets.magnitude(tenantCount));
            attributes.put(TRAFFIC_MB, Buckets.megabytes(trafficBytes));
        }
        return attributes;
    }

    /** Convenience overload using {@link #DEFAULT_FLOOR}. */
    public static Map<String, Long> endpointAttributes(
            int tenantCount, long trafficBytes, double ticksPerSecond) {
        return endpointAttributes(tenantCount, trafficBytes, ticksPerSecond, DEFAULT_FLOOR);
    }

    /**
     * @return whether tenant-derived values may be reported at this population. Exposed so a caller
     *         can skip the work of measuring something it would not be allowed to send.
     */
    public static boolean reports(int tenantCount, int floor) {
        return tenantCount >= Math.max(1, floor);
    }

    /**
     * The count as the boundary allows it to be seen, for a caller that only needs the population.
     *
     * @return the bucketed count, or empty below the floor.
     */
    public static java.util.OptionalLong tenantCount(int tenantCount, int floor) {
        return reports(tenantCount, floor)
                ? java.util.OptionalLong.of(Buckets.magnitude(tenantCount))
                : java.util.OptionalLong.empty();
    }

    /**
     * A guard for callers holding a tenant collection: it takes the size and <b>nothing else</b>.
     * Present so a reporter never has to write the line that reaches into the collection itself.
     */
    public static int populationOf(Collection<?> tenants) {
        return tenants == null ? 0 : tenants.size();
    }
}
