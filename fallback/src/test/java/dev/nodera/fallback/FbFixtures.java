package dev.nodera.fallback;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
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
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Fixed-value deterministic builders for the fallback (Phase 4) tests. */
final class FbFixtures {

    static final DimensionKey OVERWORLD = DimensionKey.overworld();
    static final int MIN_Y = -64;
    static final int SECTION_COUNT = 24;
    static final long WORLD_SEED = 0x4E4F4445_5241L;
    static final Bytes SIG = Bytes.empty();

    private FbFixtures() {
    }

    static HashService hashes() {
        return new HashService();
    }

    static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes());
    }

    static RegionId region(int rx, int rz) {
        return new RegionId(OVERWORLD, rx, rz);
    }

    static RegionSnapshot fullUniformSnapshot(RegionId region, int stateId) {
        int ox = region.originChunkX();
        int oz = region.originChunkZ();
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] palette = new int[SECTION_COUNT];
                Arrays.fill(palette, stateId);
                cols.add(new ChunkColumnState(ox + dx, oz + dz, palette, MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);
    }

    static ActionEnvelope place(RegionId region, long seq, int x, int y, int z, int stateId) {
        GameAction a = new PlaceBlockAction(new NBlockPos(x, y, z), stateId, 1);
        return new ActionEnvelope(new NodeId(new UUID(0L, seq)), seq, seq, 0L, region, a, SIG);
    }

    static ActionBatch batch(RegionId region, List<ActionEnvelope> actions) {
        return new ActionBatch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1, actions);
    }

    static RegionExecutionRequest request(RegionSnapshot base, ActionBatch batch) {
        RegionExecutionContext ctx = new RegionExecutionContext(batch.region(), batch.epoch(),
                batch.baseVersion(), batch.tickFrom(), batch.tickTo(), WORLD_SEED,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return new RegionExecutionRequest(ctx, base, batch);
    }

    static StateRoot rootOf(RegionSnapshot snapshot) {
        return StateRoot.of(hashes().hash(snapshot));
    }
}
