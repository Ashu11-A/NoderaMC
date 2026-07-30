package dev.nodera.endpoint.lane;

import dev.nodera.core.action.MovePlayerAction;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.simulation.entity.MovementRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The capture half of the movement lane (L-12): it turns where a vanilla player actually IS into
 * the {@link MovePlayerAction}s the committee validates. The engine has had the rules
 * ({@link MovementRules}) since Task 16 and nothing in the mod ever built one of these actions, so
 * player movement — the most common thing a player does — never entered the lane at all.
 *
 * <p>Kept Minecraft-free for the same reason as {@link VanillaCancelGate} and
 * {@link dev.nodera.endpoint.lane.BlockCaptureRules}: the bridge does the event plumbing, every
 * <em>judgement</em> lives here where a test can drive a movement trace tick by tick.
 *
 * <p>What it decides, per sample:
 * <ul>
 *   <li><b>Off lane</b> — the region is not on this node's lane. The anchor is dropped, because the
 *       next time this player is seen the last position this node observed says nothing about what
 *       the committee holds.</li>
 *   <li><b>First sample</b> — anchor only. A step needs a previous position; proposing one from
 *       nothing would be proposing where the player already is.</li>
 *   <li><b>Below the dead zone</b> — a standing player's position jitters by fractions of a tick's
 *       worth of motion. One signed action per player per tick for a player who has not moved is
 *       committee traffic that buys nothing; the anchor is left alone so slow drift still
 *       accumulates into a real step rather than being rounded away forever.</li>
 *   <li><b>A step</b> — one action, whose destination is exactly the sampled position. Never a
 *       rounded or clamped one: the committee is being told where the player is, and a clamp would
 *       make the lane's presence disagree with the vanilla world by design.</li>
 *   <li><b>A jump bigger than the envelope</b> — a lag spike, a mounted move, a chunk-load stall.
 *       Vanilla movement is continuous, so the segment is split into intermediate steps that each
 *       fit {@link MovementRules#MAX_STEP}, ending exactly on the sample.</li>
 *   <li><b>Beyond {@link #MAX_STEPS}</b> — a pearl, a portal, a {@code /tp}. That is a teleport,
 *       not walking: no honest replica would accept it as movement and no chain of fabricated
 *       intermediate steps should try to smuggle it through. Re-anchor and say nothing.</li>
 * </ul>
 *
 * @Thread-context per-player anchors live in a concurrent map; the bridge samples on the server
 *                 thread and the anchors are read nowhere else.
 */
public final class PlayerMovementCapture {

    /**
     * Displacement below which a sample is not worth an action, Q32.32 (1/32 block). A vanilla
     * player standing still is not perfectly still.
     */
    public static final long DEAD_ZONE = FixedVec3.ONE / 32;

    /** Most intermediate steps one sample may become before it is a teleport rather than a walk. */
    public static final int MAX_STEPS = 8;

    /** Why a sample did or did not become movement — carried so a drive can assert on it. */
    public enum Outcome {
        /** The sample became one or more signed steps. */
        CAPTURED,
        /** First sample for this player on the lane: the anchor is now set. */
        ANCHORED,
        /** The player has not meaningfully moved. */
        IDLE,
        /** The region is not on this node's lane. */
        OFF_LANE,
        /** The jump is too large to be walking; the anchor is re-seeded instead. */
        TELEPORT_RESYNC
    }

    /** The decision, with the steps attached whether or not there are any. */
    public record Plan(List<MovePlayerAction> steps, Outcome outcome) {

        public Plan {
            steps = List.copyOf(steps);
        }

        static Plan of(Outcome outcome) {
            return new Plan(List.of(), outcome);
        }
    }

    private final Map<UUID, FixedVec3> anchors = new ConcurrentHashMap<>();

    /**
     * Sample one player's position for one tick.
     *
     * @param player   the player's id.
     * @param onLane   whether this node's lane holds the player's current region.
     * @param position where the vanilla player actually is.
     * @return the steps to propose (possibly none) and why.
     */
    public Plan observe(UUID player, boolean onLane, FixedVec3 position) {
        if (player == null || position == null) {
            return Plan.of(Outcome.OFF_LANE);
        }
        if (!onLane) {
            anchors.remove(player);
            return Plan.of(Outcome.OFF_LANE);
        }
        FixedVec3 anchor = anchors.get(player);
        if (anchor == null) {
            anchors.put(player, position);
            return Plan.of(Outcome.ANCHORED);
        }
        long dx = position.x() - anchor.x();
        long dy = position.y() - anchor.y();
        long dz = position.z() - anchor.z();
        long reach = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (reach <= DEAD_ZONE) {
            return Plan.of(Outcome.IDLE);
        }
        int steps = (int) Math.min(Integer.MAX_VALUE,
                (reach + MovementRules.MAX_STEP - 1) / MovementRules.MAX_STEP);
        if (steps > MAX_STEPS) {
            anchors.put(player, position);
            return Plan.of(Outcome.TELEPORT_RESYNC);
        }
        List<MovePlayerAction> plan = new ArrayList<>(steps);
        for (int i = 1; i <= steps; i++) {
            // Integer interpolation on the fixed-point coordinates themselves: the last step is
            // exactly `position`, and no intermediate step can exceed MAX_STEP on any axis
            // because floor(d*i/n) - floor(d*(i-1)/n) <= ceil(|d|/n) <= MAX_STEP by construction.
            plan.add(new MovePlayerAction(i == steps ? position : new FixedVec3(
                    anchor.x() + Math.floorDiv(dx * i, steps),
                    anchor.y() + Math.floorDiv(dy * i, steps),
                    anchor.z() + Math.floorDiv(dz * i, steps))));
        }
        anchors.put(player, position);
        return new Plan(plan, Outcome.CAPTURED);
    }

    /** Forget a player (logout, level change, lane teardown). */
    public void forget(UUID player) {
        anchors.remove(player);
    }

    /** Forget everyone — the lane itself went away. */
    public void clear() {
        anchors.clear();
    }

    /** The last position this node proposed for {@code player}, for diagnostics and tests. */
    public Optional<FixedVec3> anchor(UUID player) {
        return Optional.ofNullable(anchors.get(player));
    }
}
