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

    // --- the owner floor -------------------------------------------------------------------------

    @Test
    void theProcessOwnerIsNeverDeoppedByARoleLookup() {
        // The exact live failure: a creator joins their own shared world, their worker is not
        // reachable so nothing vouches for their persistent key, the permission set answers MEMBER,
        // and the bridge takes away the op it had granted. On the machine they own, it must not.
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.MEMBER, true, true));
        assertEquals(Action.NONE, OpSyncDecision.decide(null, true, true));
    }

    @Test
    void theProcessOwnerIsNeverDisconnectedByARoleLookup() {
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.BANNED, true, true));
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.BANNED, false, true));
    }

    @Test
    void theFloorNeverManufacturesAnOp() {
        // It suppresses withdrawals. It does not invent authority the role model did not grant, so
        // an un-opped member stays un-opped and an operator is still opped for the usual reason.
        assertEquals(Action.NONE, OpSyncDecision.decide(WorldRole.MEMBER, false, true));
        assertEquals(Action.OP, OpSyncDecision.decide(WorldRole.OPERATOR, false, true));
    }

    @Test
    void withoutTheFloorTheMatrixIsUnchanged() {
        // The two-argument form is the three-argument form with the floor down, so every existing
        // caller and every case above keeps its previous answer.
        assertEquals(OpSyncDecision.decide(WorldRole.MEMBER, true),
                OpSyncDecision.decide(WorldRole.MEMBER, true, false));
        assertEquals(OpSyncDecision.decide(WorldRole.BANNED, false),
                OpSyncDecision.decide(WorldRole.BANNED, false, false));
    }
}
