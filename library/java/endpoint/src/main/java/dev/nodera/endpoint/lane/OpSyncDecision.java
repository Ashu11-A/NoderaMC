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
