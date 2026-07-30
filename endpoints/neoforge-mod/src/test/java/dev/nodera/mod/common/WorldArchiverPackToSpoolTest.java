package dev.nodera.mod.common;

import dev.nodera.distribution.WorldArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #37 / L-51: {@link WorldArchiver#packToSpool} packs the save into the spool {@code .nar}
 * atomically (no leftover {@code .tmp}) and round-trips through {@link WorldArchive#unpack}. MC-free.
 */
final class WorldArchiverPackToSpoolTest {

    @Test
    void packsAtomicallyAndRoundTrips(@TempDir Path saveRoot) throws Exception {
        // A minimal save tree.
        Files.writeString(saveRoot.resolve("level.dat"), "level");
        Path region = Files.createDirectories(saveRoot.resolve("region"));
        Files.write(region.resolve("r.0.0.mca"), new byte[]{1, 2, 3});
        // The host's private signing key lives under nodera/ — it must NEVER enter the blob.
        Path priv = Files.createDirectories(saveRoot.resolve("nodera"));
        Files.writeString(priv.resolve("server-identity.bin"), "SECRET");

        String worldIdHex = "deadbeefcafe";
        Path nar = WorldArchiver.packToSpool(saveRoot, worldIdHex);

        assertTrue(Files.isRegularFile(nar), "spooled .nar exists");
        // Atomic move leaves no .tmp behind.
        assertFalse(Files.exists(nar.resolveSibling(nar.getFileName() + ".tmp")),
                "no leftover .tmp");
        assertEqualsIgnoreCase(worldIdHex.substring(0, 12),
                nar.getFileName().toString().replace(".nar", ""));

        // The blob round-trips and includes the save files but NOT the nodera/ subtree.
        var unpacked = WorldArchive.unpack(Files.readAllBytes(nar));
        assertArrayEquals("level".getBytes(), unpacked.get("level.dat"));
        assertArrayEquals(new byte[]{1, 2, 3}, unpacked.get("region/r.0.0.mca"));
        assertFalse(unpacked.containsKey("nodera/server-identity.bin"),
                "the private key is excluded from the re-key blob");
    }

    private static void assertEqualsIgnoreCase(String expected, String actual) {
        assertTrue(expected.equalsIgnoreCase(actual),
                "expected spool name prefix " + expected + " but got " + actual);
    }
}
