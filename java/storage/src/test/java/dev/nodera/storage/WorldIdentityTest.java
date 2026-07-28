package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33: the author-signed per-world identity record. */
final class WorldIdentityTest {

    private static Bytes root() {
        return new dev.nodera.core.crypto.HashService().sha256("genesis".getBytes());
    }

    @Test
    void createSignsAndVerifies() {
        NodeIdentity author = NodeIdentity.generate();
        WorldIdentity id = WorldIdentity.create(author, root(), 1000L, true, true, false, Bytes.empty());
        assertTrue(id.verifySignature());
        assertTrue(id.isAuthor(author.nodeId()));
        assertEquals(author.nodeId(), id.authorNodeId());
        assertTrue(id.shared());
    }

    @Test
    void canonicalRoundTrip() {
        NodeIdentity author = NodeIdentity.generate();
        WorldIdentity id = WorldIdentity.create(author, root(), 42L, true, false, true,
                Bytes.fromHex("aabbcc"));
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        id.encode(w);
        WorldIdentity back = WorldIdentity.decode(new CanonicalReader(w.toBytes()));
        assertEquals(id, back);
        assertTrue(back.verifySignature());
    }

    @Test
    void worldIdIsStablePerAuthorAndTime() {
        NodeIdentity a = NodeIdentity.generate();
        Bytes r = root();
        Bytes id1 = WorldIdentity.deriveWorldId(r, a.publicKeyBytes(), 5L);
        Bytes id2 = WorldIdentity.deriveWorldId(r, a.publicKeyBytes(), 5L);
        Bytes id3 = WorldIdentity.deriveWorldId(r, a.publicKeyBytes(), 6L);
        assertEquals(id1, id2);
        assertNotEquals(id1, id3);
    }

    @Test
    void tamperedShareStateFailsVerification() {
        NodeIdentity author = NodeIdentity.generate();
        WorldIdentity id = WorldIdentity.create(author, root(), 1L, true, true, false, Bytes.empty());
        // Flip `shared` but keep the old signature → verification must fail.
        WorldIdentity tampered = new WorldIdentity(id.worldId(), id.authorNodeId(),
                id.authorPublicKey(), id.createdAtEpoch(), false, id.listedOnTracker(),
                id.encrypted(), id.manifestRef(), id.signature());
        assertFalse(tampered.verifySignature());
    }

    @Test
    void onlyAuthorMayResign() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity other = NodeIdentity.generate();
        WorldIdentity id = WorldIdentity.create(author, root(), 1L, true, true, false, Bytes.empty());
        assertThrows(IllegalArgumentException.class,
                () -> id.resign(other, false, false, false, Bytes.empty()));
        WorldIdentity updated = id.resign(author, false, false, true, Bytes.fromHex("dd"));
        assertTrue(updated.verifySignature());
        assertFalse(updated.shared());
        assertTrue(updated.encrypted());
        assertEquals(id.worldId(), updated.worldId());
    }

    @Test
    void aPinnedIdentityKeepsItsIdWhenTheGenesisRootMoves() {
        NodeIdentity author = NodeIdentity.generate();
        WorldIdentity first = WorldIdentity.create(author, root(), 1000L, true, true, false,
                Bytes.empty());

        // The same save, re-shared after its genesis file went missing and was re-certified from a
        // different set of loaded chunks. Deriving again is how one world became several on the
        // network: a different root is a different id, and both stayed announced.
        Bytes movedRoot = new dev.nodera.core.crypto.HashService().sha256("re-certified".getBytes());
        assertNotEquals(first.worldId(),
                WorldIdentity.deriveWorldId(movedRoot, author.publicKeyBytes(), 1000L));

        WorldIdentity second = WorldIdentity.createPinned(author, first.worldId(), 1000L, true, true,
                false, Bytes.fromHex("aa"));
        assertEquals(first.worldId(), second.worldId());
        assertTrue(second.verifySignature());
        assertTrue(second.isAuthor(author.nodeId()));
    }

    @Test
    void pinningDoesNotLetOneNodeSignAnotherNodesWorld() {
        NodeIdentity author = NodeIdentity.generate();
        NodeIdentity thief = NodeIdentity.generate();
        WorldIdentity mine = WorldIdentity.create(author, root(), 7L, true, true, false, Bytes.empty());

        // Naming an id is not claiming it. The thief can produce a record carrying the id, but it
        // is signed by, and therefore authored by, the thief — every peer that already knows the
        // world's author sees a different author, which is the check that matters.
        WorldIdentity forged = WorldIdentity.createPinned(thief, mine.worldId(), 7L, true, true,
                false, Bytes.empty());
        assertEquals(mine.worldId(), forged.worldId());
        assertFalse(forged.isAuthor(author.nodeId()));
        assertNotEquals(mine.authorNodeId(), forged.authorNodeId());
    }
}
