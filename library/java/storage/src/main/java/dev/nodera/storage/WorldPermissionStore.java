package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #36 (flaw F5): durable storage for a world's {@link WorldPermissionGrant}s, written as
 * {@code nodera-permissions.dat} beside {@code nodera-world.dat} in the save root. Before this the
 * permission set lived only in memory and every op/grant vanished on restart.
 *
 * <p>The stored set is <b>not</b> trusted blindly: each grant carries its own author/operator
 * signature, so {@link WorldPermissions#apply} re-verifies every one on reload — tampering with the
 * file is detected for free (a forged grant fails its signature and is dropped). A corrupt or
 * unreadable file is treated as an empty set (warn, never crash the world).
 *
 * <p>Pure file IO over the canonical grant encoding — no Minecraft types — so it is unit-testable
 * against a temp directory. Writes are atomic (temp file + move).
 *
 * <p>Wire form: {@code [u16 WORLD_PERMISSION_SET][u16 version][u32 count][WorldPermissionGrant...]}.
 *
 * @Thread-context call on the server thread (alongside the save it belongs to).
 */
public final class WorldPermissionStore {

    private static final Logger LOG = System.getLogger("NoderaPermissions");

    /** The file name written into the save's root directory. */
    public static final String FILE_NAME = "nodera-permissions.dat";

    private static final int ENCODING_VERSION = 1;

    private WorldPermissionStore() {
    }

    /** Resolve the permissions file inside a save's root directory. */
    public static Path fileIn(Path saveRoot) {
        return saveRoot.resolve(FILE_NAME);
    }

    /**
     * Read the persisted grants for this save. An absent file yields an empty list; a corrupt or
     * unreadable file yields an empty list plus a warning (never a crash).
     *
     * @param saveRoot the save's root directory.
     * @return the decoded grants (order-preserving), possibly empty.
     */
    public static List<WorldPermissionGrant> read(Path saveRoot) {
        Path file = fileIn(saveRoot);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            CanonicalReader r = new CanonicalReader(Bytes.unsafeWrap(bytes));
            int tag = r.readU16();
            if (tag != TypeTags.WORLD_PERMISSION_SET) {
                LOG.log(Level.WARNING, "Nodera: {0} has unexpected tag {1} — ignoring", FILE_NAME, tag);
                return List.of();
            }
            r.readVersion(ENCODING_VERSION);
            long count = r.readU32();
            List<WorldPermissionGrant> grants = new ArrayList<>();
            for (long i = 0; i < count; i++) {
                grants.add(WorldPermissionGrant.decode(r));
            }
            return grants;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Nodera: " + FILE_NAME + " is corrupt or unreadable ("
                    + e + ") — starting with no persisted grants");
            return List.of();
        }
    }

    /**
     * Atomically write the given grants into the save's root directory.
     *
     * @param saveRoot the save's root directory.
     * @param grants   the accepted grants to persist (typically {@code WorldPermissions.snapshot()}).
     * @throws IOException if the write fails.
     */
    public static void write(Path saveRoot, List<WorldPermissionGrant> grants) throws IOException {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.WORLD_PERMISSION_SET).writeU16(ENCODING_VERSION);
        w.writeU32(grants.size());
        for (WorldPermissionGrant g : grants) {
            g.encode(w);
        }
        AtomicFileWriter.write(fileIn(saveRoot), w.toBytes().toArray());
    }
}
