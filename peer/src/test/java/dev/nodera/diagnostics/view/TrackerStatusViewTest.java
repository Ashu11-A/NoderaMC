package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 31c: the Trackers-tab view model — no GUI env. Keys + arguments, never English. */
final class TrackerStatusViewTest {

    @Test
    void emptyEndpointsShowPlaceholderRow() {
        Panel panel = TrackerStatusView.panel(List.of());
        assertEquals(1, panel.rows().size());
        assertEquals(TrackerStatusView.NONE_CONFIGURED,
                panel.rows().get(0).cells().get(0).key());
    }

    @Test
    void reachableEndpointRow() {
        Row row = TrackerStatusView.rowOf(
                new TrackerEndpointStatus("127.0.0.1:25600", true, 4, 12));
        assertEquals(Cell.RAW, row.cells().get(0).key());
        assertEquals(List.of("127.0.0.1:25600"), row.cells().get(0).args());
        assertEquals(TrackerStatusView.ONLINE, row.cells().get(1).key());
        assertEquals(TrackerStatusView.WORLDS_INDEXED, row.cells().get(2).key());
        assertEquals(List.of(4), row.cells().get(2).args());
        assertEquals(TrackerStatusView.ACK_SECONDS, row.cells().get(3).key());
        assertEquals(List.of(12L), row.cells().get(3).args());
    }

    @Test
    void offlineEndpointHidesWorldCountWhenUnknown() {
        Row row = TrackerStatusView.rowOf(
                new TrackerEndpointStatus("h:1", false, -1, -1));
        assertEquals(TrackerStatusView.OFFLINE, row.cells().get(1).key());
        assertEquals(TrackerStatusView.ACK_NEVER, row.cells().get(2).key());
    }

    @Test
    void ackMinutesCarryTheMinuteCountAsAnArgument() {
        Cell minutes = TrackerStatusView.ackCell(125);
        assertEquals(TrackerStatusView.ACK_MINUTES, minutes.key());
        assertEquals(List.of(2L), minutes.args());
        assertEquals(TrackerStatusView.ACK_NEVER, TrackerStatusView.ackCell(-1).key());
        assertEquals(List.of(), TrackerStatusView.ackCell(-1).args());
    }

    @Test
    void panelRowPerEndpointInOrder() {
        Panel panel = TrackerStatusView.panel(List.of(
                new TrackerEndpointStatus("a:1", true, 1, 1),
                new TrackerEndpointStatus("b:2", false, -1, -1)));
        assertEquals(2, panel.rows().size());
        assertEquals(List.of("a:1"), panel.rows().get(0).cells().get(0).args());
        assertEquals(List.of("b:2"), panel.rows().get(1).cells().get(0).args());
    }
}
