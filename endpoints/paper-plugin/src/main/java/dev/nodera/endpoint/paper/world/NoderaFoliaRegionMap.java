package dev.nodera.endpoint.paper.world;

import dev.nodera.core.region.RegionAlignment;
import dev.nodera.core.region.RegionId;

import java.util.Optional;

/**
 * Which Nodera regions are written by the same execution thread (server task 3, L-64).
 *
 * <p>Nothing on the plugin path could answer this before, which is why a cross-region delta could
 * not even be <i>detected</i>, let alone refused. It is the prerequisite for
 * {@link CrossRegionCommit}: a commit that spans two regions is safe exactly when both regions are
 * written by one thread, and is a distributed problem the moment they are not.
 *
 * <h2>Three answers, in increasing cost</h2>
 *
 * <ol>
 *   <li><b>Not regionised.</b> Paper has one tick thread, so every pair of regions shares it.
 *       Always {@code true}, no probe.</li>
 *   <li><b>Same Folia section.</b> ALIGN-1 is exactly this guarantee: at
 *       {@code grid-exponent >= 3} a Nodera region nests wholly inside one Folia section, a Folia
 *       region owns whole sections, so two Nodera regions in one section are always one thread.
 *       Arithmetic ({@link RegionAlignment}), no probe. This is the overwhelmingly common case —
 *       four Nodera regions per section at the platform default.</li>
 *   <li><b>Different sections.</b> Folia <i>merges</i> adjacent sections into one region and splits
 *       them again as players move, so two sections may or may not be one thread and the answer
 *       changes at runtime. Only the platform knows, and it will only answer about the
 *       <b>calling</b> thread. So this case asks {@link ExecutionOwnership}, and an answer that
 *       cannot be obtained is {@code false}.</li>
 * </ol>
 *
 * <p><b>Unanswerable means "does not share".</b> A refusal beats a degrade
 * (docs/server/LIMITATIONS.md §B reading guide): treating "I could not ask" as "yes" would put two
 * threads inside one critical section, which is the corruption this class exists to prevent.
 *
 * <p>Regions in different dimensions never share a thread — Folia regionises per world.
 *
 * @Thread-context immutable apart from the injected probe. {@link #shareExecutionThread} may be
 *                 called from any thread, but the probe can only answer from a region thread, so
 *                 case 3 returns {@code false} anywhere else.
 */
public final class NoderaFoliaRegionMap {

    /**
     * The platform's answer to "does the calling thread own this chunk?".
     *
     * <p>On Folia this is {@code Server#isOwnedByCurrentRegion(World, int, int)}, reached
     * reflectively by {@link FoliaOwnershipProbe} so neither the compile classpath nor the unit
     * tests need a Folia jar.
     */
    @FunctionalInterface
    public interface ExecutionOwnership {

        /** Nobody can answer: not on a region thread, or the seam is absent. */
        ExecutionOwnership UNANSWERABLE = (chunkX, chunkZ) -> Optional.empty();

        /**
         * @return {@code true}/{@code false} when the calling thread can answer for this chunk, or
         *         empty when it cannot — which callers must read as "does not share".
         */
        Optional<Boolean> ownsChunk(int chunkX, int chunkZ);
    }

    private final boolean regionised;
    private final int gridExponent;
    private final ExecutionOwnership ownership;

    private NoderaFoliaRegionMap(
            boolean regionised, int gridExponent, ExecutionOwnership ownership) {
        this.regionised = regionised;
        this.gridExponent = gridExponent;
        this.ownership = ownership == null ? ExecutionOwnership.UNANSWERABLE : ownership;
    }

    /** Paper, or any Bukkit server with one tick thread: every region shares it. */
    public static NoderaFoliaRegionMap singleThreaded() {
        return new NoderaFoliaRegionMap(false, 0, ExecutionOwnership.UNANSWERABLE);
    }

    /**
     * Folia, at the grid exponent the platform is actually configured with.
     *
     * @param gridExponent {@code threaded-regions.grid-exponent}; must satisfy ALIGN-1, because
     *                     below it a single Nodera region is already two threads and no pairwise
     *                     answer would be meaningful.
     * @param ownership    the platform probe, or {@link ExecutionOwnership#UNANSWERABLE}.
     */
    public static NoderaFoliaRegionMap regionised(
            int gridExponent, ExecutionOwnership ownership) {
        if (!RegionAlignment.nestsCleanly(gridExponent)) {
            throw new IllegalArgumentException(RegionAlignment.preflight(gridExponent));
        }
        return new NoderaFoliaRegionMap(true, gridExponent, ownership);
    }

    /** @return whether region work must be dispatched through a regionised scheduler. */
    public boolean regionised() {
        return regionised;
    }

    /**
     * Whether {@code a} and {@code b} are written by one execution thread, and may therefore be
     * committed in one critical section.
     *
     * @return {@code false} whenever the answer is not positively known.
     */
    public boolean shareExecutionThread(RegionId a, RegionId b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both regions must be named");
        }
        if (a.equals(b)) {
            return true;
        }
        if (!regionised) {
            return true;
        }
        if (!a.dimension().equals(b.dimension())) {
            // Folia regionises per world; two dimensions are never one thread.
            return false;
        }
        if (sameSection(a, b)) {
            return true;
        }
        return ownership.ownsChunk(b.originChunkX(), b.originChunkZ()).orElse(false);
    }

    /** Whether both regions nest in the one Folia section — the ALIGN-1 fast path. */
    private boolean sameSection(RegionId a, RegionId b) {
        return RegionAlignment.sectionOf(a.originChunkX(), gridExponent)
                == RegionAlignment.sectionOf(b.originChunkX(), gridExponent)
                && RegionAlignment.sectionOf(a.originChunkZ(), gridExponent)
                == RegionAlignment.sectionOf(b.originChunkZ(), gridExponent);
    }

    /**
     * One line for the enable log, so an operator can see what the plugin resolved rather than
     * inferring it from a later failure.
     */
    public String describe() {
        if (!regionised) {
            return "cross-region commit: one tick thread, so every pair of Nodera regions shares it";
        }
        int perAxis = RegionAlignment.regionsPerSectionAxis(gridExponent);
        return "cross-region commit: regionised at grid-exponent " + gridExponent + ", "
                + (perAxis * perAxis) + " Nodera regions per Folia section always share a thread; "
                + (ownership == ExecutionOwnership.UNANSWERABLE
                        ? "no ownership probe resolved, so any wider span is refused"
                        : "wider spans are asked of the platform's own region ownership");
    }
}
