package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 31b: the pure public-world badge view model — no GUI env, keys + arguments only. */
final class PublicWorldBadgeViewTest {

    @Test
    void unsharedWorldHasNoBadge() {
        assertNull(PublicWorldBadgeView.badge(PublicWorldStatus.notShared("New World")));
        assertNull(PublicWorldBadgeView.badge(null));
    }

    @Test
    void sharedWorldBadgeCarriesCountAndIsHealthyColoured() {
        Cell cell = PublicWorldBadgeView.badge(new PublicWorldStatus("New World", true, 3));
        assertEquals(PublicWorldBadgeView.BADGE_ONLINE, cell.key());
        assertEquals(List.of(3L), cell.args());
        assertEquals(Semantic.WORLD_HEALTHY, cell.semantic());
    }

    @Test
    void unknownCountFallsBackToThePublicKeyWithNoArguments() {
        assertEquals(PublicWorldBadgeView.BADGE_PUBLIC,
                PublicWorldBadgeView.badgeCell(-1).key());
        Cell cell = PublicWorldBadgeView.badge(new PublicWorldStatus("New World", true, -1));
        assertEquals(PublicWorldBadgeView.BADGE_PUBLIC, cell.key());
        assertEquals(List.of(), cell.args());
    }

    @Test
    void zeroOnlineStillReadsExplicitly() {
        Cell cell = PublicWorldBadgeView.badgeCell(0);
        assertEquals(PublicWorldBadgeView.BADGE_ONLINE, cell.key());
        assertEquals(List.of(0L), cell.args());
    }

    @Test
    void summaryCountsOnlySharedWorlds() {
        List<PublicWorldStatus> worlds = List.of(
                new PublicWorldStatus("A", true, 2),
                PublicWorldStatus.notShared("B"),
                new PublicWorldStatus("C", true, 0));
        assertEquals(2, PublicWorldBadgeView.sharedCount(worlds));
        Cell summary = PublicWorldBadgeView.summaryCell(worlds);
        assertEquals(PublicWorldBadgeView.SUMMARY_MANY, summary.key());
        assertEquals(List.of(2L), summary.args());
    }

    @Test
    void summarySingularAndEmpty() {
        Cell one = PublicWorldBadgeView.summaryCell(
                List.of(new PublicWorldStatus("A", true, 1)));
        assertEquals(PublicWorldBadgeView.SUMMARY_ONE, one.key());
        assertEquals(List.of(), one.args());
        assertNull(PublicWorldBadgeView.summaryCell(List.of(PublicWorldStatus.notShared("B"))));
        assertNull(PublicWorldBadgeView.summaryCell(List.of()));
    }

    @Test
    void nullSaveNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PublicWorldStatus(null, true, 1));
    }
}
