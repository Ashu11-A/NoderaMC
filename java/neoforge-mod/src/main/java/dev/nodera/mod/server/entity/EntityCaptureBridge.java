package dev.nodera.mod.server.entity;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.common.entity.NetworkEntityIdAttachment;
import dev.nodera.coordinator.entity.GhostUpdatePolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

/**
 * Live Task-12 entity event bridge. Vanilla item projections are tick-suppressed; ghost-capable
 * entities keep vanilla authority and emit coalescible external mutations after their ticks.
 */
public final class EntityCaptureBridge {

    /** Owning coordinator seam; every method runs on server main thread. */
    public interface Runtime {
        boolean delegated(RegionId region);

        boolean validatedItem(RegionId region, NetworkEntityId id);

        /** Return true only after drop escrow/action reservation is durable. */
        boolean submitDrop(ServerPlayer player, ItemEntity vanillaDrop);

        /**
         * Adopt an entity discovered in a delegated region (join/sweep). Unlike a plain external
         * capture, the id may already exist in the canonical root (an attachment persisted across
         * a session restart) — the runtime resolves the expected state so the CAS never throws.
         */
        default void adoptEntity(dev.nodera.core.region.RegionId region,
                                 PersistedEntityState state) {
            externalEntity(region, null, state);
        }

        /** Return true only after pickup action reservation is durable. */
        boolean submitPickup(ServerPlayer player, RegionId region, NetworkEntityId id);

        void externalEntity(
                RegionId region, PersistedEntityState expected, PersistedEntityState replacement);

        void transferGhost(
                RegionId source, RegionId target, PersistedEntityState expected,
                PersistedEntityState replacement);

        void revokeForEntity(RegionId region, Entity entity);

        void pearlTeleported(ServerPlayer player, RegionId destination);

        void tickEnd(MinecraftServer server);

        Runtime DISABLED = new Runtime() {
            @Override public boolean delegated(RegionId region) { return false; }
            @Override public boolean validatedItem(RegionId region, NetworkEntityId id) { return false; }
            @Override public boolean submitDrop(ServerPlayer player, ItemEntity vanillaDrop) { return false; }
            @Override public boolean submitPickup(
                    ServerPlayer player, RegionId region, NetworkEntityId id) { return false; }
            @Override public void externalEntity(
                    RegionId region, PersistedEntityState expected,
                    PersistedEntityState replacement) {}
            @Override public void transferGhost(
                    RegionId source, RegionId target, PersistedEntityState expected,
                    PersistedEntityState replacement) {}
            @Override public void revokeForEntity(RegionId region, Entity entity) {}
            @Override public void pearlTeleported(ServerPlayer player, RegionId destination) {}
            @Override public void tickEnd(MinecraftServer server) {}
        };
    }

    private static final EntityCaptureBridge INSTANCE = new EntityCaptureBridge();

    private final Map<UUID, Captured> captured = new HashMap<>();
    private final Queue<Entity> deferredJoins = new ArrayDeque<>();
    private final ThreadLocal<Integer> materializationDepth = ThreadLocal.withInitial(() -> 0);
    private Runtime runtime = Runtime.DISABLED;

    private EntityCaptureBridge() {
    }

    public static EntityCaptureBridge get() {
        return INSTANCE;
    }

    public void register() {
        NeoForge.EVENT_BUS.addListener(this::onJoin);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onToss);
        NeoForge.EVENT_BUS.addListener(this::onPickupPre);
        NeoForge.EVENT_BUS.addListener(this::onPearlTeleport);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
    }

    /** Install the active coordinator runtime when live region validation starts. */
    public void runtime(Runtime runtime) {
        this.runtime = runtime == null ? Runtime.DISABLED : runtime;
    }

    /**
     * Adopt every entity already living in {@code region} into the active runtime. Entities whose
     * chunks were loaded <em>before</em> lane activation (spawn chunks, a world restored from disk)
     * fired their join events against the disabled runtime and would otherwise never be captured —
     * activation must sweep them explicitly.
     *
     * @Thread-context server thread (iterates live entities), after {@link #runtime} is installed.
     */
    public void sweep(ServerLevel level, RegionId region) {
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Player) && !entity.isRemoved()
                    && captured.get(entity.getUUID()) == null
                    && MinecraftEntityAdapters.region(entity).equals(region)) {
                captureJoin(entity);
            }
        }
    }

    /** Remove only the expected runtime, so stale session teardown cannot disable its successor. */
    public boolean uninstall(Runtime expected) {
        if (runtime != expected) {
            return false;
        }
        runtime = Runtime.DISABLED;
        captured.clear();
        deferredJoins.clear();
        return true;
    }

    /** Stop suppressing or reporting entities after their region leaves delegation. */
    public void releaseRegion(RegionId region) {
        captured.entrySet().removeIf(entry -> entry.getValue().region.equals(region));
        deferredJoins.removeIf(entity -> entity.level() instanceof ServerLevel
                && MinecraftEntityAdapters.region(entity).equals(region));
    }

    /** Suppress capture echo while the canonical applier creates/removes vanilla projections. */
    public void materialize(Runnable mutation) {
        materializationDepth.set(materializationDepth.get() + 1);
        try {
            mutation.run();
        } finally {
            materializationDepth.set(materializationDepth.get() - 1);
        }
    }

    private void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel && !(event.getEntity() instanceof Player)
                && materializationDepth.get() == 0) {
            deferredJoins.add(event.getEntity());
        }
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        for (int i = deferredJoins.size(); i > 0; i--) {
            Entity entity = deferredJoins.poll();
            if (entity != null && !entity.isRemoved() && entity.level() instanceof ServerLevel) {
                captureJoin(entity);
            }
        }
        runtime.tickEnd(event.getServer());
    }

    private void captureJoin(Entity entity) {
        RegionId region = MinecraftEntityAdapters.region(entity);
        if (!runtime.delegated(region)) {
            return;
        }
        if (entity instanceof ItemEntity item) {
            Optional<NetworkEntityId> id = NetworkEntityIdAttachment.existing(item);
            if (id.isPresent() && runtime.validatedItem(region, id.get())) {
                captured.put(entity.getUUID(), new Captured(region,
                        MinecraftEntityAdapters.item(item, id.get()), true));
                return;
            }
            NetworkEntityId allocated = NetworkEntityIdAttachment.getOrCreate(item);
            PersistedEntityState state = MinecraftEntityAdapters.item(item, allocated);
            captured.put(entity.getUUID(), new Captured(region, state, true));
            // A validated item's vanilla projection is tick-suppressed, which would freeze its
            // pickup delay forever — pickup validity now belongs to the validated lane
            // (PickupItemAction admission), so the projection must be immediately touchable.
            item.setPickUpDelay(0);
            runtime.adoptEntity(region, state);
            return;
        }
        if (!NoderaConfig.mobCapture(entity.level().dimension())) {
            runtime.revokeForEntity(region, entity);
            return;
        }
        PersistedEntityState state = MinecraftEntityAdapters.ghost(entity);
        captured.put(entity.getUUID(), new Captured(region, state, false));
        runtime.adoptEntity(region, state);
    }

    private void onTickPre(EntityTickEvent.Pre event) {
        if (!(event.getEntity().level() instanceof ServerLevel)) {
            return;
        }
        Captured tracked = captured.get(event.getEntity().getUUID());
        if (tracked != null && tracked.validatedItem) {
            if (runtime.delegated(tracked.region)) {
                event.setCanceled(true);
            } else {
                captured.remove(event.getEntity().getUUID());
            }
        }
    }

    private void onTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel) || entity instanceof Player) {
            return;
        }
        Captured prior = captured.get(entity.getUUID());
        if (prior == null || prior.validatedItem) {
            return;
        }
        PersistedEntityState current = MinecraftEntityAdapters.ghost(entity);
        RegionId currentRegion = MinecraftEntityAdapters.region(entity);
        if (!prior.region.equals(currentRegion)) {
            if (runtime.delegated(currentRegion)) {
                runtime.transferGhost(prior.region, currentRegion, prior.state, current);
            } else {
                runtime.externalEntity(prior.region, prior.state, null);
            }
        } else if (GhostUpdatePolicy.shouldEmit(prior.state, current)) {
            runtime.externalEntity(currentRegion, prior.state, current);
        } else {
            return;
        }
        captured.put(entity.getUUID(), new Captured(currentRegion, current, false));
    }

    private void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || materializationDepth.get() != 0) {
            return;
        }
        Captured prior = captured.remove(event.getEntity().getUUID());
        Entity.RemovalReason reason = event.getEntity().getRemovalReason();
        if (prior != null && reason != Entity.RemovalReason.UNLOADED_TO_CHUNK
                && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
            runtime.externalEntity(prior.region, prior.state, null);
        }
    }

    private void onToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && event.getEntity().level() instanceof ServerLevel) {
            RegionId region = MinecraftEntityAdapters.region(event.getEntity());
            if (runtime.delegated(region) && runtime.submitDrop(player, event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    private void onPickupPre(ItemEntityPickupEvent.Pre event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ItemEntity item = event.getItemEntity();
        Optional<NetworkEntityId> id = NetworkEntityIdAttachment.existing(item);
        if (id.isEmpty()) {
            return;
        }
        RegionId region = MinecraftEntityAdapters.region(item);
        if (runtime.validatedItem(region, id.get())
                && runtime.submitPickup(player, region, id.get())) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    private void onPearlTeleport(EntityTeleportEvent.EnderPearl event) {
        ServerPlayer player = event.getPlayer();
        ServerLevel level = player.serverLevel();
        runtime.pearlTeleported(player, MinecraftEntityAdapters.region(
                level, event.getTargetX(), event.getTargetZ()));
    }

    /** True for the named Task-12 review projectile. */
    public static boolean isPearl(Entity entity) {
        return entity instanceof ThrownEnderpearl;
    }

    private record Captured(
            RegionId region, PersistedEntityState state, boolean validatedItem) {
    }
}
