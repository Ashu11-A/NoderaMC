package dev.nodera.simulation;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.assertj.core.api.Assertions;

import java.util.ArrayList;
import java.util.List;

/**
 * The determinism proof (Task 3 acceptance #1). Two jqwik properties:
 * <ol>
 *   <li>For random valid place/break batches over a flat snapshot, {@code execute} twice yields an
 *       identical {@link StateRoot} and identical canonical {@code RegionDelta} bytes.</li>
 *   <li>Two batches that differ at the section level (the MVP's state granularity) yield different
 *       roots — i.e. the root is sensitive to the post-state, not a constant.</li>
 * </ol>
 *
 * <p>Thread-context: single test thread.
 */
class DeterminismPropertyTest {

    private static final RegionId REGION = TestFixtures.region(0, 0);

    private final HashService hashService = new HashService();
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashService);

    @Property(tries = 100)
    void executeTwiceIsIdentical(@ForAll("rawActions") List<GameAction> raw) {
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(REGION, 0, 0, 0);
        RegionExecutionRequest req = request(snapshot, batch(raw));

        RegionExecutionResult first = engine.execute(req);
        RegionExecutionResult second = engine.execute(req);

        Assertions.assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
        Assertions.assertThat(CanonicalEncoder.encode(second.delta()))
                .isEqualTo(CanonicalEncoder.encode(first.delta()));
    }

    @Property(tries = 100)
    void differentSectionPlacesYieldDifferentRoots(@ForAll("distinctSections") List<Integer> sections) {
        int y1 = TestFixtures.DEFAULT_MIN_Y + sections.get(0) * 16;
        int y2 = TestFixtures.DEFAULT_MIN_Y + sections.get(1) * 16;
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(REGION, 0, 0, 0);

        ActionBatch b1 = batch(List.of(placeAt(new NBlockPos(0, y1, 0))));
        ActionBatch b2 = batch(List.of(placeAt(new NBlockPos(0, y2, 0))));

        StateRoot r1 = engine.execute(request(snapshot, b1)).resultingRoot();
        StateRoot r2 = engine.execute(request(snapshot, b2)).resultingRoot();

        Assertions.assertThat(r1).isNotEqualTo(r2);
    }

    @Provide
    Arbitrary<List<GameAction>> rawActions() {
        Arbitrary<Integer> xz = Arbitraries.integers().between(0, 15);
        Arbitrary<Integer> yArb = Arbitraries.integers().between(FlatWorldRules.MIN_Y, FlatWorldRules.MAX_Y);
        Arbitrary<Integer> idArb = Arbitraries.of(
                FlatWorldRules.STONE, FlatWorldRules.DIRT, FlatWorldRules.GRASS_BLOCK,
                FlatWorldRules.COBBLESTONE, FlatWorldRules.OAK_PLANKS, FlatWorldRules.OAK_LOG,
                FlatWorldRules.GLASS, FlatWorldRules.SAND);
        Arbitrary<GameAction> place = Combinators.combine(xz, xz, yArb, idArb)
                .as((x, z, y, id) -> (GameAction) new PlaceBlockAction(new NBlockPos(x, y, z), id, 1));
        Arbitrary<GameAction> brk = Combinators.combine(xz, xz, yArb)
                .as((x, z, y) -> (GameAction) new BreakBlockAction(new NBlockPos(x, y, z)));
        Arbitrary<GameAction> action = Arbitraries.oneOf(place, brk);
        return action.list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<List<Integer>> distinctSections() {
        return Arbitraries.integers().between(0, TestFixtures.DEFAULT_SECTION_COUNT - 1)
                .list().ofSize(2)
                .filter(l -> !l.get(0).equals(l.get(1)));
    }

    private static PlaceBlockAction placeAt(NBlockPos pos) {
        return TestFixtures.place(pos, FlatWorldRules.STONE);
    }

    private ActionBatch batch(List<GameAction> raw) {
        List<ActionEnvelope> envs = new ArrayList<>(raw.size());
        long seq = 1;
        for (GameAction action : raw) {
            envs.add(TestFixtures.envelope(REGION, 0L, seq++, action));
        }
        return new ActionBatch(REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, envs);
    }

    private static RegionExecutionRequest request(RegionSnapshot snapshot, ActionBatch batch) {
        RegionExecutionContext ctx = new RegionExecutionContext(
                REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return new RegionExecutionRequest(ctx, snapshot, batch);
    }
}
