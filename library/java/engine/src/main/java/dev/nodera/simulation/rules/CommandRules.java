package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.CommandAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;

import java.util.Optional;
import java.util.Set;

/**
 * The <b>deterministic command subset</b> (Task 16 / L-14).
 *
 * <p>Commands used to be outside the validated path entirely, and that is a bigger hole than it
 * sounds: an operator's {@code /setblock} mutated a delegated region behind the committee's back,
 * so the operator's world and every validator's replica silently diverged — the exact failure class
 * the whole validated lane exists to make impossible. Here a command is an ordinary signed action:
 * validated for authority and legality, committed by quorum, identical on every replica.
 *
 * <h2>What is admitted, and what is deliberately not</h2>
 *
 * <p>A command belongs in this lane only if its effect is a <b>pure function of committed state and
 * its own arguments</b>. {@code /setblock} and {@code /fill} qualify. Things that do not, and why
 * they are excluded rather than admitted-and-hoped-for:
 *
 * <ul>
 *   <li>{@code /time set} — the committed world time is a member-agreed <i>context input</i>
 *       ({@code RegionExecutionContext.committedWorldTime}, L-6), not region state. Mutating it
 *       from inside a batch would make one replica's context disagree with the others' for the
 *       rest of the batch. It belongs to the coordinator lane that agrees the input, not here.</li>
 *   <li>{@code /give}, {@code /tp} to a player — these need the target player's root presence,
 *       which may live in another region; they are the entity-transfer lane's business, not a
 *       block command's.</li>
 *   <li>Anything reading the server's private view or a clock — inadmissible by construction.</li>
 * </ul>
 *
 * <h2>Authority</h2>
 *
 * <p>Authority is checked <b>in the engine</b>, against the committee-agreed operator set on the
 * execution context — not at the capture point. That matters: a capture-point check is advice a
 * modified client can skip, whereas a rejection here happens on every honest validator
 * independently. The operator set is derived from the signed grant chain (L-54 gossips it to every
 * co-hosting peer), so all members evaluate the same predicate.
 *
 * @Thread-context stateless; thread-confined per call.
 */
public final class CommandRules {

    /**
     * Largest volume a single {@code /fill} may touch.
     *
     * <p>Bounded because a batch's execution time is part of what keeps the tick budget honest: an
     * unbounded fill would let one authorised action stall every validator for seconds and trip the
     * lag handoff. Vanilla bounds it for the same reason.
     */
    public static final long MAX_FILL_VOLUME = 32_768L;

    private CommandRules() {
    }

    /**
     * Validate a command.
     *
     * @param view      the read-only region state.
     * @param env       the action envelope (its {@code actor} is the caller).
     * @param command   the command.
     * @param operators the committee-agreed operator set.
     * @param placeable the rule set's placeable-block predicate — a command may not mint a block a
     *                  player could not place, or {@code /setblock} would become a way to conjure
     *                  network-computed states (a powered wire, an extended piston head).
     * @return a rejection, or empty when the command is legal.
     */
    public static Optional<ActionRejection> validate(
            RegionWorldView view, ActionEnvelope env, CommandAction command,
            Set<NodeId> operators, java.util.function.IntPredicate placeable) {
        if (operators == null || !operators.contains(env.actor())) {
            // Not an operator here. Deterministic on every member, because the operator set is a
            // committee-agreed input rather than each node's local opinion.
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.UNSUPPORTED_ACTION));
        }
        if (!view.inOwnedRegion(command.from()) || !view.inOwnedRegion(command.to())) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        return switch (command.kind()) {
            case SETBLOCK, FILL -> validateBlockWrite(env, command, placeable);
            // Present in the wire enum but not admitted to the validated lane; see the class note.
            case TIME_SET -> Optional.of(
                    new ActionRejection(env, ActionRejection.Reason.UNSUPPORTED_ACTION));
        };
    }

    private static Optional<ActionRejection> validateBlockWrite(
            ActionEnvelope env, CommandAction command, java.util.function.IntPredicate placeable) {
        if (!placeable.test(command.arg())) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ILLEGAL_BLOCK));
        }
        if (command.volume() > MAX_FILL_VOLUME) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        return Optional.empty();
    }

    /**
     * Apply a validated command.
     *
     * <p>Iteration is in canonical (y, z, x) order so the mutation buffer — and therefore the
     * resulting delta's bytes — is identical on every replica, not merely equivalent.
     *
     * @param state   the mutable working state.
     * @param env     the action envelope.
     * @param command the validated command.
     * @param rng     the per-action deterministic RNG.
     */
    public static void apply(MutableRegionState state, ActionEnvelope env, CommandAction command,
                             DeterministicRandom rng) {
        if (command.kind() == CommandAction.Kind.TIME_SET) {
            return; // refused in validate; never reached
        }
        int minX = Math.min(command.from().x(), command.to().x());
        int maxX = Math.max(command.from().x(), command.to().x());
        int minY = Math.min(command.from().y(), command.to().y());
        int maxY = Math.max(command.from().y(), command.to().y());
        int minZ = Math.min(command.from().z(), command.to().z());
        int maxZ = Math.max(command.from().z(), command.to().z());
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    state.setBlock(new NBlockPos(x, y, z), command.arg(), env, rng);
                }
            }
        }
    }
}
