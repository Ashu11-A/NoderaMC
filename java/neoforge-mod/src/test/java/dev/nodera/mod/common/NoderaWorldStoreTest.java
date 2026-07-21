package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.WorldIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 33: the per-world save-folder identity file. */
final class NoderaWorldStoreTest {

    private static WorldIdentity sampleShared() {
        NodeIdentity author = NodeIdentity.generate();
        Bytes root = new dev.nodera.core.crypto.HashService().sha256("seed".getBytes());
        return WorldIdentity.create(author, root, 123L, true, true, false, Bytes.empty());
    }

    @Test
    void writeThenReadRoundTrips(@TempDir Path save) throws Exception {
        WorldIdentity id = sampleShared();
        NoderaWorldStore.write(save, id);
        Optional<WorldIdentity> back = NoderaWorldStore.read(save);
        assertTrue(back.isPresent());
        assertEquals(id, back.get());
        assertTrue(back.get().verifySignature());
    }

    @Test
    void isSharedReflectsPersistedFlag(@TempDir Path save) throws Exception {
        assertFalse(NoderaWorldStore.isShared(save)); // no file yet
        NoderaWorldStore.write(save, sampleShared());
        assertTrue(NoderaWorldStore.isShared(save));
    }

    @Test
    void missingFileReadsEmpty(@TempDir Path save) {
        assertTrue(NoderaWorldStore.read(save).isEmpty());
    }

    @Test
    void corruptFileReadsEmptyNotThrow(@TempDir Path save) throws Exception {
        java.nio.file.Files.write(NoderaWorldStore.fileIn(save), new byte[]{1, 2, 3, 4});
        assertTrue(NoderaWorldStore.read(save).isEmpty());
    }
}
