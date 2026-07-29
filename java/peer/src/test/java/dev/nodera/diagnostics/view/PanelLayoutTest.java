package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-GUI-2's exit test, run against the REAL Trackers and Rendezvous view models: every row is
 * fully visible or scrollable, at every GUI scale.
 *
 * <p>"Every GUI scale" is the whole point of the row — the panel was authored at one width and a
 * tracker row measured about 234 px against a 224 px column at GUI width 480. So the assertions
 * sweep the panel width across the range a real screen produces (a half-width panel at GUI widths
 * from 320 to 1024, at scales 1–4) and, at each one, demand both guarantees rather than sampling
 * the one width that happened to work.
 *
 * <p>The font is a stub: Minecraft's is not available here, and the layout takes its measurement as
 * a function precisely so the rule can be checked without one. Two stubs are used — a proportional
 * one and a deliberately wide monospace one — because a rule that only holds for narrow glyphs is
 * the bug this replaces.
 */
final class PanelLayoutTest {

    private static final int ROW_HEIGHT = 12;
    private static final int CELL_GAP = 8;

    /** A proportional-ish measurement: 6 px a character, 3 px for the thin ones. */
    private static final ToIntFunction<String> PROPORTIONAL = text -> {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += "il.·:'".indexOf(text.charAt(i)) >= 0 ? 3 : 6;
        }
        return w;
    };

    /** A wide monospace measurement — the same rule has to hold when everything is fatter. */
    private static final ToIntFunction<String> WIDE = text -> text.length() * 9;

    private static Panel trackers() {
        return TrackerStatusView.panel(List.of(
                new TrackerEndpointStatus("tracker.nodera.dev:25600", true, 42, 3),
                new TrackerEndpointStatus("127.0.0.1:25600", true, 4, 12),
                new TrackerEndpointStatus("a-very-long-tracker-hostname.example.org:25600", false,
                        -1, -1),
                new TrackerEndpointStatus("b:2", false, -1, 900)));
    }

    private static Panel rendezvous() {
        return RendezvousStatusView.panel(List.of(
                new RendezvousEndpointStatus("relay.nodera.dev:25601", true, 7, 2_048_000,
                        RendezvousStatusView.PathKind.RELAYED),
                new RendezvousEndpointStatus("a-very-long-relay-hostname.example.org:25601", false,
                        -1, 0, RendezvousStatusView.PathKind.NONE)));
    }

    /** The widths a half-width panel takes across the GUI widths and scales a player can pick. */
    private static List<Integer> panelWidths() {
        List<Integer> widths = new ArrayList<>();
        for (int guiWidth : new int[]{320, 400, 480, 640, 854, 1024}) {
            for (int scale = 1; scale <= 4; scale++) {
                // The screen's own arithmetic: a 12 px margin either side, split in two panels with
                // an 8 px gutter, in scaled (GUI) pixels.
                int scaled = guiWidth / scale;
                widths.add(Math.max(40, (scaled - 24 - 8) / 2));
            }
        }
        return widths;
    }

    @Test
    void noTextIsEverPlacedOutsideThePanelAtAnyScale() {
        for (ToIntFunction<String> font : List.of(PROPORTIONAL, WIDE)) {
            for (int width : panelWidths()) {
                for (Panel panel : List.of(trackers(), rendezvous())) {
                    PanelLayout.Laid laid = PanelLayout.lay("Trackers", panel, width, 60,
                            ROW_HEIGHT, CELL_GAP, 0, font);
                    for (PanelLayout.Placed placed : laid.placements()) {
                        assertThat(placed.x()).as("left edge at width %d", width)
                                .isGreaterThanOrEqualTo(0);
                        assertThat(placed.x() + placed.width())
                                .as("'%s' at panel width %d", placed.text(), width)
                                .isLessThanOrEqualTo(width);
                    }
                }
            }
        }
    }

    @Test
    void everyRowIsReachableByScrollingWhateverTheHeight() {
        for (int height : new int[]{24, 40, 60, 120, 400}) {
            PanelLayout.Laid laid = PanelLayout.lay("Trackers", trackers(), 224, height,
                    ROW_HEIGHT, CELL_GAP, 0, PROPORTIONAL);
            int contentHeight = laid.contentLines() * ROW_HEIGHT;
            if (contentHeight <= height) {
                assertThat(laid.scrollable()).as("fits at height %d", height).isFalse();
                continue;
            }
            // Taller than the viewport: the widget is told so, and at the far end of the scroll
            // the LAST line is inside the viewport — which is what "reachable" means.
            assertThat(laid.scrollable()).as("overflows at height %d", height).isTrue();
            PanelLayout.Laid bottom = PanelLayout.lay("Trackers", trackers(), 224, height,
                    ROW_HEIGHT, CELL_GAP, laid.maxScroll(), PROPORTIONAL);
            int lastLineTop = bottom.placements().stream()
                    .mapToInt(PanelLayout.Placed::y).max().orElseThrow();
            assertThat(lastLineTop).isGreaterThanOrEqualTo(0);
            assertThat(lastLineTop + ROW_HEIGHT).isLessThanOrEqualTo(height);
        }
    }

    @Test
    void noRowIsSilentlyDropped() {
        // The old widget stopped drawing at the bottom edge and said nothing. Every cell of every
        // row must be present in the placement list, scrolled or not.
        Panel panel = trackers();
        int cells = panel.rows().stream().mapToInt(r -> r.cells().size()).sum();
        PanelLayout.Laid laid = PanelLayout.lay("Trackers", panel, 224, 24, ROW_HEIGHT, CELL_GAP,
                0, PROPORTIONAL);
        assertThat(laid.placements()).hasSize(cells + 1); // + the title line
    }

    @Test
    void aCellTooWideForAWholeLineIsTruncatedNotOverflowed() {
        String monster = "an-endpoint-name-far-wider-than-any-panel:25600";
        assertThat(PROPORTIONAL.applyAsInt(monster)).isGreaterThan(100);
        String fitted = PanelLayout.fit(monster, 100, PROPORTIONAL);
        assertThat(fitted).endsWith(PanelLayout.ELLIPSIS);
        assertThat(PROPORTIONAL.applyAsInt(fitted)).isLessThanOrEqualTo(100);
    }

    @Test
    void scrollIsClampedIntoRangeRatherThanTrusted() {
        Panel panel = trackers();
        PanelLayout.Laid far = PanelLayout.lay("Trackers", panel, 224, 40, ROW_HEIGHT, CELL_GAP,
                10_000, PROPORTIONAL);
        PanelLayout.Laid end = PanelLayout.lay("Trackers", panel, 224, 40, ROW_HEIGHT, CELL_GAP,
                far.maxScroll(), PROPORTIONAL);
        assertThat(far.placements()).isEqualTo(end.placements());
        PanelLayout.Laid negative = PanelLayout.lay("Trackers", panel, 224, 40, ROW_HEIGHT,
                CELL_GAP, -50, PROPORTIONAL);
        assertThat(negative.placements().getFirst().y()).isZero();
    }
}
