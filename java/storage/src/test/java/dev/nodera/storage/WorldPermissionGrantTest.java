package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33: the author/operator-signed permission grant. */
final class WorldPermissionGrantTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
    }

    @Test
    void createSignsAndVerifies() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                WorldRole.OPERATOR, 1L);
        assertTrue(g.verifySignature());
        assertEquals(WorldRole.OPERATOR, g.role());
        assertEquals(subject.nodeId(), g.subject());
    }

    @Test
    void canonicalRoundTrip() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                WorldRole.BANNED, 7L);
        CanonicalWriter w = new CanonicalWriter();
        g.encode(w);
        WorldPermissionGrant back = WorldPermissionGrant.decode(new CanonicalReader(w.toBytes()));
        assertEquals(g, back);
        assertTrue(back.verifySignature());
        assertEquals(WorldRole.BANNED, back.role());
    }

    @Test
    void tamperedRoleFailsVerification() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                WorldRole.MEMBER, 1L);
        WorldPermissionGrant tampered = new WorldPermissionGrant(g.worldId(), g.subject(),
                WorldRole.OWNER, g.grantVersion(), g.granter(), g.granterPublicKey(), g.signature());
        assertFalse(tampered.verifySignature());
    }

    @Test
    void roleSemantics() {
        assertTrue(WorldRole.OWNER.isOperator());
        assertTrue(WorldRole.OPERATOR.isOperator());
        assertFalse(WorldRole.MEMBER.isOperator());
        assertFalse(WorldRole.BANNED.canJoin());
        assertTrue(WorldRole.MEMBER.canJoin());
        assertEquals(WorldRole.OWNER, WorldRole.fromOrdinal(WorldRole.OWNER.ordinal()));
    }
}
