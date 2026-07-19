package dev.nodera.coordinator.interference;

/**
 * The vanilla phase a foreign write was observed in (Task 11). Pushed as a context marker by the
 * mod around the entity tick loop, scheduled-tick run, and neighbor-update cascade; anything not
 * inside a pushed marker classifies as {@link #UNKNOWN}. Feeds the per-source interference rates
 * that drive {@code INTERFERENCE_RATE_HIGH} demotion.
 */
public enum MutationSource {
    /** The entity tick loop (mobs, items, fake players acting as entities). */
    ENTITY,
    /** A scheduled block/fluid tick run ({@code LevelTicks}). */
    SCHEDULED,
    /** A neighbor-update cascade crossing into the region (boundary bleed). */
    NEIGHBOR,
    /** No source marker was active — another mod, a command, or an unclassified vanilla path. */
    UNKNOWN
}
