package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33: the authenticated per-world permission evaluator. */
final class WorldPermissionsTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
    }

    @Test
    void authorIsAlwaysOwner() {
        NodeIdentity author = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());
        assertEquals(WorldRole.OWNER, perms.roleOf(author.nodeId()));
        assertTrue(perms.isOperator(author.nodeId()));
        assertTrue(perms.canJoin(author.nodeId()));
    }

    @Test
    void authorGrantsOperatorAndBan() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity bob = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());

        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), WorldRole.OPERATOR, 1L)));
        assertTrue(perms.isOperator(alice.nodeId()));

        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), bob.nodeId(), WorldRole.BANNED, 1L)));
        assertFalse(perms.canJoin(bob.nodeId()));
    }

    @Test
    void rejectsGrantFromNonAuthorNonOperator() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity stranger = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());
        // A stranger (MEMBER, no operator power) cannot grant.
        assertFalse(perms.apply(WorldPermissionGrant.create(
                stranger, worldId(), victim.nodeId(), WorldRole.OPERATOR, 1L)));
        assertEquals(WorldRole.MEMBER, perms.roleOf(victim.nodeId()));
    }

    @Test
    void newerVersionSupersedesOlder() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), WorldRole.OPERATOR, 5L)));
        // A stale (older) grant is ignored.
        assertFalse(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), WorldRole.MEMBER, 3L)));
        assertTrue(perms.isOperator(alice.nodeId()));
        // A newer grant (ban) supersedes.
        assertTrue(perms.apply(WorldPermissionGrant.create(
                author, worldId(), alice.nodeId(), WorldRole.BANNED, 6L)));
        assertFalse(perms.canJoin(alice.nodeId()));
    }

    @Test
    void authorCannotBeDemoted() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity op = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());
        perms.apply(WorldPermissionGrant.create(author, worldId(), op.nodeId(), WorldRole.OPERATOR, 1L));
        // An operator tries to ban the author → rejected; author stays OWNER.
        assertFalse(perms.apply(WorldPermissionGrant.create(
                op, worldId(), author.nodeId(), WorldRole.BANNED, 9L)));
        assertEquals(WorldRole.OWNER, perms.roleOf(author.nodeId()));
    }

    @Test
    void wrongWorldRejected() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId());
        Bytes otherWorld = new dev.nodera.core.crypto.HashService().sha256("other".getBytes());
        assertFalse(perms.apply(WorldPermissionGrant.create(
                author, otherWorld, alice.nodeId(), WorldRole.OPERATOR, 1L)));
    }
}
