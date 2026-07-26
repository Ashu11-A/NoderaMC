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

/**
 * Live coordinator implementation behind {@link EntityCaptureBridge} and
 * {@link dev.nodera.mod.server.shadow.BlockCaptureBridge}. One runtime, one signed-submit path:
 * an entity action and a block action differ in payload, never in how they reach the lane.
 */
public final class LiveEntityLaneRuntime implements EntityCaptureBridge.Runtime,
        dev.nodera.mod.server.shadow.BlockCaptureBridge.Sink, AutoCloseable {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane");

    private final WorkerValidationService validation;

    /** @return the lane's validation service (ownership diagnostics + relay metrics). */
    public WorkerValidationService validation() {
        return validation;
    }
    private final ServerEntityWorldView world;
    private final NodeIdentity authority;
    private final DurableActionJournal actions;
    private final InterferenceBuffer interference = new InterferenceBuffer();
    private final dev.nodera.coordinator.interference.InterferenceStats interferenceStats =
            new dev.nodera.coordinator.interference.InterferenceStats();
    /**
     * The live write choke point's guard. CONVERT is the only defensible default in a real game:
     * blocking a foreign write means another mod's block silently fails to appear, while converting
     * it means the region certifies what actually happened (Task 11).
     */
    private final dev.nodera.coordinator.interference.MutationGuard writeGuard =
            new dev.nodera.coordinator.interference.MutationGuard(
                    this::delegated,
                    dev.nodera.coordinator.interference.MutationGuard.Mode.CONVERT,
                    interference, interferenceStats);
    private final InterferenceCommitter committer;
    private final EntityLaneSoakMetrics metrics = new EntityLaneSoakMetrics();
    private final Set<RegionId> regions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<RegionId, ServerLevel> boundLevels =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<NetworkEntityId> ghosts = new HashSet<>();
    /** Keeps every delegated region's chunks resident — an unloaded region cannot be validated. */
    private final dev.nodera.mod.server.shadow.ChunkTicketService tickets =
            new dev.nodera.mod.server.shadow.ChunkTicketService();
    private long currentTick;

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
        dev.nodera.mod.server.shadow.BlockCaptureBridge.get().sink(this);
        // The choke point is inert until this line: a server validating nothing pays one null
        // check per block write.
        dev.nodera.mod.server.shadow.BlockWriteGuard.install(writeGuard);
        world.applierScope(writeGuard::applierScope);
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
        tickets.hold(level, snapshot.region());
        // Task 13: the engine is THE scheduler for this region now — vanilla scheduled
        // ticks for its chunks are cancelled at the source (LevelTicksMixin).
        dev.nodera.mod.server.redstone.RedstoneSuppression.activate(
                snapshot.region().regionX(), snapshot.region().regionZ());
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
        // Issue #44: cancelling the vanilla toss is only safe when THIS node is the region's
        // primary — the commit is then synchronous, so the validated item materializes in the
        // same tick the vanilla one is cancelled. On the forward path the submit is optimistic
        // (fire-and-forget to a remote primary): cancelling vanilla against that unconfirmed
        // commit made the tossed item silently snap back into the inventory whenever the remote
        // lane stalled (the live play-two "players fail to drop items" repro) — the same failure
        // shape issue #33 fixed for pickups. Local action stays vanilla-immediate; the validated
        // lane observes and reconciles via external capture instead of gating the player.
        if (!VanillaCancelGate.mayCancelVanilla(validation.lease(region), authority.nodeId())) {
            return false;
        }
        int itemId = BuiltInRegistries.ITEM.getId(vanillaDrop.getItem().getItem());
        return submit(player, region, new DropItemAction(
                itemId, vanillaDrop.getItem().getCount(),
                MinecraftEntityAdapters.fixedPosition(vanillaDrop)));
    }

    @Override
    public boolean submitPickup(ServerPlayer player, RegionId region, NetworkEntityId id) {
        // Pickups are only routed through the validated lane when THIS node is the region's
        // primary: the commit is then synchronous, so cancelling the vanilla pickup is safe.
        // On the forward path the submit is optimistic (fire-and-forget to a remote primary) —
        // cancelling vanilla against an unconfirmed commit is exactly the issue-#33 clean-slate
        // vanish (no credit, no vanilla delivery). Falling back to vanilla here is lossless:
        // the item is delivered vanilla-style and the external-capture lane reconciles the
        // canonical item's removal, with no PickupItemAction proposed so no duplicate credit.
        if (!VanillaCancelGate.mayCancelVanilla(validation.lease(region), authority.nodeId())) {
            return false;
        }
        boolean committed = submit(player, region, new PickupItemAction(id));
        if (committed) {
            // Player-triggered, rare — the live proof that a pickup went through the validated
            // lane (committee + inventory credit) rather than the vanilla fallback.
            LOG.info("validated pickup committed: {} by {} in {}",
                    id, player.getGameProfile().getName(), region);
        }
        return committed;
    }

    /**
     * The block-capture lane's entry point (minecraft Task 2 deliverable 1). Block actions take the
     * identical signed path as entity actions — nothing about a place or a break is special once it
     * is an {@link ActionEnvelope} — and vanilla is never cancelled for them, so there is no
     * {@code VanillaCancelGate} check here: the player's own edit already happened.
     */
    @Override
    public boolean submitBlockAction(
            ServerPlayer player, RegionId region, dev.nodera.core.action.GameAction action) {
        return submit(player, region, action);
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
        // Same staleness rule as externalEntity (and the same live crash when violated —
        // "ghost transfer canonical guards failed" took the whole server tick down on the
        // ownership drive once resumed store heads made canonical richer than the bridge's
        // cache): the caller's `expected` is a hint, canonical is the authority. Resolve the
        // CAS inputs from canonical; a transfer canonical has already absorbed (id present in
        // target) or cannot express (id absent everywhere) is a no-op, not a crash.
        PersistedEntityState canonicalSource = world.getEntity(source, expected.id());
        PersistedEntityState canonicalTarget = world.getEntity(target, replacement.id());
        if (canonicalSource == null) {
            if (canonicalTarget == null) {
                LOG.debug("ghost transfer {}->{} dropped: id {} unknown to canonical",
                        source, target, expected.id());
            }
            return;
        }
        if (canonicalTarget != null) {
            return; // already transferred (replay/late event)
        }
        expected = canonicalSource;
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
        // L-60: this runs on whichever node SAW the entity, which under field-of-view ownership is
        // usually not a node that owns the region — entities spawn and tick on the session server,
        // and the seats sit on the players' nodes. `refuseRegion` therefore both drops whatever
        // this node holds and tells the mesh, and answers false for every repeat so a dimension
        // full of mobs logs once per region rather than once per spawn.
        boolean first = validation.refuseRegion(
                region, dev.nodera.protocol.simulationmsg.RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        if (first) {
            LOG.info("entity lane revoked {} — non-delegable entity {} (enable mobCapture to keep "
                            + "it); refusal announced to the mesh",
                    region, entity.getType().builtInRegistryHolder().key().location());
        }
        regions.remove(region);
        ServerLevel level = boundLevels.remove(region);
        if (level != null) {
            tickets.release(level, region);
        }
        EntityCaptureBridge.get().releaseRegion(region);
    }

    @Override
    public void pearlTeleported(ServerPlayer player, RegionId destination) {
        // The destination is evidence, not state: EntityCaptureBridge logs it on the pearl drive's
        // own logger, and nothing in the lane reads it back. Keeping a field for it would be a
        // second, silently-stale copy of something already recorded.
    }

    @Override
    public void tickEnd(MinecraftServer server) {
        currentTick = server.getTickCount();
        world.retryPendingCredits(server);
        metrics.recordGhostMobTicks(ghosts.size());
        // A crash is the case durability exists for, and a crash never reaches close(): checkpoint
        // the reputation view every 30 s so at most half a minute of observation is ever lost.
        if (currentTick % 600 == 0) {
            validation.persistState();
        }
        interferenceStats.advanceTick();
        for (RegionId region : regions) {
            validation.currentSnapshot(region).ifPresent(snapshot ->
                    committer.onCommittedVersion(region, snapshot.version()));
        }
        try {
            committer.onTickEnd(validation::pipelineState);
        } catch (RuntimeException requiresResync) {
            metrics.recordResync();
        }
        // Issue #46.1: a player whose client cannot keep up must not hold its regions — and every
        // other player's border crossing into them — hostage. Sustained unanswered forwards move
        // primacy to a member that can do the work. Network failures degrade, never crash a tick.
        try {
            for (RegionId handedOff : validation.tickLagHandoff(currentTick, System.nanoTime())) {
                LOG.info("region {} handed off: its primary left forwarded actions unanswered "
                        + "past the lag threshold", handedOff);
            }
        } catch (RuntimeException degraded) {
            LOG.warn("Nodera: lag-handoff window failed (region work continues): {}",
                    degraded.toString());
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
        dev.nodera.mod.server.shadow.BlockCaptureBridge.get().uninstall(this);
        if (dev.nodera.mod.server.shadow.BlockWriteGuard.guard() == writeGuard) {
            dev.nodera.mod.server.shadow.BlockWriteGuard.install(null);
        }
        world.applierScope(null);
        for (RegionId region : regions) {
            dev.nodera.mod.server.redstone.RedstoneSuppression.deactivate(
                    region.regionX(), region.regionZ());
        }
        // Release per region, not per level: a session may hold regions in more than one
        // dimension, and a ticket is only removable through the level that issued it.
        for (java.util.Map.Entry<RegionId, ServerLevel> bound : boundLevels.entrySet()) {
            tickets.release(bound.getValue(), bound.getKey());
        }
        boundLevels.clear();
        regions.clear();
        ghosts.clear();
    }
}
