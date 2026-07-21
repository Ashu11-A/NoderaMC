package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 31b: the pure public-world badge view model — no GUI env. */
final class PublicWorldBadgeViewTest {

    @Test
    void unsharedWorldHasNoBadge() {
        assertNull(PublicWorldBadgeView.badge(PublicWorldStatus.notShared("New World")));
        assertNull(PublicWorldBadgeView.badge(null));
    }

    @Test
    void sharedWorldBadgeShowsCountAndIsHealthyColoured() {
        Cell cell = PublicWorldBadgeView.badge(new PublicWorldStatus("New World", true, 3));
        assertEquals("● 3 online", cell.text());
        assertEquals(Semantic.WORLD_HEALTHY, cell.semantic());
    }

    @Test
    void unknownCountFallsBackToPublicLabel() {
        assertEquals("● Public", PublicWorldBadgeView.badgeText(-1));
        Cell cell = PublicWorldBadgeView.badge(new PublicWorldStatus("New World", true, -1));
        assertEquals("● Public", cell.text());
    }

    @Test
    void zeroOnlineStillReadsExplicitly() {
        assertEquals("● 0 online", PublicWorldBadgeView.badgeText(0));
    }

    @Test
    void summaryCountsOnlySharedWorlds() {
        List<PublicWorldStatus> worlds = List.of(
                new PublicWorldStatus("A", true, 2),
                PublicWorldStatus.notShared("B"),
                new PublicWorldStatus("C", true, 0));
        assertEquals(2, PublicWorldBadgeView.sharedCount(worlds));
        assertEquals("2 worlds shared to Nodera", PublicWorldBadgeView.summary(worlds));
    }

    @Test
    void summarySingularAndEmpty() {
        assertEquals("1 world shared to Nodera",
                PublicWorldBadgeView.summary(List.of(new PublicWorldStatus("A", true, 1))));
        assertNull(PublicWorldBadgeView.summary(List.of(PublicWorldStatus.notShared("B"))));
        assertNull(PublicWorldBadgeView.summary(List.of()));
    }

    @Test
    void nullSaveNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PublicWorldStatus(null, true, 1));
    }
}
