package dev.nodera.mod.server.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.DropItemAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.coordinator.PipelineState;
import dev.nodera.coordinator.entity.EntityLaneSoakMetrics;
import dev.nodera.coordinator.entity.EntityLaneRouting;
import dev.nodera.coordinator.interference.InterferenceBuffer;
import dev.nodera.coordinator.interference.InterferenceCommitter;
import dev.nodera.peer.validation.DurableActionJournal;
import dev.nodera.peer.validation.WorkerValidationService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Live coordinator implementation behind {@link EntityCaptureBridge}. */
public final class LiveEntityLaneRuntime implements EntityCaptureBridge.Runtime, AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane");

    private final WorkerValidationService validation;

    /** @return the lane's validation service (ownership diagnostics). */
    WorkerValidationService validation() {
        return validation;
    }
    private final ServerEntityWorldView world;
    private final NodeIdentity authority;
    private final DurableActionJournal actions;
    private final InterferenceBuffer interference = new InterferenceBuffer();
    private final InterferenceCommitter committer;
    private final EntityLaneSoakMetrics metrics = new EntityLaneSoakMetrics();
    private final Set<RegionId> regions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<RegionId, ServerLevel> boundLevels =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<NetworkEntityId> ghosts = new HashSet<>();
    private long currentTick;
    private RegionId lastPearlDestination;

    public LiveEntityLaneRuntime(
            WorkerValidationService validation,
            ServerEntityWorldView world,
            NodeIdentity authority,
            DurableActionJournal actions) {
        if (validation == null || world == null || authority == null || actions == null) {
            throw new IllegalArgumentException("live entity runtime arguments must not be null");
        }
        this.validation = validation;
        this.world = world;
        this.authority = authority;
        this.actions = actions;
        HashService hashes = new HashService();
        this.committer = new InterferenceCommitter(
                interference,
                (region, version, minimumBodyVersion) ->
                        dev.nodera.core.state.StateRoot.of(hashes.hash(
                                world.reExtract(region, version, currentTick))),
                world::setSnapshotBodyVersion,
                (delta, certificate) -> {
                    validation.commitExternal(delta, certificate, currentTick);
                    metrics.recordCommit();
                    // Debug: with a busy ghost lane this fires near once per tick per region.
                    LOG.debug("entity lane committed {} entity mutation(s) in {} (v{})",
                            delta.entityMutations().size(), delta.region(),
                            delta.resultingVersion().value());
                },
                authority);
    }

    /** Expose event capture only after region state and durable recovery are ready. */
    public void install() {
        EntityCaptureBridge.get().runtime(this);
        // Entities whose chunks predate activation (spawn chunks, restored worlds) joined against
        // the disabled runtime — adopt them now that capture is live. install() may run on the
        // async bootstrap thread; entity iteration belongs on the server thread.
        for (java.util.Map.Entry<RegionId, ServerLevel> bound : boundLevels.entrySet()) {
            ServerLevel level = bound.getValue();
            RegionId region = bound.getKey();
            level.getServer().execute(() -> EntityCaptureBridge.get().sweep(level, region));
        }
    }

    /** Bind one delegated region to both live projection and worker validation. */
    public void activate(ServerLevel level, RegionSnapshot snapshot, RegionLease lease) {
        world.bind(level, snapshot);
        validation.activateRegion(snapshot, lease);
        committer.onCommittedVersion(snapshot.region(), snapshot.version());
        regions.add(snapshot.region());
        boundLevels.put(snapshot.region(), level);
        for (PersistedEntityState entity : snapshot.entities()) {
            if (entity.kind() == EntityKind.GHOST) {
                ghosts.add(entity.id());
            }
        }
    }

    @Override
    public boolean delegated(RegionId region) {
        return regions.contains(region)
                && validation.pipelineState(region) != PipelineState.REVOKED;
    }

    @Override
    public boolean validatedItem(RegionId region, NetworkEntityId id) {
        return validation.currentSnapshot(region).stream()
                .flatMap(snapshot -> snapshot.entities().stream())
                .anyMatch(entity -> entity.id().equals(id) && entity.kind() == EntityKind.ITEM);
    }

    @Override
    public boolean submitDrop(ServerPlayer player, ItemEntity vanillaDrop) {
        if (!vanillaDrop.getItem().isComponentsPatchEmpty()
                || vanillaDrop.getItem().getCount() > 255) {
            return false;
        }
        RegionId region = MinecraftEntityAdapters.region(vanillaDrop);
        int itemId = BuiltInRegistries.ITEM.getId(vanillaDrop.getItem().getItem());
        return submit(player, region, new DropItemAction(
                itemId, vanillaDrop.getItem().getCount(),
                MinecraftEntityAdapters.fixedPosition(vanillaDrop)));
    }

    @Override
    public boolean submitPickup(ServerPlayer player, RegionId region, NetworkEntityId id) {
        boolean committed = submit(player, region, new PickupItemAction(id));
        if (committed) {
            // Player-triggered, rare — the live proof that a pickup went through the validated
            // lane (committee + inventory credit) rather than the vanilla fallback.
            LOG.info("validated pickup committed: {} by {} in {}",
                    id, player.getGameProfile().getName(), region);
        }
        return committed;
    }

    private boolean submit(
            ServerPlayer player, RegionId region, dev.nodera.core.action.GameAction action) {
        Optional<RegionSnapshot> current = validation.currentSnapshot(region);
        if (current.isEmpty() || !playerRegion(player).equals(region)) {
            return false;
        }
        NodeId actor = new NodeId(player.getUUID());
        validation.registerActor(actor, authority.publicKeyBytes());
        long playerSequence = actions.nextPlayerSequence(actor);
        long serverSequence = actions.nextServerSequence();
        long tick = current.get().tick() + 1;
        ActionEnvelope unsigned = new ActionEnvelope(
                actor, playerSequence, serverSequence, tick, region, action, Bytes.empty());
        ActionEnvelope signed = new ActionEnvelope(
                actor, playerSequence, serverSequence, tick, region, action,
                authority.sign(unsigned.signedPortion()));
        try {
            // No-host routing: if another player's node is this region's primary, the captured
            // action is forwarded to it over the mesh — that player proposes, the committee votes,
            // and the committed delta comes back via CommitAnnounce. Optimistic true: rejection is
            // the committee's decision, reconciled by the committed-state application (A-1).
            if (validation.forwardToPrimary(signed)) {
                LOG.debug("action for {} forwarded to its primary", region);
                return true;
            }
            return validation.proposeBatch(region, tick, tick, java.util.List.of(signed)).isPresent();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public void adoptEntity(RegionId region, PersistedEntityState state) {
        if (!delegated(region)) {
            return;
        }
        // A restart re-discovers entities whose ids already live in the canonical root (persistent
        // attachments) — resolve the expected state so the external CAS updates instead of throwing.
        PersistedEntityState expected = world.getEntity(region, state.id());
        externalEntity(region, expected, state);
    }

    @Override
    public void externalEntity(
            RegionId region, PersistedEntityState expected, PersistedEntityState replacement) {
        if (!delegated(region)) {
            LOG.debug("external entity in non-delegated {} ignored", region);
            return;
        }
        // The bridge's cached prior state can lag the canonical root when flushes interleave
        // (observed live as "external entity capture does not match canonical state"). The CAS
        // must run against what the canonical world actually holds — the caller's `expected` is
        // only a hint; canonical is the authority. A removal of an id canonical no longer holds
        // is a no-op rather than a throw.
        PersistedEntityState measuredId = replacement != null ? replacement : expected;
        PersistedEntityState canonicalExpected = world.getEntity(region, measuredId.id());
        if (replacement == null && canonicalExpected == null) {
            return;
        }
        expected = canonicalExpected;
        LOG.debug("captured external entity in {} ({} -> {})", region,
                expected == null ? "none" : expected.id(),
                replacement == null ? "removed" : replacement.id());
        world.captureExternal(region, expected, replacement);
        interference.recordEntity(region, expected, replacement);
        PersistedEntityState measured = replacement != null ? replacement : expected;
        if (measured.kind() == EntityKind.GHOST) {
            if (replacement == null) {
                ghosts.remove(measured.id());
            } else {
                ghosts.add(measured.id());
            }
            CanonicalWriter writer = new CanonicalWriter();
            measured.encode(writer);
            metrics.recordGhostUpdate(writer.size());
        }
    }

    @Override
    public void transferGhost(
            RegionId source, RegionId target, PersistedEntityState expected,
            PersistedEntityState replacement) {
        if (!delegated(source)) {
            return;
        }
        if (EntityLaneRouting.ghostBorder(true, delegated(target))
                == EntityLaneRouting.GhostBorderRoute.MATERIALIZE_VANILLA) {
            externalEntity(source, expected, null);
            return;
        }
        world.captureExternalTransfer(source, target, expected, replacement);
        interference.recordEntity(source, expected, null);
        interference.recordEntity(target, null, replacement);
        ghosts.add(replacement.id());
        CanonicalWriter writer = new CanonicalWriter();
        replacement.encode(writer);
        metrics.recordGhostUpdate(writer.size());
    }

    @Override
    public void revokeForEntity(RegionId region, Entity entity) {
        LOG.info("entity lane revoked {} — non-delegable entity {} (enable mobCapture to keep it)",
                region, entity.getType().builtInRegistryHolder().key().location());
        validation.revokeRegion(region);
        regions.remove(region);
        EntityCaptureBridge.get().releaseRegion(region);
    }

    @Override
    public void pearlTeleported(ServerPlayer player, RegionId destination) {
        lastPearlDestination = destination;
    }

    @Override
    public void tickEnd(MinecraftServer server) {
        currentTick = server.getTickCount();
        world.retryPendingCredits(server);
        metrics.recordGhostMobTicks(ghosts.size());
        for (RegionId region : regions) {
            validation.currentSnapshot(region).ifPresent(snapshot ->
                    committer.onCommittedVersion(region, snapshot.version()));
        }
        try {
            committer.onTickEnd(validation::pipelineState);
        } catch (RuntimeException requiresResync) {
            metrics.recordResync();
        }
    }

    /**
     * Diagnostics view (Task 18 L-31 exit): the validated entity ids currently controlled per
     * delegated region, straight from the validation lane's committed snapshots.
     *
     * @Thread-context safe from the diagnostics sample thread ({@code regions} is concurrent and
     *                 {@code WorkerValidationService} is internally synchronized).
     */
    public dev.nodera.diagnostics.model.EntityControl entityControl() {
        java.util.Map<RegionId, java.util.List<Long>> out = new java.util.LinkedHashMap<>();
        for (RegionId region : regions) {
            validation.currentSnapshot(region).ifPresent(snapshot -> out.put(region,
                    snapshot.entities().stream()
                            .map(e -> e.id().value())
                            .sorted()
                            .toList()));
        }
        return new dev.nodera.diagnostics.model.EntityControl(out);
    }

    public EntityLaneSoakMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    public Optional<RegionId> lastPearlDestination() {
        return Optional.ofNullable(lastPearlDestination);
    }

    /** Live action admission: event capture proves actor/session; position is recomputed per action. */
    public static WorkerValidationService.ActionAdmission admission(MinecraftServer server) {
        return (action, base) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(action.actor().value());
            return player != null && playerRegion(player).equals(base.region());
        };
    }

    private static RegionId playerRegion(ServerPlayer player) {
        return RegionId.fromChunk(
                MinecraftEntityAdapters.dimension(player.serverLevel()),
                player.chunkPosition().x, player.chunkPosition().z);
    }

    @Override
    public void close() {
        EntityCaptureBridge.get().uninstall(this);
        regions.clear();
        ghosts.clear();
    }
}
