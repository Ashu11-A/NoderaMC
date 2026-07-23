package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The canonical world archive (the continuity lane's payload): byte-exact round trips,
 * insertion-order independence (determinism), traversal safety, and the manifest binding.
 */
final class WorldArchiveTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void packUnpackRoundTripsByteExactly() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("level.dat", bytes("level"));
        files.put("region/r.0.0.mca", bytes("region-bytes"));
        files.put("nodera-world.dat", bytes("identity"));

        byte[] blob = WorldArchive.pack(files);
        SequencedMap<String, byte[]> back = WorldArchive.unpack(blob);

        assertThat(back.keySet()).containsExactly(
                "level.dat", "nodera-world.dat", "region/r.0.0.mca");
        assertThat(back.get("region/r.0.0.mca")).isEqualTo(bytes("region-bytes"));
    }

    @Test
    void packingIsDeterministicRegardlessOfInsertionOrder() {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put("b.dat", bytes("b"));
        a.put("a.dat", bytes("a"));
        Map<String, byte[]> b = new LinkedHashMap<>();
        b.put("a.dat", bytes("a"));
        b.put("b.dat", bytes("b"));

        assertThat(WorldArchive.pack(a)).isEqualTo(WorldArchive.pack(b));
        assertThat(WorldArchive.contentIdOf(WorldArchive.pack(a)))
                .isEqualTo(WorldArchive.contentIdOf(WorldArchive.pack(b)));
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThatThrownBy(() -> WorldArchive.pack(Map.of("../evil", bytes("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorldArchive.pack(Map.of("/abs", bytes("x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorldArchive.pack(Map.of("a/../../b", bytes("x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadMagic() {
        assertThatThrownBy(() -> WorldArchive.unpack(bytes("not an archive at all")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void directoryRoundTripHonoursTheSaveFilter(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("region"));
        Files.createDirectories(dir.resolve("nodera/entity-lane"));
        Files.createDirectories(dir.resolve("nodera/spool"));
        Files.write(dir.resolve("level.dat"), bytes("level"));
        Files.write(dir.resolve("region/r.0.0.mca"), bytes("mca"));
        Files.write(dir.resolve("session.lock"), bytes("lock"));
        // The public identity/genesis records live at the save root and MUST travel …
        Files.write(dir.resolve("nodera-world.dat"), bytes("id"));
        Files.write(dir.resolve("nodera-genesis.dat"), bytes("genesis"));
        // … while the nodera/ runtime subtree — above all the host's PRIVATE KEY — must not.
        Files.write(dir.resolve("nodera/server-identity.bin"), bytes("PRIVATE-KEY"));
        Files.write(dir.resolve("nodera/entity-lane/LOCK"), bytes("rocks"));
        Files.write(dir.resolve("nodera/spool/x.nar"), bytes("nested-archive"));

        byte[] blob = WorldArchive.packSaveDirectory(dir);
        assertThat(WorldArchive.entryPaths(blob)).containsExactly(
                "level.dat", "nodera-genesis.dat", "nodera-world.dat", "region/r.0.0.mca");

        Path out = dir.resolve("restored");
        WorldArchive.unpackInto(blob, out);
        assertThat(Files.readAllBytes(out.resolve("region/r.0.0.mca"))).isEqualTo(bytes("mca"));
        assertThat(Files.exists(out.resolve("session.lock"))).isFalse();
        assertThat(Files.exists(out.resolve("nodera/server-identity.bin"))).isFalse();
    }

    @Test
    void manifestBindsEveryPieceAndOrdersByVersion() {
        byte[] blob = new byte[WorldArchive.ARCHIVE_PIECE_BYTES + 1234];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 31);
        }
        PieceManifest v1 = WorldArchive.manifestFor(1, blob);
        PieceManifest v2 = WorldArchive.manifestFor(2, blob);

        assertThat(v1.pieceCount()).isEqualTo(2);
        assertThat(v1.region()).isEqualTo(WorldArchive.ARCHIVE_REGION);
        assertThat(v1.isSupersededBy(v2)).isTrue();
        for (int i = 0; i < v1.pieceCount(); i++) {
            Piece p = v1.piece(i);
            Bytes payload = new Bytes(blob, (int) p.offset(), (int) p.length());
            assertThat(v1.verifyPiece(i, payload)).isTrue();
        }
        assertThat(v1.verifyPiece(0, Bytes.unsafeWrap(new byte[10]))).isFalse();
    }
}
