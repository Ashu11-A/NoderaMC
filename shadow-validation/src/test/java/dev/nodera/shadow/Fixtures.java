package dev.nodera.shadow;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
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
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Fixed-value deterministic builders for the shadow-validation tests (mirrors the simulation module's
 * TestFixtures, which lives in a different test source set). No clocks, no {@link UUID#randomUUID} in
 * the fixtures themselves — test-side action selection uses a seeded {@link java.util.Random}, which
 * is fine (it is outside the engine's deterministic path).
 */
final class Fixtures {

    static final DimensionKey OVERWORLD = DimensionKey.overworld();
    static final int MIN_Y = -64;
    static final int SECTION_COUNT = 24; // [-64, 320)
    static final Bytes SIG = Bytes.empty();

    private Fixtures() {
    }

    static HashService hashes() {
        return new HashService();
    }

    static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes());
    }

    /** A stable non-trivial world seed shared by every participant. */
    static final long WORLD_SEED = 0x4E4F4445_5241L;

    static SessionParams params() {
        return SessionParams.flatWorld(WORLD_SEED);
    }

    static NodeId node(long lo) {
        return new NodeId(new UUID(0L, lo));
    }

    static RegionId region(int rx, int rz) {
        return new RegionId(OVERWORLD, rx, rz);
    }

    static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int stateId) {
        int[] palette = new int[SECTION_COUNT];
        Arrays.fill(palette, stateId);
        return new ChunkColumnState(chunkX, chunkZ, palette, MIN_Y, SECTION_COUNT);
    }

    /** A snapshot at {@link SnapshotVersion#INITIAL} covering all 64 owned chunks uniform in {@code stateId}. */
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

    static ActionBatch batch(RegionId region, SnapshotVersion baseVersion, long tickFrom, long tickTo,
                             List<ActionEnvelope> actions) {
        return new ActionBatch(region, RegionEpoch.INITIAL, baseVersion, tickFrom, tickTo, actions);
    }

    static StateRoot rootOf(RegionSnapshot snapshot) {
        return StateRoot.of(hashes().hash(snapshot));
    }
}
