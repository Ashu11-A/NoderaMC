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
                        PieceState.MISSING), 4, 9);
        // 2/5 = 400 permille → "40.0%"
        assertEquals("New World · 40.0% held · 2/5 pieces · 4 seeders · 9 peers sharing",
                PieceMapView.aggregates(map));
    }

    @Test
    void negativeIndexRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PieceMapView.PieceCell(-1, PieceState.HELD));
    }

    // --- holders: "how many peers are sharing this world" -------------------------------------

    @Test
    void holdersCountsEveryPeerSharingTheWorldNotJustCompleteSeeders() {
        // 2 complete seeders inside a swarm of 7 holders: the other 5 hold part of it and can
        // still serve pieces, which is what keeps a world alive when no full copy is online.
        PieceMap map = PieceMapView.map("W", List.of(PieceState.HELD, PieceState.MISSING), 2, 7);
        assertEquals(2, map.seeders());
        assertEquals(7, map.holders());
    }

    @Test
    void holdersCanNeverBeFewerThanSeedersBecauseACompleteCopyIsACopy() {
        // A feed reporting more full seeders than total holders is describing an impossible swarm;
        // clamping keeps the rendered line self-consistent instead of showing "4 seeders · 1 peer".
        PieceMap map = PieceMapView.map("W", List.of(PieceState.HELD), 4, 1);
        assertEquals(4, map.seeders());
        assertEquals(4, map.holders());
    }

    @Test
    void aMapBuiltWithoutAHolderCountTreatsItsSeedersAsTheHolders() {
        PieceMap map = PieceMapView.map("W", List.of(PieceState.HELD), 3);
        assertEquals(3, map.seeders());
        assertEquals(3, map.holders());
    }

    @Test
    void negativeHoldersRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PieceMap("W", List.of(), 0, -1));
    }

    // --- grid rows: the scrolling window's bound -----------------------------------------------

    @Test
    void aPartialFinalRowStillCountsSoItsPiecesStayReachable() {
        assertEquals(1, PieceMapView.rowsFor(1, 16));
        assertEquals(1, PieceMapView.rowsFor(16, 16));
        assertEquals(2, PieceMapView.rowsFor(17, 16));
        assertEquals(4, PieceMapView.rowsFor(50, 16));
    }

    @Test
    void anExactMultipleDoesNotGainAPhantomEmptyRow() {
        assertEquals(2, PieceMapView.rowsFor(32, 16));
        assertEquals(10, PieceMapView.rowsFor(100, 10));
    }

    @Test
    void degenerateRowInputsAreZeroNotADivideByZero() {
        assertEquals(0, PieceMapView.rowsFor(0, 16));
        assertEquals(0, PieceMapView.rowsFor(-5, 16));
        assertEquals(0, PieceMapView.rowsFor(10, 0));
    }

    @Test
    void aLargeManifestSpansManyRowsWhichIsWhyTheGridScrolls() {
        // An ~11 MB save at 64 KiB pieces is ~176 pieces: 3 rows on a wide screen, 15 on a narrow
        // one — far more than the old "stop drawing when out of room" renderer could show.
        assertEquals(3, PieceMapView.rowsFor(176, 60));
        assertEquals(15, PieceMapView.rowsFor(176, 12));
    }
}
