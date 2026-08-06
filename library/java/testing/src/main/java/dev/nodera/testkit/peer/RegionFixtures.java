package dev.nodera.testkit.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The region and action values every committee test starts from.
 *
 * <p>These four methods were copied verbatim into nine integration tests, along with the three
 * constants they read. That is not a saving of nine copies but a correctness matter: a base
 * snapshot is an INPUT to a deterministic engine, so two suites whose fixtures drifted apart would
 * be computing different roots while appearing to test the same thing.
 *
 * <p>The world seed is a default, not a constant of the system — {@link PeerTestHarness} takes it
 * as a named parameter because at least one suite deliberately runs on a different one.
 *
 * <p>Thread-context: pure functions; any thread.
 */
public final class RegionFixtures {

    /** The seed nearly every committee test runs on. Overridable per node; never assumed. */
    public static final long WORLD_SEED = 0x4E4F4445_5241L;

    /** Vanilla's overworld floor, which the flat-world rules are written against. */
    public static final int MIN_Y = -64;

    /** Sections per column at that floor. */
    public static final int SECTION_COUNT = 24;

    private RegionFixtures() {}

    /**
     * A column whose every section holds one block state.
     *
     * @param stateId the block state to fill with.
     */
    public static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    /**
     * A complete 8×8-column region at {@link SnapshotVersion#INITIAL} and tick 0, filled uniformly.
     *
     * <p>Complete matters: a committee re-executes what it is given, so a partial snapshot would
     * make every member agree on a world that has holes in it.
     *
     * @param region  the region to build.
     * @param stateId the block state every column holds.
     */
    public static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> columns = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                columns.add(uniformColumn(ox + dx, oz + dz, stateId));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, columns);
    }

    /**
     * An action envelope carrying {@code actor}'s real Ed25519 signature over its signed portion.
     *
     * <p>The player and server sequence numbers are both {@code seq}; the target tick is separate
     * because the skew cases turn on the two disagreeing.
     *
     * @param actor      the identity that signs, and is named as the actor.
     * @param region     the region the action claims to belong to.
     * @param seq        both sequence numbers.
     * @param targetTick the tick the action was signed against.
     * @param action     the action itself.
     */
    public static ActionEnvelope signed(NodeIdentity actor, RegionId region, long seq,
                                        long targetTick, GameAction action) {
        ActionEnvelope unsigned = new ActionEnvelope(
                actor.nodeId(), seq, seq, targetTick, region, action, Bytes.empty());
        return new ActionEnvelope(actor.nodeId(), seq, seq, targetTick, region, action,
                actor.sign(unsigned.signedPortion()));
    }

    /**
     * A signed block placement — the action nearly every committee round is driven by.
     *
     * @param stateId the block state to place.
     */
    public static ActionEnvelope place(NodeIdentity actor, RegionId region, long seq,
                                       long targetTick, int x, int y, int z, int stateId) {
        return signed(actor, region, seq, targetTick,
                new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1));
    }
}
