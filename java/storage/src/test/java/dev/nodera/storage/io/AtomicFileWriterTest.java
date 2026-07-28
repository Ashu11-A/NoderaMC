package dev.nodera.storage.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class AtomicFileWriterTest {

    @Test
    void ownerOnlyWriteCreatesARestrictedFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("identity.bin");

        AtomicFileWriter.writeOwnerOnly(target, new byte[] {1, 2, 3});

        assertThat(Files.getPosixFilePermissions(target)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void failedReplacementDeletesTheSecretBearingTempFile(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("identity.bin");
        Files.createDirectories(target);
        Files.write(target.resolve("prevents-replacement"), new byte[] {9});

        assertThatThrownBy(() -> AtomicFileWriter.writeOwnerOnly(target, new byte[] {1, 2, 3}))
                .isInstanceOf(IOException.class);

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp")))
                    .isEmpty();
        }
    }

    @Test
    void cleanupFailureIsSuppressedOnThePrimaryFailure(@TempDir Path dir) throws Exception {
        Path nonEmptyDirectory = dir.resolve("temp");
        Files.createDirectories(nonEmptyDirectory);
        Files.write(nonEmptyDirectory.resolve("child"), new byte[] {1});
        IOException primary = new IOException("write failed");

        AtomicFileWriter.deleteTempAfterFailure(nonEmptyDirectory, primary);

        assertThat(primary.getSuppressed()).hasSize(1);
        assertThat(primary.getSuppressed()[0]).isInstanceOf(DirectoryNotEmptyException.class);
    }
}
