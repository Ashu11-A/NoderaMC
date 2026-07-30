package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33 / issue #36: the author/operator-signed, key-bound permission grant. */
final class WorldPermissionGrantTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
    }

    @Test
    void createSignsAndBindsSubjectKey() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                subject.publicKeyBytes(), WorldRole.OPERATOR, 1L);
        assertTrue(g.verifySignature());
        assertEquals(WorldPermissionGrant.V2, g.version());
        assertEquals(WorldRole.OPERATOR, g.role());
        assertEquals(subject.nodeId(), g.subject());
        assertEquals(subject.publicKeyBytes(), g.subjectPublicKey());
    }

    @Test
    void v2CanonicalRoundTrip() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                subject.publicKeyBytes(), WorldRole.BANNED, 7L);
        CanonicalWriter w = new CanonicalWriter();
        g.encode(w);
        WorldPermissionGrant back = WorldPermissionGrant.decode(new CanonicalReader(w.toBytes()));
        assertEquals(g, back);
        assertTrue(back.verifySignature());
        assertEquals(subject.publicKeyBytes(), back.subjectPublicKey());
    }

    @Test
    void v1CanonicalRoundTripKeepsEmptySubjectKeyAndValidSignature() {
        // A grant minted by an older build (key-less v1) must still decode and verify — the
        // signedPortion re-encodes in v1 so the legacy signature stays valid.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.createLegacyV1(author, worldId(),
                subject.nodeId(), WorldRole.MEMBER, 1L);
        assertEquals(WorldPermissionGrant.V1, g.version());
        assertTrue(g.subjectPublicKey().isEmpty());
        assertTrue(g.verifySignature());
        CanonicalWriter w = new CanonicalWriter();
        g.encode(w);
        WorldPermissionGrant back = WorldPermissionGrant.decode(new CanonicalReader(w.toBytes()));
        assertEquals(g, back);
        assertTrue(back.verifySignature());
        assertTrue(back.subjectPublicKey().isEmpty());
    }

    @Test
    void tamperedRoleFailsVerification() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                subject.publicKeyBytes(), WorldRole.MEMBER, 1L);
        WorldPermissionGrant tampered = new WorldPermissionGrant(g.version(), g.worldId(), g.subject(),
                g.subjectPublicKey(), WorldRole.OWNER, g.grantVersion(), g.granter(),
                g.granterPublicKey(), g.signature());
        assertFalse(tampered.verifySignature());
    }

    @Test
    void tamperedSubjectKeyFailsVerification() {
        // Swapping the bound subject key must break the signature (it is inside signedPortion).
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        WorldPermissionGrant g = WorldPermissionGrant.create(author, worldId(), subject.nodeId(),
                subject.publicKeyBytes(), WorldRole.OPERATOR, 1L);
        WorldPermissionGrant tampered = new WorldPermissionGrant(g.version(), g.worldId(), g.subject(),
                attacker.publicKeyBytes(), g.role(), g.grantVersion(), g.granter(),
                g.granterPublicKey(), g.signature());
        assertFalse(tampered.verifySignature());
    }

    @Test
    void v1GrantCannotCarrySubjectKey() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        assertThrows(IllegalArgumentException.class, () -> new WorldPermissionGrant(
                WorldPermissionGrant.V1, worldId(), subject.nodeId(), subject.publicKeyBytes(),
                WorldRole.MEMBER, 1L, author.nodeId(), author.publicKeyBytes(), Bytes.empty()));
    }

    @Test
    void v2CreateRequiresNonEmptySubjectKey() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity subject = NodeIdentity.generate();
        assertThrows(IllegalArgumentException.class, () -> WorldPermissionGrant.create(
                author, worldId(), subject.nodeId(), Bytes.empty(), WorldRole.MEMBER, 1L));
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
