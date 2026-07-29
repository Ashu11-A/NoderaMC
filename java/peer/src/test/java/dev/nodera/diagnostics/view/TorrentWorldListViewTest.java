package dev.nodera.diagnostics.view;

import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 26 acceptance #1 (Minecraft-free): tracker data → correct rows, counts, health colour
 * semantics; search filters by name; ordering is deterministic regardless of input order.
 *
 * <p>MC-GUI-5: rows carry translation keys + arguments, so the assertions below name keys and
 * argument lists — never rendered English, which this view model no longer produces.
 */
final class TorrentWorldListViewTest {

    private static TorrentWorldEntry world(String name, WorldHealth health) {
        return new TorrentWorldEntry(name, 3, 4096, 9750, health, -1, "");
    }

    /** The first argument of a cell — for RAW cells, the data itself. */
    private static Object arg0(Cell cell) {
        return cell.args().isEmpty() ? null : cell.args().get(0);
    }

    @Test
    void rowCarriesCountsReliabilityAndHealthCells() {
        Panel panel = TorrentWorldListView.panel(
                List.of(new TorrentWorldEntry("SkyBlock", 7, 12_345, 9750, WorldHealth.HEALTHY, -1, "")), "");
        assertThat(panel.titleKey()).isEqualTo(TorrentWorldListView.PANEL_TITLE);
        assertThat(panel.rows()).hasSize(1);
        List<Cell> cells = panel.rows().get(0).cells();
        assertThat(cells.get(0).key()).isEqualTo(Cell.RAW);
        assertThat(arg0(cells.get(0))).isEqualTo("SkyBlock");
        assertThat(cells.get(0).bold()).isTrue();

        // Population, storage, reliability and health are keys the lang file words — the counts
        // ride along as arguments, so a translator can reorder or re-pluralise them.
        assertThat(cells.get(1).key()).isEqualTo(TorrentWorldListView.KEY_PLAYERS);
        assertThat(cells.get(1).args()).containsExactly(7L);
        assertThat(cells.get(2).key()).isEqualTo(TorrentWorldListView.KEY_CHUNKS);
        assertThat(cells.get(2).args()).containsExactly(12_345L);
        assertThat(cells.get(3).key()).isEqualTo(TorrentWorldListView.KEY_RELIABILITY);
        assertThat(cells.get(3).args()).containsExactly("97.5%");
        assertThat(cells.get(4).key()).isEqualTo("nodera.world.health.healthy");
        assertThat(cells).hasSize(5); // no countdown cell when none is running
    }

    @Test
    void healthMapsToTheDedicatedWorldSemantics() {
        assertThat(TorrentWorldListView.semanticOf(WorldHealth.HEALTHY))
                .isEqualTo(Semantic.WORLD_HEALTHY);
        assertThat(TorrentWorldListView.semanticOf(WorldHealth.DEGRADED))
                .isEqualTo(Semantic.WORLD_DEGRADED);
        assertThat(TorrentWorldListView.semanticOf(WorldHealth.DEAD))
                .isEqualTo(Semantic.WORLD_DEAD);
        // The lost-data world colours red through WORLD_DEGRADED — never the session DEGRADED
        // (yellow), which would silently recolour the Task 18 HUD.
        Panel panel = TorrentWorldListView.panel(List.of(world("Lost", WorldHealth.DEGRADED)), "");
        assertThat(panel.rows().get(0).cells().get(0).semantic()).isEqualTo(Semantic.WORLD_DEGRADED);
    }

    @Test
    void countdownCellAppearsOnlyWhileTheClockRuns() {
        Panel counting = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("Fading", 0, 10, 5000, WorldHealth.DEGRADED, 86_340, "")), "");
        List<Cell> cells = counting.rows().get(0).cells();
        assertThat(cells.get(5).key()).isEqualTo(TorrentWorldListView.KEY_COUNTDOWN);
        assertThat(cells.get(5).args()).containsExactly("23h59m");
        assertThat(cells.get(5).semantic()).isEqualTo(Semantic.WORLD_DEGRADED);

        Panel subMinute = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("Fading", 0, 10, 5000, WorldHealth.DEGRADED, 30, "")), "");
        assertThat(subMinute.rows().get(0).cells().get(5).args()).containsExactly("<1m");
    }

    @Test
    void ownerCellAppearsAfterTheNameWhenAHostIsKnown() {
        Panel panel = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("MyWorld", 2, 100, 9750, WorldHealth.HEALTHY, -1, "Steve")), "");
        List<Cell> cells = panel.rows().get(0).cells();
        assertThat(arg0(cells.get(0))).isEqualTo("MyWorld");
        // owner cell inserted right after the name, as a key carrying the host name
        assertThat(cells.get(1).key()).isEqualTo(TorrentWorldListView.KEY_BY_HOST);
        assertThat(cells.get(1).args()).containsExactly("Steve");
        assertThat(cells.get(2).key()).isEqualTo(TorrentWorldListView.KEY_PLAYERS);
        // A blank host inserts no owner cell (indices stay as the other tests assume).
        Panel noHost = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("MyWorld", 2, 100, 9750, WorldHealth.HEALTHY, -1, "")), "");
        assertThat(noHost.rows().get(0).cells().get(1).key())
                .isEqualTo(TorrentWorldListView.KEY_PLAYERS);
    }

    @Test
    @DisplayName("a world nobody could count reads as unknown, never as a negative population")
    void anUnknownPlayerCountIsNeverRenderedAsANumber() {
        // The live screenshot this comes from: a row reading "-1 online · 100% reliable", next to
        // rows reading "0 online". The sentinel for "nothing that can see into this world has
        // reported" had reached the screen as a population, and a player comparing the two had no
        // way to tell which was a measurement.
        TorrentWorldEntry unknown =
                new TorrentWorldEntry("Unknown", -1, 4, 10_000, WorldHealth.HEALTHY, -1, "");

        assertThat(unknown.playersKnown()).isFalse();
        assertThat(unknown.playersCell().key()).isEqualTo(TorrentWorldListView.KEY_PLAYERS_UNKNOWN);
        // The unknown sentinel never becomes a count argument on any cell of the row.
        assertThat(TorrentWorldListView.panel(List.of(unknown), "").rows().get(0).cells())
                .noneMatch(c -> c.args().contains(-1L) || c.args().contains(-1));

        // A node in the world reporting an empty world is a different, real answer.
        Cell empty = TorrentWorldListView.playersCell(0, Semantic.NEUTRAL);
        assertThat(empty.key()).isEqualTo(TorrentWorldListView.KEY_PLAYERS);
        assertThat(empty.args()).containsExactly(0L);
    }

    @Test
    void searchFiltersByNameCaseInsensitive() {
        List<TorrentWorldEntry> worlds = List.of(
                world("SkyBlock", WorldHealth.HEALTHY),
                world("Creative Plots", WorldHealth.HEALTHY),
                world("skywars", WorldHealth.HEALTHY));
        Panel filtered = TorrentWorldListView.panel(worlds, "SKY");
        assertThat(filtered.rows()).extracting(r -> arg0(r.cells().get(0)))
                .containsExactly("SkyBlock", "skywars");
        assertThat(TorrentWorldListView.panel(worlds, "  ").rows()).hasSize(3); // blank = all
        assertThat(TorrentWorldListView.panel(worlds, "nether").rows()).isEmpty();
    }

    @Test
    void orderIsDeterministicRegardlessOfInputOrder() {
        List<TorrentWorldEntry> forward = List.of(
                world("alpha", WorldHealth.HEALTHY), world("Bravo", WorldHealth.HEALTHY),
                world("charlie", WorldHealth.DEAD));
        List<TorrentWorldEntry> reversed = List.of(
                world("charlie", WorldHealth.DEAD), world("Bravo", WorldHealth.HEALTHY),
                world("alpha", WorldHealth.HEALTHY));
        assertThat(TorrentWorldListView.panel(forward, ""))
                .isEqualTo(TorrentWorldListView.panel(reversed, ""));
        assertThat(TorrentWorldListView.panel(forward, "").rows())
                .extracting(r -> arg0(r.cells().get(0)))
                .containsExactly("alpha", "Bravo", "charlie");
    }

    @Test
    void reliabilityFormattingIsPureIntegerMathAndClamped() {
        assertThat(TorrentWorldListView.formatReliability(10_000)).isEqualTo("100.0%");
        assertThat(TorrentWorldListView.formatReliability(9_500)).isEqualTo("95.0%");
        assertThat(TorrentWorldListView.formatReliability(1)).isEqualTo("0.0%");
        assertThat(TorrentWorldListView.formatReliability(-5)).isEqualTo("0.0%");
        assertThat(TorrentWorldListView.formatReliability(20_000)).isEqualTo("100.0%");
    }
}
