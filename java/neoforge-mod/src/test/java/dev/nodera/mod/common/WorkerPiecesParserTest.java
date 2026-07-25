package dev.nodera.mod.common;

import dev.nodera.mod.common.WorkerPiecesParser.PieceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader for the worker's {@code NODERA-PIECES} reply.
 *
 * <p>These assertions are the contract between {@code WorkerControlHandler.piecesJson} and the
 * client's piece map: if the bitmap encoding or a field name drifts on either side, the grid goes
 * silently grey rather than failing loudly, so it is pinned here.
 */
@DisplayName("WorkerPiecesParser")
final class WorkerPiecesParserTest {

    /** Build a reply exactly as the worker writes it, for a given held-piece pattern. */
    private static String reply(int pieceCount, int... heldIndexes) {
        BitSet held = new BitSet();
        for (int index : heldIndexes) {
            held.set(index);
        }
        return "{\"world_id\":\"abcd1234\",\"manifest_root\":\"ff00\",\"version\":3,"
                + "\"piece_count\":" + pieceCount + ",\"held_count\":" + heldIndexes.length + ","
                + "\"total_bytes\":11534336,"
                + "\"held_bitmap\":\"" + Base64.getEncoder().encodeToString(held.toByteArray())
                + "\",\"holders\":[\"node-a\",\"node-b\"]}";
    }

    @Test
    @DisplayName("every field and the exact held/missing pattern survive the round trip")
    void parsesAFullReply() {
        PieceInfo info = WorkerPiecesParser.parse(reply(10, 0, 3, 9));

        assertEquals("abcd1234", info.worldId());
        assertEquals("ff00", info.manifestRoot());
        assertEquals(3, info.version());
        assertEquals(10, info.pieceCount());
        assertEquals(3, info.heldCount());
        assertEquals(11_534_336L, info.totalBytes());
        assertEquals(java.util.List.of("node-a", "node-b"), info.holders());
        assertFalse(info.isEmpty());

        for (int i = 0; i < 10; i++) {
            boolean expected = i == 0 || i == 3 || i == 9;
            assertEquals(expected, info.held().get(i), "piece " + i);
        }
    }

    @Test
    @DisplayName("a world with nothing held yet parses as all-missing, not as absent")
    void parsesAnAllMissingReply() {
        PieceInfo info = WorkerPiecesParser.parse(reply(4));
        assertFalse(info.isEmpty(), "4 pieces is a real map, even with none held");
        assertEquals(4, info.pieceCount());
        assertEquals(0, info.heldCount());
        assertTrue(info.held().isEmpty());
    }

    @Test
    @DisplayName("a fully-held world reports every bit set")
    void parsesAFullyHeldReply() {
        PieceInfo info = WorkerPiecesParser.parse(reply(3, 0, 1, 2));
        assertEquals(3, info.heldCount());
        assertEquals(3, info.held().cardinality());
    }

    @Test
    @DisplayName("absent, blank, and error replies all read as 'no map', never as an exception")
    void degradesToEmpty() {
        assertTrue(WorkerPiecesParser.parse(null).isEmpty());
        assertTrue(WorkerPiecesParser.parse("").isEmpty());
        assertTrue(WorkerPiecesParser.parse("NODERA-ERR no piece data for world").isEmpty());
        assertTrue(WorkerPiecesParser.parse("{}").isEmpty());
        assertTrue(WorkerPiecesParser.parse("not json at all").isEmpty());
    }

    @Test
    @DisplayName("a corrupt bitmap reads as nothing-held rather than crashing the screen")
    void aCorruptBitmapIsAllMissing() {
        String corrupt = "{\"piece_count\":8,\"held_count\":8,\"held_bitmap\":\"!!!not-base64!!!\","
                + "\"holders\":[]}";
        PieceInfo info = WorkerPiecesParser.parse(corrupt);
        assertEquals(8, info.pieceCount());
        assertTrue(info.held().isEmpty(), "an undecodable bitmap must not be guessed at");
    }

    @Test
    @DisplayName("an empty holders array parses as no peers, not as a parse failure")
    void emptyHoldersArray() {
        PieceInfo info = WorkerPiecesParser.parse(
                "{\"piece_count\":2,\"held_count\":0,\"held_bitmap\":\"\",\"holders\":[]}");
        assertEquals(2, info.pieceCount());
        assertTrue(info.holders().isEmpty());
    }
}
