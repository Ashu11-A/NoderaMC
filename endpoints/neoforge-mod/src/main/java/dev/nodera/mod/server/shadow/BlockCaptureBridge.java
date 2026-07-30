package dev.nodera.mod.server.shadow;

import dev.nodera.endpoint.lane.BlockCaptureRules;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.mod.server.entity.MinecraftEntityAdapters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live block-action capture lane (minecraft Task 2 deliverable 1; the half of engine Task 3
 * that only a real game can supply). A player placing or breaking a block in a region this node's
 * lane holds becomes a signed {@link PlaceBlockAction} / {@link BreakBlockAction} on the validated
 * lane, exactly as the headless shadow soak has always assumed.
 *
 * <p><b>Events, not mixins.</b> {@code BlockEvent.EntityPlaceEvent} and {@code BlockEvent.BreakEvent}
 * carry everything the lane needs — who, where, from what, to what — so this bridge adds no surface
 * to A-7 (version churn). Listeners run at {@code EventPriority.LOW} and never receive cancelled
 * events, which is the priority {@code COMPATIBILITY.md} §1 makes normative: a protection or claims
 * mod at any earlier priority always wins, and Nodera captures what the world actually agreed to
 * rather than what someone proposed.
 *
 * <p><b>Capture, never cancel.</b> Vanilla keeps its outcome and the player sees their own edit
 * immediately; the network validates afterwards. This is the {@code VanillaCancelGate} contract read
 * across to blocks — the lane is a validator after the fact, never a gate in front of the player's
 * own game (issues #33 and #44). A rejected action is the committee's business, reconciled by the
 * applier, and it costs the player nothing while the network decides.
 *
 * <p><b>Crash-contained.</b> NeoForge's event bus rethrows listener exceptions into the server tick
 * (issue #39), so both listeners self-catch: a capture fault degrades the lane, never the game.
 *
 * @Thread-context every listener runs on the server main thread.
 */
public final class BlockCaptureBridge {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaBlockCapture");

    /** The lane seam: the node's single signed-submit path, installed when a lane activates. */
    public interface Sink {

        /** @return whether this node's lane holds {@code region}. */
        boolean delegated(RegionId region);

        /**
         * @return whether this node can route an action for {@code region} to its owner without
         *         holding it — the observer path (L-80). A dedicated server owns no regions under
         *         field-of-view ownership and is still the only process that sees the edits.
         */
        default boolean observes(RegionId region) {
            return false;
        }

        /**
         * Submit one captured block action. Implementations sign it, forward it to the region's
         * primary when that is another node, and otherwise propose it locally.
         *
         * @return whether the action was admitted to the lane.
         */
        boolean submitBlockAction(ServerPlayer player, RegionId region, GameAction action);

        /** The no-lane default: nothing is delegated, nothing is captured. */
        Sink DISABLED = new Sink() {
            @Override public boolean delegated(RegionId region) {
                return false;
            }

            @Override public boolean observes(RegionId region) {
                return false;
            }

            @Override public boolean submitBlockAction(
                    ServerPlayer player, RegionId region, GameAction action) {
                return false;
            }
        };
    }

    private static final BlockCaptureBridge INSTANCE = new BlockCaptureBridge();

    private final Map<BlockCaptureRules.Reason, AtomicLong> outcomes =
            new EnumMap<>(BlockCaptureRules.Reason.class);
    private volatile Sink sink = Sink.DISABLED;

    private BlockCaptureBridge() {
        for (BlockCaptureRules.Reason reason : BlockCaptureRules.Reason.values()) {
            outcomes.put(reason, new AtomicLong());
        }
    }

    public static BlockCaptureBridge get() {
        return INSTANCE;
    }

    /** Subscribe the listeners once, at mod construction — capture stays inert until a lane installs. */
    public void register() {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, this::onPlace);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, this::onBreak);
    }

    /** Install the active lane; {@code null} restores the disabled sink. */
    public void sink(Sink sink) {
        this.sink = sink == null ? Sink.DISABLED : sink;
    }

    /** Detach {@code sink} if it is still the installed one (lane shutdown). */
    public void uninstall(Sink sink) {
        if (this.sink == sink) {
            this.sink = Sink.DISABLED;
        }
    }

    /**
     * @return how many edits landed in each outcome since boot — the counter behind the diagnostics
     *         line "captured N, dropped M (unsupported/foreign)". Ordered by the reason enum.
     */
    public Map<BlockCaptureRules.Reason, Long> outcomes() {
        Map<BlockCaptureRules.Reason, Long> out = new EnumMap<>(BlockCaptureRules.Reason.class);
        outcomes.forEach((reason, count) -> out.put(reason, count.get()));
        return out;
    }

    /**
     * Drive one edit through the capture path as {@code player}, for the scripted suites
     * (minecraft L-80 / issue #67 clause 1).
     *
     * <p>The event listeners below submit only when the placer is a {@link ServerPlayer}, because
     * the player's key signs the action — correct, and it leaves the path undrivable from a script,
     * since an RCON {@code /setblock} fires neither event. This is the same code the listeners run
     * after their event unwrapping: the same {@link BlockCaptureRules} decision, the same ledger,
     * the same signed {@link #submit}. It cannot capture anything the rules would refuse, and it is
     * reached only from an OP-gated command.
     *
     * @return whether the edit was submitted to the lane.
     */
    public boolean driveAsPlayer(ServerPlayer player, BlockPos pos, BlockState state,
                                 boolean place) {
        if (player == null || pos == null || state == null) {
            return false;
        }
        try {
            ServerLevel level = player.serverLevel();
            RegionId region = region(level, pos);
            int paletteId = PaletteMapper.idOf(state);
            BlockCaptureRules.Decision decision = place
                    ? BlockCaptureRules.place(
                            sink.delegated(region) || sink.observes(region),
                            paletteId, pos.getY(), isFakePlayer(player))
                    : BlockCaptureRules.breakBlock(
                            sink.delegated(region) || sink.observes(region),
                            paletteId, pos.getY(), isFakePlayer(player));
            record(decision);
            if (!decision.capture()) {
                return false;
            }
            if (place) {
                submit(player, region,
                        new PlaceBlockAction(blockPos(pos), paletteId, faceOf(player)), pos);
            } else {
                submit(player, region, new BreakBlockAction(blockPos(pos)), pos);
            }
            return true;
        } catch (RuntimeException | LinkageError degraded) {
            LOG.warn("Nodera: driven block capture failed (world unaffected): {}",
                    degraded.toString());
            return false;
        }
    }

    private void onPlace(BlockEvent.EntityPlaceEvent event) {
        try {
            if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = event.getPos();
            RegionId region = region(level, pos);
            int paletteId = PaletteMapper.idOf(event.getPlacedBlock());
            BlockCaptureRules.Decision decision = BlockCaptureRules.place(
                    sink.delegated(region) || sink.observes(region),
                    paletteId, pos.getY(), isFakePlayer(event.getEntity()));
            record(decision);
            if (!decision.capture() || !(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            submit(player, region, new PlaceBlockAction(
                    blockPos(pos), paletteId, faceOf(event.getEntity())), pos);
        } catch (RuntimeException | LinkageError degraded) {
            // The bus rethrows into the tick loop: a capture fault must never take the server down.
            LOG.warn("Nodera: block place capture failed (world unaffected): {}",
                    degraded.toString());
        }
    }

    private void onBreak(BlockEvent.BreakEvent event) {
        try {
            if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = event.getPos();
            RegionId region = region(level, pos);
            BlockState broken = event.getState();
            int paletteId = PaletteMapper.idOf(broken);
            BlockCaptureRules.Decision decision = BlockCaptureRules.breakBlock(
                    sink.delegated(region) || sink.observes(region),
                    paletteId, pos.getY(), isFakePlayer(event.getPlayer()));
            record(decision);
            if (!decision.capture() || !(event.getPlayer() instanceof ServerPlayer player)) {
                return;
            }
            submit(player, region, new BreakBlockAction(blockPos(pos)), pos);
        } catch (RuntimeException | LinkageError degraded) {
            LOG.warn("Nodera: block break capture failed (world unaffected): {}",
                    degraded.toString());
        }
    }

    private void submit(ServerPlayer player, RegionId region, GameAction action, BlockPos pos) {
        if (sink.submitBlockAction(player, region, action)) {
            LOG.debug("captured {} at {} in {}", action.getClass().getSimpleName(), pos, region);
        }
    }

    private void record(BlockCaptureRules.Decision decision) {
        outcomes.get(decision.reason()).incrementAndGet();
    }

    private static RegionId region(ServerLevel level, BlockPos pos) {
        return RegionId.fromChunk(MinecraftEntityAdapters.dimension(level),
                Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }

    private static NBlockPos blockPos(BlockPos pos) {
        return new NBlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * The placement face. Vanilla's place event carries the state placed but not the face it was
     * placed against, so the face is reconstructed from where the placer is looking — the same
     * information the player used. It is carried in the signed action for completeness (every
     * validator hashes the identical bytes) and no rule reads it today.
     */
    private static int faceOf(Entity placer) {
        if (placer == null) {
            return Direction.UP.get3DDataValue();
        }
        return Direction.getNearest(placer.getLookAngle()).getOpposite().get3DDataValue();
    }

    private static boolean isFakePlayer(Entity entity) {
        return entity instanceof FakePlayer;
    }

    /** Test seam: the level type check the listeners perform, without an event. */
    static boolean isServerLevel(LevelAccessor level) {
        return level instanceof ServerLevel;
    }
}
