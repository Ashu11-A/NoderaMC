package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.view.RendezvousStatusView.PathKind;
import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 31c: the Rendezvous-tab view model — no GUI env. Keys + arguments, never English. */
final class RendezvousStatusViewTest {

    @Test
    void emptyEndpointsShowPlaceholderRow() {
        Panel panel = RendezvousStatusView.panel(List.of());
        assertEquals(1, panel.rows().size());
        assertEquals(RendezvousStatusView.NONE_CONFIGURED,
                panel.rows().get(0).cells().get(0).key());
    }

    @Test
    void registeredRelayedEndpointRow() {
        Row row = RendezvousStatusView.rowOf(new RendezvousEndpointStatus(
                "127.0.0.1:25601", true, 2, 5L * 1024 * 1024, PathKind.RELAYED));
        assertEquals(List.of("127.0.0.1:25601"), row.cells().get(0).args());
        assertEquals(RendezvousStatusView.REGISTERED, row.cells().get(1).key());
        assertEquals("nodera.rendezvous.path.relayed", row.cells().get(2).key());
        assertEquals(RendezvousStatusView.RESERVATIONS, row.cells().get(3).key());
        assertEquals(List.of(2), row.cells().get(3).args());
        assertEquals(RendezvousStatusView.RELAYED, row.cells().get(4).key());
        assertEquals(List.of("5.0 MB"), row.cells().get(4).args());
    }

    @Test
    void nullPathDefaultsToNone() {
        RendezvousEndpointStatus s =
                new RendezvousEndpointStatus("h:1", false, -1, 0, null);
        assertEquals(PathKind.NONE, s.path());
        assertEquals("nodera.rendezvous.path.none", RendezvousStatusView.pathKey(s.path()));
    }

    @Test
    void pathKeys() {
        assertEquals("nodera.rendezvous.path.direct",
                RendezvousStatusView.pathKey(PathKind.DIRECT));
        assertEquals("nodera.rendezvous.path.punched",
                RendezvousStatusView.pathKey(PathKind.PUNCHED));
    }

    @Test
    void byteFormatting() {
        assertEquals("0 B", RendezvousStatusView.formatBytes(0));
        assertEquals("1.5 KB", RendezvousStatusView.formatBytes(1536));
        assertEquals("1.0 GB", RendezvousStatusView.formatBytes(1024L * 1024 * 1024));
    }
}
