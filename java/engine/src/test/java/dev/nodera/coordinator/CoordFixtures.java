package dev.nodera.coordinator;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Fixed-value deterministic builders for the coordinator tests. */
final class CoordFixtures {

    static final DimensionKey OVERWORLD = DimensionKey.overworld();
    static final int MIN_Y = -64;
    static final int SECTION_COUNT = 24;
    static final long WORLD_SEED = 0x4E4F4445_5241L;
    static final Bytes SIG = Bytes.empty();

    private CoordFixtures() {
    }

    static HashService hashes() {
        return new HashService();
    }

    static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes());
    }

    static NodeId node(long lo) {
        return new NodeId(new UUID(0L, lo));
    }

    static NodeCapabilities caps(int cores, double reliability, int maxPrimary, int maxValidator) {
        return NodeCapabilities.of(cores, 4L << 30, 50, reliability, maxPrimary, maxValidator, true);
    }

    static NodeCapabilities caps() {
        return caps(4, 0.99, 4, 8);
    }

    static RegionId region(int rx, int rz) {
        return new RegionId(OVERWORLD, rx, rz);
    }

    static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                cols.add(uniformColumn(ox + dx, oz + dz, stateId));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);
    }

    static ActionEnvelope place(RegionId region, long seq, long tick, int x, int y, int z, int stateId) {
        GameAction a = new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1);
        return new ActionEnvelope(node(1L), seq, seq, tick, region, a, SIG);
    }

    static ActionEnvelope brk(RegionId region, long seq, long tick, int x, int y, int z) {
        GameAction a = new BreakBlockAction(new NBlockPos(x, y, z));
        return new ActionEnvelope(node(1L), seq, seq, tick, region, a, SIG);
    }

    static ActionBatch batch(RegionId region, RegionEpoch epoch, SnapshotVersion base,
                             long tickFrom, long tickTo, List<ActionEnvelope> actions) {
        return new ActionBatch(region, epoch, base, tickFrom, tickTo, actions);
    }

    static RegionExecutionContext contextFor(ActionBatch batch) {
        return new RegionExecutionContext(batch.region(), batch.epoch(), batch.baseVersion(),
                batch.tickFrom(), batch.tickTo(), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }

    static RegionExecutionRequest request(RegionSnapshot base, ActionBatch batch) {
        return new RegionExecutionRequest(contextFor(batch), base, batch);
    }
}
