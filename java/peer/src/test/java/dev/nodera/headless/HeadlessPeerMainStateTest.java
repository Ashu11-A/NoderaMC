package dev.nodera.headless;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class HeadlessPeerMainStateTest {

    @Test
    void startupStateSurvivesANonPosixFileSystem(@TempDir Path dir) throws Exception {
        URI zipUri = URI.create("jar:" + dir.resolve("worker-state.zip").toUri());
        try (FileSystem zip = FileSystems.newFileSystem(zipUri, Map.of("create", "true"))) {
            Path stateDir = zip.getPath("/state");
            Files.createDirectories(stateDir);
            assertThat(zip.supportedFileAttributeViews()).doesNotContain("posix");
            assertThat(Files.getFileStore(stateDir)
                    .supportsFileAttributeView(PosixFileAttributeView.class)).isFalse();

            Path identity = stateDir.resolve("identity.bin");
            Path worlds = stateDir.resolve("worlds.dat");
            Path keys = stateDir.resolve("world-keys");
            String worldId = new dev.nodera.core.crypto.HashService()
                    .sha256("zipfs".getBytes()).toHex();

            PeerNode.LocalState started =
                    PeerNode.openLocalState(identity, worlds, keys);
            started.worldRegistry().put(worldId, "First name", false, Bytes.empty());
            started.worldRegistry().put(worldId, "Updated name", false, Bytes.empty());

            PeerNode.LocalState restarted =
                    PeerNode.openLocalState(identity, worlds, keys);
            assertThat(restarted.identity().nodeId()).isEqualTo(started.identity().nodeId());
            assertThat(restarted.worldRegistry().find(worldId).orElseThrow().name())
                    .isEqualTo("Updated name");
        }
    }
}
