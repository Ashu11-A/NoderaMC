package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 31c: the Trackers-tab view model — no GUI env. */
final class TrackerStatusViewTest {

    @Test
    void emptyEndpointsShowPlaceholderRow() {
        Panel panel = TrackerStatusView.panel(List.of());
        assertEquals(1, panel.rows().size());
        assertTrue(panel.rows().get(0).cells().get(0).text().contains("No trackers"));
    }

    @Test
    void reachableEndpointRow() {
        Row row = TrackerStatusView.rowOf(
                new TrackerEndpointStatus("127.0.0.1:25600", true, 4, 12));
        assertEquals("127.0.0.1:25600", row.cells().get(0).text());
        assertEquals("online", row.cells().get(1).text());
        assertEquals("4 worlds", row.cells().get(2).text());
        assertEquals("ack 12s ago", row.cells().get(3).text());
    }

    @Test
    void offlineEndpointHidesWorldCountWhenUnknown() {
        Row row = TrackerStatusView.rowOf(
                new TrackerEndpointStatus("h:1", false, -1, -1));
        assertEquals("offline", row.cells().get(1).text());
        assertEquals("no ack", row.cells().get(2).text());
    }

    @Test
    void ackMinutesFormatting() {
        assertEquals("ack 2m ago", TrackerStatusView.formatAck(125));
        assertEquals("no ack", TrackerStatusView.formatAck(-1));
    }

    @Test
    void panelRowPerEndpointInOrder() {
        Panel panel = TrackerStatusView.panel(List.of(
                new TrackerEndpointStatus("a:1", true, 1, 1),
                new TrackerEndpointStatus("b:2", false, -1, -1)));
        assertEquals(2, panel.rows().size());
        assertEquals("a:1", panel.rows().get(0).cells().get(0).text());
        assertEquals("b:2", panel.rows().get(1).cells().get(0).text());
    }
}
