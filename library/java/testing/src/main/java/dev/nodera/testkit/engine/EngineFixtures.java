package dev.nodera.testkit.engine;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The four things every engine test does before it can assert anything: build the engine, run a
 * batch of actions for N ticks, build a uniform world to run them in, and read one block back out
 * of the result.
 *
 * <p>{@code executeTicks} was an eight-line body copied into <b>eighteen</b> files, differing only
 * in the world seed. {@code blockAt} was ten lines in nine. Four more fixture classes
 * ({@code FbFixtures}, {@code simulation/TestFixtures}, and the committee/coordinator/shadow trio)
 * each retyped {@code uniformColumn} and {@code fullUniformSnapshot} byte-for-byte modulo the class
 * name.
 *
 * <p>The parameters that survive here are the ones the copies genuinely disagreed about — the world
 * seed, the tick window, the region — because a default that quietly settles a disagreement leaves
 * both tests green and one of them meaningless.
 *
 * <p><b>The standard vertical extent is MVP-fixed</b> at {@code [-64, 320)}, 24 sections of 16
 * blocks. Every copy of these builders hard-coded that pair, and a test that needs another shape
 * builds its own {@link ChunkColumnState}.
 *
 * <p>Thread-context: stateless static helpers; the returned engine is safe to share across a test
 * class because it holds no mutable state.
 */
public final class EngineFixtures {

    /** Standard MVP world bottom. */
    public static final int MIN_Y = -64;

    /** Standard MVP section count: 24 × 16 blocks = {@code [-64, 320)}. */
    public static final int SECTION_COUNT = 24;

    /** Chunks along one edge of a region. */
    private static final int REGION_CHUNKS = 8;

    private EngineFixtures() {
    }

    /**
     * The flat-world engine every deterministic test runs against.
     *
     * @return a fresh engine pinned to {@link FlatWorldRules}' version and registry fingerprint.
     */
    public static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(),
                new HashService());
    }

    /**
     * Run {@code actions} against {@code base} for {@code tickCount} ticks from tick 0.
     *
     * <p>The epoch is {@link RegionEpoch#INITIAL} and the base version is whatever the snapshot
     * carries, which is what every copy of this did — an engine test is about the rules, not about
     * lease bookkeeping.
     *
     * @param engine the engine to execute on.
     * @param region the region the batch belongs to.
     * @param base the snapshot to start from.
     * @param actions the actions in the batch.
     * @param tickCount how many ticks to advance.
     * @param worldSeed the world seed; the one parameter the eighteen copies disagreed about.
     * @return the engine's result.
     */
    public static RegionExecutionResult executeTicks(RegionEngine engine, RegionId region,
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount, long worldSeed) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, worldSeed,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    /**
     * The block state id at {@code pos}, or {@code -1} when no column in the snapshot covers it.
     *
     * <p>{@code -1} rather than a throw is deliberate and was the behaviour of all nine copies: a
     * rule test asserting that something did <em>not</em> spread into an uncovered column wants an
     * answer, not an exception.
     *
     * @param snapshot the snapshot to read.
     * @param pos the world position.
     * @return the block state id, or {@code -1}.
     */
    public static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (ChunkColumnState col : snapshot.chunks()) {
            if (col.chunkX() == Math.floorDiv(pos.x(), 16)
                    && col.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                int section = Math.floorDiv(pos.y() - col.minY(), 16);
                return col.blockAt(section,
                        Math.floorMod(pos.x(), 16),
                        Math.floorMod(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.z(), 16));
            }
        }
        return -1;
    }

    /**
     * One chunk column, every section filled with {@code stateId}.
     *
     * @param chunkX chunk coordinate.
     * @param chunkZ chunk coordinate.
     * @param stateId the block state id to fill with.
     * @return the column.
     */
    public static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    /**
     * A snapshot covering all 64 chunks a region owns, each uniform in {@code stateId}.
     *
     * @param region the region to fill.
     * @param stateId the block state id to fill with.
     * @return the snapshot, at {@link SnapshotVersion#INITIAL} and tick 0.
     */
    public static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        List<ChunkColumnState> columns = new ArrayList<>(REGION_CHUNKS * REGION_CHUNKS);
        for (int dx = 0; dx < REGION_CHUNKS; dx++) {
            for (int dz = 0; dz < REGION_CHUNKS; dz++) {
                columns.add(uniformColumn(originX + dx, originZ + dz, stateId));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, columns);
    }
}
