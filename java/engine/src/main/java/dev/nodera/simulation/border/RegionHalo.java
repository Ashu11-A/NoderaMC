package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;

/**
 * Read-only view of a region's halo ring (Task 3). The halo is the {@code HALO_CHUNKS}-chunk ring a
 * region needs to read for border mechanics (neighbour-dependent block updates, redstone) but never
 * owns.
 *
 * <p><b>MVP stub.</b> The flat-world rule set has no neighbour-dependent mechanics, so this class
 * holds nothing and returns {@link #AIR} ({@code 0}) for every read. {@link dev.nodera.simulation.MutableRegionState}
 * is self-contained for MVP reads (it returns AIR for positions outside the snapshot's covered
 * chunks). The real halo — backed by neighbour snapshot slices and consulted by redstone-aware
 * rules — arrives with Task 13; this class exists now so the engine and rule-set APIs can take a
 * typed halo reference from day one.
 *
 * @Thread-context immutable, any thread.
 */
public final class RegionHalo {

    /** Air state id (palette constant mirrored locally for the stub). */
    public static final int AIR = 0;

    private final RegionId region;

    /**
     * @param region the region whose halo this view represents.
     * @Thread-context deterministic; safe from any thread.
     */
    public RegionHalo(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        this.region = region;
    }

    /**
     * @return the region whose halo this view represents.
     * @Thread-context deterministic; safe from any thread.
     */
    public RegionId region() {
        return region;
    }

    /**
     * @return {@link #AIR} ({@code 0}) for every position in the MVP stub.
     * @Thread-context deterministic; safe from any thread.
     */
    public int getBlock(NBlockPos pos) {
        return AIR;
    }
}
