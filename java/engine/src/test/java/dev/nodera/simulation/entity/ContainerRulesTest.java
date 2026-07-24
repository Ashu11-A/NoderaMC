package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.ContainerAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ContainerEntry;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 / L-10 (container-lane increment 2): the CHEST block + validated deposit/withdraw
 * through the action lane + break-drop. A modified client can neither conjure items into a chest
 * nor pull out what is not there; a withdraw credits inventory exactly once through the pickup
 * lane; a broken chest spills validated ITEM entities — replica-identical everywhere.
 */
final class ContainerRulesTest {

    private static final NBlockPos CHEST_POS = new NBlockPos(64, 64, 64);
    private static final FixedVec3 NEAR = FixedVec3.fromExternal(63.5, 64.5, 64.5);

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        long from = base.tick();
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), from, from + tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), from, from + tickCount, 777L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    /** A base world whose chest block already stands, with {@code stack} in slot 0 (or empty). */
    private RegionSnapshot chestWorld(ItemSlot stack) {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot withChest = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                air,
                executeTicks(air, List.of(TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(CHEST_POS, FlatWorldRules.CHEST))), 1).delta(),
                1L);
        if (stack.isEmpty()) {
            return withChest;
        }
        List<ItemSlot> slots = new ArrayList<>();
        for (int i = 0; i < ContainerRules.CHEST_SLOTS; i++) {
            slots.add(ItemSlot.EMPTY);
        }
        slots.set(0, stack);
        return new RegionSnapshot(region, withChest.version(), withChest.tick(),
                withChest.chunks(), withChest.entities(), List.of(), List.of(),
                List.of(new ContainerEntry(CHEST_POS, slots)),
                RegionSnapshot.CONTAINER_ENCODING_VERSION);
    }

    private static ContainerAction deposit(int itemId, int count) {
        return new ContainerAction(CHEST_POS, NEAR, ContainerAction.Mode.DEPOSIT, 0, itemId, count);
    }

    private static ContainerAction withdraw(int itemId, int count) {
        return new ContainerAction(CHEST_POS, NEAR, ContainerAction.Mode.WITHDRAW, 0, itemId, count);
    }

    @Test
    void depositEntersTheRootDeterministically() {
        RegionSnapshot base = chestWorld(ItemSlot.EMPTY);
        List<ActionEnvelope> actions = List.of(
                TestFixtures.envelope(region, 1L, 2L, deposit(42, 5)));

        RegionExecutionResult first = executeTicks(base, actions, 1);
        RegionExecutionResult second = executeTicks(base, actions, 1);
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 2L);
        assertThat(settled.containers()).hasSize(1);
        assertThat(settled.containers().get(0).slots().get(0))
                .as("the deposit landed in slot 0 of the committed root")
                .isEqualTo(new ItemSlot(42, 5));
    }

    @Test
    void withdrawEmptiesTheSlotAndCreditsInventoryThroughTheDelta() {
        RegionSnapshot base = chestWorld(new ItemSlot(42, 5));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 1L, 2L, withdraw(42, 5))), 1);

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 2L);
        assertThat(settled.containers())
                .as("the fully-emptied chest leaves the sparse table")
                .isEmpty();
        assertThat(result.delta().inventoryCredits())
                .as("the withdraw rides the same replay-safe credit lane as pickup")
                .hasSize(1);
        assertThat(result.delta().inventoryCredits().get(0).itemStackId()).isEqualTo(42);
        assertThat(result.delta().inventoryCredits().get(0).count()).isEqualTo(5);
    }

    @Test
    void overdraftAndWrongItemWithdrawalsAreRejected() {
        RegionSnapshot base = chestWorld(new ItemSlot(42, 5));
        RegionExecutionResult overdraft = executeTicks(base,
                List.of(TestFixtures.envelope(region, 1L, 2L, withdraw(42, 6))), 1);
        RegionSnapshot afterOverdraft = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, overdraft.delta(), 2L);
        assertThat(afterOverdraft.containers().get(0).slots().get(0))
                .as("you cannot pull out more than the root holds")
                .isEqualTo(new ItemSlot(42, 5));

        RegionExecutionResult wrongItem = executeTicks(base,
                List.of(TestFixtures.envelope(region, 1L, 2L, withdraw(7, 1))), 1);
        RegionSnapshot afterWrong = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, wrongItem.delta(), 2L);
        assertThat(afterWrong.containers().get(0).slots().get(0))
                .as("you cannot withdraw an item the slot does not hold")
                .isEqualTo(new ItemSlot(42, 5));
        assertThat(wrongItem.delta().inventoryCredits()).isEmpty();
    }

    @Test
    void occupiedSlotRefusesAForeignDepositAndOutOfReachIsRejected() {
        RegionSnapshot base = chestWorld(new ItemSlot(42, 5));
        RegionSnapshot afterForeign = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base,
                executeTicks(base, List.of(TestFixtures.envelope(region, 1L, 2L,
                        deposit(7, 1))), 1).delta(),
                2L);
        assertThat(afterForeign.containers().get(0).slots().get(0))
                .as("a different item cannot stack onto an occupied slot")
                .isEqualTo(new ItemSlot(42, 5));

        ContainerAction tooFar = new ContainerAction(CHEST_POS,
                FixedVec3.fromExternal(50.5, 64.5, 64.5),
                ContainerAction.Mode.DEPOSIT, 0, 9, 1);
        RegionSnapshot afterFar = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base,
                executeTicks(base, List.of(TestFixtures.envelope(region, 1L, 2L, tooFar)), 1)
                        .delta(),
                2L);
        assertThat(afterFar.containers().get(0).slots().get(0))
                .as("a 14-block 'reach' is rejected by validation")
                .isEqualTo(new ItemSlot(42, 5));
    }

    @Test
    void hopperDrainsTheChestAboveIntoTheChestBelowDeterministically() {
        // Column: chest(66) → hopper(65) → chest(64); the top chest holds 3 items in slot 0.
        NBlockPos top = new NBlockPos(70, 66, 70);
        NBlockPos mid = new NBlockPos(70, 65, 70);
        NBlockPos bottom = new NBlockPos(70, 64, 70);
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot built = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                air,
                executeTicks(air, List.of(
                        TestFixtures.envelope(region, 0L, 1L,
                                TestFixtures.place(bottom, FlatWorldRules.CHEST)),
                        TestFixtures.envelope(region, 0L, 2L,
                                TestFixtures.place(top, FlatWorldRules.CHEST)),
                        TestFixtures.envelope(region, 0L, 3L,
                                TestFixtures.place(mid, FlatWorldRules.HOPPER))), 1).delta(),
                1L);
        List<ItemSlot> slots = new ArrayList<>();
        for (int i = 0; i < ContainerRules.CHEST_SLOTS; i++) {
            slots.add(ItemSlot.EMPTY);
        }
        slots.set(0, new ItemSlot(42, 3));
        RegionSnapshot base = new RegionSnapshot(region, built.version(), built.tick(),
                built.chunks(), built.entities(), built.scheduledTicks(), built.blockEvents(),
                List.of(new ContainerEntry(top, slots)),
                RegionSnapshot.CONTAINER_ENCODING_VERSION);

        // 5 cycles' worth of ticks: pull tick N, push tick N+8 — 3 items need ~4 cycles.
        RegionExecutionResult first = executeTicks(base, List.of(), 50);
        RegionExecutionResult second = executeTicks(base, List.of(), 50);
        assertThat(second.resultingRoot())
                .as("the 8-tick hopper machine is replica-identical")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), base.tick() + 50);
        assertThat(settled.containers())
                .as("everything drained to the bottom chest (top + hopper entries left the table)")
                .hasSize(1);
        ContainerEntry drained = settled.containers().get(0);
        assertThat(drained.pos()).isEqualTo(bottom);
        assertThat(drained.slots().get(0))
                .as("all 3 items arrived, one per cycle")
                .isEqualTo(new ItemSlot(42, 3));
    }

    @Test
    void breakingAChestSpillsItsContentsAsValidatedItems() {
        RegionSnapshot base = chestWorld(new ItemSlot(42, 5));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 1L, 2L,
                        new dev.nodera.core.action.BreakBlockAction(CHEST_POS))), 1);
        RegionExecutionResult again = executeTicks(base,
                List.of(TestFixtures.envelope(region, 1L, 2L,
                        new dev.nodera.core.action.BreakBlockAction(CHEST_POS))), 1);
        assertThat(again.resultingRoot())
                .as("the spill is replica-identical")
                .isEqualTo(result.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 2L);
        assertThat(settled.containers()).isEmpty();
        List<dev.nodera.core.state.PersistedEntityState> items = settled.entities().stream()
                .filter(e -> e.kind() == EntityKind.ITEM).toList();
        assertThat(items).hasSize(1);
        assertThat(ItemEntityRules.decodePayload(items.get(0).payload()))
                .as("the spilled stack carries the chest's exact contents")
                .isEqualTo(new ItemEntityRules.ItemStack(42, 5));
    }
}
