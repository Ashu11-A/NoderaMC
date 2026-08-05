package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.WorldRole;

/**
 * Issue #36 (F3/F4): the pure op-sync decision — what {@link OperatorBridge} should do for a player
 * given their effective {@link WorldRole} and whether the bridge currently ops them. Kept free of any
 * Minecraft type so the full matrix is unit-testable off a live server.
 */
public final class OpSyncDecision {

    private OpSyncDecision() {
    }

    /** The action the bridge should take. */
    public enum Action {
        /** Grant operator. */ OP,
        /** Revoke the op the bridge previously granted. */ DEOP,
        /** BANNED — remove from the live game. */ DISCONNECT,
        /** No change. */ NONE
    }

    /**
     * @param role      the player's effective role, or {@code null} for "no verified Nodera identity"
     *                  (treated like MEMBER).
     * @param oppedByUs whether the bridge currently ops this player.
     * @return the action to take.
     */
    public static Action decide(WorldRole role, boolean oppedByUs) {
        return decide(role, oppedByUs, false);
    }

    /**
     * As {@link #decide(WorldRole, boolean)}, additionally refusing to take anything away from the
     * person whose game this is.
     *
     * <h2>Why this case exists</h2>
     *
     * <p>On an integrated server there is exactly one player who owns the process, the save file and
     * the machine. Every input to the role decision above can be wrong about them for reasons that
     * have nothing to do with what they are entitled to: their companion worker was restarting when
     * they joined, so no delegation was signed and their persistent identity was invisible; the
     * permission set belongs to a world they copied rather than authored; a grant had not gossiped
     * to this node yet. Each of those produced {@code MEMBER}, and {@code MEMBER} while opped means
     * {@code DEOP}.
     *
     * <p>The cost of being wrong here is not symmetric with the cost of being wrong the other way.
     * Wrongly opping a stranger is bounded — the world's peers still verify every signed grant and
     * nothing on the network changes hands. Wrongly de-opping the owner takes administration of a
     * world away from the person sitting in front of it, permanently as far as they can tell,
     * because {@code /op} is itself gated on being an operator.
     *
     * <p>So it is a floor, not a grant: it can only suppress a {@code DEOP} or a {@code DISCONNECT},
     * it never manufactures an {@code OP} that the role model did not ask for, and it has no meaning
     * at all on a dedicated server, where no player owns the process.
     *
     * @param role           the player's effective role, or {@code null} for "no verified identity".
     * @param oppedByUs      whether the bridge currently ops this player.
     * @param isProcessOwner whether this player owns the integrated server they are playing on.
     * @return the action to take.
     */
    public static Action decide(WorldRole role, boolean oppedByUs, boolean isProcessOwner) {
        Action action = decideByRole(role, oppedByUs);
        if (isProcessOwner && (action == Action.DEOP || action == Action.DISCONNECT)) {
            return Action.NONE;
        }
        return action;
    }

    private static Action decideByRole(WorldRole role, boolean oppedByUs) {
        if (role != null && !role.canJoin()) {
            return Action.DISCONNECT; // BANNED overrides everything
        }
        if (role != null && role.isOperator()) {
            return oppedByUs ? Action.NONE : Action.OP;
        }
        // MEMBER / unknown: revoke only an op WE granted (a role downgrade), never a foreign op.
        return oppedByUs ? Action.DEOP : Action.NONE;
    }
}
