package dev.nodera.mod.server.entity;

import net.minecraft.world.entity.Pose;

import java.util.EnumMap;
import java.util.Map;

/**
 * Nodera's own wire codes for Minecraft's {@link Pose} (Task 14 phase 6, retiring the sharpest edge
 * of {@code Plan.7} R5).
 *
 * <p>Ghost state is <b>hashed</b>, and it used to carry {@code entity.getPose().ordinal()}. That
 * makes the declaration order of an enum <em>in somebody else's codebase</em> part of Nodera's
 * consensus contract: Mojang inserting a pose, or reordering two for readability, silently changes
 * every state root that has an entity in it — with nothing in this repository changing, no test
 * failing, and no way to tell afterwards which side of the change a stored root came from.
 *
 * <p>A Nodera-owned table ends that. Upstream can do what it likes with its own enum; the numbers on
 * our wire are ours.
 *
 * <p><b>Assigning a code is permanent.</b> Append; never renumber, never reuse. A pose added
 * upstream that has no code here is written as {@link #UNKNOWN_POSE} rather than guessed at — a
 * wrong pose in a hashed root is worse than an unrecognised one, because two peers on different
 * Minecraft builds would each guess differently and disagree about the root while agreeing about
 * the world.
 *
 * <p>Thread-context: immutable static table; any thread.
 */
public final class PoseCodes {

    private PoseCodes() {}

    /**
     * The code written for a pose this build has no number for.
     *
     * <p>Deliberately a sentinel rather than a fallback to some "similar" pose: every peer writes
     * the same thing for a pose it does not know, so they agree with each other even while
     * disagreeing with a newer peer — which is a version-skew question the handshake answers, not
     * something to paper over here.
     */
    public static final int UNKNOWN_POSE = 0xFFFF;

    private static final Map<Pose, Integer> CODES = new EnumMap<>(Pose.class);

    static {
        // The vanilla 1.21 pose set, numbered from 1 in the order it happened to have at the time
        // this table was written. From here the numbers are ours and do not move.
        assign(Pose.STANDING, 1);
        assign(Pose.FALL_FLYING, 2);
        assign(Pose.SLEEPING, 3);
        assign(Pose.SWIMMING, 4);
        assign(Pose.SPIN_ATTACK, 5);
        assign(Pose.CROUCHING, 6);
        assign(Pose.LONG_JUMPING, 7);
        assign(Pose.DYING, 8);
        assign(Pose.CROAKING, 9);
        assign(Pose.USING_TONGUE, 10);
        assign(Pose.SITTING, 11);
        assign(Pose.ROARING, 12);
        assign(Pose.SNIFFING, 13);
        assign(Pose.EMERGING, 14);
        assign(Pose.DIGGING, 15);
        assign(Pose.SLIDING, 16);
        assign(Pose.SHOOTING, 17);
        assign(Pose.INHALING, 18);
    }

    /** Assign a code, tolerating a pose that does not exist in the Minecraft version being built. */
    private static void assign(Pose pose, int code) {
        if (pose != null) {
            CODES.put(pose, code);
        }
    }

    /**
     * The wire code for a pose.
     *
     * @param pose the pose, or {@code null}.
     * @return its permanent code, or {@link #UNKNOWN_POSE} for one this table does not name.
     * @Thread-context any thread.
     */
    public static int codeOf(Pose pose) {
        if (pose == null) {
            return UNKNOWN_POSE;
        }
        return CODES.getOrDefault(pose, UNKNOWN_POSE);
    }

    /** How many poses this build has numbered — used by the table's own snapshot test. */
    public static int assignedCount() {
        return CODES.size();
    }
}
