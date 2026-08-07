package dev.nodera.simulation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.testkit.engine.EngineFixtures;

import java.util.List;
import java.util.UUID;

/**
 * The {@code simulation} tests' own façade over {@link EngineFixtures}: the rule suites work in
 * bare {@link dev.nodera.core.action.GameAction}s and wrap them once, where every other suite works
 * in whole envelopes, so {@code place}/{@code brk} here mean something different from the engine
 * fixture's methods of the same name. Everything the two DID agree on — the dimension, the vertical
 * extent, the region and column builders — delegates rather than being re-typed, because a base
 * snapshot is an input to a deterministic engine and two copies that drift compute different roots
 * while appearing to test the same thing.
 *
 * <p>Everything here is fixed-value (no clocks, no {@link UUID#randomUUID}) so tests stay
 * reproducible — the engine determinism bet must not depend on test-time randomness either.
 *
 * <p>Thread-context: single test thread.
 */
public final class TestFixtures {

    /** Fixed actor used by every test-built envelope. */
    public static final NodeId ACTOR = new NodeId(new UUID(0L, 0x4C6F6E676572L));
    /** Empty placeholder signature; the engine never verifies signatures (coordinator's job). */
    public static final Bytes SIG = Bytes.empty();
    /** The overworld dimension, used everywhere in the MVP. */
    public static final DimensionKey OVERWORLD = EngineFixtures.OVERWORLD;

    /** Standard MVP vertical extent: {@code [-64, 320]} = 24 sections of 16 blocks. */
    public static final int DEFAULT_MIN_Y = EngineFixtures.MIN_Y;
    public static final int DEFAULT_SECTION_COUNT = EngineFixtures.SECTION_COUNT;

    private TestFixtures() {}

    /** Region at the grid coordinate given, in the overworld. */
    public static RegionId region(int regionX, int regionZ) {
        return EngineFixtures.region(regionX, regionZ);
    }

    /** A single chunk column uniform in {@code uniformStateId} across all sections. */
    public static ChunkColumnState uniformColumn(int chunkX, int chunkZ, int uniformStateId) {
        return EngineFixtures.uniformColumn(chunkX, chunkZ, uniformStateId);
    }

    /** A single chunk column whose sections are set from the supplied array (defensive copy). */
    public static ChunkColumnState column(int chunkX, int chunkZ, int[] palette) {
        return new ChunkColumnState(chunkX, chunkZ, palette, DEFAULT_MIN_Y, DEFAULT_SECTION_COUNT);
    }

    /** A snapshot covering exactly one chunk column at a uniform state id. */
    public static RegionSnapshot singleColumnSnapshot(RegionId region, int chunkX, int chunkZ, int uniformStateId) {
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                List.of(uniformColumn(chunkX, chunkZ, uniformStateId)));
    }

    /** A snapshot covering every owned chunk of {@code region}, each uniform in {@code uniformStateId}. */
    public static RegionSnapshot fullUniformSnapshot(RegionId region, int uniformStateId) {
        return EngineFixtures.fullUniformSnapshot(region, uniformStateId);
    }

    /** A {@link PlaceBlockAction} on face UP (1). */
    public static PlaceBlockAction place(NBlockPos pos, int blockStateId) {
        return new PlaceBlockAction(pos, blockStateId, 1);
    }

    /** A {@link BreakBlockAction}. */
    public static BreakBlockAction brk(NBlockPos pos) {
        return new BreakBlockAction(pos);
    }

    /** A fully-formed envelope with fixed actor, empty signature, and {@code targetTick = tickFrom}. */
    public static ActionEnvelope envelope(RegionId region, long tickFrom, long serverSeq, GameAction action) {
        return new ActionEnvelope(ACTOR, serverSeq, serverSeq, tickFrom, region, action, SIG);
    }

    /** Initial epoch, zero. */
    public static RegionEpoch initialEpoch() {
        return RegionEpoch.INITIAL;
    }
}
