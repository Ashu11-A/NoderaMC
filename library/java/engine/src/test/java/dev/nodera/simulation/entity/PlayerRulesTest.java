package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.ContainerAction;
import dev.nodera.core.action.MovePlayerAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ContainerEntry;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
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
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The player: how one moves, what one carries, what one opens, and how one enters the entity lane
 * at all.
 *
 * <p>Four sibling classes over one subject. A player's inventory, the container it interacts with,
 * the movement that carries it there and the registration that made it exist are one object's
 * lifecycle, and they imported the same fifteen types to say so.
 */
final class PlayerRulesTest {

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
    @Nested
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
            return EngineFixtures.executeTicks(engine, region, base, actions, 1, 99999L);
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

    /**
     * Task 16 / L-11: player inventory is VALIDATED ROOT STATE. A registered PLAYER entity's payload
     * carries owner + the 36-slot inventory; pickups land there (not in the one-way credit lane), a
     * cross-dimension/region hand-off carries the whole inventory through the dupe-proof transfer
     * pipeline (removed at source, materialised exactly once at target), and the credit stopgap
     * survives only for actors without a registered player entity.
     */
    @Nested
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

    /**
     * Task 16 / L-10 (container-lane increment 2): the CHEST block + validated deposit/withdraw
     * through the action lane + break-drop. A modified client can neither conjure items into a chest
     * nor pull out what is not there; a withdraw credits inventory exactly once through the pickup
     * lane; a broken chest spills validated ITEM entities — replica-identical everywhere.
     */
    @Nested
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
        void comparatorEmitsTheChestFillLevelAndReactsToDepositsAndWithdrawals() {
            NBlockPos comparator = new NBlockPos(65, 64, 64);
            NBlockPos wirePos = new NBlockPos(66, 64, 64);
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionSnapshot built = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    air,
                    executeTicks(air, List.of(
                            TestFixtures.envelope(region, 0L, 1L,
                                    TestFixtures.place(CHEST_POS, FlatWorldRules.CHEST)),
                            TestFixtures.envelope(region, 0L, 2L,
                                    TestFixtures.place(comparator, FlatWorldRules.COMPARATOR_EAST)),
                            TestFixtures.envelope(region, 0L, 3L,
                                    TestFixtures.place(wirePos, FlatWorldRules.WIRE_0))), 1).delta(),
                    1L);
            int emptyWire = new dev.nodera.simulation.MutableRegionState(
                    built, dev.nodera.core.region.RegionBounds.of(region)).getBlock(wirePos);
            assertThat(dev.nodera.simulation.rules.RedstoneRules.wirePower(emptyWire))
                    .as("an empty chest emits nothing through the comparator")
                    .isZero();

            RegionSnapshot afterDeposit = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    built,
                    executeTicks(built, List.of(TestFixtures.envelope(region, 1L, 4L,
                            deposit(42, 5))), 1).delta(),
                    2L);
            int poweredWire = new dev.nodera.simulation.MutableRegionState(
                    afterDeposit, dev.nodera.core.region.RegionBounds.of(region)).getBlock(wirePos);
            assertThat(dev.nodera.simulation.rules.RedstoneRules.wirePower(poweredWire))
                    .as("the deposit re-settles the comparator's network to the fill signal")
                    .isEqualTo(ContainerRules.containerSignal(
                            afterDeposit.containers().get(0), ContainerRules.CHEST_SLOTS));

            RegionSnapshot afterWithdraw = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    afterDeposit,
                    executeTicks(afterDeposit, List.of(TestFixtures.envelope(region, 2L, 5L,
                            withdraw(42, 5))), 1).delta(),
                    3L);
            int drainedWire = new dev.nodera.simulation.MutableRegionState(
                    afterWithdraw, dev.nodera.core.region.RegionBounds.of(region)).getBlock(wirePos);
            assertThat(dev.nodera.simulation.rules.RedstoneRules.wirePower(drainedWire))
                    .as("emptying the chest drops the comparator output back to 0")
                    .isZero();
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

    /**
     * Task 16 / L-12: player movement is a committee-validated action. A legal step moves the
     * committed root presence; a speed/teleport cheat and a wall clip die in validation on every
     * honest replica; a border step becomes the dupe-proof cross-region transfer carrying the whole
     * player payload.
     */
    @Nested
    final class MovementRulesTest {

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);
        private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

        private RegionExecutionResult executeTicks(
                RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
            return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 313131L);
        }

        private static NodeId actor() {
            return TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 1L,
                    new PickupItemAction(NetworkEntityId.allocate(
                            TestFixtures.region(0, 0), SnapshotVersion.INITIAL, 9))).actor();
        }

        private static PersistedEntityState playerAt(RegionId r, NodeId owner, double x, double y,
                                                     double z) {
            return new PersistedEntityState(
                    NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, 1),
                    EntityKind.PLAYER, PlayerRules.PLAYER_TYPE_ID,
                    FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                    0, PersistedEntityState.NEVER_DESPAWN,
                    PlayerRules.emptyInventoryPayload(owner));
        }

        @Test
        void aLegalStepMovesTheCommittedPresenceDeterministically() {
            NodeId owner = actor();
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(playerAt(region, owner, 64.5, 64.5, 64.5)));
            List<ActionEnvelope> step = List.of(TestFixtures.envelope(region, 0L, 1L,
                    new MovePlayerAction(FixedVec3.fromExternal(65.25, 64.5, 64.5))));

            RegionExecutionResult first = executeTicks(base, step, 1);
            RegionExecutionResult second = executeTicks(base, step, 1);
            assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, first.delta(), 1L);
            assertThat(PlayerRules.findPlayer(settled.entities(), owner).pos().blockX())
                    .as("the committed presence moved")
                    .isEqualTo(65);
        }

        @Test
        void speedCheatsAndWallClipsDieInValidation() {
            NodeId owner = actor();
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(playerAt(region, owner, 64.5, 64.5, 64.5)));

            // Teleport: 10 blocks in one action.
            RegionSnapshot afterTeleport = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base,
                    executeTicks(base, List.of(TestFixtures.envelope(region, 0L, 1L,
                            new MovePlayerAction(FixedVec3.fromExternal(74.5, 64.5, 64.5)))), 1)
                            .delta(),
                    1L);
            assertThat(PlayerRules.findPlayer(afterTeleport.entities(), owner).pos().blockX())
                    .as("a 10-block 'step' is rejected — the presence never moved")
                    .isEqualTo(64);

            // Wall clip: destination cell is stone.
            RegionSnapshot walled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base,
                    executeTicks(base, List.of(TestFixtures.envelope(region, 0L, 1L,
                            TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.STONE))), 1)
                            .delta(),
                    1L);
            RegionSnapshot afterClip = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    walled,
                    new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                            FlatWorldRules.registryFingerprint(), hashes)
                            .execute(new RegionExecutionRequest(
                                    new RegionExecutionContext(region, RegionEpoch.INITIAL,
                                            walled.version(), 1, 2, 313131L,
                                            FlatWorldRules.RULES_VERSION,
                                            FlatWorldRules.registryFingerprint()),
                                    walled,
                                    new ActionBatch(region, RegionEpoch.INITIAL, walled.version(),
                                            1, 2, List.of(TestFixtures.envelope(region, 1L, 2L,
                                            new MovePlayerAction(
                                                    FixedVec3.fromExternal(65.5, 64.5, 64.5)))))))
                            .delta(),
                    2L);
            assertThat(PlayerRules.findPlayer(afterClip.entities(), owner).pos().blockX())
                    .as("clipping into stone is rejected")
                    .isEqualTo(64);
        }

        @Test
        void aBorderStepBecomesTheDupeProofCrossRegionTransfer() {
            NodeId owner = actor();
            RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
            // Region (0,0) owns x in [0,128); stand at the east edge and step over it.
            RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                    air.chunks(), List.of(playerAt(region, owner, 127.6, 64.5, 64.5)));
            RegionExecutionResult result = executeTicks(base,
                    List.of(TestFixtures.envelope(region, 0L, 1L,
                            new MovePlayerAction(FixedVec3.fromExternal(128.4, 64.5, 64.5)))), 1);

            assertThat(result.delta().transferIntents())
                    .as("the border step rides the transfer pipeline")
                    .hasSize(1);
            assertThat(result.delta().transferIntents().get(0).targetRegion().originChunkX())
                    .isEqualTo(8);
            RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                    base, result.delta(), 1L);
            assertThat(PlayerRules.findPlayer(settled.entities(), owner))
                    .as("dupe-proof: the presence left the source exactly once")
                    .isNull();
        }
    }
}
