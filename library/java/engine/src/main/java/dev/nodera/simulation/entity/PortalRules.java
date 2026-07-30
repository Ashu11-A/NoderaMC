package dev.nodera.simulation.entity;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.List;

/**
 * The Task 16 portal lane (L-14): cross-dimension travel IS generalized region transfer. A
 * {@code RegionId} has always carried its {@link DimensionKey}, and {@code EntityTransferIntent}
 * is dimension-agnostic — so a portal is nothing more than a rule that emits a transfer intent
 * whose target region lives in the OTHER dimension. The committee/coordinator transfer pipeline
 * (prepare/accept/commit, joint certificates) applies unchanged; no new protocol.
 *
 * <p><b>MVP semantics:</b> an engine-owned entity whose block cell is a {@code NETHER_PORTAL}
 * transfers at the end of the tick to the corresponding nether region (overworld coordinates
 * scale 8:1, vanilla), or back at 1:8. GHOSTs never portal here — their movement is
 * server-authoritative until their species retires.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class PortalRules {

    /** The nether dimension key (vanilla namespace). */
    public static final DimensionKey NETHER = DimensionKey.of("minecraft", "the_nether");
    /** Vanilla overworld↔nether coordinate ratio. */
    public static final int NETHER_SCALE = 8;

    private PortalRules() {
    }

    /** The per-tick phase: any engine-owned entity standing in a portal cell transfers. */
    public static void tick(MutableRegionState state, long tick) {
        List<PersistedEntityState> travellers = new ArrayList<>();
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() == EntityKind.GHOST) {
                continue; // vanilla-authoritative mobs don't engine-portal
            }
            NBlockPos cell = new NBlockPos(
                    entity.pos().blockX(), entity.pos().blockY(), entity.pos().blockZ());
            if (state.inOwnedRegion(cell)
                    && state.getBlock(cell) == FlatWorldRules.NETHER_PORTAL) {
                travellers.add(entity);
            }
        }
        for (PersistedEntityState traveller : travellers) {
            boolean toNether = state.region().dimension().equals(DimensionKey.overworld());
            DimensionKey targetDim = toNether ? NETHER : DimensionKey.overworld();
            long divisor = toNether ? NETHER_SCALE : 1;
            long multiplier = toNether ? 1 : NETHER_SCALE;
            // Pure integer fixed-point scaling: world X/Z scale by the ratio, Y is preserved.
            FixedVec3 scaled = new FixedVec3(
                    traveller.pos().x() / divisor * multiplier,
                    traveller.pos().y(),
                    traveller.pos().z() / divisor * multiplier);
            RegionId target = RegionId.fromChunk(
                    targetDim,
                    Math.floorDiv(scaled.blockX(), 16),
                    Math.floorDiv(scaled.blockZ(), 16));
            state.transferEntity(target, traveller.withMotion(scaled, traveller.vel()));
        }
    }
}
