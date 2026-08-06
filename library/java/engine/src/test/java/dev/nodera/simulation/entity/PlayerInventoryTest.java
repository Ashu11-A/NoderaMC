package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 / L-11: player inventory is VALIDATED ROOT STATE. A registered PLAYER entity's payload
 * carries owner + the 36-slot inventory; pickups land there (not in the one-way credit lane), a
 * cross-dimension/region hand-off carries the whole inventory through the dupe-proof transfer
 * pipeline (removed at source, materialised exactly once at target), and the credit stopgap
 * survives only for actors without a registered player entity.
 */
final class PlayerInventoryTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 99999L);
    }

    private static PersistedEntityState playerEntity(RegionId r, NodeId owner, int seq,
                                                     double x, double y, double z) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.PLAYER, PlayerRules.PLAYER_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN,
                PlayerRules.emptyInventoryPayload(owner));
    }

    private static PersistedEntityState itemEntity(RegionId r, int seq, double x, double y,
                                                   double z, int itemId, int count) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.ITEM, itemId,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(itemId, count));
    }

    @Test
    void pickupLandsInThePlayerRootInventoryInsteadOfACredit() {
        NodeId actor = TestFixtures.envelope(region, 0L, 1L,
                new PickupItemAction(NetworkEntityId.allocate(
                        region, SnapshotVersion.INITIAL, 9))).actor();
        PersistedEntityState player = playerEntity(region, actor, 1, 64.5, 64.5, 64.5);
        PersistedEntityState item = itemEntity(region, 2, 64.5, 64.5, 64.5, 42, 3);
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(player, item));

        List<ActionEnvelope> pickup = List.of(TestFixtures.envelope(region, 0L, 1L,
                new PickupItemAction(item.id())));
        RegionExecutionResult first = executeTicks(base, pickup, 1);
        RegionExecutionResult second = executeTicks(base, pickup, 1);
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

        assertThat(first.delta().inventoryCredits())
                .as("the credit stopgap is bypassed — the inventory is root state now")
                .isEmpty();
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 1L);
        PersistedEntityState after = PlayerRules.findPlayer(settled.entities(), actor);
        assertThat(after).isNotNull();
        assertThat(PlayerRules.decode(after.payload()).inventory().get(0))
                .as("the stack landed in the player's committed inventory")
                .isEqualTo(new ItemSlot(42, 3));
        assertThat(settled.entities().stream().filter(e -> e.kind() == EntityKind.ITEM))
                .as("the item entity left the world exactly once")
                .isEmpty();
    }

    @Test
    void withoutARegisteredPlayerEntityTheCreditStopgapStillApplies() {
        PersistedEntityState item = itemEntity(region, 2, 64.5, 64.5, 64.5, 42, 3);
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(item));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L,
                        new PickupItemAction(item.id()))), 1);
        assertThat(result.delta().inventoryCredits())
                .as("no player entity registered ⇒ the legacy one-way credit lane")
                .hasSize(1);
    }

    @Test
    void meleeOnAPlayerWoundsTheCommittedHealthAndAKillDropsTheInventory() {
        NodeId actor = TestFixtures.envelope(region, 0L, 1L,
                new PickupItemAction(NetworkEntityId.allocate(
                        region, SnapshotVersion.INITIAL, 9))).actor();
        PersistedEntityState victim = playerEntity(region, actor, 1, 64.5, 64.5, 64.5);
        // Load the victim's inventory and drop the health to one melee blow from death.
        PlayerRules.PlayerState decoded = PlayerRules.decode(victim.payload());
        java.util.List<ItemSlot> slots = new java.util.ArrayList<>(decoded.inventory());
        slots.set(0, new ItemSlot(42, 9));
        victim = new PersistedEntityState(victim.id(), victim.kind(), victim.typeId(),
                victim.pos(), victim.vel(), victim.ageTicks(), victim.despawnTick(),
                PlayerRules.payload(new PlayerRules.PlayerState(
                        actor, MobCombatRules.MELEE_DAMAGE, PlayerRules.PLAYER_MAX_HEALTH, slots)));
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(victim));

        List<ActionEnvelope> strike = List.of(TestFixtures.envelope(region, 0L, 1L,
                new dev.nodera.core.action.AttackEntityAction(
                        victim.id(), FixedVec3.fromExternal(63.5, 64.5, 64.5))));
        RegionExecutionResult first = executeTicks(base, strike, 1);
        RegionExecutionResult second = executeTicks(base, strike, 1);
        assertThat(second.resultingRoot())
                .as("PvP damage is replica-identical committed state")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 1L);
        assertThat(PlayerRules.findPlayer(settled.entities(), actor))
                .as("health hit zero: the player's root presence is GONE (respawn = live lane)")
                .isNull();
        List<PersistedEntityState> drops = settled.entities().stream()
                .filter(e -> e.kind() == EntityKind.ITEM).toList();
        assertThat(drops)
                .as("death spills the committed inventory — nothing dupes, nothing vanishes")
                .hasSize(1);
        assertThat(ItemEntityRules.decodePayload(drops.get(0).payload()))
                .isEqualTo(new ItemEntityRules.ItemStack(42, 9));
    }

    @Test
    void aSurvivablePlayerBlowJustDecrementsCommittedHealth() {
        NodeId actor = TestFixtures.envelope(region, 0L, 1L,
                new PickupItemAction(NetworkEntityId.allocate(
                        region, SnapshotVersion.INITIAL, 9))).actor();
        PersistedEntityState victim = playerEntity(region, actor, 1, 64.5, 64.5, 64.5);
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(victim));
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base,
                executeTicks(base, List.of(TestFixtures.envelope(region, 0L, 1L,
                        new dev.nodera.core.action.AttackEntityAction(
                                victim.id(), FixedVec3.fromExternal(63.5, 64.5, 64.5)))), 1)
                        .delta(),
                1L);
        PersistedEntityState after = PlayerRules.findPlayer(settled.entities(), actor);
        assertThat(after).isNotNull();
        assertThat(PlayerRules.decode(after.payload()).health())
                .as("the strike took exactly MELEE_DAMAGE off the committed health")
                .isEqualTo(PlayerRules.PLAYER_MAX_HEALTH - MobCombatRules.MELEE_DAMAGE);
    }

    @Test
    void portalHandOffCarriesTheInventoryExactlyOnce() {
        NodeId actor = TestFixtures.envelope(region, 0L, 1L,
                new PickupItemAction(NetworkEntityId.allocate(
                        region, SnapshotVersion.INITIAL, 9))).actor();
        PersistedEntityState player = playerEntity(region, actor, 1, 80.5, 64.5, 80.5);
        // Pre-load the inventory: slot 0 holds the valuables that must not dupe.
        PlayerRules.PlayerState loaded = PlayerRules.decode(player.payload());
        java.util.List<ItemSlot> slots = new java.util.ArrayList<>(loaded.inventory());
        slots.set(0, new ItemSlot(42, 17));
        player = new PersistedEntityState(player.id(), player.kind(), player.typeId(),
                player.pos(), player.vel(), player.ageTicks(), player.despawnTick(),
                PlayerRules.payload(new PlayerRules.PlayerState(actor,
                        PlayerRules.PLAYER_MAX_HEALTH, PlayerRules.PLAYER_MAX_HEALTH, slots)));
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(player));

        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(80, 64, 80),
                                FlatWorldRules.NETHER_PORTAL))), 1);

        assertThat(result.delta().transferIntents()).hasSize(1);
        PersistedEntityState travelling = result.delta().transferIntents().get(0).targetState();
        assertThat(PlayerRules.decode(travelling.payload()).inventory().get(0))
                .as("the whole inventory rides the transfer intent")
                .isEqualTo(new ItemSlot(42, 17));
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 1L);
        assertThat(PlayerRules.findPlayer(settled.entities(), actor))
                .as("dupe-proof: the player (and inventory) left the source region")
                .isNull();
    }
}
