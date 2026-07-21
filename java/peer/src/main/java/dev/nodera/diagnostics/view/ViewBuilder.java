package dev.nodera.diagnostics.view;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.diagnostics.classify.ZoneClassifier;
import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.Health;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.diagnostics.state.Semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link DiagnosticsView} (Panel/Row/Cell + {@link Semantic}) from a
 * {@link TelemetrySnapshot} — the MC-free report logic (Task 18).
 *
 * <p>Each subcommand in the {@code /nodera} / {@code /noderac} tree selects one or more of these
 * panels; every panel flows through the same {@code ComponentRenderer}, so all command output is a
 * uniform, colour-coded table. The snapshot → view mapping is pure and unit-tested (acceptance #1).
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ViewBuilder {

    /** Panel keys (subcommand selectors). */
    public static final String SESSION = "session";
    public static final String PEERS = "peers";
    public static final String NET = "net";
    public static final String REGIONS = "regions";
    public static final String ZONE = "zone";
    public static final String ENTITIES = "entities";
    public static final String SERVER = "server";
    public static final String HEALTH = "health";

    private ViewBuilder() {}

    // ---- full view -----------------------------------------------------------------------

    /** @return the full ordered view (every panel). */
    public static DiagnosticsView full(TelemetrySnapshot s) {
        return new DiagnosticsView(List.of(
                sessionPanel(s), netPanel(s, null), peersPanel(s),
                regionsPanel(s), entitiesPanel(s), healthPanel(s)));
    }

    // ---- panels --------------------------------------------------------------------------

    /** The session panel: epoch, gateway (GOLD/YELLOW unless self → SELF), role, member count. */
    public static Panel sessionPanel(TelemetrySnapshot s) {
        SessionInfo ses = s.session();
        List<Row> rows = new ArrayList<>();
        rows.add(row("epoch", cell(String.valueOf(ses.epoch()), Semantic.NEUTRAL)));
        NodeId gw = ses.gatewayId();
        Semantic gwSem = gw != null && gw.equals(s.self()) ? Semantic.SELF : Semantic.GATEWAY;
        rows.add(row("gateway", Cell.bold(gw == null ? "—" : shortId(gw), gwSem)));
        rows.add(row("role", cell(ses.selfRole(), Semantic.SELF)));
        rows.add(row("members", cell(String.valueOf(ses.memberCount()), Semantic.NEUTRAL)));
        return Panel.titled(SESSION, Semantic.HEADING, rows);
    }

    /** The peers panel: one row per peer — id, route, role, last-seen, keepalives, up/down. */
    public static Panel peersPanel(TelemetrySnapshot s) {
        SessionInfo ses = s.session();
        List<Row> rows = new ArrayList<>();
        for (PeerLink p : ses.peers()) {
            Semantic idSem = p.id().equals(s.self()) ? Semantic.SELF
                    : (ses.gatewayId() != null && ses.gatewayId().equals(p.id()) ? Semantic.GATEWAY
                    : Semantic.NEUTRAL);
            Semantic upSem = p.up() ? Semantic.HEALTHY : Semantic.CRITICAL;
            rows.add(Row.of(
                    Cell.bold(shortId(p.id()), idSem),
                    cell(p.route(), Semantic.SECONDARY),
                    cell(p.role(), Semantic.SECONDARY),
                    Cell.of(p.up() ? "up" : "down", upSem),
                    cell("ka=" + p.keepAlives(), Semantic.SECONDARY)));
        }
        if (rows.isEmpty()) {
            rows.add(row("peers", cell("(none)", Semantic.SECONDARY)));
        }
        return Panel.titled(PEERS, Semantic.HEADING, rows);
    }

    /**
     * The net panel: cumulative bytes/frames + rates, then the per-type breakdown. If
     * {@code typeFilter} is non-null, only that type's row is shown.
     */
    public static Panel netPanel(TelemetrySnapshot s, String typeFilter) {
        NetStats n = s.net();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.of("▲ tx", Semantic.TX),
                cell(formatBytes(n.bytesTx()) + " / " + n.framesTx() + " f", Semantic.NEUTRAL),
                Cell.of(formatRate(n.bytesPerSecTx()), Semantic.TX)));
        rows.add(Row.of(Cell.of("▼ rx", Semantic.RX),
                cell(formatBytes(n.bytesRx()) + " / " + n.framesRx() + " f", Semantic.NEUTRAL),
                Cell.of(formatRate(n.bytesPerSecRx()), Semantic.RX)));
        // Per-type breakdown (frame counts), filtered or all.
        for (Map.Entry<String, long[]> e : n.byType().entrySet()) {
            String name = e.getKey();
            if (typeFilter != null && !name.equalsIgnoreCase(typeFilter)) {
                continue;
            }
            long[] tr = e.getValue();
            rows.add(Row.of(
                    cell(name, Semantic.SECONDARY),
                    Cell.of(String.valueOf(tr[0]), Semantic.TX),
                    Cell.of(String.valueOf(tr[1]), Semantic.RX)));
        }
        return Panel.titled(NET, Semantic.HEADING, rows);
    }

    /** The regions panel: owned/validator/replica counts + chunk total, or the placeholder line. */
    public static Panel regionsPanel(TelemetrySnapshot s) {
        RegionOwnership r = s.regions();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.bold("owned", Semantic.OWNED),
                cell(r.primary().size() + " region(s)", Semantic.OWNED)));
        rows.add(Row.of(Cell.bold("validator", Semantic.VALIDATING),
                cell(r.validator().size() + " region(s)", Semantic.VALIDATING)));
        rows.add(Row.of(Cell.bold("replica", Semantic.REPLICA),
                cell(r.replica().size() + " region(s)", Semantic.REPLICA)));
        rows.add(row("chunks", cell(String.valueOf(r.ownedChunks()), Semantic.NEUTRAL)));
        if (r.isEmpty()) {
            rows.add(row("note", Cell.of("no delegated regions (Task 6)", Semantic.UNASSIGNED)));
        }
        return Panel.titled(REGIONS, Semantic.HEADING, rows);
    }

    /** The zone panel: the region at the position + its {@link OwnershipState}. */
    public static Panel zonePanel(TelemetrySnapshot s, DimensionKey dim, int blockX, int blockZ) {
        var region = ZoneClassifier.regionAt(dim, blockX, blockZ);
        OwnershipState state = ZoneClassifier.classify(dim, blockX, blockZ, s.regions());
        String coord = region.regionX() + "," + region.regionZ();
        List<Row> rows = List.of(
                row("region", cell(coord, Semantic.NEUTRAL)),
                row("state", Cell.bold(state.name(), ownershipSemantic(state))));
        return Panel.titled(ZONE, Semantic.HEADING, rows);
    }

    /** The entities panel: total controlled + per-region, or the placeholder line. */
    public static Panel entitiesPanel(TelemetrySnapshot s) {
        EntityControl ec = s.entities();
        List<Row> rows = new ArrayList<>();
        rows.add(row("total", cell(String.valueOf(ec.totalCount()), Semantic.NEUTRAL)));
        for (Map.Entry<dev.nodera.core.region.RegionId, List<Long>> e : ec.entities().entrySet()) {
            rows.add(row(regionKey(e.getKey()),
                    cell(e.getValue().size() + " entit" + (e.getValue().size() == 1 ? "y" : "ies"),
                            Semantic.SECONDARY)));
        }
        if (ec.totalCount() == 0) {
            rows.add(row("note", Cell.of("no controlled entities (Task 12)", Semantic.UNASSIGNED)));
        }
        return Panel.titled(ENTITIES, Semantic.HEADING, rows);
    }

    /** The server/bootstrap panel: route, member table, aggregate net. */
    public static Panel serverPanel(TelemetrySnapshot s) {
        SessionInfo ses = s.session();
        List<Row> rows = new ArrayList<>();
        rows.add(row("role", cell(ses.selfRole(), Semantic.SELF)));
        rows.add(row("members", cell(String.valueOf(ses.memberCount()), Semantic.NEUTRAL)));
        rows.add(Row.of(Cell.of("▲ tx", Semantic.TX),
                cell(formatBytes(s.net().bytesTx()), Semantic.NEUTRAL)));
        rows.add(Row.of(Cell.of("▼ rx", Semantic.RX),
                cell(formatBytes(s.net().bytesRx()), Semantic.NEUTRAL)));
        return Panel.titled(SERVER, Semantic.HEADING, rows);
    }

    /** The health panel: state + reason. */
    public static Panel healthPanel(TelemetrySnapshot s) {
        Health h = s.health().state();
        List<Row> rows = new ArrayList<>();
        rows.add(row("state", Cell.bold(h.name(), healthSemantic(h))));
        if (!s.health().reason().isEmpty()) {
            rows.add(row("reason", cell(s.health().reason(), Semantic.SECONDARY)));
        }
        return Panel.titled(HEALTH, Semantic.HEADING, rows);
    }

    // ---- semantic + format helpers -------------------------------------------------------

    /** Map an {@link OwnershipState} to its {@link Semantic}. */
    public static Semantic ownershipSemantic(OwnershipState s) {
        return switch (s) {
            case OWNED -> Semantic.OWNED;
            case VALIDATING -> Semantic.VALIDATING;
            case REPLICA -> Semantic.REPLICA;
            case FOREIGN -> Semantic.FOREIGN;
            case UNASSIGNED -> Semantic.UNASSIGNED;
        };
    }

    /** Map a {@link Health} to its {@link Semantic}. */
    public static Semantic healthSemantic(Health h) {
        return switch (h) {
            case HEALTHY -> Semantic.HEALTHY;
            case DEGRADED -> Semantic.DEGRADED;
            case CRITICAL -> Semantic.CRITICAL;
        };
    }

    /** Human-readable byte size (binary units): {@code 0}, {@code 1.2 KiB}, {@code 3.4 MiB}, … */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        // floor(log2(bytes)); each 1024× band is one exponent. exp >= 1 for bytes >= 1024, so the
        // "KMGTPE" index (exp - 1) is always in bounds — the earlier `(unit - 1) / 10` form threw
        // StringIndexOutOfBoundsException for the [1024, 2048) band (charAt(-1)).
        int unit = 63 - Long.numberOfLeadingZeros(bytes);
        int exp = unit / 10;
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %ciB",
                bytes / (double) (1L << (exp * 10)), pre);
    }

    /** Human-readable rate: {@code 1.2 KiB/s}. */
    public static String formatRate(double bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "0 B/s";
        }
        long rounded = Math.round(bytesPerSec);
        if (rounded < 1024L) {
            return rounded + " B/s";
        }
        int unit = 63 - Long.numberOfLeadingZeros(rounded);
        int exp = unit / 10;
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %ciB/s",
                bytesPerSec / (double) (1L << (exp * 10)), pre);
    }

    /** @return the first 8 hex chars of {@code id}'s UUID — a compact peer label. */
    public static String shortId(NodeId id) {
        String s = id.value().toString();
        return s.length() <= 8 ? s : s.substring(0, 8);
    }

    private static String regionKey(dev.nodera.core.region.RegionId r) {
        return r.regionX() + "," + r.regionZ();
    }

    private static Cell cell(String text, Semantic sem) {
        return Cell.of(text, sem);
    }

    private static Row row(String label, Cell value) {
        return Row.of(Cell.of(label, Semantic.SECONDARY), value);
    }
}
