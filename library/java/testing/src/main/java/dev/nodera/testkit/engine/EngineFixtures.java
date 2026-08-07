package dev.nodera.testkit.engine;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Everything an engine test does before it can assert anything: build the engine, build a world to
 * run in, build the actions, frame them as a batch and a request, run them, and read one block or
 * one root back out.
 *
 * <h2>Why one class</h2>
 *
 * <p>There were <b>nine</b>. {@code CommFixtures}, {@code CoordFixtures}, {@code shadow/Fixtures},
 * {@code FbFixtures}, {@code simulation/TestFixtures}, {@code DistFixtures},
 * {@code StoreFixtures}, {@code testkit.peer.RegionFixtures} and this one each carried their own
 * {@code uniformColumn}, {@code fullUniformSnapshot}, {@code region}, {@code node} and
 * {@code hashes} — byte-identical modulo the class name, and re-typed rather than shared because
 * each lived in a different package. That is not merely nine copies of a loop: a base snapshot is
 * an <em>input</em> to a deterministic engine, so two suites whose copies drifted apart would
 * compute different roots while appearing to test the same thing. Four of the nine are gone
 * outright; the four that carried something genuinely their own — a bare-{@code GameAction}
 * façade, a signed envelope, a swarm peer, a storage row — kept that half and delegate the rest.
 *
 * <h2>Why the actor is a parameter and not a default</h2>
 *
 * <p>{@link #place} and {@link #brk} take the acting {@link NodeId} explicitly because the four
 * copies genuinely disagreed about it, and the disagreement is load-bearing: the coordinator and
 * shadow suites pinned every action to {@code node(1)} so that a batch is one player's ordered
 * stream, while the fallback and committee suites derived the actor from the sequence number so
 * that a batch is many players contending. A default would have settled that silently and left one
 * of the two suites asserting something it does not mean. Likewise the tick: the fallback copy
 * pinned it to 0 and the others did not, so it stays a parameter too. Same rule as
 * {@code executeTicks}' {@code worldSeed}. It is written down in {@code docs/testing/TESTING.md}.
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

    /**
     * The seed nearly every deterministic suite runs on. A <em>default</em>, not a constant of the
     * system: {@link #executeTicks} and {@code PeerTestHarness} both take it as a parameter because
     * at least one suite deliberately runs on another one.
     */
    public static final long WORLD_SEED = 0x4E4F4445_5241L;

    /** The dimension the MVP lives in; every fixture region is in it. */
    public static final DimensionKey OVERWORLD = DimensionKey.overworld();

    /** Chunks along one edge of a region. */
    private static final int REGION_CHUNKS = 8;

    /**
     * The placeholder signature on an unsigned envelope. The engine never verifies signatures —
     * that is the coordinator's job — so an engine fixture that signed would be asserting the
     * wrong layer. {@code testkit.peer.RegionFixtures} is the one that signs, and it is used by
     * the suites that mean to.
     */
    private static final Bytes SIG = Bytes.empty();

    private EngineFixtures() {
    }

    /** A fresh hasher. Stateless, so a per-call instance costs nothing and shares nothing. */
    public static HashService hashes() {
        return new HashService();
    }

    /**
     * The flat-world engine every deterministic test runs against.
     *
     * @return a fresh engine pinned to {@link FlatWorldRules}' version and registry fingerprint.
     */
    public static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes());
    }

    /**
     * A region on the static grid, in {@link #OVERWORLD}.
     *
     * @param regionX grid coordinate.
     * @param regionZ grid coordinate.
     * @return the region id.
     */
    public static RegionId region(int regionX, int regionZ) {
        return new RegionId(OVERWORLD, regionX, regionZ);
    }

    /**
     * A fixed-value node id, so a failure names a stable peer rather than a fresh UUID.
     *
     * @param low the low 64 bits of the UUID; the high bits are always zero.
     * @return the node id.
     */
    public static NodeId node(long low) {
        return new NodeId(new UUID(0L, low));
    }

    /**
     * The state root of {@code snapshot}, as the engine would compute it.
     *
     * @param snapshot the snapshot to hash.
     * @return its root.
     */
    public static StateRoot rootOf(RegionSnapshot snapshot) {
        return StateRoot.of(hashes().hash(snapshot));
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

    /**
     * A snapshot whose columns all differ from one another, so that pieces split out of it are not
     * accidentally equal and a de-duplicating bug cannot pass.
     *
     * @param region the region to fill.
     * @param version the snapshot version to stamp.
     * @param tick the tick to stamp.
     * @return the snapshot.
     */
    public static RegionSnapshot variedSnapshot(RegionId region, SnapshotVersion version, long tick) {
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        List<ChunkColumnState> columns = new ArrayList<>(REGION_CHUNKS * REGION_CHUNKS);
        for (int dx = 0; dx < REGION_CHUNKS; dx++) {
            for (int dz = 0; dz < REGION_CHUNKS; dz++) {
                int[] palette = new int[SECTION_COUNT];
                for (int s = 0; s < SECTION_COUNT; s++) {
                    palette[s] = (dx * REGION_CHUNKS + dz) * 31 + s;
                }
                columns.add(new ChunkColumnState(originX + dx, originZ + dz, palette,
                        MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, version, tick, columns);
    }

    /**
     * An unsigned block placement on face UP.
     *
     * @param region the region the action claims to belong to.
     * @param actor the acting node — explicit, because the copies disagreed (see the class note).
     * @param seq both the player and server sequence numbers.
     * @param tick the target tick.
     * @param x block coordinate.
     * @param y block coordinate.
     * @param z block coordinate.
     * @param stateId the block state to place.
     * @return the envelope.
     */
    public static ActionEnvelope place(RegionId region, NodeId actor, long seq, long tick,
            int x, int y, int z, int stateId) {
        return new ActionEnvelope(actor, seq, seq, tick, region,
                new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1), SIG);
    }

    /**
     * An unsigned block break, the mirror of {@link #place}.
     *
     * @param region the region the action claims to belong to.
     * @param actor the acting node.
     * @param seq both the player and server sequence numbers.
     * @param tick the target tick.
     * @param x block coordinate.
     * @param y block coordinate.
     * @param z block coordinate.
     * @return the envelope.
     */
    public static ActionEnvelope brk(RegionId region, NodeId actor, long seq, long tick,
            int x, int y, int z) {
        return new ActionEnvelope(actor, seq, seq, tick, region,
                new BreakBlockAction(new NBlockPos(x, y, z)), SIG);
    }

    /**
     * A batch, spelled out. Every field is a parameter because the copies that pinned some of them
     * pinned <em>different</em> ones.
     *
     * @param region the region the batch belongs to.
     * @param epoch the lease epoch it was authorised under.
     * @param baseVersion the snapshot version it starts from.
     * @param tickFrom first tick, inclusive.
     * @param tickTo last tick, inclusive.
     * @param actions the actions.
     * @return the batch.
     */
    public static ActionBatch batch(RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            long tickFrom, long tickTo, List<ActionEnvelope> actions) {
        return new ActionBatch(region, epoch, baseVersion, tickFrom, tickTo, actions);
    }

    /**
     * The execution context {@code batch} implies, on {@link #WORLD_SEED} and
     * {@link FlatWorldRules}.
     *
     * @param batch the batch to frame.
     * @return the context.
     */
    public static RegionExecutionContext contextFor(ActionBatch batch) {
        return new RegionExecutionContext(batch.region(), batch.epoch(), batch.baseVersion(),
                batch.tickFrom(), batch.tickTo(), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }

    /**
     * {@code base} and {@code batch} framed as one request via {@link #contextFor}.
     *
     * @param base the snapshot to execute against.
     * @param batch the batch to execute.
     * @return the request.
     */
    public static RegionExecutionRequest request(RegionSnapshot base, ActionBatch batch) {
        return new RegionExecutionRequest(contextFor(batch), base, batch);
    }
}
