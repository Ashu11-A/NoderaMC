package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * Task 31c: the pure view model behind the multiplayer screen's <b>Rendezvous</b> tab — one row per
 * configured {@code nodera-rendezvous} endpoint (Task 29), showing whether this node is registered,
 * how many relay reservations are active, how many bytes have been relayed, and how the transport
 * path resolved (direct / punched / relayed). Registered endpoints are green.
 *
 * <p>Layering: {@code diagnostics} → {@code core} only; the mod-side tab unpacks
 * {@code RendezvousPeerTransport}/{@code TransportSelector} metrics into
 * {@link RendezvousEndpointStatus} before calling in. Stateless static functions — gate-testable.
 *
 * @Thread-context stateless; any thread.
 */
public final class RendezvousStatusView {

    /** Lang key for the Rendezvous tab title. */
    public static final String TITLE = "nodera.multiplayer.tab.rendezvous";

    /** How the transport last resolved a peer path. */
    public enum PathKind { DIRECT, PUNCHED, RELAYED, NONE }

    /**
     * One rendezvous endpoint's live status.
     *
     * @param endpoint          {@code host:port} of the rendezvous service.
     * @param registered        whether this node holds a live signed record there.
     * @param activeReservations relay reservations currently open (negative = unknown).
     * @param bytesRelayed      total bytes relayed through this endpoint.
     * @param path              how the transport last resolved a peer path.
     */
    public record RendezvousEndpointStatus(
            String endpoint, boolean registered, int activeReservations,
            long bytesRelayed, PathKind path) {
        public RendezvousEndpointStatus {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            if (path == null) {
                path = PathKind.NONE;
            }
        }
    }

    private RendezvousStatusView() {
    }

    /** Build the Rendezvous tab panel from the live endpoint statuses, in configured order. */
    public static Panel panel(List<RendezvousEndpointStatus> endpoints) {
        List<Row> rows = new ArrayList<>();
        if (endpoints == null || endpoints.isEmpty()) {
            rows.add(Row.of(Cell.of("No rendezvous configured", Semantic.SECONDARY)));
            return Panel.titled(TITLE, Semantic.HEADING, rows);
        }
        for (RendezvousEndpointStatus e : endpoints) {
            rows.add(rowOf(e));
        }
        return Panel.titled(TITLE, Semantic.HEADING, rows);
    }

    static Row rowOf(RendezvousEndpointStatus e) {
        Semantic health = e.registered() ? Semantic.HEALTHY : Semantic.CRITICAL;
        List<Cell> cells = new ArrayList<>(4);
        cells.add(Cell.bold(e.endpoint(), health));
        cells.add(Cell.of(e.registered() ? "registered" : "unregistered", health));
        cells.add(Cell.of(pathLabel(e.path()), pathSemantic(e.path())));
        if (e.activeReservations() >= 0) {
            cells.add(Cell.of(e.activeReservations() + " relays", Semantic.SECONDARY));
        }
        cells.add(Cell.of(formatBytes(e.bytesRelayed()) + " relayed", Semantic.SECONDARY));
        return new Row(cells);
    }

    static String pathLabel(PathKind path) {
        return switch (path) {
            case DIRECT -> "direct";
            case PUNCHED -> "hole-punched";
            case RELAYED -> "relayed";
            case NONE -> "—";
        };
    }

    static Semantic pathSemantic(PathKind path) {
        return switch (path) {
            case DIRECT -> Semantic.HEALTHY;
            case PUNCHED -> Semantic.WORLD_HEALTHY;
            case RELAYED -> Semantic.DEGRADED;
            case NONE -> Semantic.SECONDARY;
        };
    }

    /** Bytes → {@code "0 B"}/{@code "12.3 KB"}/{@code "4.5 MB"}/{@code "1.2 GB"} (pure integer math). */
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        long value = bytes;
        int unit = -1;
        // Advance while the value is >= 1 MiB-equivalent so we keep one decimal of the last unit.
        long whole = value;
        long frac = 0;
        do {
            frac = (whole % 1024) * 10 / 1024;
            whole /= 1024;
            unit++;
        } while (whole >= 1024 && unit < units.length - 1);
        return whole + "." + frac + " " + units[unit];
    }
}
