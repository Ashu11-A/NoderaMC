package dev.nodera.testkit;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic test-fixture builder for Nodera's region state and action types. {@code FakeRegion}
 * produces byte-stable {@link RegionSnapshot}s, {@link ActionEnvelope}s, and {@link ActionBatch}es
 * that exercise the real {@code core}/{@code protocol} encoders without requiring a Minecraft world
 * or the simulation engine. Used by {@code ReplayFixtureTest} (Task 5), divergence hunts, and the
 * {@code ClusterHarness} (Task 7) to seed replicas with known state.
 *
 * <p>All factories are pure: identical inputs produce structurally identical outputs, so fixtures
 * encoded twice are byte-equal (asserted by {@code FixtureIOTest}).
 *
 * <p>The MVP geometry mirrors {@link FlatWorldRules}: {@code MIN_Y = -64}, {@code 24} vertical
 * sections (covering {@code -64..319}), and an {@code 8×8} chunk grid per region
 * ({@link NoderaConstants#REGION_SIZE_CHUNKS}). Palette ids are the {@link FlatWorldRules}
 * constants ({@link FlatWorldRules#AIR AIR}, {@link FlatWorldRules#STONE STONE}, …).
 *
 * <p><b>Signing.</b> Action envelopes built here carry a placeholder all-zero
 * {@code Ed25519}-length signature. Testkit does not sign; real signing is the caller's job — a
 * production caller must replace the signature before the envelope crosses a trust boundary.
 *
 * <p>Thread-context: all methods are pure and safe from any thread.
 */
public final class FakeRegion {

    /** Length of an Ed25519 signature, used to size the placeholder zero signature. */
    public static final int SIGNATURE_BYTES = 64;

    /** Placeholder all-zero signature used by {@link #place} / {@link #breakBlock}. */
    public static final Bytes ZERO_SIGNATURE = Bytes.unsafeWrap(new byte[SIGNATURE_BYTES]);

    /** The {@link PlaceBlockAction} face index used by {@link #place} ({@code 1 = UP}). */
    public static final int DEFAULT_FACE = 1;

    private static final int SECTION_COUNT =
            (FlatWorldRules.MAX_Y - FlatWorldRules.MIN_Y + 1) / 16;

    private FakeRegion() {}

    /**
     * @param regionX region X index on the static grid.
     * @param regionZ region Z index on the static grid.
     * @return the {@link RegionId} for that overworld region.
     * @Thread-context any thread.
     */
    public static RegionId overworldRegion(int regionX, int regionZ) {
        return new RegionId(DimensionKey.overworld(), regionX, regionZ);
    }

    /**
     * Build an {@code 8×8}-chunk overworld {@link RegionSnapshot} whose every section is
     * {@link FlatWorldRules#AIR AIR}. Each chunk is a {@link ChunkColumnState} with
     * {@code minY = -64} and {@code 24} sections.
     *
     * @param region  the region id.
     * @param version the snapshot version.
     * @param tick    the snapshot tick.
     * @return a flat, all-air region snapshot of 64 chunks.
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context any thread.
     */
    public static RegionSnapshot emptyFlatSnapshot(
            RegionId region, SnapshotVersion version, long tick) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        return new RegionSnapshot(region, version, tick, buildChunks(region, null, -1));
    }

    /**
     * Build an {@code 8×8}-chunk overworld {@link RegionSnapshot} identical to
     * {@link #emptyFlatSnapshot} except that the single vertical section containing world height
     * {@code y} is {@link FlatWorldRules#STONE STONE} across every chunk; all other sections remain
     * {@link FlatWorldRules#AIR AIR}.
     *
     * @param region  the region id.
     * @param version the snapshot version.
     * @param tick    the snapshot tick.
     * @param y       world height whose containing section becomes STONE.
     * @return a region snapshot with a single stone section across the region.
     * @throws IllegalArgumentException if any argument is null or {@code y} is outside
     *                                  {@code [FlatWorldRules.MIN_Y, FlatWorldRules.MAX_Y]}.
     * @Thread-context any thread.
     */
    public static RegionSnapshot stoneLayerSnapshot(
            RegionId region, SnapshotVersion version, long tick, int y) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(version, "version");
        if (y < FlatWorldRules.MIN_Y || y > FlatWorldRules.MAX_Y) {
            throw new IllegalArgumentException(
                    "y must be in [" + FlatWorldRules.MIN_Y + "," + FlatWorldRules.MAX_Y + "]: " + y);
        }
        int sectionIndex = (y - FlatWorldRules.MIN_Y) / 16;
        return new RegionSnapshot(region, version, tick, buildChunks(region, FlatWorldRules.STONE, sectionIndex));
    }

    /**
     * Build a {@link PlaceBlockAction} envelope with face {@code UP} and the placeholder
     * {@link #ZERO_SIGNATURE}. {@code playerSeq} is set equal to {@code serverSeq}; testkit does
     * not model the per-player sequence.
     *
     * @param region        the target region.
     * @param actor         the acting node.
     * @param serverSeq     the per-server sequence number (also used as {@code playerSeq}).
     * @param tick          the target tick for the action.
     * @param pos           the target block position.
     * @param blockStateId  the palette id to place.
     * @return a well-formed, unsigned {@link ActionEnvelope}.
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context any thread.
     */
    public static ActionEnvelope place(
            RegionId region, NodeId actor, long serverSeq, long tick,
            NBlockPos pos, int blockStateId) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(pos, "pos");
        PlaceBlockAction action = new PlaceBlockAction(pos, blockStateId, DEFAULT_FACE);
        return new ActionEnvelope(actor, serverSeq, serverSeq, tick, region, action, ZERO_SIGNATURE);
    }

    /**
     * Build a {@link BreakBlockAction} envelope with the placeholder {@link #ZERO_SIGNATURE}.
     * {@code playerSeq} is set equal to {@code serverSeq}.
     *
     * @param region     the target region.
     * @param actor      the acting node.
     * @param serverSeq  the per-server sequence number (also used as {@code playerSeq}).
     * @param tick       the target tick for the action.
     * @param pos        the target block position.
     * @return a well-formed, unsigned {@link ActionEnvelope}.
     * @throws IllegalArgumentException if any argument is null.
     * @Thread-context any thread.
     */
    public static ActionEnvelope breakBlock(
            RegionId region, NodeId actor, long serverSeq, long tick, NBlockPos pos) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(pos, "pos");
        BreakBlockAction action = new BreakBlockAction(pos);
        return new ActionEnvelope(actor, serverSeq, serverSeq, tick, region, action, ZERO_SIGNATURE);
    }

    /**
     * Assemble an {@link ActionBatch} over the given actions in server-sequence order, preserving
     * the argument order exactly (the encoder never re-sorts action lists).
     *
     * @param region      the target region.
     * @param epoch       the region epoch.
     * @param baseVersion the snapshot version the batch is computed against.
     * @param tickFrom    inclusive start tick of the batch window.
     * @param tickTo      inclusive end tick of the batch window.
     * @param actions     the actions, in server-sequence order.
     * @return a well-formed {@link ActionBatch}.
     * @throws IllegalArgumentException if {@code region}, {@code epoch}, {@code baseVersion}, or
     *                                  {@code actions} is null.
     * @Thread-context any thread.
     */
    public static ActionBatch batch(
            RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, ActionEnvelope... actions) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(baseVersion, "baseVersion");
        Objects.requireNonNull(actions, "actions");
        List<ActionEnvelope> list = new ArrayList<>(actions.length);
        for (ActionEnvelope env : actions) {
            list.add(env);
        }
        return new ActionBatch(region, epoch, baseVersion, tickFrom, tickTo, list);
    }

    private static List<ChunkColumnState> buildChunks(RegionId region, Integer layerId, int layerSection) {
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        List<ChunkColumnState> chunks = new ArrayList<>(NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS);
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                int[] palette = new int[SECTION_COUNT];
                if (layerId != null) {
                    palette[layerSection] = layerId;
                }
                chunks.add(new ChunkColumnState(
                        originX + dx, originZ + dz, palette, FlatWorldRules.MIN_Y, SECTION_COUNT));
            }
        }
        return chunks;
    }
}
