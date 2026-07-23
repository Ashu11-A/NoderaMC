package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The single evaluator for whether a region may enter the validated (delegated) lane (Task 6). The
 * {@link Reason} enum declares the <b>full</b> vocabulary now — including the Task 11 evaluators —
 * so Task 11 only adds the checks, never the enum or the re-evaluation loop. Non-delegable is the
 * default: delegation is opt-in per region and a non-delegable region keeps untouched vanilla
 * execution.
 *
 * @Thread-context confined to the coordinator thread; the evaluation is pure over its inputs.
 */
public final class DelegabilityPolicy {

    /** Why a region is not delegable. An empty set of reasons means DELEGABLE. */
    public enum Reason {
        // --- evaluated in Task 6 ---
        /** The region (or its footprint) contains a block outside the MVP palette. */
        UNSUPPORTED_PALETTE,
        /** Not all of the region's chunks are loaded. */
        CHUNKS_NOT_LOADED,
        /** Fewer eligible nodes than the committee needs. */
        NO_ELIGIBLE_NODES,
        /** A cross-region operation touching this region is still resolving. */
        CROSS_REGION_PENDING,
        /** The region lies outside generated terrain. */
        OUTSIDE_GENERATED_TERRAIN,
        /** {@code delegation.requireGuard} is set, the Task 11 guard is absent, and this is not the
         *  flat-world MVP profile — refuse rather than run the CAS-resync storm on a normal world. */
        GUARD_REQUIRED,

        // --- declared now, evaluated in Task 11 (interference guard, chunk lifecycle) ---
        /** An entity is present in the region (entity lane is Task 12). */
        ENTITY_PRESENT,
        /** A neighbour in the delegable ring carries UNSUPPORTED blocks. */
        NEIGHBOR_UNSUPPORTED,
        /** A fake player is mutating the region. */
        FAKE_PLAYER_ACTIVE,
        /** The measured foreign-mutation rate exceeds the revoke threshold. */
        INTERFERENCE_RATE_HIGH,
        /** Region is loaded only by a foreign ticket and has no player whose view owns it. */
        NO_PLAYER_PRESENT
    }

    /**
     * Per-region delegability inputs the coordinator gathers each evaluation cycle. The Task 11
     * inputs (entity presence, neighbor-ring palette, fake players, interference rate) are gathered
     * by the mod-side probes and the {@code interference} package.
     *
     * @param paletteSupported  every block in the footprint is in the MVP palette.
     * @param chunksLoaded      all of the region's chunks are loaded.
     * @param eligibleNodeCount how many eligible, connected, reliable nodes exist.
     * @param crossRegionPending a cross-region op touching the region is resolving.
     * @param terrainGenerated  the region lies inside generated terrain.
     * @param flatMvpProfile    the region matches the flat-world MVP profile (probe rate 0).
     * @param guardPresent      the Task 11 interference guard is installed.
     * @param entityPresent     an entity unsupported by {@link EntityDelegabilityRules} is inside
     *                          the region bounds (ITEM is allowed; GHOST requires mobCapture).
     * @param neighborUnsupported a region in the delegable neighbor ring fails the palette check.
     * @param fakePlayerActive  a fake player mutated the region within the cooldown window.
     * @param playerPresent     at least one player view currently owns the loaded region.
     * @param interferencePerWindow foreign writes in the last
     *                              {@code INTERFERENCE_RATE_WINDOW_TICKS} (from
     *                              {@code InterferenceStats.ratePerWindow}).
     */
    public record Inputs(
            boolean paletteSupported,
            boolean chunksLoaded,
            int eligibleNodeCount,
            boolean crossRegionPending,
            boolean terrainGenerated,
            boolean flatMvpProfile,
            boolean guardPresent,
            boolean entityPresent,
            boolean neighborUnsupported,
            boolean fakePlayerActive,
            boolean playerPresent,
            long interferencePerWindow
    ) {
        /** A region that satisfies every Task 6 gate (flat MVP, palette ok, chunks loaded, quorum). */
        public static Inputs delegableFlatMvp(int eligibleNodeCount) {
            return new Inputs(true, true, eligibleNodeCount, false, true, true, false,
                    false, false, false, true, 0);
        }
    }

    /** The verdict for one region. */
    public record Delegability(RegionId region, Set<Reason> reasons) {
        public Delegability {
            EnumSet<Reason> copy = EnumSet.noneOf(Reason.class);
            copy.addAll(reasons);
            reasons = Collections.unmodifiableSet(copy);
        }

        /** @return {@code true} if the region may be delegated (no blocking reasons). */
        public boolean isDelegable() {
            return reasons.isEmpty();
        }
    }

    private final int committeeSize;
    private final boolean requireGuard;

    /**
     * @param committeeSize the committee size a region must be able to staff.
     * @param requireGuard  the {@code delegation.requireGuard} config (default true).
     */
    public DelegabilityPolicy(int committeeSize, boolean requireGuard) {
        if (committeeSize < 1) {
            throw new IllegalArgumentException("committeeSize must be >= 1");
        }
        this.committeeSize = committeeSize;
        this.requireGuard = requireGuard;
    }

    /** Evaluate the full (Task 6 + Task 11) delegability reason set for {@code region}. */
    public Delegability evaluate(RegionId region, Inputs in) {
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        if (!in.paletteSupported()) {
            reasons.add(Reason.UNSUPPORTED_PALETTE);
        }
        if (!in.chunksLoaded()) {
            reasons.add(Reason.CHUNKS_NOT_LOADED);
        }
        if (in.eligibleNodeCount() < committeeSize) {
            reasons.add(Reason.NO_ELIGIBLE_NODES);
        }
        if (in.crossRegionPending()) {
            reasons.add(Reason.CROSS_REGION_PENDING);
        }
        if (!in.terrainGenerated()) {
            reasons.add(Reason.OUTSIDE_GENERATED_TERRAIN);
        }
        if (requireGuard && !in.guardPresent() && !in.flatMvpProfile()) {
            reasons.add(Reason.GUARD_REQUIRED);
        }
        if (NoderaConstants.ENTITY_EXCLUSION && in.entityPresent()) {
            reasons.add(Reason.ENTITY_PRESENT);
        }
        if (in.neighborUnsupported()) {
            reasons.add(Reason.NEIGHBOR_UNSUPPORTED);
        }
        if (in.fakePlayerActive()) {
            reasons.add(Reason.FAKE_PLAYER_ACTIVE);
        }
        if (!in.playerPresent()) {
            reasons.add(Reason.NO_PLAYER_PRESENT);
        }
        if (in.interferencePerWindow() > NoderaConstants.INTERFERENCE_REVOKE_RATE) {
            reasons.add(Reason.INTERFERENCE_RATE_HIGH);
        }
        return new Delegability(region, reasons);
    }
}
