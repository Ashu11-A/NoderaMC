package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
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
 * L-11's production contract, driven end to end without a running game.
 *
 * <p>The engine has been able to keep a player's inventory in the validated root since Task 16 and
 * nothing on the production path ever registered a {@code PLAYER} entity, so every pickup took the
 * one-way {@code InventoryCredit} branch and every movement step was rejected
 * {@code ENTITY_NOT_FOUND}. Registering one is only safe together with the inventory transition:
 * the moment the entity exists the engine stops emitting the credit the mod mirrors back into the
 * real inventory, so a naive empty registration deletes what a player picks up.
 *
 * <p>These tests hold both directions at once — the seed carries what the player already had, and
 * every committed gain is exactly what the live view hands back to vanilla — including across a
 * logout/login, where vanilla is the durable store and the root is re-seeded from it.
 */
final class PlayerRootRegistrationTest {

    private static final int DIAMOND = 42;
    private static final int TORCH = 7;

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    /** A stand-in for the player's REAL vanilla inventory: what the mod seeds from and delivers to. */
    private static final class VanillaInventory {
        private final List<ItemSlot> slots = new ArrayList<>();

        VanillaInventory() {
            for (int i = 0; i < PlayerRules.PLAYER_SLOTS; i++) {
                slots.add(ItemSlot.EMPTY);
            }
        }

        VanillaInventory holding(int slot, int itemStackId, int count) {
            slots.set(slot, new ItemSlot(itemStackId, count));
            return this;
        }

        /** Vanilla's own "add": stack onto a matching slot, else the first empty one. */
        void add(int itemStackId, int count) {
            for (int i = 0; i < slots.size(); i++) {
                ItemSlot s = slots.get(i);
                if (!s.isEmpty() && s.itemStackId() == itemStackId && s.count() + count <= 255) {
                    slots.set(i, new ItemSlot(itemStackId, s.count() + count));
                    return;
                }
            }
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).isEmpty()) {
                    slots.set(i, new ItemSlot(itemStackId, count));
                    return;
                }
            }
            throw new IllegalStateException("vanilla inventory full");
        }

        int total(int itemStackId) {
            return slots.stream()
                    .filter(s -> !s.isEmpty() && s.itemStackId() == itemStackId)
                    .mapToInt(ItemSlot::count).sum();
        }

        List<ItemSlot> snapshot() {
            return List.copyOf(slots);
        }
    }

    private RegionExecutionResult executeTicks(RegionSnapshot base, List<ActionEnvelope> actions) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, 99999L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    /** Exactly what {@code LiveEntityLaneRuntime.registerPlayer} builds, minus Minecraft. */
    private PersistedEntityState register(NodeId owner, VanillaInventory vanilla, int seq) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, seq),
                EntityKind.PLAYER, PlayerRules.PLAYER_TYPE_ID,
                FixedVec3.fromExternal(64.5, 64.5, 64.5), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN,
                PlayerRootRegistration.seedPayload(
                        owner, PlayerRules.PLAYER_MAX_HEALTH, PlayerRules.PLAYER_MAX_HEALTH,
                        vanilla.snapshot()));
    }

    private PersistedEntityState item(int seq, int itemId, int count) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, seq),
                EntityKind.ITEM, itemId,
                FixedVec3.fromExternal(64.5, 64.5, 64.5), FixedVec3.ZERO,
                0, ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(itemId, count));
    }

    private RegionSnapshot snapshotOf(List<PersistedEntityState> entities) {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, air.chunks(), entities);
    }

    @Test
    void registrationSeedsTheRootFromTheInventoryThePlayerIsActuallyHolding() {
        NodeId owner = TestFixtures.envelope(
                region, 0L, 1L, new PickupItemAction(
                        NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 9))).actor();
        VanillaInventory vanilla = new VanillaInventory()
                .holding(0, DIAMOND, 12).holding(5, TORCH, 64);

        PlayerRules.PlayerState seeded =
                PlayerRules.decode(register(owner, vanilla, 1).payload());

        assertThat(seeded.owner()).isEqualTo(owner);
        assertThat(seeded.inventory()).hasSize(PlayerRules.PLAYER_SLOTS);
        assertThat(seeded.inventory().get(0))
                .as("an empty seed would make everything the player carries invisible to the lane")
                .isEqualTo(new ItemSlot(DIAMOND, 12));
        assertThat(seeded.inventory().get(5)).isEqualTo(new ItemSlot(TORCH, 64));
        assertThat(PlayerRootRegistration.gains(null, register(owner, vanilla, 1).payload()))
                .as("a seed is not a gain — projecting it would duplicate the inventory on login")
                .isEmpty();
    }

    @Test
    void aPickupWithAPlayerRegisteredStillReachesTheRealVanillaInventory() {
        NodeId owner = TestFixtures.envelope(
                region, 0L, 1L, new PickupItemAction(
                        NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 9))).actor();
        VanillaInventory vanilla = new VanillaInventory().holding(0, DIAMOND, 12);

        PersistedEntityState player = register(owner, vanilla, 1);
        PersistedEntityState dropped = item(2, DIAMOND, 3);
        RegionSnapshot base = snapshotOf(List.of(player, dropped));

        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L, new PickupItemAction(dropped.id()))));

        assertThat(result.delta().inventoryCredits())
                .as("with a player root registered the credit stopgap is bypassed")
                .isEmpty();
        RegionSnapshot settled =
                dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L);
        PersistedEntityState after = PlayerRules.findPlayer(settled.entities(), owner);
        assertThat(after).isNotNull();

        // The mod's projection half: whatever the root gained is handed to the real inventory.
        List<PlayerRootRegistration.Gain> gains =
                PlayerRootRegistration.gains(player.payload(), after.payload());
        assertThat(gains).containsExactly(new PlayerRootRegistration.Gain(DIAMOND, 3));
        gains.forEach(g -> vanilla.add(g.itemStackId(), g.count()));

        assertThat(vanilla.total(DIAMOND))
                .as("the picked-up stack is in the player's hands, not stranded in the root")
                .isEqualTo(15);
        assertThat(PlayerRules.decode(after.payload()).inventory().stream()
                .filter(s -> !s.isEmpty() && s.itemStackId() == DIAMOND)
                .mapToInt(ItemSlot::count).sum())
                .as("and committed exactly once in the validated root — no dupe, no loss")
                .isEqualTo(15);
    }

    @Test
    void aLogoutAndLoginRoundTripsTheInventoryWithoutLosingOrDuplicatingAnything() {
        NodeId owner = TestFixtures.envelope(
                region, 0L, 1L, new PickupItemAction(
                        NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 9))).actor();
        VanillaInventory vanilla = new VanillaInventory().holding(0, DIAMOND, 12);

        // Session one: register, pick something up, project the gain back into vanilla.
        PersistedEntityState player = register(owner, vanilla, 1);
        PersistedEntityState dropped = item(2, DIAMOND, 3);
        RegionSnapshot base = snapshotOf(List.of(player, dropped));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L, new PickupItemAction(dropped.id()))));
        RegionSnapshot settled =
                dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L);
        PersistedEntityState committed = PlayerRules.findPlayer(settled.entities(), owner);
        PlayerRootRegistration.gains(player.payload(), committed.payload())
                .forEach(g -> vanilla.add(g.itemStackId(), g.count()));

        // Logout: the root presence is dropped. Vanilla is the durable store.
        assertThat(vanilla.total(DIAMOND)).isEqualTo(15);

        // Login: registration re-seeds the root from that same vanilla inventory.
        Bytes reSeeded = register(owner, vanilla, 3).payload();
        assertThat(PlayerRules.decode(reSeeded).inventory().stream()
                .filter(s -> !s.isEmpty() && s.itemStackId() == DIAMOND)
                .mapToInt(ItemSlot::count).sum())
                .as("the round trip neither lost the pickup nor counted it twice")
                .isEqualTo(15);
        assertThat(PlayerRootRegistration.gains(null, reSeeded))
                .as("re-seeding on login owes vanilla nothing — it came FROM vanilla")
                .isEmpty();
    }

    @Test
    void onlyGainsAreProjected_lossesBelongToVanillaAlready() {
        NodeId owner = TestFixtures.envelope(
                region, 0L, 1L, new PickupItemAction(
                        NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 9))).actor();
        Bytes before = PlayerRootRegistration.seedPayload(
                owner, PlayerRules.PLAYER_MAX_HEALTH, PlayerRules.PLAYER_MAX_HEALTH,
                new VanillaInventory().holding(0, DIAMOND, 12).snapshot());
        Bytes after = PlayerRootRegistration.seedPayload(
                owner, PlayerRules.PLAYER_MAX_HEALTH, PlayerRules.PLAYER_MAX_HEALTH,
                new VanillaInventory().holding(0, DIAMOND, 4).snapshot());

        assertThat(PlayerRootRegistration.gains(before, after))
                .as("a drop already left the real inventory; re-taking it would delete items")
                .isEmpty();
        assertThat(PlayerRootRegistration.gains(after, before))
                .containsExactly(new PlayerRootRegistration.Gain(DIAMOND, 8));
    }

    @Test
    void aShortOrOversizedVanillaInventoryStillSeedsAStructuralSlotTable() {
        NodeId owner = TestFixtures.envelope(
                region, 0L, 1L, new PickupItemAction(
                        NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 9))).actor();

        assertThat(PlayerRootRegistration.seedSlots(List.of(new ItemSlot(DIAMOND, 1))))
                .hasSize(PlayerRules.PLAYER_SLOTS)
                .startsWith(new ItemSlot(DIAMOND, 1));
        assertThat(PlayerRootRegistration.seedSlots(null)).hasSize(PlayerRules.PLAYER_SLOTS);
        assertThat(PlayerRules.decode(PlayerRootRegistration.seedPayload(
                owner, 20, 20, PlayerRootRegistration.seedSlots(null))).inventory())
                .hasSize(PlayerRules.PLAYER_SLOTS);
    }
}
