package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.PieceSplitter;
import dev.nodera.storage.Compression;
import dev.nodera.storage.ContentId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The world id in a manifest file name arrives from the control socket.
 *
 * <p>{@code NODERA-SEED} and {@code NODERA-SEED-REGION} both take it as an argument, and it used to
 * be concatenated into a file name exactly as received while the other two components were
 * machine-made — the lane is hex-encoded precisely because a region's identity contains characters a
 * file name may not, and the manifest root is a hash. So the one untrusted part was the one that was
 * not encoded, and {@code Path.resolve} follows {@code ../}.
 */
final class ManifestIndexStorePathTest {

    private static PieceManifest manifest() {
        byte[] blob = new byte[64];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) i;
        }
        List<Piece> pieces = PieceSplitter.splitFixed(blob, 64);
        Bytes hash = new dev.nodera.core.crypto.HashService().sha256(blob);
        return PieceManifest.of(
                new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0),
                new SnapshotVersion(1L), 1L, StateRoot.of(hash),
                new ContentId(hash, blob.length, Compression.NONE), blob.length, pieces);
    }

    @Test
    void aTraversingWorldIdWritesNothingOutsideTheStore(@TempDir Path tmp) throws Exception {
        Path content = tmp.resolve("content");
        Files.createDirectories(content);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        ManifestIndexStore store = new ManifestIndexStore(() -> content);

        // `put` swallows and logs its failures by design — losing one manifest must not take the
        // node down — so the assertion is about the file system, which is the thing that matters.
        store.put("../../outside/pwned", manifest());
        store.put("..%2f..%2foutside%2fpwned", manifest());
        store.put("/etc/passwd", manifest());

        try (var walk = Files.walk(outside)) {
            assertThat(walk.filter(Files::isRegularFile).toList())
                    .as("nothing may be written outside the manifest store")
                    .isEmpty();
        }
        try (var walk = Files.walk(tmp)) {
            assertThat(walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".manifest")).toList())
                    .as("and the refused writes leave no manifest anywhere at all")
                    .isEmpty();
        }
    }

    @Test
    void anOrdinaryHexWorldIdStillRoundTrips(@TempDir Path tmp) throws Exception {
        Path content = tmp.resolve("content");
        Files.createDirectories(content);
        ManifestIndexStore store = new ManifestIndexStore(() -> content);
        PieceManifest manifest = manifest();

        store.put("8ff6ce2c5f1f", manifest);

        assertThat(store.loadAll())
                .as("the guard must not cost a legitimate id its file")
                .hasSize(1)
                .allSatisfy(entry -> {
                    assertThat(entry.worldIdHex()).isEqualTo("8ff6ce2c5f1f");
                    assertThat(entry.manifest().manifestRoot()).isEqualTo(manifest.manifestRoot());
                });

        store.remove("8ff6ce2c5f1f", manifest);
        assertThat(store.loadAll()).isEmpty();
    }
}
