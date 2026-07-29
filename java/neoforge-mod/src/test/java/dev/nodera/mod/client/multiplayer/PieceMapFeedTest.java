package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.PieceMapView.PieceState;
import dev.nodera.mod.common.WorkerPiecesParser;
import dev.nodera.mod.common.WorkerPiecesParser.PieceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The worker-report → piece-map conversion behind the Minecraft client's piece grid.
 *
 * <p>Pure over its inputs, so the whole rendering decision is testable without a GUI env, a worker,
 * or a network: the widget only tiles what this produces.
 */
@DisplayName("PieceMapFeed.toMap")
final class PieceMapFeedTest {

    private static PieceInfo info(int pieceCount, int heldCount, BitSet held, String... holders) {
        return new PieceInfo("abcd", "root", 1, pieceCount, heldCount, 1024, held,
                List.of(holders));
    }

    private static BitSet bits(int... indexes) {
        BitSet set = new BitSet();
        for (int index : indexes) {
            set.set(index);
        }
        return set;
    }

    @Test
    @DisplayName("a held bit becomes a green cell and everything else stays missing")
    void heldBitsBecomeGreenCells() {
        PieceMap map = PieceMapFeed.toMap(info(5, 2, bits(1, 4)), "New World");

        assertEquals("New World", map.worldName());
        assertEquals(5, map.total());
        assertEquals(PieceState.MISSING, map.cells().get(0).state());
        assertEquals(PieceState.HELD, map.cells().get(1).state());
        assertEquals(PieceState.MISSING, map.cells().get(2).state());
        assertEquals(PieceState.MISSING, map.cells().get(3).state());
        assertEquals(PieceState.HELD, map.cells().get(4).state());
        assertEquals(2, map.count(PieceState.HELD));
        assertEquals(400, map.heldPermille());
        // HELD is the green cell the player is looking for.
        assertEquals(dev.nodera.diagnostics.state.Semantic.WORLD_HEALTHY,
                PieceMapView.semanticOf(PieceState.HELD));
    }

    @Test
    @DisplayName("a complete local copy counts this node as a seeder, alongside remote holders")
    void aCompleteLocalCopyCountsAsASeeder() {
        PieceMap map = PieceMapFeed.toMap(info(3, 3, bits(0, 1, 2), "node-a", "node-b"),
                "Shared");
        // 2 remote holders + this node = 3 peers sharing; this node is the one full seeder we know.
        assertEquals(1, map.seeders());
        assertEquals(3, map.holders());
    }

    @Test
    @DisplayName("a partial local copy is not a seeder, but the remote holders still count")
    void aPartialLocalCopyIsNotASeeder() {
        PieceMap map = PieceMapFeed.toMap(info(4, 1, bits(2), "node-a", "node-b", "node-c"),
                "Shared");
        assertEquals(0, map.seeders());
        assertEquals(3, map.holders());
    }

    @Test
    @DisplayName("a world with no manifest yields an empty grid, not a fabricated one")
    void anAbsentReportYieldsAnEmptyGrid() {
        PieceMap empty = PieceMapFeed.toMap(PieceInfo.empty(), "Nothing");
        assertEquals(0, empty.total());
        assertEquals(0, empty.seeders());
        assertEquals(0, empty.holders());
        assertEquals("Nothing", empty.worldName());

        assertEquals(0, PieceMapFeed.toMap(null, "Nothing").total());
    }

    @Test
    @DisplayName("the worker's own reply parses straight through into a grid")
    void endToEndFromAWorkerReply() {
        // The exact JSON shape WorkerControlHandler.piecesJson emits.
        String reply = "{\"world_id\":\"abcd\",\"manifest_root\":\"ff\",\"version\":2,"
                + "\"piece_count\":4,\"held_count\":4,\"total_bytes\":4096,"
                + "\"held_bitmap\":\"" + java.util.Base64.getEncoder()
                        .encodeToString(bits(0, 1, 2, 3).toByteArray()) + "\","
                + "\"holders\":[\"remote-1\"]}";

        PieceMap map = PieceMapFeed.toMap(WorkerPiecesParser.parse(reply), "Live");
        assertEquals(4, map.total());
        assertEquals(4, map.count(PieceState.HELD));
        assertEquals(1000, map.heldPermille());
        assertEquals(1, map.seeders());
        assertEquals(2, map.holders());
        assertEquals(PieceMapView.AGGREGATES, PieceMapView.aggregates(map).key());
        assertEquals(java.util.List.of("Live", "100.0", 4L, 4, 1, 2),
                PieceMapView.aggregates(map).args());
    }
}
