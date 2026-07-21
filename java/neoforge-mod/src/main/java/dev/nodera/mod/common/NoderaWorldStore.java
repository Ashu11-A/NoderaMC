package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.WorldIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Task 33: reads and writes a world's {@link WorldIdentity} record in its save folder as
 * {@code nodera-world.dat}. This is the per-world config file the request asks for — it assigns the
 * world a unique hash (the {@code worldId}), records the original author, and persists whether the
 * world has been "Opened to Nodera", so re-opening the save restores its shared status.
 *
 * <p>Pure file IO over the canonical {@link WorldIdentity} encoding — no Minecraft types — so it is
 * unit-testable against a temp directory. Writes are atomic (temp file + move).
 *
 * @Thread-context call on the server thread (alongside the save it belongs to).
 */
public final class NoderaWorldStore {

    /** The file name written into the save's root directory. */
    public static final String FILE_NAME = "nodera-world.dat";

    private NoderaWorldStore() {
    }

    /** Resolve the identity file inside a save's root directory. */
    public static Path fileIn(Path saveRoot) {
        return saveRoot.resolve(FILE_NAME);
    }

    /** @return the persisted {@link WorldIdentity} for this save, if present + decodable. */
    public static Optional<WorldIdentity> read(Path saveRoot) {
        Path file = fileIn(saveRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return Optional.of(WorldIdentity.decode(new CanonicalReader(Bytes.unsafeWrap(bytes))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // corrupt/old file → treat as unshared, do not crash the world
        }
    }

    /** Atomically write a {@link WorldIdentity} into the save's root directory. */
    public static void write(Path saveRoot, WorldIdentity identity) throws IOException {
        CanonicalWriter w = new CanonicalWriter();
        identity.encode(w);
        dev.nodera.storage.io.AtomicFileWriter.write(fileIn(saveRoot), w.toBytes().toArray());
    }

    /** @return whether this save has been opened to Nodera (a persisted, shared identity exists). */
    public static boolean isShared(Path saveRoot) {
        return read(saveRoot).map(WorldIdentity::shared).orElse(false);
    }
}
