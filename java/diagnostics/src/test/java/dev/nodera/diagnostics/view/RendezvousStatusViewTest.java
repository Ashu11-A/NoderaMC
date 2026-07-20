package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.view.RendezvousStatusView.PathKind;
import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 31c: the Rendezvous-tab view model — no GUI env. */
final class RendezvousStatusViewTest {

    @Test
    void emptyEndpointsShowPlaceholderRow() {
        Panel panel = RendezvousStatusView.panel(List.of());
        assertEquals(1, panel.rows().size());
        assertTrue(panel.rows().get(0).cells().get(0).text().contains("No rendezvous"));
    }

    @Test
    void registeredRelayedEndpointRow() {
        Row row = RendezvousStatusView.rowOf(new RendezvousEndpointStatus(
                "127.0.0.1:25601", true, 2, 5L * 1024 * 1024, PathKind.RELAYED));
        assertEquals("127.0.0.1:25601", row.cells().get(0).text());
        assertEquals("registered", row.cells().get(1).text());
        assertEquals("relayed", row.cells().get(2).text());
        assertEquals("2 relays", row.cells().get(3).text());
        assertEquals("5.0 MB relayed", row.cells().get(4).text());
    }

    @Test
    void nullPathDefaultsToNone() {
        RendezvousEndpointStatus s =
                new RendezvousEndpointStatus("h:1", false, -1, 0, null);
        assertEquals(PathKind.NONE, s.path());
        assertEquals("—", RendezvousStatusView.pathLabel(s.path()));
    }

    @Test
    void pathLabels() {
        assertEquals("direct", RendezvousStatusView.pathLabel(PathKind.DIRECT));
        assertEquals("hole-punched", RendezvousStatusView.pathLabel(PathKind.PUNCHED));
    }

    @Test
    void byteFormatting() {
        assertEquals("0 B", RendezvousStatusView.formatBytes(0));
        assertEquals("1.5 KB", RendezvousStatusView.formatBytes(1536));
        assertEquals("1.0 GB", RendezvousStatusView.formatBytes(1024L * 1024 * 1024));
    }
}
