package dev.nodera.diagnostics.view;

import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 26 acceptance #1 (Minecraft-free): tracker data → correct rows, counts, health colour
 * semantics; search filters by name; ordering is deterministic regardless of input order.
 */
final class TorrentWorldListViewTest {

    private static TorrentWorldEntry world(String name, WorldHealth health) {
        return new TorrentWorldEntry(name, 3, 4096, 9750, health, -1, "");
    }

    @Test
    void rowCarriesCountsReliabilityAndHealthCells() {
        Panel panel = TorrentWorldListView.panel(
                List.of(new TorrentWorldEntry("SkyBlock", 7, 12_345, 9750, WorldHealth.HEALTHY, -1, "")), "");
        assertThat(panel.title()).isEqualTo(TorrentWorldListView.PANEL_TITLE);
        assertThat(panel.rows()).hasSize(1);
        List<Cell> cells = panel.rows().get(0).cells();
        assertThat(cells.get(0).text()).isEqualTo("SkyBlock");
        assertThat(cells.get(0).bold()).isTrue();
        assertThat(cells.get(1).text()).isEqualTo("7 players");
        assertThat(cells.get(2).text()).isEqualTo("12345 chunks");
        assertThat(cells.get(3).text()).isEqualTo("97.5%");
        assertThat(cells.get(4).text()).isEqualTo("HEALTHY");
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
        assertThat(cells.get(5).text()).isEqualTo("drops in 23h59m");
        assertThat(cells.get(5).semantic()).isEqualTo(Semantic.WORLD_DEGRADED);

        Panel subMinute = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("Fading", 0, 10, 5000, WorldHealth.DEGRADED, 30, "")), "");
        assertThat(subMinute.rows().get(0).cells().get(5).text()).isEqualTo("drops in <1m");
    }

    @Test
    void ownerCellAppearsAfterTheNameWhenAHostIsKnown() {
        Panel panel = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("MyWorld", 2, 100, 9750, WorldHealth.HEALTHY, -1, "Steve")), "");
        List<Cell> cells = panel.rows().get(0).cells();
        assertThat(cells.get(0).text()).isEqualTo("MyWorld");
        assertThat(cells.get(1).text()).isEqualTo("by Steve"); // owner cell inserted right after name
        assertThat(cells.get(2).text()).isEqualTo("2 players");
        // A blank host inserts no owner cell (indices stay as the other tests assume).
        Panel noHost = TorrentWorldListView.panel(List.of(
                new TorrentWorldEntry("MyWorld", 2, 100, 9750, WorldHealth.HEALTHY, -1, "")), "");
        assertThat(noHost.rows().get(0).cells().get(1).text()).isEqualTo("2 players");
    }

    @Test
    void searchFiltersByNameCaseInsensitive() {
        List<TorrentWorldEntry> worlds = List.of(
                world("SkyBlock", WorldHealth.HEALTHY),
                world("Creative Plots", WorldHealth.HEALTHY),
                world("skywars", WorldHealth.HEALTHY));
        Panel filtered = TorrentWorldListView.panel(worlds, "SKY");
        assertThat(filtered.rows()).extracting(r -> r.cells().get(0).text())
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
                .extracting(r -> r.cells().get(0).text())
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
