package dev.nodera.mod.server.entity;

import dev.nodera.mod.common.RegionSeedSpool;
import dev.nodera.endpoint.lane.ObserverOwnership;
import dev.nodera.endpoint.lane.PlayerLanePresence;
import dev.nodera.endpoint.lane.RedstoneSuppression;
import dev.nodera.endpoint.lane.VanillaCancelGate;
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
import dev.nodera.mod.common.entity.NetworkEntityIdAttachment;
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
    /**
     * Regions this node has SEEN a non-delegable entity in. Separate from the validation lane's
     * refused set because the two answer different questions: "has the mesh been told" versus "has
     * this node ever said so out loud", and only the second one keeps a live drive honest.
     */
    private final Set<RegionId> observedNonDelegable =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Feeds locally-captured actions to this node's render overlay (L-16). Resolved per call, so a
     * client validation lane that starts after this runtime does is still picked up.
     */
    /**
     * Holds this player's in-flight actions across a gateway migration (engine L-17 / #35). Never
     * frozen unless the runtime reports a gateway change, so the ordinary path is one map put and
     * one remove.
     */
    private final dev.nodera.peer.GatewayHandover handover = new dev.nodera.peer.GatewayHandover();

    private final dev.nodera.peer.view.PredictionFeed predictions =
            new dev.nodera.peer.view.PredictionFeed(
                    dev.nodera.mod.client.entity.ClientValidationLane::replicaView);

    /** Keeps every delegated region's chunks resident — an unloaded region cannot be validated. */
    private final dev.nodera.mod.server.shadow.ChunkTicketService tickets =
            new dev.nodera.mod.server.shadow.ChunkTicketService();

    /**
     * Hands every region this node commits to the always-on worker (worker L-41).
     *
     * <p>The client lane has done this since L-41 landed; the <b>server</b> lane never did, and the
     * asymmetry had a cost that showed up on screen: the machine running the world — the one whose
     * seats cover the spawn and everywhere its player stands — contributed <em>no</em> validated
     * region to the network, so its regions died with its game while a joiner's survived. The
     * bargain is the same one the world archive already makes for the save, and it belongs on both
     * commit paths or on neither.
     */
    private final dev.nodera.mod.common.RegionSeedSpool regionSeeds =
            dev.nodera.mod.common.RegionSeedSpool.companion();
    /**
     * Where a block edit's proposal is voted on, off the world thread.
     *
     * <p>Single-threaded and bounded: proposals for one node must keep their order, and a queue that
     * can grow without limit turns a stalled committee into an out-of-memory error instead of a
     * dropped edit. A full queue drops the newest proposal, which the committed state reconciles —
     * the same contract the forward path and {@code submitMove} already work under.
     */
    private final java.util.concurrent.ThreadPoolExecutor proposals =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r, "nodera-block-proposals");
                        t.setDaemon(true);
                        return t;
                    },
                    LiveEntityLaneRuntime::onProposalQueueFull);

    /** Proposals dropped because the queue was full — see {@link #onProposalQueueFull}. */
    private static final java.util.concurrent.atomic.AtomicLong DROPPED_PROPOSALS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * What happens when the proposal queue is full.
     *
     * <p>Still a drop, and that is deliberate: the queue is bounded because an unbounded one turns a
     * stalled committee into an out-of-memory error, and blocking here would put the world thread
     * back inside the vote this executor exists to get it out of.
     *
     * <p>What changes is that it is no longer silent. It was a bare {@code DiscardPolicy} with no
     * counter and no log, so a committee that could not keep up looked exactly like one that had
     * nothing to do. The world state itself survives a drop — the block already landed in vanilla,
     * the write guard recorded it, and the interference committer converts it into a certified
     * external delta regardless — and the action stays unacknowledged in the handover, so a resume
     * replays it. What is lost is the action's own journal entry, which is worth a line in the log.
     */
    private static void onProposalQueueFull(
            Runnable dropped, java.util.concurrent.ThreadPoolExecutor executor) {
        long total = DROPPED_PROPOSALS.incrementAndGet();
        // Powers of two: the first few say it started, and a sustained stall does not drown the log.
        if (Long.bitCount(total) == 1) {
            LOG.warn("block proposal queue full — {} proposal(s) dropped so far; the edits "
                    + "themselves are carried by the interference lane, but the committee is not "
                    + "keeping up with this world", total);
        }
    }

    /** How many block proposals have been dropped for a full queue since this JVM started. */
    public static long droppedProposals() {
        return DROPPED_PROPOSALS.get();
    }

    private final dev.nodera.core.state.ChunkStampBook stamps;

    private long currentTick;

    /**
     * When each of this world's columns was last written here.
     *
     * @return the book, for anything building or merging an index against this world.
     */
    public dev.nodera.core.state.ChunkStampBook stamps() {
        return stamps;
    }

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
        // Provenance for this world's columns: which of two differing copies of a chunk is more
        // recent, in a form another machine can compare without either clock being right. Installed
        // on the view so every canonical write — foreign or applied — records itself.
        this.stamps = new dev.nodera.core.state.ChunkStampBook(authority.nodeId());
        world.stampBook(stamps);
        this.committer = new InterferenceCommitter(
                interference,
                // Through the view, not around it: the view knows whether the region has changed
                // since it last answered, and can say "the same" for free. Hashing here instead
                // meant a full re-extract and SHA-256 of every column on every commit — and again,
                // for the same state, when the certified delta was verified.
                (region, version, minimumBodyVersion) ->
                        world.regionRoot(region, version, currentTick),
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
        // Half a second, not every tick. A commit re-extracts and hashes a whole region, and a
        // region is pending whenever any ghost moved — so this ran up to once per tick per region
        // on the server thread, which is what a live two-player session measured as 15.5 TPS.
        // Ordering is preserved by flushing the region explicitly before anything proposes into it.
        this.committer.setCommitIntervalTicks(COMMIT_INTERVAL_TICKS);
        // Only the region's primary may certify a foreign write against it. Without this the
        // committer attempted every pending region, the sink refused every one this node does not
        // own, and the buffer was restored and retried on the next cadence — forever, on the world
        // thread, reported as a resync that never resolved.
        this.committer.certifiable(validation::isPrimaryOf);
    }

    /** See {@link InterferenceCommitter#setCommitIntervalTicks}. */
    private static final int COMMIT_INTERVAL_TICKS = 10;

    /** Expose event capture only after region state and durable recovery are ready. */
    public void install() {
        // The spool decides for itself whether to push (it throttles per region, never blocks the
        // committing thread, and skips when there is no world identity yet), so this stays one call.
        validation.onCommit((snapshot, root) -> regionSeeds.offer(snapshot));
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
        // Simulated only where this node is the region's PRIMARY. A validator re-executes what it
        // is given and compares roots; it needs the ground readable, not running.
        tickets.hold(level, snapshot.region(), authority.nodeId().equals(lease.primary()));
        // Task 13: the engine is THE scheduler for this region now — vanilla scheduled
        // ticks for its chunks are cancelled at the source (LevelTicksMixin).
        dev.nodera.endpoint.lane.RedstoneSuppression.activate(
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
    public boolean mayCancelVanilla(RegionId region) {
        return VanillaCancelGate.mayCancelVanilla(validation.lease(region), authority.nodeId());
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
     * L-12's submit path: a captured step becomes a signed {@code MovePlayerAction} on the same
     * lane as every other action. Vanilla is never cancelled for it — movement stays
     * vanilla-immediate and the committee's verdict reconciles the committed presence — so there
     * is no {@link VanillaCancelGate} check here.
     *
     * <p><b>The presence gate.</b> {@code MovementRules} validates a step against the actor's
     * committed {@link dev.nodera.core.state.EntityKind#PLAYER} entity, and a step for an actor
     * with no such entity is rejected {@code ENTITY_NOT_FOUND} on every replica. Registering that
     * entity is L-11's job, not this one's, and doing it here would be actively harmful: with a
     * player entity present the engine routes pickups into the validated root inventory instead of
     * the {@code InventoryCredit} stopgap this mod actually mirrors back into the vanilla
     * inventory, so seeding an empty one would make picked-up items disappear from real
     * inventories. So the step is proposed only once the committee already holds the presence it
     * would move — which is honest about what is wired and costs a stationary lane nothing.
     */
    @Override
    public boolean submitMove(
            ServerPlayer player, RegionId region, dev.nodera.core.action.MovePlayerAction move) {
        NodeId actor = new NodeId(player.getUUID());
        if (!PlayerLanePresence.registered(validation.currentSnapshot(region), actor)) {
            return false;
        }
        return submit(player, region, move);
    }

    /**
     * L-11's production registration: the player's {@code PLAYER} root entity, seeded from the
     * inventory they are actually holding.
     *
     * <p>It goes down the adoption path rather than through an action, because a player standing
     * in a delegated region is a FACT of the vanilla world in exactly the way an item entity
     * discovered by the sweep is — there is nothing for a committee to validate about it, and the
     * adoption path already resolves the canonical CAS so a re-register after a restart updates
     * instead of throwing.
     *
     * <p><b>Why the seed matters.</b> {@code EntityRuleSet.applyPickup} routes into the validated
     * root inventory the moment this entity exists and falls back to the one-way
     * {@code InventoryCredit} only when it does not. Registering an EMPTY root would therefore make
     * everything the player was already carrying invisible to the lane and — worse — send picked-up
     * items into a root nothing mirrors back. Seeding from vanilla plus
     * {@code ServerEntityWorldView}'s gain projection closes both directions.
     */
    @Override
    public boolean registerPlayer(ServerPlayer player, RegionId region) {
        if (!delegated(region)) {
            return false;
        }
        NodeId owner = new NodeId(player.getUUID());
        NetworkEntityId id = NetworkEntityIdAttachment.getOrCreate(player);
        int max = Math.max(1, Math.round(player.getMaxHealth()));
        int health = Math.min(max, Math.max(1, Math.round(player.getHealth())));
        adoptEntity(region, new PersistedEntityState(
                id, EntityKind.PLAYER,
                dev.nodera.simulation.entity.PlayerRules.PLAYER_TYPE_ID,
                MinecraftEntityAdapters.fixedPosition(player),
                MinecraftEntityAdapters.fixedVelocity(player),
                0, PersistedEntityState.NEVER_DESPAWN,
                dev.nodera.simulation.entity.PlayerRootRegistration.seedPayload(
                        owner, health, max, vanillaSlots(player))));
        boolean registered = PlayerLanePresence.registered(
                validation.currentSnapshot(region), owner);
        if (registered) {
            LOG.info("player root registered: {} in {} ({} slots seeded from vanilla)",
                    player.getGameProfile().getName(), region,
                    dev.nodera.simulation.entity.PlayerRules.PLAYER_SLOTS);
        }
        return registered;
    }

    /** Drop the root presence; vanilla keeps the durable inventory (see the class javadoc). */
    @Override
    public void unregisterPlayer(ServerPlayer player, RegionId region) {
        if (!delegated(region)) {
            return;
        }
        NetworkEntityId id = NetworkEntityIdAttachment.getOrCreate(player);
        PersistedEntityState current = world.getEntity(region, id);
        if (current != null && current.kind() == EntityKind.PLAYER) {
            externalEntity(region, current, null);
        }
    }

    /**
     * The player's real main inventory (36 slots: 27 + hotbar) as validated slots. Component
     * patches — enchantments, custom names — collapse to the plain item id, which is what the
     * validated slot table can express; the vanilla stack itself is untouched.
     */
    private static java.util.List<dev.nodera.core.state.ContainerEntry.ItemSlot> vanillaSlots(
            ServerPlayer player) {
        java.util.List<dev.nodera.core.state.ContainerEntry.ItemSlot> slots =
                new java.util.ArrayList<>();
        for (int slot = 0; slot < dev.nodera.simulation.entity.PlayerRules.PLAYER_SLOTS; slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                slots.add(dev.nodera.core.state.ContainerEntry.ItemSlot.EMPTY);
                continue;
            }
            slots.add(new dev.nodera.core.state.ContainerEntry.ItemSlot(
                    BuiltInRegistries.ITEM.getId(stack.getItem()),
                    Math.min(255, stack.getCount())));
        }
        return slots;
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
        if (delegated(region)) {
            return submit(player, region, action, false);
        }
        return submitAsObserver(player, region, action);
    }

    /**
     * L-80: capture on a node that holds no seat. Under field-of-view ownership a dedicated server
     * owns nothing and still sees everything, so the action is signed here and forwarded to the
     * region's planned primary. There is no local snapshot to anchor a tick against, so the
     * server's own tick is used — the receiving primary re-verifies the actor signature, the
     * admission rule and the batch before proposing anything, which is what makes the observer a
     * courier rather than an authority.
     */
    private boolean submitAsObserver(
            ServerPlayer player, RegionId region, dev.nodera.core.action.GameAction action) {
        NodeId primary = ObserverOwnership.primaryOf(region);
        if (primary == null || primary.equals(authority.nodeId())) {
            return false;
        }
        NodeId actor = new NodeId(player.getUUID());
        validation.registerActor(actor, authority.publicKeyBytes());
        long playerSequence = actions.nextPlayerSequence(actor);
        long serverSequence = actions.nextServerSequence();
        long tick = Math.max(0L, currentTick) + 1;
        ActionEnvelope unsigned = new ActionEnvelope(
                actor, playerSequence, serverSequence, tick, region, action, Bytes.empty());
        ActionEnvelope signed = new ActionEnvelope(
                actor, playerSequence, serverSequence, tick, region, action,
                authority.sign(unsigned.signedPortion()));
        try {
            boolean sent = validation.forwardTo(primary, signed);
            if (sent) {
                LOG.debug("observer forwarded a block action for {} to its primary {}",
                        region, primary);
            }
            return sent;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    /**
     * The observer half of the capture gate: this node cannot validate {@code region}, but it knows
     * who can and can reach them.
     */
    @Override
    public boolean observes(RegionId region) {
        NodeId primary = ObserverOwnership.primaryOf(region);
        return primary != null && !primary.equals(authority.nodeId()) && !delegated(region);
    }

    /**
     * The gateway lifecycle listener for this runtime (engine L-17 / #35).
     *
     * <p>Registered on the client peer runtime so a gateway migration freezes this player's submit
     * path instead of dropping what was in flight. The replay re-attempts each held action down the
     * same forward-or-propose path it would have taken originally, and each one that leaves the
     * node is acknowledged, so a second migration replays only what is still outstanding.
     *
     * @return the listener, for the runtime to fan events to.
     */
    public dev.nodera.peer.GatewayHandoverListener gatewayListener(NodeId self) {
        return new dev.nodera.peer.GatewayHandoverListener(handover, self, this::replay);
    }

    /** Re-send held actions after a migration; each success is acknowledged so it replays once. */
    private void replay(java.util.List<ActionEnvelope> held) {
        for (ActionEnvelope a : held) {
            try {
                boolean sent = validation.forwardToPrimary(a)
                        || validation.proposeBatch(a.region(), a.targetTick(), a.targetTick(),
                                java.util.List.of(a)).isPresent();
                if (sent) {
                    handover.acknowledge(a.actor(), a.playerSeq());
                }
            } catch (RuntimeException unavailable) {
                // Still no route. The action stays held — an unacknowledged action is exactly what
                // the next resume replays, so a gateway that comes up later still gets it.
                LOG.debug("replay of {} deferred: {}", a.region(), unavailable.toString());
            }
        }
    }

    private boolean submit(
            ServerPlayer player, RegionId region, dev.nodera.core.action.GameAction action) {
        return submit(player, region, action, true);
    }

    /**
     * Capture one action.
     *
     * @param awaitTheCommittee whether the caller may be blocked until the committee decides.
     *
     *        <p><b>Only a caller whose vanilla outcome depends on the answer may pass true.</b>
     *        {@code proposeBatch} waits on {@code round.done().await(voteTimeout + 500)} — five and a
     *        half seconds with the current settings — and the block-capture path calls this from
     *        {@code BlockCaptureBridge} on the <b>server main thread</b>. One edit whose committee
     *        lacked a reachable majority froze the tick loop for that long; a few of them exceeded
     *        the vanilla keepalive and the client was kicked, which the continuity lane then
     *        correctly treated as a lost host and answered with a world reopen. A player reported it
     *        as "a loading screen appears when I break a block".
     *
     *        <p>Drops and pickups still pass true, because their return value is what cancels
     *        vanilla ({@code EntityCaptureBridge}) and an optimistic answer there re-opens issues
     *        #33/#44. A block edit has already happened in the world when this is called, so
     *        nothing is waiting on the verdict — which is the same deal {@code submitMove} took.
     */
    private boolean submit(
            ServerPlayer player, RegionId region, dev.nodera.core.action.GameAction action,
            boolean awaitTheCommittee) {
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
        // L-16: the render overlay takes the action the moment it is captured, so this player's
        // own edit is on screen now rather than when the committee's commit comes back. The
        // overlay is cosmetic — the committed base stays the authority and reconciles it — and a
        // node with no renderer (every dedicated server) predicts nothing.
        predictions.onLocalAction(signed);
        // L-17 / #35: hold the action instead of losing it while this session has no gateway.
        // `submit` returns false only while frozen, and a frozen submit is held for replay — so
        // the optimistic `true` here is the same contract the forward path already has: the
        // committee's decision arrives later either way, and what changes is only that a gateway
        // migration no longer silently drops the swing a player just took.
        if (!handover.submit(signed)) {
            return true;
        }
        try {
            // No-host routing: if another player's node is this region's primary, the captured
            // action is forwarded to it over the mesh — that player proposes, the committee votes,
            // and the committed delta comes back via CommitAnnounce. Optimistic true: rejection is
            // the committee's decision, reconciled by the committed-state application (A-1).
            if (validation.forwardToPrimary(signed)) {
                LOG.debug("action for {} forwarded to its primary", region);
                handover.acknowledge(actor, playerSequence);
                return true;
            }
            if (!awaitTheCommittee) {
                // Off the tick. The proposal still happens, on the same executor the forward path
                // uses; what changes is that the world thread does not sit through the vote.
                // Flushed on THIS thread, before handing off: the buffer is the world thread's
                // and must not be touched from the proposal executor.
                committer.flushNow(region);
                proposals.execute(() -> {
                    try {
                        if (validation.proposeBatch(region, tick, tick,
                                java.util.List.of(signed)).isPresent()) {
                            handover.acknowledge(actor, playerSequence);
                        }
                    } catch (RuntimeException unavailable) {
                        // Held rather than lost: an unacknowledged action is exactly what the next
                        // resume replays.
                        LOG.debug("deferred proposal for {} failed: {}", region,
                                unavailable.toString());
                    }
                });
                return true;
            }
            // The proposal's base is the committed root, so anything still buffered for this
            // region has to land first — otherwise the batch is built on a state the region has
            // already moved past. This is the ordering the commit cadence trades against.
            committer.flushNow(region);
            boolean proposed = validation.proposeBatch(
                    region, tick, tick, java.util.List.of(signed)).isPresent();
            if (proposed) {
                // It left this node, so a later handover must not replay it. Delivery, not commit:
                // the committee's verdict is reconciled by the committed-state application.
                handover.acknowledge(actor, playerSequence);
            }
            return proposed;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public void registerVanillaEntity(RegionId region, dev.nodera.core.state.NetworkEntityId id,
                                      net.minecraft.world.entity.Entity entity) {
        if (!delegated(region)) {
            return;
        }
        world.registerVanillaEntity(region, id, entity);
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
        boolean announced = validation.refuseRegion(
                region, dev.nodera.protocol.simulationmsg.RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
        // Log on the first OBSERVATION per region, not on the first refusal. The two differ
        // whenever the region was already in the refused set — because a peer announced it first,
        // or because this node refused it earlier — and in that case the old code said nothing at
        // all. A live mob drive then reads the lane as silent when it is in fact working, which is
        // exactly what `e2e-mobs.sh` G2b reported. One line per region either way: a nether full
        // of piglins must not be a nether full of log lines.
        if (observedNonDelegable.add(region)) {
            LOG.info("entity lane revoked {} — non-delegable entity {} (enable mobCapture to keep "
                            + "it); refusal announced to the mesh{}",
                    region, entity.getType().builtInRegistryHolder().key().location(),
                    announced ? "" : " earlier");
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
        // Stopped first, so nothing proposes into a lane that is being torn down.
        proposals.shutdownNow();
        EntityCaptureBridge.get().uninstall(this);
        dev.nodera.mod.server.shadow.BlockCaptureBridge.get().uninstall(this);
        if (dev.nodera.mod.server.shadow.BlockWriteGuard.guard() == writeGuard) {
            dev.nodera.mod.server.shadow.BlockWriteGuard.install(null);
        }
        world.applierScope(null);
        for (RegionId region : regions) {
            dev.nodera.endpoint.lane.RedstoneSuppression.deactivate(
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
        // Waits for an in-flight push: it is writing into the spool directory, and close() runs on
        // the world-unload path that is entitled to tear that down.
        regionSeeds.close();
    }
}
