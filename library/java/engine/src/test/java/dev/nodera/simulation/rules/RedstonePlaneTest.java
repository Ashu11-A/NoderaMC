package dev.nodera.simulation.rules;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.InteractBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.ActionRejection.Reason;
import dev.nodera.testkit.engine.EngineFixtures;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redstone plane of the flat-world rule set: signal propagation, the components that read it,
 * and the two-phase block events that act on it.
 *
 * <p>Five sibling classes, one subject. They were five files whose import blocks were the same
 * fourteen lines and whose subject was the same graph — a wire network, the things that drive it
 * and the things it drives. Each keeps its own class Javadoc naming the vanilla behaviour it pins
 * and the failure it was written from, and JUnit reports every {@code @Nested @Test} individually,
 * so nothing about a failure report changes.
 *
 * <p>Each nest keeps its own engine and world seed. They differ, and a shared one would have been
 * a default quietly settling a disagreement between five tests.
 */
final class RedstonePlaneTest {

    /**
     * Task 13 increment 4 (L-26): the static redstone signal graph through the FULL engine path —
     * validation, per-block application, deterministic network settling, delta emission, root
     * hashing. Wire power is block state, so every assertion below is also a root assertion.
     */
    @Nested
    final class RedstoneGraphTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult execute(RegionSnapshot base, List<ActionEnvelope> actions) {
            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, 12345L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
            return engine.execute(new RegionExecutionRequest(ctx, base, batch));
        }


        private List<ActionEnvelope> wireLineWithLever(int wires) {
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.LEVER_OFF)));
            for (int x = 1; x <= wires; x++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
            }
            return actions;
        }

        @Test
        void leverToggleSettlesTheWireLineWithDecayAndIdenticalRootsAcrossReplicas() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = wireLineWithLever(5);
            actions.add(TestFixtures.envelope(region, 0L, 99L,
                    new InteractBlockAction(new NBlockPos(0, 64, 0))));

            RegionExecutionResult first = execute(base, actions);
            RegionExecutionResult second = execute(base, actions);

            assertThat(first.stats().actionsRejected()).isZero();
            assertThat(second.resultingRoot())
                    .as("two replicas settle the network to the identical root")
                    .isEqualTo(first.resultingRoot());

            RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 1L);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(0, 64, 0)))
                    .isEqualTo(FlatWorldRules.LEVER_ON);
            // Wire adjacent to the source carries 15; each further hop decays by 1.
            for (int x = 1; x <= 5; x++) {
                assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(x, 64, 0)))
                        .as("wire at x=%d", x)
                        .isEqualTo(RedstoneRules.wireWithPower(15 - (x - 1)));
            }
        }

        @Test
        void breakingTheSourceDepowersTheNetwork() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> build = new ArrayList<>();
            long seq = 1;
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
            for (int x = 1; x <= 3; x++) {
                build.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
            }
            RegionSnapshot powered = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, execute(base, build).delta(), 1L);
            assertThat(EngineFixtures.blockAt(powered, new NBlockPos(1, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));

            RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                    0, powered.chunks(), powered.entities());
            RegionExecutionResult broken = execute(rebased, List.of(
                    TestFixtures.envelope(region, 0L, 50L,
                            new dev.nodera.core.action.BreakBlockAction(new NBlockPos(0, 64, 0)))));
            RegionSnapshot depowered = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    rebased, broken.delta(), 1L);
            for (int x = 1; x <= 3; x++) {
                assertThat(EngineFixtures.blockAt(depowered, new NBlockPos(x, 64, 0)))
                        .as("wire at x=%d depowered", x)
                        .isEqualTo(FlatWorldRules.WIRE_0);
            }
        }

        @Test
        void strongestOfTwoSourcesWinsPerWire() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> build = new ArrayList<>();
            long seq = 1;
            // Sources at both ends of a 5-wire line: every wire settles at max(from-left, from-right).
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
            for (int x = 1; x <= 5; x++) {
                build.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
            }
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(6, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, execute(base, build).delta(), 1L);
            // Symmetric line: both ends adjacent to a source read 15, the middle reads 13.
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(1, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(3, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(13));
            assertThat(EngineFixtures.blockAt(settled, new NBlockPos(5, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));
        }

        @Test
        void networkComputedStatesAreNeverPlaceable() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionResult poweredWire = execute(base, List.of(
                    TestFixtures.envelope(region, 0L, 1L,
                            TestFixtures.place(new NBlockPos(1, 64, 0),
                                    RedstoneRules.wireWithPower(5)))));
            RegionExecutionResult leverOn = execute(base, List.of(
                    TestFixtures.envelope(region, 0L, 2L,
                            TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.LEVER_ON))));
            RegionExecutionResult repeaterOn = execute(base, List.of(
                    TestFixtures.envelope(region, 0L, 3L,
                            TestFixtures.place(new NBlockPos(1, 64, 0),
                                    FlatWorldRules.REPEATER_EAST_ON))));
            RegionExecutionResult buttonOn = execute(base, List.of(
                    TestFixtures.envelope(region, 0L, 4L,
                            TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.BUTTON_ON))));
            assertThat(poweredWire.stats().rejections())
                    .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
            assertThat(leverOn.stats().rejections())
                    .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
            assertThat(repeaterOn.stats().rejections())
                    .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
            assertThat(buttonOn.stats().rejections())
                    .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
        }

        @Test
        void interactingWithANonInteractableBlockIsRejectedDeterministically() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionResult result = execute(base, List.of(
                    TestFixtures.envelope(region, 0L, 1L,
                            new InteractBlockAction(new NBlockPos(1, 64, 0)))));
            assertThat(result.stats().rejections())
                    .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
        }

        @Test
        void torchIsExtinguishedByAPoweredSupportThroughTheTickQueue() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> build = new ArrayList<>();
            long seq = 1;
            // Redstone block at y=64, torch standing on it at y=65: input powered -> the torch
            // must go out — one region tick later, THROUGH the hashed scheduled-tick queue.
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 2), FlatWorldRules.REDSTONE_BLOCK)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 65, 2), FlatWorldRules.TORCH_ON)));

            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 2, build);
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 2, 12345L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
            RegionExecutionResult result = engine.execute(
                    new RegionExecutionRequest(ctx, base, batch));
            RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, result.delta(), 2L);

            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(2, 65, 2)))
                    .as("powered support extinguishes the torch after its scheduled delay")
                    .isEqualTo(FlatWorldRules.TORCH_OFF);
            // Replica determinism for the timed path.
            assertThat(engine.execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot())
                    .isEqualTo(result.resultingRoot());
        }

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 12345L);
        }

        @Test
        void repeaterOutputsThroughItsFrontOnly() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> build = new ArrayList<>();
            long seq = 1;
            // Source behind an east-facing repeater; wire in front AND a wire beside it. Only the
            // front wire may power — repeater emission is directional, unlike every other source.
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.REPEATER_EAST_OFF)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.WIRE_0)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 1), FlatWorldRules.WIRE_0)));

            RegionExecutionResult result = executeTicks(base, build, 3);
            RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, result.delta(), 3L);
            assertThat(result.stats().actionsRejected()).isZero();
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(1, 64, 0)))
                    .as("powered input switches the repeater ON through the tick queue")
                    .isEqualTo(FlatWorldRules.REPEATER_EAST_ON);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(2, 64, 0)))
                    .as("the front wire receives the repeated 15")
                    .isEqualTo(RedstoneRules.wireWithPower(15));
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(1, 64, 1)))
                    .as("the side wire receives NOTHING — emission is directional")
                    .isEqualTo(FlatWorldRules.WIRE_0);
        }

        @Test
        void eachRepeaterStageAddsExactlyOneTickOfDelay() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            // lever - wire - repeater - wire - repeater - wire, lever flipped at tick 0. The signal
            // front must advance one repeater per tick: the delay is consensus state in the queue.
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.LEVER_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.REPEATER_EAST_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(3, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(4, 64, 0), FlatWorldRules.REPEATER_EAST_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(5, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    new InteractBlockAction(new NBlockPos(0, 64, 0))));

            // After one tick: stage 1 has fired (its flip was scheduled for tick 1), stage 2 has
            // only just been scheduled — the front sits BETWEEN the repeaters.
            RegionSnapshot afterOne = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, actions, 1).delta(), 1L);
            assertThat(EngineFixtures.blockAt(afterOne, new NBlockPos(1, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));
            assertThat(EngineFixtures.blockAt(afterOne, new NBlockPos(2, 64, 0)))
                    .isEqualTo(FlatWorldRules.REPEATER_EAST_ON);
            assertThat(EngineFixtures.blockAt(afterOne, new NBlockPos(3, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));
            assertThat(EngineFixtures.blockAt(afterOne, new NBlockPos(4, 64, 0)))
                    .as("stage 2 lags stage 1 by exactly one tick")
                    .isEqualTo(FlatWorldRules.REPEATER_EAST_OFF);
            assertThat(EngineFixtures.blockAt(afterOne, new NBlockPos(5, 64, 0)))
                    .isEqualTo(FlatWorldRules.WIRE_0);

            // One more tick: stage 2 fires and the whole chain is hot.
            RegionSnapshot afterTwo = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, actions, 2).delta(), 2L);
            assertThat(EngineFixtures.blockAt(afterTwo, new NBlockPos(4, 64, 0)))
                    .isEqualTo(FlatWorldRules.REPEATER_EAST_ON);
            assertThat(EngineFixtures.blockAt(afterTwo, new NBlockPos(5, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));

            // Replica determinism for the staged path.
            assertThat(executeTicks(base, actions, 3).resultingRoot())
                    .isEqualTo(executeTicks(base, actions, 3).resultingRoot());
        }

        @Test
        void buttonPressPowersTheWireAndAutoReleasesThroughTheTickQueue() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.BUTTON_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    new InteractBlockAction(new NBlockPos(0, 64, 0))));

            // Mid-press: button ON, wire hot.
            RegionSnapshot pressed = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, actions, 5).delta(), 5L);
            assertThat(EngineFixtures.blockAt(pressed, new NBlockPos(0, 64, 0)))
                    .isEqualTo(FlatWorldRules.BUTTON_ON);
            assertThat(EngineFixtures.blockAt(pressed, new NBlockPos(1, 64, 0)))
                    .isEqualTo(RedstoneRules.wireWithPower(15));

            // Past the auto-off horizon: released and depowered, all through the hashed queue.
            RegionExecutionResult full = executeTicks(
                    base, actions, RedstoneRules.BUTTON_PRESS_TICKS + 2);
            RegionSnapshot released = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, full.delta(), RedstoneRules.BUTTON_PRESS_TICKS + 2);
            assertThat(EngineFixtures.blockAt(released, new NBlockPos(0, 64, 0)))
                    .as("the press auto-releases after BUTTON_PRESS_TICKS")
                    .isEqualTo(FlatWorldRules.BUTTON_OFF);
            assertThat(EngineFixtures.blockAt(released, new NBlockPos(1, 64, 0)))
                    .isEqualTo(FlatWorldRules.WIRE_0);
            assertThat(executeTicks(base, actions, RedstoneRules.BUTTON_PRESS_TICKS + 2)
                    .resultingRoot()).isEqualTo(full.resultingRoot());
        }

        @Test
        void torchClockOscillatesDeterministicallyAcrossReplicas() {
            // THE acceptance-1 device: a torch whose own output wire powers its support — the
            // classic torch clock. Every batch of ticks must yield identical roots on every
            // replica, and the schedule (the clock's phase) lives in the hashed queue.
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> build = new ArrayList<>();
            long seq = 1;
            // Support wire at (2,64,2); torch on it at (2,65,2); wire from the torch's level
            // feeding back down to the support: torch ON powers wire, wire powers support,
            // support extinguishes torch, wire depowers, torch relights — oscillation.
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 2), FlatWorldRules.WIRE_0)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 65, 3), FlatWorldRules.WIRE_0)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 3), FlatWorldRules.WIRE_0)));
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 65, 2), FlatWorldRules.TORCH_ON)));

            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 10, build);
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 10, 12345L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());

            RegionExecutionResult first = engine.execute(new RegionExecutionRequest(ctx, base, batch));
            RegionExecutionResult second = engine.execute(new RegionExecutionRequest(ctx, base, batch));
            assertThat(second.resultingRoot())
                    .as("10 ticks of a torch clock settle to the identical root on every replica")
                    .isEqualTo(first.resultingRoot());
            Bytes d1 = dev.nodera.core.crypto.CanonicalEncoder.encode(first.delta());
            Bytes d2 = dev.nodera.core.crypto.CanonicalEncoder.encode(second.delta());
            assertThat(d2).isEqualTo(d1);
        }
    }

    /**
     * Task 14 (L-5): observers + quasi-connectivity. The observer watches its facing cell and
     * pulses 15 out its BACK for one tick (rise +1, fall +2 — both edges live in the hashed
     * queue); the piston additionally reads power through the cell above it (QC), and BUD
     * staleness emerges from update-driven re-evaluation rather than being simulated.
     */
    @Nested
    final class ObserverQcTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 12345L);
        }


        /** Observer at (5,64,0) watching EAST (6,64,0), wire on its back at (4,64,0). */
        private List<ActionEnvelope> observerRig() {
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(5, 64, 0), FlatWorldRules.OBSERVER_EAST_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(4, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(4, 64, 1), FlatWorldRules.WIRE_0)));
            // The observed change: a block appears in the watched cell.
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(6, 64, 0), FlatWorldRules.STONE)));
            return actions;
        }

        @Test
        void observedChangeRaisesTheBackPulseOneTickLater() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot mid = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, observerRig(), 1).delta(), 1L);
            assertThat(EngineFixtures.blockAt(mid, new NBlockPos(5, 64, 0)))
                    .as("the pulse rises one tick after the observed change")
                    .isEqualTo(FlatWorldRules.OBSERVER_EAST_ON);
            assertThat(EngineFixtures.blockAt(mid, new NBlockPos(4, 64, 0)))
                    .as("the back wire reads the pulse")
                    .isEqualTo(RedstoneRules.wireWithPower(15));
        }

        @Test
        void thePulseFallsOnItsOwnOneTickAfterRising() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot after = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, observerRig(), 3).delta(), 3L);
            assertThat(EngineFixtures.blockAt(after, new NBlockPos(5, 64, 0)))
                    .as("the pulse is transient state — it falls without further input")
                    .isEqualTo(FlatWorldRules.OBSERVER_EAST_OFF);
            assertThat(EngineFixtures.blockAt(after, new NBlockPos(4, 64, 0)))
                    .isEqualTo(FlatWorldRules.WIRE_0);
            // Replica determinism for the two-edge pulse.
            assertThat(executeTicks(base, observerRig(), 3).resultingRoot())
                    .isEqualTo(executeTicks(base, observerRig(), 3).resultingRoot());
        }

        @Test
        void observerNeverPowersItsSides() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot mid = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, observerRig(), 1).delta(), 1L);
            assertThat(EngineFixtures.blockAt(mid, new NBlockPos(5, 64, 1)))
                    .as("side cell reads nothing — the pulse leaves through the back only")
                    .isEqualTo(FlatWorldRules.AIR);
            // The side wire at (4,64,1) is powered only THROUGH the back wire's hop (14), never
            // directly by the observer (which would read 15).
            assertThat(EngineFixtures.blockAt(mid, new NBlockPos(4, 64, 1)))
                    .isEqualTo(RedstoneRules.wireWithPower(14));
        }

        @Test
        void quasiConnectivityPowersAPistonThroughTheCellAboveIt() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            // Power reaches ONLY the cell above the piston: a redstone block two cells up powers
            // (10,65,10) as-if-a-block-were-there; the piston itself has no direct neighbor power.
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(10, 66, 10), FlatWorldRules.REDSTONE_BLOCK)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(10, 64, 10),
                            FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

            RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, executeTicks(base, actions, 2).delta(), 2L);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(10, 64, 10)))
                    .as("QC: the piston extends although only the cell above it is powered")
                    .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
        }

        @Test
        void observerOnStatesAreNeverPlaceable() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionResult result = executeTicks(base, List.of(
                    TestFixtures.envelope(region, 0L, 1L,
                            TestFixtures.place(new NBlockPos(3, 64, 3),
                                    FlatWorldRules.OBSERVER_WEST_ON))), 1);
            assertThat(result.stats().rejections())
                    .extracting(ActionRejection::reason)
                    .containsExactly(ActionRejection.Reason.ILLEGAL_BLOCK);
        }
    }

    /**
     * Task 14 (L-6): the daylight sensor — a redstone source whose output is the member-agreed world
     * time crossed with committed sky light. Noon under open sky powers a wire; night or a roofed
     * sensor stays dark; replicas agree.
     */
    @Nested
    final class DaylightSensorTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeAt(RegionSnapshot base, long worldTime,
                                                List<ActionEnvelope> actions) {
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, 31337L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), worldTime);
            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
            return engine.execute(new RegionExecutionRequest(ctx, base, batch));
        }

        private static List<ActionEnvelope> sensorAndWire() {
            return List.of(
                    TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 1L,
                            TestFixtures.place(new NBlockPos(64, 64, 64), FlatWorldRules.DAYLIGHT_SENSOR)),
                    TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 2L,
                            TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.WIRE_0)));
        }

        private static int wirePowerAfter(RegionSnapshot settled) {
            return RedstoneRules.wirePower(
                    new dev.nodera.simulation.MutableRegionState(settled, dev.nodera.core.region.RegionBounds.of(
                            TestFixtures.region(0, 0))).getBlock(new NBlockPos(65, 64, 64)));
        }

        @Test
        void noonUnderOpenSkyPowersTheWire() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionExecutionResult first = executeAt(base, 6_000L, sensorAndWire());
            RegionExecutionResult second = executeAt(base, 6_000L, sensorAndWire());
            assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
            assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, first.delta(), 1L)))
                    .as("noon + open sky ⇒ the sensor emits 15 into the adjacent wire")
                    .isEqualTo(15);
        }

        @Test
        void nightLeavesTheWireDark() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionExecutionResult result = executeAt(base, 18_000L, sensorAndWire());
            assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L)))
                    .as("midnight ⇒ the sensor is dark")
                    .isZero();
        }

        @Test
        void roofedSensorStaysDarkEvenAtNoon() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            // Roof first, then sensor + wire: the wire's placement triggers the network recompute, at
            // which point the sensor reads the already-blocked sky (sky/time reactivity as a discrete
            // event is the documented follow-on; today the sensor reads sky at recompute time).
            List<ActionEnvelope> roofed = List.of(
                    TestFixtures.envelope(region, 0L, 1L,
                            TestFixtures.place(new NBlockPos(64, 65, 64), FlatWorldRules.STONE)),
                    TestFixtures.envelope(region, 0L, 2L,
                            TestFixtures.place(new NBlockPos(64, 64, 64), FlatWorldRules.DAYLIGHT_SENSOR)),
                    TestFixtures.envelope(region, 0L, 3L,
                            TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.WIRE_0)));
            RegionExecutionResult result = executeAt(base, 6_000L, roofed);
            assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L)))
                    .as("an opaque block above the sensor blocks the sky even at noon")
                    .isZero();
        }
    }

    /**
     * Task 13 increment 7 (L-26): pistons — the {@code BlockEventEntry} two-phase consumer. Motion
     * is scheduled as a hashed pending event and fired next tick with a fire-time power re-check;
     * every failure mode (push limit, immovable block, region border) fails CLOSED and every
     * assertion runs through the full engine path, so it is also a root assertion.
     */
    @Nested
    final class PistonRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 12345L);
        }


        private static RegionSnapshot advance(
                RegionSnapshot base, RegionExecutionResult result, long tick) {
            return dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), tick);
        }

        /** Powered east-facing piston at (1,64,0) with {@code stones} in its push line. */
        private List<ActionEnvelope> poweredPistonWithLine(int stones) {
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
            for (int x = 0; x < stones; x++) {
                actions.add(TestFixtures.envelope(region, 0L, seq++,
                        TestFixtures.place(new NBlockPos(2 + x, 64, 0), FlatWorldRules.STONE)));
            }
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0),
                            FlatWorldRules.PISTON_RETRACTED_BASE + 3)));
            return actions;
        }

        @Test
        void poweredPistonExtendsAndPushesTheLine() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = poweredPistonWithLine(2);

            RegionExecutionResult result = executeTicks(base, actions, 1);
            RegionSnapshot advanced = advance(base, result, 1L);
            assertThat(result.stats().actionsRejected()).isZero();
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(1, 64, 0)))
                    .as("the base commits to its extended state")
                    .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(2, 64, 0)))
                    .as("the head occupies the front cell")
                    .isEqualTo(FlatWorldRules.PISTON_HEAD_BASE + 3);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(3, 64, 0))).isEqualTo(FlatWorldRules.STONE);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(4, 64, 0)))
                    .as("the whole line shifted one cell east")
                    .isEqualTo(FlatWorldRules.STONE);

            // Replica determinism: the motion is driven by hashed pending events.
            assertThat(executeTicks(base, actions, 1).resultingRoot())
                    .isEqualTo(result.resultingRoot());
        }

        @Test
        void depoweredPistonRetractsItsHeadWithoutPullingBlocks() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot extended = advance(
                    base, executeTicks(base, poweredPistonWithLine(1), 1), 1L);
            RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                    0, extended.chunks(), extended.entities(),
                    extended.scheduledTicks(), extended.blockEvents(), extended.bodyVersion());

            RegionExecutionResult broken = executeTicks(rebased, List.of(
                    TestFixtures.envelope(region, 0L, 50L,
                            new BreakBlockAction(new NBlockPos(0, 64, 0)))), 1);
            RegionSnapshot retracted = advance(rebased, broken, 1L);
            assertThat(EngineFixtures.blockAt(retracted, new NBlockPos(1, 64, 0)))
                    .as("losing power retracts the base")
                    .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
            assertThat(EngineFixtures.blockAt(retracted, new NBlockPos(2, 64, 0)))
                    .as("the head cell empties")
                    .isEqualTo(FlatWorldRules.AIR);
            assertThat(EngineFixtures.blockAt(retracted, new NBlockPos(3, 64, 0)))
                    .as("the non-sticky MVP pulls nothing back")
                    .isEqualTo(FlatWorldRules.STONE);
        }

        @Test
        void pushLimitOfTwelveFailsClosed() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionSnapshot atLimit = advance(
                    base, executeTicks(base, poweredPistonWithLine(12), 1), 1L);
            assertThat(EngineFixtures.blockAt(atLimit, new NBlockPos(1, 64, 0)))
                    .as("exactly 12 blocks still move")
                    .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);

            RegionSnapshot overLimit = advance(
                    base, executeTicks(base, poweredPistonWithLine(13), 1), 1L);
            assertThat(EngineFixtures.blockAt(overLimit, new NBlockPos(1, 64, 0)))
                    .as("a 13-block line refuses to move — the piston stays retracted")
                    .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
            assertThat(EngineFixtures.blockAt(overLimit, new NBlockPos(2, 64, 0)))
                    .isEqualTo(FlatWorldRules.STONE);
        }

        @Test
        void redstoneComponentsInTheLineAreImmovable() {
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.STONE)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(3, 64, 0), FlatWorldRules.LEVER_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0),
                            FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

            RegionSnapshot advanced = advance(base, executeTicks(base, actions, 1), 1L);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(1, 64, 0)))
                    .as("a redstone component in the line blocks the motion entirely")
                    .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
            assertThat(EngineFixtures.blockAt(advanced, new NBlockPos(3, 64, 0)))
                    .isEqualTo(FlatWorldRules.LEVER_OFF);
        }

        @Test
        void pistonMotionSurvivesADeltaBoundaryMidFlight() {
            // Power arrives through a repeater whose flip fires in the LAST tick of the batch:
            // the piston's motion event is enqueued AFTER that tick's block-event drain, so the
            // pending BlockEventEntry must cross the delta boundary as hashed state and fire in
            // the NEXT batch.
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            List<ActionEnvelope> actions = new ArrayList<>();
            long seq = 1;
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.LEVER_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.REPEATER_EAST_OFF)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(3, 64, 0), FlatWorldRules.WIRE_0)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(4, 64, 0),
                            FlatWorldRules.PISTON_RETRACTED_BASE + 3)));
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    new dev.nodera.core.action.InteractBlockAction(new NBlockPos(0, 64, 0))));

            RegionExecutionResult placed = executeTicks(base, actions, 1);
            RegionSnapshot mid = advance(base, placed, 1L);
            assertThat(mid.blockEvents())
                    .as("the pending motion IS the snapshot state at the boundary")
                    .hasSize(1);
            assertThat(EngineFixtures.blockAt(mid, new NBlockPos(4, 64, 0)))
                    .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);

            RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                    0, mid.chunks(), mid.entities(),
                    mid.scheduledTicks(), mid.blockEvents(), mid.bodyVersion());
            RegionSnapshot fired = advance(rebased, executeTicks(rebased, List.of(), 1), 1L);
            assertThat(EngineFixtures.blockAt(fired, new NBlockPos(4, 64, 0)))
                    .as("the carried event fires in the next batch")
                    .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
            assertThat(fired.blockEvents()).isEmpty();
        }
    }

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
    @Nested
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
                    dev.nodera.simulation.entity.MobState.fresh(20, 20).encode());
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
            assertThat(EngineFixtures.blockAt(afterEmpty, new NBlockPos(5, 64, 0)))
                    .as("nothing standing there ⇒ the plate stays up")
                    .isEqualTo(FlatWorldRules.PRESSURE_PLATE_OFF);
            assertThat(EngineFixtures.blockAt(afterEmpty, new NBlockPos(6, 64, 0))).isEqualTo(FlatWorldRules.WIRE_0);

            RegionSnapshot occupied = withEntities(List.of(standingAt(1L, 5, 64, 0)));
            RegionSnapshot afterStand =
                    advance(occupied, executeTicks(occupied, plateAndWire(), 2), 2L);
            assertThat(EngineFixtures.blockAt(afterStand, new NBlockPos(5, 64, 0)))
                    .isEqualTo(FlatWorldRules.PRESSURE_PLATE_ON);
            assertThat(EngineFixtures.blockAt(afterStand, new NBlockPos(6, 64, 0)))
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
            assertThat(EngineFixtures.blockAt(after, new NBlockPos(5, 64, 0)))
                    .isEqualTo(FlatWorldRules.PRESSURE_PLATE_OFF);
        }

        @Test
        void thePlateStaysDownWhileOccupiedAndTheReleaseIsHashedState() {
            RegionSnapshot base = withEntities(List.of(standingAt(1L, 5, 64, 0)));
            int ticks = PressurePlateRules.PLATE_RELEASE_TICKS * 3;
            RegionExecutionResult result = executeTicks(base, plateAndWire(), ticks);
            RegionSnapshot after = advance(base, result, ticks);

            assertThat(EngineFixtures.blockAt(after, new NBlockPos(5, 64, 0)))
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
                assertThat(EngineFixtures.blockAt(after, new NBlockPos(2, 64, 0)))
                        .as("the head occupies the front cell (family-aware ids)")
                        .isEqualTo(expectedHead);
                assertThat(EngineFixtures.blockAt(after, new NBlockPos(3, 64, 0)))
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
                assertThat(EngineFixtures.blockAt(after, new NBlockPos(1, 64, 0)))
                        .as("both families retract")
                        .isEqualTo(retractedId);
                assertThat(EngineFixtures.blockAt(after, new NBlockPos(2, 64, 0)))
                        .as(sticky ? "sticky pulls the stone into the vacated head cell"
                                : "a plain piston pulls nothing back")
                        .isEqualTo(sticky ? FlatWorldRules.STONE : FlatWorldRules.AIR);
                assertThat(EngineFixtures.blockAt(after, new NBlockPos(3, 64, 0)))
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
}
