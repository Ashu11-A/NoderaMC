package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33 / issue #36: the authenticated, key-anchored per-world permission evaluator. */
final class WorldPermissionsTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
    }

    private static WorldPermissions perms(NodeIdentity author) {
        return new WorldPermissions(worldId(), author.nodeId(), author.publicKeyBytes());
    }

    /** Sign an attacker-chosen grant body — used to build forgeries the real API would never mint. */
    private static WorldPermissionGrant forge(NodeIdentity signer, Bytes worldId,
                                              NodeIdentity subject, WorldRole role, long grantVersion,
                                              NodeIdentity spoofedGranter, Bytes granterPublicKey) {
        WorldPermissionGrant unsigned = new WorldPermissionGrant(WorldPermissionGrant.V2, worldId,
                subject.nodeId(), subject.publicKeyBytes(), role, grantVersion,
                spoofedGranter.nodeId(), granterPublicKey, Bytes.empty());
        Bytes sig = signer.sign(unsigned.signedPortion());
        return new WorldPermissionGrant(WorldPermissionGrant.V2, worldId, subject.nodeId(),
                subject.publicKeyBytes(), role, grantVersion, spoofedGranter.nodeId(),
                granterPublicKey, sig);
    }

    @Test
    void authorIsAlwaysOwner() {
        NodeIdentity author = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertEquals(WorldRole.OWNER, perms.roleOf(author.nodeId(), author.publicKeyBytes()));
        assertTrue(perms.isOperator(author.nodeId(), author.publicKeyBytes()));
        assertTrue(perms.canJoin(author.nodeId()));
    }

    @Test
    void authorNodeIdWithWrongKeyIsNotOwner() {
        // F2b core: announcing the author's NodeId under an attacker key confers nothing.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertEquals(WorldRole.MEMBER, perms.roleOf(author.nodeId(), attacker.publicKeyBytes()));
        assertFalse(perms.isOperator(author.nodeId(), attacker.publicKeyBytes()));
    }

    @Test
    void authorGrantsOperatorAndBan() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity bob = NodeIdentity.generate();
        WorldPermissions perms = perms(author);

        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        assertTrue(perms.isOperator(alice.nodeId(), alice.publicKeyBytes()));

        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), bob.nodeId(), bob.publicKeyBytes(), WorldRole.BANNED, 1L)));
        assertFalse(perms.canJoin(bob.nodeId()));
    }

    @Test
    void grantedOperatorUnderWrongKeyGetsNothing() {
        // F2 core: alice is a keyed OPERATOR, but an attacker announcing alice's NodeId under their
        // own key inherits nothing — the role is bound to alice's key.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        assertTrue(perms.isOperator(alice.nodeId(), alice.publicKeyBytes()));
        assertFalse(perms.isOperator(alice.nodeId(), attacker.publicKeyBytes()));
        assertEquals(WorldRole.MEMBER, perms.roleOf(alice.nodeId(), attacker.publicKeyBytes()));
    }

    @Test
    void forgedAuthorSignedGrantWithAttackerKeyRejected() {
        // F2b attack: granter NodeId spoofed to the author, but signed with (and carrying) the
        // attacker's key. verifySignature() passes against that key; apply() must still reject
        // because the key is not the real author key.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        WorldPermissionGrant forged = forge(attacker, worldId(), victim, WorldRole.OPERATOR, 1L,
                author /* spoofed granter NodeId */, attacker.publicKeyBytes());
        assertTrue(forged.verifySignature(), "forgery self-verifies against the attacker key");
        assertFalse(perms.apply(forged), "but is rejected — granter key is not the author key");
        assertEquals(WorldRole.MEMBER, perms.roleOf(victim.nodeId(), victim.publicKeyBytes()));
    }

    @Test
    void v1GrantCannotConferOperator() {
        // F2: a key-less v1 grant may not carry operator power (the role would attach to a bare,
        // spoofable NodeId).
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertFalse(perms.apply(WorldPermissionGrant.createLegacyV1(
                author, worldId(), alice.nodeId(), WorldRole.OPERATOR, 1L)));
        // A v1 MEMBER grant is still accepted (coarse, NodeId-scoped, no privilege).
        assertTrue(perms.apply(WorldPermissionGrant.createLegacyV1(
                author, worldId(), alice.nodeId(), WorldRole.MEMBER, 1L)));
    }

    @Test
    void operatorMayGrantMemberButNotOperator() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();  // will be operator
        NodeIdentity bob = NodeIdentity.generate();
        NodeIdentity carol = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        // Operator alice grants bob MEMBER → allowed.
        assertTrue(perms.apply(WorldPermissionGrant.create(
                alice, worldId(), bob.nodeId(), bob.publicKeyBytes(), WorldRole.MEMBER, 1L)));
        // Operator alice tries to mint carol OPERATOR → rejected (OWNER-only authority).
        assertFalse(perms.apply(WorldPermissionGrant.create(
                alice, worldId(), carol.nodeId(), carol.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        assertFalse(perms.isOperator(carol.nodeId(), carol.publicKeyBytes()));
    }

    @Test
    void operatorAuthorityRequiresMatchingKey() {
        // An attacker who knows alice's operator NodeId but not her key cannot sign grants as her.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        // granter NodeId = alice, but signed with (and carrying) the attacker key.
        WorldPermissionGrant forged = forge(attacker, worldId(), victim, WorldRole.MEMBER, 1L,
                alice, attacker.publicKeyBytes());
        assertFalse(perms.apply(forged));
    }

    @Test
    void rejectsGrantFromNonAuthorNonOperator() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity stranger = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertFalse(perms.apply(WorldPermissionGrant.create(
                stranger, worldId(), victim.nodeId(), victim.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
        assertEquals(WorldRole.MEMBER, perms.roleOf(victim.nodeId(), victim.publicKeyBytes()));
    }

    @Test
    void newerVersionSupersedesOlder() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 5L)));
        // A stale (older) grant is ignored.
        assertFalse(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.MEMBER, 3L)));
        assertTrue(perms.isOperator(alice.nodeId(), alice.publicKeyBytes()));
        // A newer grant (ban) supersedes.
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), alice.publicKeyBytes(), WorldRole.BANNED, 6L)));
        assertFalse(perms.canJoin(alice.nodeId()));
    }

    @Test
    void authorCannotBeDemoted() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity op = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        perms.apply(WorldPermissionGrant.create(author, worldId(), op.nodeId(), op.publicKeyBytes(),
                WorldRole.OPERATOR, 1L));
        // An operator tries to ban the author → rejected; author stays OWNER.
        assertFalse(perms.apply(WorldPermissionGrant.create(
                op, worldId(), author.nodeId(), author.publicKeyBytes(), WorldRole.BANNED, 9L)));
        assertEquals(WorldRole.OWNER, perms.roleOf(author.nodeId(), author.publicKeyBytes()));
    }

    @Test
    void wrongWorldRejected() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        Bytes otherWorld = new dev.nodera.core.crypto.HashService().sha256("other".getBytes());
        assertFalse(perms.apply(WorldPermissionGrant.create(
                author, otherWorld, alice.nodeId(), alice.publicKeyBytes(), WorldRole.OPERATOR, 1L)));
    }

    @Test
    void snapshotReturnsAcceptedGrants() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity bob = NodeIdentity.generate();
        WorldPermissions perms = perms(author);
        perms.apply(WorldPermissionGrant.create(author, worldId(), alice.nodeId(),
                alice.publicKeyBytes(), WorldRole.OPERATOR, 1L));
        perms.apply(WorldPermissionGrant.create(author, worldId(), bob.nodeId(),
                bob.publicKeyBytes(), WorldRole.MEMBER, 1L));
        assertEquals(2, perms.snapshot().size());
    }
}
