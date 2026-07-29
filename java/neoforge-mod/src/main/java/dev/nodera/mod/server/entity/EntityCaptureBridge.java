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

        /**
         * Propose one validated movement step (L-12). Vanilla movement is never cancelled for it —
         * the client proposes and the committee decides, and a step the committee refuses is
         * reconciled by the committed state like any other rejection.
         *
         * @return true if the step left this node (proposed or forwarded).
         */
        boolean submitMove(
                ServerPlayer player, RegionId region,
                dev.nodera.core.action.MovePlayerAction move);

        /**
         * Register {@code player}'s {@code PLAYER} root entity in {@code region}, seeded from the
         * player's ACTUAL vanilla inventory (L-11). The seed is what makes registration safe: the
         * engine routes pickups into the validated root the moment a player entity exists, so an
         * empty one would silently swallow everything a player was already carrying.
         *
         * @return true once the committee holds the presence.
         */
        default boolean registerPlayer(ServerPlayer player, RegionId region) {
            return false;
        }

        /** Drop {@code player}'s root presence from {@code region} (logout, region hand-off). */
        default void unregisterPlayer(ServerPlayer player, RegionId region) {
        }

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
            @Override public boolean submitMove(
                    ServerPlayer player, RegionId region,
                    dev.nodera.core.action.MovePlayerAction move) { return false; }
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
    /** Regions this bridge has seen ANY entity in — the evidence that joins reach it at all. */
    private final java.util.Set<RegionId> observedRegions = new java.util.HashSet<>();
    private final Queue<Entity> deferredJoins = new ArrayDeque<>();
    /** L-12: per-player movement anchors — the producer of every {@code MovePlayerAction}. */
    private final PlayerMovementCapture movement = new PlayerMovementCapture();
    /** L-11: the region each player's committed {@code PLAYER} root presence currently lives in. */
    private final Map<UUID, RegionId> presence = new HashMap<>();
    private final ThreadLocal<Integer> materializationDepth = ThreadLocal.withInitial(() -> 0);
    /**
     * The installed coordinator, or {@link Runtime#DISABLED}.
     *
     * <p><b>volatile, and that is the whole bug.</b> It is written by
     * {@link LiveEntityLaneRuntime#install()} on the {@code nodera-entity-lane-boot} thread and read
     * on the server thread by every capture. Without the write barrier the server thread is free to
     * go on reading its cached {@code DISABLED} for as long as it likes — and {@code DISABLED}
     * answers every method by doing nothing, so capture and revocation both vanish with no
     * exception and no log line anywhere. Three dispatched runs of `e2e-mobs.sh` chased that as
     * "the lane said nothing" (L-60): the diagnostic finally showed the lane observing nether
     * regions and deciding {@code capture=false} correctly, then calling into a runtime that was
     * not there.
     */
    private volatile Runtime runtime = Runtime.DISABLED;

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
        movement.clear();
        presence.clear();
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

    /** @return the entity's type id, e.g. {@code minecraft:zombie} (L-24's per-species switch). */
    private static String speciesOf(Entity entity) {
        return entity.getType().builtInRegistryHolder().key().location().toString();
    }

    private void captureJoin(Entity entity) {
        RegionId region = MinecraftEntityAdapters.region(entity);
        // The first entity this lane sees in a region, whatever happens to it next. Every other
        // line here is conditional — a ghost line needs a capture, a revoke line needs a refusal —
        // so a region that is never OBSERVED and a region observed-and-ignored looked identical in
        // a log. That ambiguity is what made `e2e-mobs.sh` G2b unreadable: "the lane said nothing"
        // could mean the join never arrived or the decision went the other way, and the two want
        // opposite fixes. Once per region, so a busy world stays quiet.
        if (observedRegions.add(region)) {
            // `runtime` is printed because it is the one thing the log could never show: every
            // other line here comes from the bridge itself, so a DISABLED runtime — which answers
            // every method by doing nothing — looks exactly like a quiet world. Five live runs
            // could not tell those apart.
            GHOST_LOG.info("LANE: {} observed (first entity: {}, capture={}, runtime={})", region,
                    speciesOf(entity),
                    NoderaConfig.mobCapture(entity.level().dimension(), speciesOf(entity)),
                    runtime == Runtime.DISABLED ? "DISABLED" : runtime.getClass().getSimpleName());
        }
        // L-60: "this dimension never opted into mob capture" is a CONFIG fact, true on every node,
        // and it disqualifies the region from the validated lane no matter who owns it. Under
        // field-of-view ownership the seats sit on the players' nodes while entities spawn and tick
        // on the session server, which routinely owns nothing — so the node that SEES the
        // disqualifying entity is usually not the node holding the region. Checking this before the
        // ownership gate is what lets that node say so instead of returning in silence.
        if (!(entity instanceof ItemEntity)
                && !NoderaConfig.mobCapture(entity.level().dimension(), speciesOf(entity))) {
            runtime.revokeForEntity(region, entity);
            return;
        }
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
        PersistedEntityState state = MinecraftEntityAdapters.ghost(entity);
        captured.put(entity.getUUID(), new Captured(region, state, false));
        runtime.adoptEntity(region, state);
        // The counterpart of the revoke line: a region that starts holding ghosts says so ONCE.
        // Without it the only observable difference between "capture works here" and "nothing
        // happened" is a per-node counter that belongs to whichever node owns the region — which
        // is not the node an operator's command runs on. Once per region keeps a busy world quiet.
        if (ghostRegionsAnnounced.add(region)) {
            GHOST_LOG.info("GHOST: {} now holds ghost mobs (first: {})", region,
                    entity.getType().builtInRegistryHolder().key().location());
        }
        if (isPearl(entity)) {
            // The pearl is the named review projectile of the entity lane (L-50): it is captured
            // as a ghost, it crosses regions, and it ends in a teleport that must land in the
            // region the lane says it does. Each of those three moments is logged so a scripted
            // drive can assert them — the ghost lane is otherwise silent by design.
            PEARL_LOG.info("PEARL: ghost {} captured in {}", state.id().value(), region);
        }
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
        if (!(entity.level() instanceof ServerLevel)) {
            return;
        }
        if (entity instanceof Player) {
            if (entity instanceof ServerPlayer player) {
                ensurePlayerPresence(player);
                capturePlayerMove(player);
            }
            return;
        }
        Captured prior = captured.get(entity.getUUID());
        if (prior == null || prior.validatedItem) {
            return;
        }
        PersistedEntityState current = MinecraftEntityAdapters.ghost(entity);
        RegionId currentRegion = MinecraftEntityAdapters.region(entity);
        try {
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
        } catch (RuntimeException laneFailure) {
            // Crash containment: a lane-state failure on one ghost must never take the server
            // tick down (a guard mismatch here crashed the whole integrated server on the live
            // ownership drive). Drop this ghost's capture — resync/re-capture owns recovery.
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane").warn(
                    "entity-lane capture failed for {} in {} — dropping capture ({})",
                    entity.getUUID(), currentRegion, laneFailure.toString());
            captured.remove(entity.getUUID());
            return;
        }
        captured.put(entity.getUUID(), new Captured(currentRegion, current, false));
    }

    /**
     * L-11's production call site: the committee learns the player exists.
     *
     * <p>Nothing in production ever registered an {@code EntityKind.PLAYER} entity, so every
     * player-root rule the engine has carried since Task 16 — the validated inventory, movement
     * validation, player combat — was unreachable code. A player ticking inside a region this node
     * holds is exactly the moment the presence becomes true, and re-checked every tick because a
     * region can be delegated (or revoked) at any point in a session.
     *
     * <p>The {@code presence} map is the cheap half: once the register call has succeeded for a
     * region there is nothing to do until the player crosses into a different one, and crossing
     * drops the stale presence first so the same player is never registered in two regions at
     * once.
     *
     * <p>Self-catching, because a NeoForge listener exception is NOT isolated — it rethrows into
     * the server tick and takes the integrated server down with it.
     */
    private void ensurePlayerPresence(ServerPlayer player) {
        try {
            RegionId region = MinecraftEntityAdapters.region(player);
            RegionId held = presence.get(player.getUUID());
            if (!runtime.delegated(region)) {
                // Not ours to hold. Forget the cache rather than unregistering: a region this node
                // no longer holds is a region whose entities it must not mutate.
                presence.remove(player.getUUID());
                return;
            }
            if (region.equals(held)) {
                return;
            }
            if (held != null) {
                runtime.unregisterPlayer(player, held);
                presence.remove(player.getUUID());
            }
            if (runtime.registerPlayer(player, region)) {
                presence.put(player.getUUID(), region);
            }
        } catch (RuntimeException laneFailure) {
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane").debug(
                    "player-root registration for {} skipped this tick ({})",
                    player.getUUID(), laneFailure.toString());
            presence.remove(player.getUUID());
        }
    }

    /**
     * L-12's production call site: the vanilla player's real position, every tick, becomes the
     * signed steps the committee validates. Self-catching because a NeoForge listener exception is
     * NOT isolated — it rethrows into the server tick — and latency-hiding movement capture must
     * never be the thing that takes a world down.
     */
    private void capturePlayerMove(ServerPlayer player) {
        try {
            RegionId region = MinecraftEntityAdapters.region(player);
            PlayerMovementCapture.Plan plan = movement.observe(
                    player.getUUID(), runtime.delegated(region),
                    MinecraftEntityAdapters.fixedPosition(player));
            for (dev.nodera.core.action.MovePlayerAction step : plan.steps()) {
                runtime.submitMove(player, region, step);
            }
        } catch (RuntimeException laneFailure) {
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane").debug(
                    "movement capture for {} skipped this tick ({})",
                    player.getUUID(), laneFailure.toString());
            movement.forget(player.getUUID());
        }
    }

    private void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel) || materializationDepth.get() != 0) {
            return;
        }
        if (event.getEntity() instanceof Player) {
            // A logout or a dimension change: the next position this node sees is unrelated to the
            // last one it proposed, and an anchor kept across that gap manufactures a "teleport".
            movement.forget(event.getEntity().getUUID());
            // L-11: the presence goes with the player. Vanilla is the durable inventory store —
            // Minecraft persists it across the logout and every root gain was projected into it
            // when it committed — so dropping the root here loses nothing, and the next login
            // re-seeds the root from that same vanilla inventory.
            RegionId held = presence.remove(event.getEntity().getUUID());
            if (held != null && event.getEntity() instanceof ServerPlayer player) {
                try {
                    runtime.unregisterPlayer(player, held);
                } catch (RuntimeException laneFailure) {
                    org.slf4j.LoggerFactory.getLogger("NoderaEntityLane").debug(
                            "player-root unregistration for {} failed ({})",
                            player.getUUID(), laneFailure.toString());
                }
            }
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
        RegionId destination = MinecraftEntityAdapters.region(
                level, event.getTargetX(), event.getTargetZ());
        runtime.pearlTeleported(player, destination);
        PEARL_LOG.info("PEARL: {} teleported to {} (delegated: {})",
                player.getGameProfile().getName(), destination, runtime.delegated(destination));
    }

    /** Regions already announced as holding ghosts — the announcement is once per region. */
    private final java.util.Set<RegionId> ghostRegionsAnnounced =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The ghost lane's evidence stream (L-50): one line the first time a region holds ghosts. */
    private static final org.slf4j.Logger GHOST_LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaGhostLane");

    /** The pearl drive's evidence stream (L-50); quiet in any session that throws none. */
    private static final org.slf4j.Logger PEARL_LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaPearlDrive");

    /** True for the named Task-12 review projectile. */
    public static boolean isPearl(Entity entity) {
        return entity instanceof ThrownEnderpearl;
    }

    private record Captured(
            RegionId region, PersistedEntityState state, boolean validatedItem) {
    }
}
