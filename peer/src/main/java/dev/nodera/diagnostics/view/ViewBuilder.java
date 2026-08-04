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
 * <p>MC-GUI-5: every label and every phrase this class emits is a translation <b>key</b> plus
 * arguments ({@link Cell#tr}); only measured data goes out verbatim ({@link Cell#raw}). Being
 * Minecraft-free is the reason this class exists — it must not therefore become the place English
 * is assembled, because nothing downstream can translate a finished sentence.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class ViewBuilder {

    /** Panel title keys (also the subcommand selectors). */
    public static final String SESSION = "nodera.diag.panel.session";
    public static final String PEERS = "nodera.diag.panel.peers";
    public static final String NET = "nodera.diag.panel.net";
    public static final String REGIONS = "nodera.diag.panel.regions";
    public static final String ZONE = "nodera.diag.panel.zone";
    public static final String ENTITIES = "nodera.diag.panel.entities";
    public static final String SERVER = "nodera.diag.panel.server";
    public static final String HEALTH = "nodera.diag.panel.health";

    /** Row-label keys. */
    public static final String LBL_EPOCH = "nodera.diag.label.epoch";
    public static final String LBL_GATEWAY = "nodera.diag.label.gateway";
    public static final String LBL_ROLE = "nodera.diag.label.role";
    public static final String LBL_MEMBERS = "nodera.diag.label.members";
    public static final String LBL_PEERS = "nodera.diag.label.peers";
    public static final String LBL_CHUNKS = "nodera.diag.label.chunks";
    public static final String LBL_NOTE = "nodera.diag.label.note";
    public static final String LBL_TOTAL = "nodera.diag.label.total";
    public static final String LBL_STATE = "nodera.diag.label.state";
    public static final String LBL_REASON = "nodera.diag.label.reason";
    public static final String LBL_REGION = "nodera.diag.label.region";
    public static final String LBL_OWNED = "nodera.diag.label.owned";
    public static final String LBL_VALIDATOR = "nodera.diag.label.validator";
    public static final String LBL_REPLICA = "nodera.diag.label.replica";
    public static final String LBL_CERTIFIED = "nodera.diag.label.certified";
    public static final String LBL_PENDING = "nodera.diag.label.pending";
    public static final String LBL_SOLO = "nodera.diag.label.solo";

    /** Value keys. */
    public static final String VAL_NONE = "nodera.diag.value.none";
    public static final String VAL_NO_PEERS = "nodera.diag.value.no_peers";
    public static final String VAL_UP = "nodera.diag.value.up";
    public static final String VAL_DOWN = "nodera.diag.value.down";
    public static final String VAL_KEEPALIVES = "nodera.diag.value.keepalives";
    public static final String VAL_TX = "nodera.diag.value.tx";
    public static final String VAL_RX = "nodera.diag.value.rx";
    public static final String VAL_BYTES_FRAMES = "nodera.diag.value.bytes_frames";
    public static final String VAL_REGION_COUNT = "nodera.diag.value.region_count";
    public static final String VAL_NO_REGIONS = "nodera.diag.value.no_regions";
    public static final String VAL_CERTIFIED = "nodera.diag.value.certified";
    public static final String VAL_PENDING = "nodera.diag.value.pending";
    public static final String VAL_SOLO = "nodera.diag.value.solo";
    public static final String VAL_ENTITY_COUNT_ONE = "nodera.diag.value.entity_count_one";
    public static final String VAL_ENTITY_COUNT_MANY = "nodera.diag.value.entity_count_many";
    public static final String VAL_NO_ENTITIES = "nodera.diag.value.no_entities";

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
        rows.add(row(LBL_EPOCH, Cell.raw(ses.epoch())));
        NodeId gw = ses.gatewayId();
        Semantic gwSem = gw != null && gw.equals(s.self()) ? Semantic.SELF : Semantic.GATEWAY;
        rows.add(row(LBL_GATEWAY, gw == null
                ? Cell.boldTr(VAL_NONE, gwSem)
                : Cell.boldRaw(shortId(gw), gwSem)));
        rows.add(row(LBL_ROLE, Cell.raw(ses.selfRole(), Semantic.SELF)));
        rows.add(row(LBL_MEMBERS, Cell.raw(ses.memberCount())));
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
                    Cell.boldRaw(shortId(p.id()), idSem),
                    Cell.raw(p.route(), Semantic.SECONDARY),
                    Cell.raw(p.role(), Semantic.SECONDARY),
                    Cell.tr(p.up() ? VAL_UP : VAL_DOWN, upSem),
                    Cell.tr(VAL_KEEPALIVES, Semantic.SECONDARY, p.keepAlives())));
        }
        if (rows.isEmpty()) {
            rows.add(row(LBL_PEERS, Cell.tr(VAL_NO_PEERS, Semantic.SECONDARY)));
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
        rows.add(Row.of(Cell.tr(VAL_TX, Semantic.TX),
                Cell.tr(VAL_BYTES_FRAMES, Semantic.NEUTRAL, formatBytes(n.bytesTx()), n.framesTx()),
                Cell.raw(formatRate(n.bytesPerSecTx()), Semantic.TX)));
        rows.add(Row.of(Cell.tr(VAL_RX, Semantic.RX),
                Cell.tr(VAL_BYTES_FRAMES, Semantic.NEUTRAL, formatBytes(n.bytesRx()), n.framesRx()),
                Cell.raw(formatRate(n.bytesPerSecRx()), Semantic.RX)));
        // Per-type breakdown (frame counts), filtered or all.
        for (Map.Entry<String, long[]> e : n.byType().entrySet()) {
            String name = e.getKey();
            if (typeFilter != null && !name.equalsIgnoreCase(typeFilter)) {
                continue;
            }
            long[] tr = e.getValue();
            rows.add(Row.of(
                    Cell.raw(name, Semantic.SECONDARY),
                    Cell.raw(tr[0], Semantic.TX),
                    Cell.raw(tr[1], Semantic.RX)));
        }
        return Panel.titled(NET, Semantic.HEADING, rows);
    }

    /** The regions panel: owned/validator/replica counts + chunk total, or the placeholder line. */
    public static Panel regionsPanel(TelemetrySnapshot s) {
        RegionOwnership r = s.regions();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.of(Cell.boldTr(LBL_OWNED, Semantic.OWNED),
                Cell.tr(VAL_REGION_COUNT, Semantic.OWNED, r.primary().size())));
        rows.add(Row.of(Cell.boldTr(LBL_VALIDATOR, Semantic.VALIDATING),
                Cell.tr(VAL_REGION_COUNT, Semantic.VALIDATING, r.validator().size())));
        rows.add(Row.of(Cell.boldTr(LBL_REPLICA, Semantic.REPLICA),
                Cell.tr(VAL_REGION_COUNT, Semantic.REPLICA, r.replica().size())));
        rows.add(row(LBL_CHUNKS, Cell.raw(r.ownedChunks())));
        rows.addAll(certificationRows(r));
        if (r.isEmpty()) {
            rows.add(row(LBL_NOTE, Cell.tr(VAL_NO_REGIONS, Semantic.UNASSIGNED)));
        }
        return Panel.titled(REGIONS, Semantic.HEADING, rows);
    }

    /**
     * The certification rows of the regions panel (issue #47.3): what consensus has actually done
     * to the leases this node holds. Only non-zero states are rendered, and {@code solo} carries
     * its reason inline so a population shortfall never reads as a stalled commit.
     */
    private static List<Row> certificationRows(RegionOwnership r) {
        int certified = r.countCertification(RegionOwnership.Certification.CERTIFIED);
        int pending = r.countCertification(RegionOwnership.Certification.PENDING);
        int solo = r.countCertification(RegionOwnership.Certification.SOLO);
        List<Row> rows = new ArrayList<>();
        if (certified + pending + solo == 0) {
            return rows;
        }
        if (certified > 0) {
            rows.add(Row.of(Cell.boldTr(LBL_CERTIFIED, Semantic.OWNED),
                    Cell.tr(VAL_CERTIFIED, Semantic.OWNED, certified)));
        }
        if (pending > 0) {
            rows.add(Row.of(Cell.boldTr(LBL_PENDING, Semantic.VALIDATING),
                    Cell.tr(VAL_PENDING, Semantic.VALIDATING, pending)));
        }
        if (solo > 0) {
            rows.add(Row.of(Cell.boldTr(LBL_SOLO, Semantic.UNASSIGNED),
                    Cell.tr(VAL_SOLO, Semantic.UNASSIGNED, solo)));
        }
        return rows;
    }

    /** The zone panel: the region at the position + its {@link OwnershipState}. */
    public static Panel zonePanel(TelemetrySnapshot s, DimensionKey dim, int blockX, int blockZ) {
        return zonePanel(s, dim, blockX, blockZ, null);
    }

    /**
     * As {@link #zonePanel(TelemetrySnapshot, DimensionKey, int, int)}, with the state decided by
     * the caller.
     *
     * <p>The caller knows something this class cannot: <b>whose</b> zone is being asked about.
     * Classifying against {@code s.regions()} answers for the node that took the snapshot, which is
     * correct only when that is also the player asking — and on a player-hosted world it is the host
     * for everybody, so a joiner was told their own ground was foreign.
     *
     * @param state the already-resolved state, or {@code null} to classify against the snapshot.
     */
    public static Panel zonePanel(TelemetrySnapshot s, DimensionKey dim, int blockX, int blockZ,
                                  OwnershipState state) {
        var region = ZoneClassifier.regionAt(dim, blockX, blockZ);
        if (state == null) {
            state = ZoneClassifier.classify(dim, blockX, blockZ, s.regions());
        }
        String coord = region.regionX() + "," + region.regionZ();
        List<Row> rows = List.of(
                row(LBL_REGION, Cell.raw(coord)),
                row(LBL_STATE, Cell.boldRaw(state.name(), ownershipSemantic(state))));
        return Panel.titled(ZONE, Semantic.HEADING, rows);
    }

    /** The entities panel: total controlled + per-region, or the placeholder line. */
    public static Panel entitiesPanel(TelemetrySnapshot s) {
        EntityControl ec = s.entities();
        List<Row> rows = new ArrayList<>();
        rows.add(row(LBL_TOTAL, Cell.raw(ec.totalCount())));
        for (Map.Entry<dev.nodera.core.region.RegionId, List<Long>> e : ec.entities().entrySet()) {
            int n = e.getValue().size();
            rows.add(Row.of(Cell.raw(regionKey(e.getKey()), Semantic.SECONDARY),
                    Cell.tr(n == 1 ? VAL_ENTITY_COUNT_ONE : VAL_ENTITY_COUNT_MANY,
                            Semantic.SECONDARY, n)));
        }
        if (ec.totalCount() == 0) {
            rows.add(row(LBL_NOTE, Cell.tr(VAL_NO_ENTITIES, Semantic.UNASSIGNED)));
        }
        return Panel.titled(ENTITIES, Semantic.HEADING, rows);
    }

    /** The server/bootstrap panel: route, member table, aggregate net. */
    public static Panel serverPanel(TelemetrySnapshot s) {
        SessionInfo ses = s.session();
        List<Row> rows = new ArrayList<>();
        rows.add(row(LBL_ROLE, Cell.raw(ses.selfRole(), Semantic.SELF)));
        rows.add(row(LBL_MEMBERS, Cell.raw(ses.memberCount())));
        rows.add(Row.of(Cell.tr(VAL_TX, Semantic.TX),
                Cell.raw(formatBytes(s.net().bytesTx()))));
        rows.add(Row.of(Cell.tr(VAL_RX, Semantic.RX),
                Cell.raw(formatBytes(s.net().bytesRx()))));
        return Panel.titled(SERVER, Semantic.HEADING, rows);
    }

    /** The health panel: state + reason. */
    public static Panel healthPanel(TelemetrySnapshot s) {
        Health h = s.health().state();
        List<Row> rows = new ArrayList<>();
        rows.add(row(LBL_STATE, Cell.boldRaw(h.name(), healthSemantic(h))));
        if (!s.health().reason().isEmpty()) {
            rows.add(row(LBL_REASON, Cell.raw(s.health().reason(), Semantic.SECONDARY)));
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

    private static Row row(String labelKey, Cell value) {
        return Row.of(Cell.tr(labelKey, Semantic.SECONDARY), value);
    }
}
