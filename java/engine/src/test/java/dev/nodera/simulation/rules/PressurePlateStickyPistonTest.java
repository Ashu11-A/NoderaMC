package dev.nodera.simulation.rules;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last two components of Task 13's palette v2, and therefore L-26's remaining exit stage:
 * <b>the pressure plate</b> (the one redstone source driven by the entity lane rather than by
 * blocks or by a scheduled tick) and <b>the sticky piston</b> (the one that pulls).
 *
 * <p>The plate is the interesting one. Every other source answers to a block; a plate answers to
 * where something is standing, so it only became expressible once entities were validated root
 * state. Because they are, plate power is a pure function of the committed root — which is what
 * these tests assert, rather than that the plate merely "works". Everything runs through the full
 * engine path, so each assertion is also a root assertion.
 */
final class PressurePlateStickyPistonTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        return executeFrom(base, actions, base.tick(), base.tick() + tickCount);
    }

    private RegionExecutionResult executeFrom(
            RegionSnapshot base, List<ActionEnvelope> actions, long tickFrom, long tickTo) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), tickFrom, tickTo, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), tickFrom, tickTo, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static RegionSnapshot advance(
            RegionSnapshot base, RegionExecutionResult result, long tick) {
        return dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), tick);
    }

    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (var col : snapshot.chunks()) {
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

    /** The uniform base snapshot with {@code entities} present in the root. */
    private RegionSnapshot withEntities(List<PersistedEntityState> entities) {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        return new RegionSnapshot(base.region(), base.version(), base.tick(),
                base.chunks(), entities);
    }

    private static PersistedEntityState standingAt(long id, int x, int y, int z) {
        return new PersistedEntityState(new NetworkEntityId(id), EntityKind.MOB, 1,
                new FixedVec3((long) x << 32, (long) y << 32, (long) z << 32), FixedVec3.ZERO,
                1, PersistedEntityState.NEVER_DESPAWN,
                dev.nodera.simulation.entity.MobCombatRules.vitalsPayload(20, 20));
    }

    private static PersistedEntityState ghostAt(long id, int x, int y, int z) {
        return new PersistedEntityState(new NetworkEntityId(id), EntityKind.GHOST, 1,
                new FixedVec3((long) x << 32, (long) y << 32, (long) z << 32), FixedVec3.ZERO,
                1, 6000, Bytes.empty());
    }

    /** Place a plate at (5,64,0) with a wire beside it. */
    private List<ActionEnvelope> plateAndWire() {
        List<ActionEnvelope> actions = new ArrayList<>();
        actions.add(TestFixtures.envelope(region, 0L, 1,
                TestFixtures.place(new NBlockPos(5, 64, 0), FlatWorldRules.PRESSURE_PLATE_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, 2,
                TestFixtures.place(new NBlockPos(6, 64, 0), FlatWorldRules.WIRE_0)));
        return actions;
    }

    // --- the pressure plate --------------------------------------------------------------------

    @Test
    void anEntityStandingOnAPlatePressesItAndPowersTheAdjacentWire() {
        RegionSnapshot empty = withEntities(List.of());
        RegionSnapshot afterEmpty = advance(empty, executeTicks(empty, plateAndWire(), 2), 2L);
        assertThat(blockAt(afterEmpty, new NBlockPos(5, 64, 0)))
                .as("nothing standing there ⇒ the plate stays up")
                .isEqualTo(FlatWorldRules.PRESSURE_PLATE_OFF);
        assertThat(blockAt(afterEmpty, new NBlockPos(6, 64, 0))).isEqualTo(FlatWorldRules.WIRE_0);

        RegionSnapshot occupied = withEntities(List.of(standingAt(1L, 5, 64, 0)));
        RegionSnapshot afterStand =
                advance(occupied, executeTicks(occupied, plateAndWire(), 2), 2L);
        assertThat(blockAt(afterStand, new NBlockPos(5, 64, 0)))
                .isEqualTo(FlatWorldRules.PRESSURE_PLATE_ON);
        assertThat(blockAt(afterStand, new NBlockPos(6, 64, 0)))
                .as("a pressed plate is a 15-power omni source like a lever")
                .isEqualTo(FlatWorldRules.WIRE_15);
    }

    @Test
    void theSameEntitySetProducesTheSameRootWhateverOrderItArrivesIn() {
        List<PersistedEntityState> mobs = List.of(
                standingAt(7L, 5, 64, 0), standingAt(3L, 9, 64, 9), standingAt(5L, 2, 64, 2));
        List<PersistedEntityState> reversed = new ArrayList<>(mobs);
        java.util.Collections.reverse(reversed);

        // Canonical id ordering is what makes the mutation sequence — and therefore the delta's
        // bytes — replica-identical, not merely equivalent.
        assertThat(executeTicks(withEntities(reversed), plateAndWire(), 3).resultingRoot())
                .as("plate evaluation is a pure function of the committed root")
                .isEqualTo(executeTicks(withEntities(mobs), plateAndWire(), 3).resultingRoot());
    }

    @Test
    void aGhostNeverPressesAPlate() {
        // GHOST positions are server-authoritative. Letting one drive validated state would put a
        // non-validated input into the root.
        RegionSnapshot base = withEntities(List.of(ghostAt(2L, 5, 64, 0)));
        RegionSnapshot after = advance(base, executeTicks(base, plateAndWire(), 2), 2L);
        assertThat(blockAt(after, new NBlockPos(5, 64, 0)))
                .isEqualTo(FlatWorldRules.PRESSURE_PLATE_OFF);
    }

    @Test
    void thePlateStaysDownWhileOccupiedAndTheReleaseIsHashedState() {
        RegionSnapshot base = withEntities(List.of(standingAt(1L, 5, 64, 0)));
        int ticks = PressurePlateRules.PLATE_RELEASE_TICKS * 3;
        RegionExecutionResult result = executeTicks(base, plateAndWire(), ticks);
        RegionSnapshot after = advance(base, result, ticks);

        assertThat(blockAt(after, new NBlockPos(5, 64, 0)))
                .as("a standing entity holds the plate down indefinitely — the timer re-arms")
                .isEqualTo(FlatWorldRules.PRESSURE_PLATE_ON);
        assertThat(after.scheduledTicks())
                .as("the pending release lives in the HASHED queue, so it survives a delta boundary")
                .isNotEmpty();
    }

    @Test
    void aPressedPlateCannotBeMintedByAPlayer() {
        // Otherwise a modified client could conjure 15 power with nothing standing on it.
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = executeTicks(base, List.of(
                TestFixtures.envelope(region, 0L, 1, TestFixtures.place(
                        new NBlockPos(5, 64, 0), FlatWorldRules.PRESSURE_PLATE_ON))), 1);
        assertThat(result.stats().actionsRejected()).isEqualTo(1);
    }

    // --- the sticky piston ---------------------------------------------------------------------

    /** An east-facing piston of the given family at (1,64,0), powered, with a stone in front. */
    private List<ActionEnvelope> poweredPiston(boolean sticky) {
        int retracted = (sticky ? FlatWorldRules.STICKY_PISTON_RETRACTED_BASE
                : FlatWorldRules.PISTON_RETRACTED_BASE) + 3;
        List<ActionEnvelope> actions = new ArrayList<>();
        actions.add(TestFixtures.envelope(region, 0L, 1,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, 2,
                TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.STONE)));
        actions.add(TestFixtures.envelope(region, 0L, 3,
                TestFixtures.place(new NBlockPos(1, 64, 0), retracted)));
        return actions;
    }

    @Test
    void bothFamiliesExtendTheSameWay() {
        for (boolean sticky : new boolean[]{true, false}) {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot after = advance(base, executeTicks(base, poweredPiston(sticky), 1), 1L);

            int expectedHead = (sticky ? FlatWorldRules.STICKY_PISTON_HEAD_BASE
                    : FlatWorldRules.PISTON_HEAD_BASE) + 3;
            assertThat(blockAt(after, new NBlockPos(2, 64, 0)))
                    .as("the head occupies the front cell (family-aware ids)")
                    .isEqualTo(expectedHead);
            assertThat(blockAt(after, new NBlockPos(3, 64, 0)))
                    .as("the pushed stone moved one cell east")
                    .isEqualTo(FlatWorldRules.STONE);
        }
    }

    @Test
    void onlyTheStickyFamilyPullsItsBlockBackOnRetraction() {
        for (boolean sticky : new boolean[]{true, false}) {
            // Extend under power…
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot extended =
                    advance(base, executeTicks(base, poweredPiston(sticky), 1), 1L);

            // …then break the power source: the retraction fires on the next tick.
            RegionExecutionResult retraction = executeFrom(extended, List.of(
                    TestFixtures.envelope(region, 1L, 4,
                            TestFixtures.brk(new NBlockPos(0, 64, 0)))), 1L, 3L);
            RegionSnapshot after = advance(extended, retraction, 3L);

            int retractedId = (sticky ? FlatWorldRules.STICKY_PISTON_RETRACTED_BASE
                    : FlatWorldRules.PISTON_RETRACTED_BASE) + 3;
            assertThat(blockAt(after, new NBlockPos(1, 64, 0)))
                    .as("both families retract")
                    .isEqualTo(retractedId);
            assertThat(blockAt(after, new NBlockPos(2, 64, 0)))
                    .as(sticky ? "sticky pulls the stone into the vacated head cell"
                            : "a plain piston pulls nothing back")
                    .isEqualTo(sticky ? FlatWorldRules.STONE : FlatWorldRules.AIR);
            assertThat(blockAt(after, new NBlockPos(3, 64, 0)))
                    .isEqualTo(sticky ? FlatWorldRules.AIR : FlatWorldRules.STONE);
        }
    }

    @Test
    void bothNewComponentsAreWiredIntoTheGraphAndTheRulesVersionMovedWithThem() {
        // The registry fingerprint is the number every committee member pins: adding components
        // MUST move it, so a peer on the old palette refuses rather than silently diverging. The
        // version itself keeps moving (5 since obsidian joined the palette for L-2's lava/water
        // interactions), so what is pinned here is that it is at least the version these two
        // components arrived in — not a literal that has to be edited by every later palette bump.
        assertThat(FlatWorldRules.RULES_VERSION).isGreaterThanOrEqualTo(4);
        assertThat(RedstoneRules.isSticky(FlatWorldRules.STICKY_PISTON_HEAD_BASE)).isTrue();
        assertThat(RedstoneRules.isSticky(FlatWorldRules.PISTON_HEAD_BASE)).isFalse();
        assertThat(RedstoneRules.isPistonBase(FlatWorldRules.STICKY_PISTON_RETRACTED_BASE)).isTrue();
        assertThat(RedstoneRules.isPistonHead(FlatWorldRules.STICKY_PISTON_HEAD_BASE + 3)).isTrue();
        assertThat(RedstoneRules.isRedstoneFamily(FlatWorldRules.PRESSURE_PLATE_OFF)).isTrue();
        assertThat(RedstoneRules.emittedPower(FlatWorldRules.PRESSURE_PLATE_ON)).isEqualTo(15);
        assertThat(RedstoneRules.pistonFacing(FlatWorldRules.STICKY_PISTON_HEAD_BASE + 2))
                .as("facing decoding is family-aware")
                .isEqualTo(2);
    }
}
