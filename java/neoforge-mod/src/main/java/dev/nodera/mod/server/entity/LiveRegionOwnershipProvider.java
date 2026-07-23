package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.source.RegionOwnershipProvider;
import dev.nodera.peer.validation.WorkerValidationService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live {@link RegionOwnershipProvider} (the regions half of the L-31 exit): while a validation
 * lane is active — the server-side entity-lane session <i>or</i> the joiner's client lane — the
 * HUD's region panel and {@code /nodera regions} show this node's real ownership straight from the
 * lane's leases: {@code OWNED} for regions this node primaries, {@code VALIDATING} where it votes,
 * {@code REPLICA} otherwise, with per-region epoch + lease expiry. No active lane renders the
 * empty placeholder, exactly as before.
 *
 * <p>Thread-context: {@link #regions()} on the diagnostics sample thread; activation on the lane
 * bootstrap/client threads. All state is one {@link AtomicReference}.
 */
public final class LiveRegionOwnershipProvider implements RegionOwnershipProvider {

    /** The active lane: its validation service and the node whose ownership it represents. */
    public record ActiveLane(WorkerValidationService service, NodeId self) {
    }

    private static final AtomicReference<ActiveLane> ACTIVE = new AtomicReference<>();
    private static final LiveRegionOwnershipProvider INSTANCE = new LiveRegionOwnershipProvider();

    private LiveRegionOwnershipProvider() {
    }

    /** The singleton registered into the diagnostics collectors (replaces the L-31 stub). */
    public static LiveRegionOwnershipProvider get() {
        return INSTANCE;
    }

    /** Called when a validation lane goes live (server session or client lane). */
    public static void activate(WorkerValidationService service, NodeId self) {
        ACTIVE.set(new ActiveLane(service, self));
    }

    /** Called on lane teardown; only clears if {@code service} is still the active one. */
    public static void deactivate(WorkerValidationService service) {
        ActiveLane lane = ACTIVE.get();
        if (lane != null && lane.service() == service) {
            ACTIVE.compareAndSet(lane, null);
        }
    }

    @Override
    public RegionOwnership regions() {
        ActiveLane lane = ACTIVE.get();
        if (lane == null) {
            return RegionOwnership.empty();
        }
        List<RegionId> primary = new ArrayList<>();
        List<RegionId> validator = new ArrayList<>();
        List<RegionId> replica = new ArrayList<>();
        Map<RegionId, RegionOwnership.LeaseInfo> leases = new LinkedHashMap<>();
        for (RegionId region : lane.service().activeRegionIds()) {
            Optional<RegionLease> lease = lane.service().lease(region);
            if (lease.isEmpty()) {
                replica.add(region);
                continue;
            }
            RegionLease l = lease.get();
            if (lane.self().equals(l.primary())) {
                primary.add(region);
            } else if (l.validators().contains(lane.self())) {
                validator.add(region);
            } else {
                replica.add(region);
            }
            leases.put(region, new RegionOwnership.LeaseInfo(
                    l.epoch().value(), l.expiresAtTick()));
        }
        // 8×8 chunks per region (NoderaConstants.REGION_SIZE_CHUNKS²).
        RegionOwnership ownership =
                new RegionOwnership(primary, validator, replica, primary.size() * 64, leases);
        if (!ownership.isEmpty() && LOGGED_LIVE.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger("NoderaRegions").info(
                    "region ownership live: {} owned / {} validating / {} replica "
                            + "({} owned chunks) — UNASSIGNED placeholder retired",
                    primary.size(), validator.size(), replica.size(), primary.size() * 64);
        }
        return ownership;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED_LIVE =
            new java.util.concurrent.atomic.AtomicBoolean();
}
