package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;

/**
 * ALIGN-1 — the invariant that every Nodera region nests inside exactly one Folia region
 * (`docs/server/REFERENCE.md` §2).
 *
 * <p>A Folia section is {@code 2^g} chunks per side, anchored at chunk 0, where {@code g} is
 * {@code threaded-regions.grid-exponent}. A Nodera region is {@value NoderaConstants#REGION_SIZE_CHUNKS}
 * chunks per side, also anchored at chunk 0. For {@code g >= 3} the region divides the section
 * exactly, so every region lies wholly inside one section and therefore inside one Folia region,
 * which owns whole sections.
 *
 * <p><b>Why this is worth a class of its own.</b> It is the reason the whole server category is
 * tractable: all Minecraft-side work for one Nodera region runs on exactly one Folia region thread,
 * so the single-writer invariant holds for free and per-region parallelism is a property of the
 * platform rather than something the plugin builds. It is arithmetic, it decides whether the plugin
 * may enable at all, and it must be testable without a server — so it lives here, in core, and the
 * plugin only reads a number out of a config file and asks.
 *
 * <p><b>Refusing beats degrading.</b> Below {@code g = 3} a Nodera region straddles two Folia
 * sections, which means two threads writing one authority unit: corruption with a delay fuse that
 * would surface as an unreproducible state-root divergence days later. The plugin refuses to enable
 * and says how to fix it.
 *
 * @Thread-context pure functions; safe from any thread.
 */
public final class RegionAlignment {

    /** The smallest grid exponent at which a Nodera region nests cleanly. */
    public static final int MINIMUM_GRID_EXPONENT = 3;

    /** Folia's own default, and what a normal operator runs. */
    public static final int DEFAULT_GRID_EXPONENT = 4;

    private RegionAlignment() {
    }

    /** @return the side length of a Folia section, in chunks, for {@code gridExponent}. */
    public static int sectionChunks(int gridExponent) {
        if (gridExponent < 0 || gridExponent > 30) {
            throw new IllegalArgumentException("grid exponent out of range: " + gridExponent);
        }
        return 1 << gridExponent;
    }

    /** @return whether every Nodera region nests inside exactly one Folia section at this exponent. */
    public static boolean nestsCleanly(int gridExponent) {
        return gridExponent >= MINIMUM_GRID_EXPONENT;
    }

    /**
     * @return how many whole Nodera regions fit along one axis of a Folia section. At the default
     *         exponent this is 2, so a section holds four regions and never a partial one.
     */
    public static int regionsPerSectionAxis(int gridExponent) {
        if (!nestsCleanly(gridExponent)) {
            throw new IllegalStateException(
                    "regions do not nest at grid exponent " + gridExponent);
        }
        return sectionChunks(gridExponent) / NoderaConstants.REGION_SIZE_CHUNKS;
    }

    /** @return the Folia section index containing {@code chunk} on one axis. */
    public static int sectionOf(int chunkCoordinate, int gridExponent) {
        return Math.floorDiv(chunkCoordinate, sectionChunks(gridExponent));
    }

    /**
     * The preflight the plugin runs at enable.
     *
     * @return an empty string when the configuration is safe, or the refusal message — naming the
     *         setting, the value found, and the fix — when it is not. A message rather than a
     *         boolean because the operator has to be told what to change; a plugin that disables
     *         itself silently is indistinguishable from one that failed to load.
     */
    public static String preflight(int gridExponent) {
        if (nestsCleanly(gridExponent)) {
            return "";
        }
        return "Nodera refuses to enable: threaded-regions.grid-exponent is " + gridExponent
                + ", and a Nodera region (" + NoderaConstants.REGION_SIZE_CHUNKS
                + " chunks per side) then straddles two Folia sections — two threads writing one"
                + " authority unit, which corrupts state roots days later rather than immediately."
                + " Set threaded-regions.grid-exponent to at least " + MINIMUM_GRID_EXPONENT
                + " (Folia's default is " + DEFAULT_GRID_EXPONENT
                + ") in config/paper-global.yml and restart.";
    }

    /**
     * Whether a whole region is inside one section — the property the live assertion checks on all
     * four corners of every region handed out (server task 3).
     *
     * @param regionX      region X (region coordinates, not chunks).
     * @param regionZ      region Z.
     * @param gridExponent the platform's grid exponent.
     */
    public static boolean regionNestsInOneSection(int regionX, int regionZ, int gridExponent) {
        if (!nestsCleanly(gridExponent)) {
            return false;
        }
        int size = NoderaConstants.REGION_SIZE_CHUNKS;
        int minChunkX = regionX * size;
        int minChunkZ = regionZ * size;
        return sectionOf(minChunkX, gridExponent) == sectionOf(minChunkX + size - 1, gridExponent)
                && sectionOf(minChunkZ, gridExponent) == sectionOf(minChunkZ + size - 1, gridExponent);
    }
}
