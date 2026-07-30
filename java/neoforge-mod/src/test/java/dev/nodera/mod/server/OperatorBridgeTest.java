package dev.nodera.mod.server;

import dev.nodera.endpoint.lane.OpSyncDecision;
import dev.nodera.core.identity.WorldRole;
import dev.nodera.endpoint.lane.OpSyncDecision.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #36 (F3/F4): the pure op-sync decision matrix (Minecraft-free). */
final class OperatorBridgeTest {

    @Test
    void operatorNotYetOppedGetsOpped() {
        assertEquals(Action.OP, OpSyncDecision.decide(WorldRole.OPERATOR, false));
        assertEquals(Action.OP, OpSyncDecision.decide(WorldRole.OWNER, false));
    }

    @Test
    void operatorAlreadyOppedIsLeftAlone() {
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.OPERATOR, true));
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.OWNER, true));
    }

    @Test
    void memberWeOppedIsDeopped() {
        // A downgrade from OPERATOR to MEMBER revokes the op the bridge granted.
        assertEquals(Action.DEOP, OpSyncDecision.decide(WorldRole.MEMBER, true));
    }

    @Test
    void memberWeDidNotOpIsUntouched() {
        // A server-configured op (not granted by the bridge) is never stomped.
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.MEMBER, false));
    }

    @Test
    void bannedIsAlwaysDisconnected() {
        assertEquals(Action.DISCONNECT, OpSyncDecision.decide(WorldRole.BANNED, false));
        assertEquals(Action.DISCONNECT, OpSyncDecision.decide(WorldRole.BANNED, true));
    }

    @Test
    void noVerifiedIdentityBehavesLikeMember() {
        assertEquals(Action.NONE, OpSyncDecision.decide(null, false));
        assertEquals(Action.DEOP, OpSyncDecision.decide(null, true));
    }
}
