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
    public static final String TITLE = "nodera.multiplayer.panel.rendezvous";
    /** Lang key for the "no rendezvous configured" placeholder row. */
    public static final String NONE_CONFIGURED = "nodera.rendezvous.none_configured";
    /** Lang key for a registered endpoint. */
    public static final String REGISTERED = "nodera.rendezvous.registered";
    /** Lang key for an unregistered endpoint. */
    public static final String UNREGISTERED = "nodera.rendezvous.unregistered";
    /** Lang key for the open-reservation count ({@code %s} = count). */
    public static final String RESERVATIONS = "nodera.rendezvous.reservations";
    /** Lang key for the relayed-volume cell ({@code %s} = formatted size). */
    public static final String RELAYED = "nodera.rendezvous.relayed";
    /** Lang key prefix for {@link PathKind}; the enum name (lower case) completes it. */
    public static final String PATH_PREFIX = "nodera.rendezvous.path.";

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
            rows.add(Row.of(Cell.tr(NONE_CONFIGURED, Semantic.SECONDARY)));
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
        cells.add(Cell.boldRaw(e.endpoint(), health));
        cells.add(Cell.tr(e.registered() ? REGISTERED : UNREGISTERED, health));
        cells.add(Cell.tr(pathKey(e.path()), pathSemantic(e.path())));
        if (e.activeReservations() >= 0) {
            cells.add(Cell.tr(RESERVATIONS, Semantic.SECONDARY, e.activeReservations()));
        }
        cells.add(Cell.tr(RELAYED, Semantic.SECONDARY, formatBytes(e.bytesRelayed())));
        return new Row(cells);
    }

    /** @return the lang key naming this path kind — the label itself lives in the lang file. */
    public static String pathKey(PathKind path) {
        PathKind kind = path == null ? PathKind.NONE : path;
        return PATH_PREFIX + kind.name().toLowerCase(java.util.Locale.ROOT);
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
