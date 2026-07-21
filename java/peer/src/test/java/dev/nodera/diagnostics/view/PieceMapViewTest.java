package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import dev.nodera.diagnostics.view.PieceMapView.PieceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 31d: the pure piece-map view model — no GUI env. */
final class PieceMapViewTest {

    @Test
    void mapPreservesIndexOrderAndStates() {
        PieceMap map = PieceMapView.map("New World",
                List.of(PieceState.HELD, PieceState.MISSING, PieceState.SYNCING), 3);
        assertEquals(3, map.total());
        assertEquals(0, map.cells().get(0).index());
        assertEquals(PieceState.HELD, map.cells().get(0).state());
        assertEquals(PieceState.SYNCING, map.cells().get(2).state());
        assertEquals(3, map.seeders());
    }

    @Test
    void nullStateBecomesMissing() {
        PieceMap map = PieceMapView.map("W", java.util.Arrays.asList(PieceState.HELD, null), 1);
        assertEquals(PieceState.MISSING, map.cells().get(1).state());
    }

    @Test
    void heldPermilleAndCounts() {
        PieceMap map = PieceMapView.map("W",
                List.of(PieceState.HELD, PieceState.HELD, PieceState.MISSING, PieceState.MISSING), 2);
        assertEquals(2, map.count(PieceState.HELD));
        assertEquals(500, map.heldPermille()); // 2/4
    }

    @Test
    void emptyMapIsFullyHeldByConvention() {
        PieceMap map = PieceMapView.map("W", List.of(), 0);
        assertEquals(0, map.total());
        assertEquals(1000, map.heldPermille());
    }

    @Test
    void heldIsGreenLockedIsCritical() {
        assertEquals(Semantic.WORLD_HEALTHY, PieceMapView.semanticOf(PieceState.HELD));
        assertEquals(Semantic.CRITICAL, PieceMapView.semanticOf(PieceState.LOCKED));
        assertEquals(Semantic.WORLD_DEAD, PieceMapView.semanticOf(PieceState.ENCRYPTED_NO_KEY));
    }

    @Test
    void aggregatesLine() {
        PieceMap map = PieceMapView.map("New World",
                List.of(PieceState.HELD, PieceState.HELD, PieceState.MISSING, PieceState.MISSING,
                        PieceState.MISSING), 4);
        // 2/5 = 400 permille → "40.0%"
        assertEquals("New World · 40.0% held · 2/5 pieces · 4 seeders",
                PieceMapView.aggregates(map));
    }

    @Test
    void negativeIndexRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PieceMapView.PieceCell(-1, PieceState.HELD));
    }
}
