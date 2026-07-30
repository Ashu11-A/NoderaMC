package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #36 (F5): durable grant storage + tamper/corruption tolerance. */
final class WorldPermissionStoreTest {

    private static Bytes worldId() {
        return new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
    }

    @Test
    void roundTripsThroughReload(@TempDir Path saveRoot) throws IOException {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity alice = NodeIdentity.generate();
        NodeIdentity bob = NodeIdentity.generate();
        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId(),
                author.publicKeyBytes());
        perms.apply(WorldPermissionGrant.create(author, worldId(), alice.nodeId(),
                alice.publicKeyBytes(), WorldRole.OPERATOR, 1L));
        perms.apply(WorldPermissionGrant.create(author, worldId(), bob.nodeId(),
                bob.publicKeyBytes(), WorldRole.BANNED, 1L));

        WorldPermissionStore.write(saveRoot, perms.snapshot());

        // Reload into a fresh evaluator — apply() re-verifies every signature.
        WorldPermissions reloaded = new WorldPermissions(worldId(), author.nodeId(),
                author.publicKeyBytes());
        List<WorldPermissionGrant> grants = WorldPermissionStore.read(saveRoot);
        assertEquals(2, grants.size());
        for (WorldPermissionGrant g : grants) {
            assertTrue(reloaded.apply(g));
        }
        assertTrue(reloaded.isOperator(alice.nodeId(), alice.publicKeyBytes()));
        assertEquals(WorldRole.BANNED, reloaded.roleOf(bob.nodeId(), bob.publicKeyBytes()));
    }

    @Test
    void absentFileIsEmpty(@TempDir Path saveRoot) {
        assertTrue(WorldPermissionStore.read(saveRoot).isEmpty());
    }

    @Test
    void corruptFileIsEmpty(@TempDir Path saveRoot) throws IOException {
        Files.write(WorldPermissionStore.fileIn(saveRoot), new byte[]{1, 2, 3, 4, 5});
        assertTrue(WorldPermissionStore.read(saveRoot).isEmpty());
    }

    @Test
    void tamperedGrantIsDroppedOnReapply(@TempDir Path saveRoot) throws IOException {
        // A forged grant persisted to disk fails its signature check on reapply and confers nothing.
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity attacker = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        // Attacker signs a grant claiming the author as granter but with their own key.
        WorldPermissionGrant unsigned = new WorldPermissionGrant(WorldPermissionGrant.V2, worldId(),
                victim.nodeId(), victim.publicKeyBytes(), WorldRole.OPERATOR, 1L, author.nodeId(),
                attacker.publicKeyBytes(), Bytes.empty());
        Bytes sig = attacker.sign(unsigned.signedPortion());
        WorldPermissionGrant forged = new WorldPermissionGrant(WorldPermissionGrant.V2, worldId(),
                victim.nodeId(), victim.publicKeyBytes(), WorldRole.OPERATOR, 1L, author.nodeId(),
                attacker.publicKeyBytes(), sig);
        WorldPermissionStore.write(saveRoot, List.of(forged));

        WorldPermissions perms = new WorldPermissions(worldId(), author.nodeId(),
                author.publicKeyBytes());
        for (WorldPermissionGrant g : WorldPermissionStore.read(saveRoot)) {
            perms.apply(g);
        }
        assertEquals(WorldRole.MEMBER, perms.roleOf(victim.nodeId(), victim.publicKeyBytes()));
    }
}
