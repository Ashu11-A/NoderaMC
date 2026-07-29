package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.MutableWorldView;
import dev.nodera.mod.common.entity.NetworkEntityIdAttachment;
import dev.nodera.peer.validation.InventoryCreditPersistence;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.entity.PlayerRootRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live Task-12 {@link MutableWorldView}: canonical snapshots remain exact fixed-point state while
 * vanilla entities are projections updated only after the canonical mutation scope commits.
 */
public final class ServerEntityWorldView implements MutableWorldView {

    private final InMemoryWorldView canonical = new InMemoryWorldView();
    private final Map<RegionId, ServerLevel> levels = new HashMap<>();
    private final Map<ProjectionKey, UUID> vanillaEntities = new HashMap<>();
    private final Map<String, InventoryCredit> pendingCredits = new LinkedHashMap<>();
    /** L-11: committed root inventory gains still owed to a real vanilla inventory. */
    private final List<PendingGain> pendingGains = new ArrayList<>();
    private final InventoryCreditPersistence creditPersistence;
    private List<Runnable> stagedEffects;
    /**
     * How a projection announces itself as the applier's own write. Without it the choke point
     * would classify every committed block this view writes as foreign and certify the lane's own
     * commits back to itself.
     */
    private java.util.function.Consumer<Runnable> applierScope = Runnable::run;

    public ServerEntityWorldView(InventoryCreditPersistence creditPersistence) {
        if (creditPersistence == null) {
            throw new IllegalArgumentException("credit persistence must not be null");
        }
        this.creditPersistence = creditPersistence;
        for (InventoryCredit credit : creditPersistence.retained()) {
            pendingCredits.put(creditKey(credit), credit);
        }
    }

    /**
     * Install the write-guard scope every block projection runs inside; {@code null} restores the
     * plain "just run it" behaviour used when nothing guards writes.
     */
    public void applierScope(java.util.function.Consumer<Runnable> scope) {
        this.applierScope = scope == null ? Runnable::run : scope;
    }

    /** Bind a live level to its canonical base snapshot before activating validation. */
    public void bind(ServerLevel level, RegionSnapshot snapshot) {
        if (level == null || snapshot == null
                || !MinecraftEntityAdapters.dimension(level).equals(snapshot.region().dimension())) {
            throw new IllegalArgumentException("level and snapshot dimension must agree");
        }
        levels.put(snapshot.region(), level);
        canonical.load(snapshot);
        for (Entity entity : level.getAllEntities()) {
            if (MinecraftEntityAdapters.region(entity).equals(snapshot.region())) {
                NetworkEntityIdAttachment.existing(entity).ifPresent(id -> vanillaEntities.put(
                        new ProjectionKey(snapshot.region(), id), entity.getUUID()));
            }
        }
    }

    @Override
    public boolean isRegionLoaded(RegionId region) {
        return levels.containsKey(region) && canonical.isRegionLoaded(region);
    }

    @Override
    public MutationScope beginMutation() {
        if (stagedEffects != null) {
            throw new IllegalStateException("nested live-world mutation is not supported");
        }
        MutationScope delegate = canonical.beginMutation();
        stagedEffects = new ArrayList<>();
        return new MutationScope() {
            private boolean committed;

            @Override
            public void commit() {
                if (committed) {
                    throw new IllegalStateException("mutation scope already committed");
                }
                delegate.commit();
                committed = true;
                List<Runnable> effects = List.copyOf(stagedEffects);
                stagedEffects = null;
                for (Runnable effect : effects) {
                    effect.run();
                }
            }

            @Override
            public void close() {
                try {
                    delegate.close();
                } finally {
                    stagedEffects = null;
                }
            }
        };
    }

    @Override
    public RegionSnapshot reExtract(RegionId region, SnapshotVersion version, long tick) {
        return canonical.reExtract(region, version, tick);
    }

    @Override
    public void setSnapshotBodyVersion(RegionId region, int bodyVersion) {
        canonical.setSnapshotBodyVersion(region, bodyVersion);
    }

    @Override
    public int getBlock(RegionId region, NBlockPos pos) {
        return canonical.getBlock(region, pos);
    }

    @Override
    public void setBlock(RegionId region, NBlockPos pos, int stateId) {
        int previous = canonical.getBlock(region, pos);
        canonical.setBlock(region, pos, stateId);
        if (previous == stateId) {
            return;
        }
        // The apply half of the block lane (minecraft Task 2 deliverable 3): a committed block
        // mutation has to become a block a player can see. Projections are staged exactly like item
        // projections and run after the canonical scope commits — the world is never written from a
        // mutation that then aborts. Outside a scope (snapshot load, recovery) canonical is the only
        // thing being rebuilt, so there is nothing to project.
        if (stagedEffects != null) {
            stagedEffects.add(() -> projectBlock(region, pos, stateId));
        }
    }

    /**
     * Write one committed palette state into the live world. An id the running game cannot express
     * — a resource pack or version skew the binding did not anticipate — is skipped with a log line
     * rather than guessed at: a wrong block is a divergence, a missing one is visible interference.
     */
    private void projectBlock(RegionId region, NBlockPos pos, int stateId) {
        ServerLevel level = levels.get(region);
        if (level == null) {
            return;
        }
        var state = dev.nodera.mod.server.shadow.PaletteMapper.stateOf(stateId);
        if (state.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger("NoderaBlockApply").warn(
                    "committed state id {} has no vanilla projection in this game — block at "
                            + "{},{},{} left as it was", stateId, pos.x(), pos.y(), pos.z());
            return;
        }
        applierScope.accept(() -> level.setBlock(
                new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z()), state.get(),
                net.minecraft.world.level.block.Block.UPDATE_ALL));
    }

    @Override
    public PersistedEntityState getEntity(RegionId region, NetworkEntityId id) {
        return canonical.getEntity(region, id);
    }

    @Override
    public void setEntity(RegionId region, PersistedEntityState entity) {
        requireScope();
        PersistedEntityState previous = canonical.getEntity(region, entity.id());
        canonical.setEntity(region, entity);
        if (entity.kind() == EntityKind.ITEM) {
            stagedEffects.add(() -> projectItem(region, entity));
            return;
        }
        if (entity.kind() == EntityKind.PLAYER) {
            // L-11's other direction. Once a PLAYER root exists the engine stops emitting the
            // one-way InventoryCredit for a pickup and puts the stack in the validated root
            // instead — so unless the root's gains are projected back, a picked-up item is
            // committed correctly and never reaches the player's hands.
            //
            // Registration comes in through captureExternal, not here, so `previous` is non-null
            // for every ENGINE-committed change and the seed is never mistaken for a gain.
            List<PlayerRootRegistration.Gain> gains = PlayerRootRegistration.gains(
                    previous != null && previous.kind() == EntityKind.PLAYER
                            ? previous.payload() : null,
                    entity.payload());
            if (!gains.isEmpty()) {
                NodeId owner = dev.nodera.simulation.entity.PlayerRules
                        .decode(entity.payload()).owner();
                stagedEffects.add(() -> projectPlayerGains(region, owner, gains));
            }
        }
    }

    /**
     * Hand the validated root's gains to the real player. Anything undeliverable right now — the
     * player logged out between commit and projection, or their vanilla inventory is full — is
     * queued on the same retry pump as an {@code InventoryCredit} rather than dropped, because the
     * gain has already been removed from the world as an item entity: forgetting it here is the
     * exact "picked-up items disappear" regression a naive registration causes.
     */
    private void projectPlayerGains(
            RegionId region, NodeId owner, List<PlayerRootRegistration.Gain> gains) {
        ServerLevel level = levels.get(region);
        if (level == null) {
            pendingGains.add(new PendingGain(owner, gains));
            return;
        }
        deliverGains(level.getServer(), new PendingGain(owner, gains));
    }

    /** @return true when every gain reached the player's real inventory. */
    private boolean deliverGains(MinecraftServer server, PendingGain pending) {
        ServerPlayer player = server.getPlayerList().getPlayer(pending.owner().value());
        if (player == null) {
            pendingGains.add(pending);
            return false;
        }
        List<PlayerRootRegistration.Gain> undelivered = new ArrayList<>();
        for (PlayerRootRegistration.Gain gain : pending.gains()) {
            ItemStack stack = new ItemStack(
                    BuiltInRegistries.ITEM.byId(gain.itemStackId()), gain.count());
            EntityCaptureBridge.get().materialize(() -> player.getInventory().add(stack));
            if (!stack.isEmpty()) {
                undelivered.add(new PlayerRootRegistration.Gain(
                        gain.itemStackId(), stack.getCount()));
            }
        }
        if (!undelivered.isEmpty()) {
            pendingGains.add(new PendingGain(pending.owner(), List.copyOf(undelivered)));
            return false;
        }
        return true;
    }

    /** One committed root gain still owed to a real vanilla inventory. */
    private record PendingGain(NodeId owner, List<PlayerRootRegistration.Gain> gains) {
    }

    @Override
    public void removeEntity(RegionId region, NetworkEntityId id) {
        requireScope();
        PersistedEntityState previous = canonical.getEntity(region, id);
        canonical.removeEntity(region, id);
        if (previous != null && previous.kind() == EntityKind.ITEM) {
            stagedEffects.add(() -> removeProjection(region, id));
        }
    }

    @Override
    public InventoryCredit getInventoryCredit(InventoryCredit credit) {
        return canonical.getInventoryCredit(credit);
    }

    @Override
    public void creditInventory(InventoryCredit credit) {
        requireScope();
        canonical.creditInventory(credit);
        stagedEffects.add(() -> queueOrDeliver(credit));
    }

    /** Retry credits whose player was offline or lacked enough inventory capacity. */
    public void retryPendingCredits(MinecraftServer server) {
        for (InventoryCredit credit : List.copyOf(pendingCredits.values())) {
            if (deliver(server, credit)) {
                pendingCredits.remove(creditKey(credit));
            }
        }
        // L-11: the root-inventory half of the same pump. A player who logged out between the
        // commit and the projection, or whose inventory was full, still gets what the committee
        // says is theirs.
        List<PendingGain> owed = List.copyOf(pendingGains);
        pendingGains.clear();
        for (PendingGain pending : owed) {
            deliverGains(server, pending);
        }
    }

    /** Mirror one vanilla-authoritative ghost change into canonical state before certification. */
    public void captureExternal(
            RegionId region, PersistedEntityState expected, PersistedEntityState replacement) {
        NetworkEntityId id = expected != null ? expected.id() : replacement.id();
        PersistedEntityState current = canonical.getEntity(region, id);
        if (!java.util.Objects.equals(current, expected)) {
            throw new IllegalStateException("external entity capture does not match canonical state");
        }
        if (replacement == null) {
            canonical.removeEntity(region, id);
        } else {
            canonical.setEntity(region, replacement);
        }
    }

    /** Atomically re-home one vanilla-authoritative ghost across canonical region tables. */
    public void captureExternalTransfer(
            RegionId source, RegionId target, PersistedEntityState expected,
            PersistedEntityState replacement) {
        try (MutationScope scope = canonical.beginMutation()) {
            if (!java.util.Objects.equals(
                    canonical.getEntity(source, expected.id()), expected)
                    || canonical.getEntity(target, replacement.id()) != null) {
                throw new IllegalStateException("ghost transfer canonical guards failed");
            }
            canonical.removeEntity(source, expected.id());
            canonical.setEntity(target, replacement);
            scope.commit();
        }
    }

    private void projectItem(RegionId region, PersistedEntityState state) {
        ServerLevel level = level(region);
        Entity existing = existing(region, state.id());
        ItemEntityRules.ItemStack payload = ItemEntityRules.decodePayload(state.payload());
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.byId(payload.itemStackId()), payload.count());
        Runnable projection = () -> {
            ItemEntity item;
            if (existing instanceof ItemEntity existingItem && !existingItem.isRemoved()) {
                item = existingItem;
                item.setItem(stack);
            } else {
                item = new ItemEntity(
                        level,
                        dev.nodera.core.state.FixedVec3.toExternal(state.pos().x()),
                        dev.nodera.core.state.FixedVec3.toExternal(state.pos().y()),
                        dev.nodera.core.state.FixedVec3.toExternal(state.pos().z()), stack);
                NetworkEntityIdAttachment.assign(item, state.id());
                if (!level.addFreshEntity(item)) {
                    throw new IllegalStateException("vanilla item projection could not be spawned");
                }
                vanillaEntities.put(new ProjectionKey(region, state.id()), item.getUUID());
            }
            item.setPos(
                    dev.nodera.core.state.FixedVec3.toExternal(state.pos().x()),
                    dev.nodera.core.state.FixedVec3.toExternal(state.pos().y()),
                    dev.nodera.core.state.FixedVec3.toExternal(state.pos().z()));
            item.setDeltaMovement(
                    dev.nodera.core.state.FixedVec3.toExternal(state.vel().x()),
                    dev.nodera.core.state.FixedVec3.toExternal(state.vel().y()),
                    dev.nodera.core.state.FixedVec3.toExternal(state.vel().z()));
        };
        EntityCaptureBridge.get().materialize(projection);
    }

    private void removeProjection(RegionId region, NetworkEntityId id) {
        Entity entity = existing(region, id);
        if (entity != null) {
            EntityCaptureBridge.get().materialize(entity::discard);
        }
        vanillaEntities.remove(new ProjectionKey(region, id));
    }

    private Entity existing(RegionId region, NetworkEntityId id) {
        UUID uuid = vanillaEntities.get(new ProjectionKey(region, id));
        return uuid == null ? null : level(region).getEntity(uuid);
    }

    private void queueOrDeliver(InventoryCredit credit) {
        creditPersistence.retain(credit);
        ServerLevel level = levels.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no live level bound"));
        if (!deliver(level.getServer(), credit)) {
            pendingCredits.putIfAbsent(creditKey(credit), credit);
        }
    }

    private static boolean deliver(MinecraftServer server, InventoryCredit credit) {
        ServerPlayer player = server.getPlayerList().getPlayer(credit.actor().value());
        if (player == null || NetworkEntityIdAttachment.hasApplied(player, credit)) {
            return player != null;
        }
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.byId(credit.itemStackId()), credit.count());
        if (inventoryCapacity(player, stack) < stack.getCount()) {
            return false;
        }
        if (!NetworkEntityIdAttachment.markApplied(player, credit)) {
            return true;
        }
        if (!player.getInventory().add(stack) || !stack.isEmpty()) {
            throw new IllegalStateException("capacity preflight disagreed with inventory add");
        }
        return true;
    }

    private static int inventoryCapacity(ServerPlayer player, ItemStack incoming) {
        int capacity = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack current = player.getInventory().getItem(slot);
            if (current.isEmpty()) {
                capacity += incoming.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(current, incoming)) {
                capacity += Math.max(0, current.getMaxStackSize() - current.getCount());
            }
            if (capacity >= incoming.getCount()) {
                return capacity;
            }
        }
        return capacity;
    }

    private void requireScope() {
        if (stagedEffects == null) {
            throw new IllegalStateException("live mutation requires an active scope");
        }
    }

    private ServerLevel level(RegionId region) {
        ServerLevel level = levels.get(region);
        if (level == null) {
            throw new IllegalStateException("region is not bound to a live level: " + region);
        }
        return level;
    }

    private static String creditKey(InventoryCredit credit) {
        return credit.actor().value() + ":" + Long.toUnsignedString(credit.entityId().value());
    }

    private record ProjectionKey(RegionId region, NetworkEntityId id) {
    }
}
