package dev.nodera.diagnostics.view;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.Health;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.diagnostics.state.Semantic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ViewBuilder} report logic — Panel/Row/Cell + {@link Semantic} for a crafted snapshot
 * (Task 18 acceptance #1).
 *
 * <p>MC-GUI-5: assertions name translation keys and argument lists. The builder emits no English,
 * so there is no rendered text here to assert against.
 */
final class ViewBuilderTest {

    private static final NodeId SELF = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final NodeId OTHER = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private TelemetrySnapshot snapshot() {
        SessionInfo ses = new SessionInfo(7L, SELF, true, 2, "bootstrap",
                List.of(new PeerLink(SELF, "127.0.0.1:25566", true, "gateway", -1L, 0L, true),
                        new PeerLink(OTHER, "127.0.0.1:25568", false, "peer", 120L, 4L, true)));
        NetStats net = new NetStats(2048, 4096, 5, 9, 100, 200, 1, 2,
                Map.of("SessionKeepAlive", new long[]{3, 5}, "PeerJoin", new long[]{2, 4}));
        return new TelemetrySnapshot(100L, SELF, true, ses, net,
                RegionOwnership.empty(), EntityControl.empty(), HealthStat.healthy());
    }

    @Test
    void sessionPanelMarksSelfGatewayWithSelfSemantic() {
        Panel p = ViewBuilder.sessionPanel(snapshot());
        assertThat(p.titleKey()).isEqualTo(ViewBuilder.SESSION);
        // Find the gateway row's value cell and assert it carries SELF + bold (we are the gateway).
        Cell gatewayValue = findRowSecondCell(p, ViewBuilder.LBL_GATEWAY);
        assertThat(gatewayValue.semantic()).isEqualTo(Semantic.SELF);
        assertThat(gatewayValue.bold()).isTrue();
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_EPOCH).args()).containsExactly("7");
    }

    @Test
    void peersPanelHasOneRowPerPeerAndKeepaliveCounts() {
        Panel p = ViewBuilder.peersPanel(snapshot());
        // 2 members → 2 data rows.
        assertThat(p.rows()).hasSize(2);
        Cell otherId = p.rows().get(1).cells().get(0);
        assertThat(otherId.key()).isEqualTo(Cell.RAW);
        assertThat(otherId.args()).containsExactly(ViewBuilder.shortId(OTHER));
        Cell keepAlives = p.rows().get(1).cells().get(4);
        assertThat(keepAlives.key()).isEqualTo(ViewBuilder.VAL_KEEPALIVES);
        assertThat(keepAlives.args()).containsExactly(4L);
    }

    @Test
    void netPanelIncludesPerTypeBreakdown() {
        Panel p = ViewBuilder.netPanel(snapshot(), null);
        assertThat(argStrings(p)).contains("SessionKeepAlive", "PeerJoin");
        assertThat(keys(p)).contains(ViewBuilder.VAL_TX, ViewBuilder.VAL_RX);
    }

    @Test
    void netPanelTypeFilterShowsOnlyThatType() {
        Panel p = ViewBuilder.netPanel(snapshot(), "SessionKeepAlive");
        assertThat(argStrings(p)).contains("SessionKeepAlive").doesNotContain("PeerJoin");
    }

    @Test
    void regionsPanelShowsPlaceholderWhenEmpty() {
        Panel p = ViewBuilder.regionsPanel(snapshot());
        assertThat(keys(p)).contains(ViewBuilder.VAL_NO_REGIONS); // placeholder note
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_OWNED).semantic()).isEqualTo(Semantic.OWNED);
    }

    @Test
    void zonePanelClassifiesOriginAsUnassignedWhenEmpty() {
        Panel p = ViewBuilder.zonePanel(snapshot(), DimensionKey.overworld(), 0, 0);
        Cell state = findRowSecondCell(p, ViewBuilder.LBL_STATE);
        assertThat(state.args()).containsExactly(OwnershipState.UNASSIGNED.name());
        assertThat(state.semantic()).isEqualTo(Semantic.UNASSIGNED);
    }

    @Test
    void zonePanelTurnsGreenInsideAnOwnedRegion() {
        RegionId origin = new RegionId(DimensionKey.overworld(), 0, 0);
        RegionOwnership own = new RegionOwnership(List.of(origin), List.of(), List.of(), 64, Map.of());
        TelemetrySnapshot s = new TelemetrySnapshot(1L, SELF, false, null, null, own,
                EntityControl.empty(), HealthStat.healthy());
        Panel p = ViewBuilder.zonePanel(s, DimensionKey.overworld(), 10, 10);
        Cell state = findRowSecondCell(p, ViewBuilder.LBL_STATE);
        assertThat(state.args()).containsExactly(OwnershipState.OWNED.name());
        assertThat(state.semantic()).isEqualTo(Semantic.OWNED);
    }

    @Test
    void entitiesPanelShowsPlaceholderWhenEmpty() {
        Panel p = ViewBuilder.entitiesPanel(snapshot());
        assertThat(keys(p)).contains(ViewBuilder.VAL_NO_ENTITIES);
    }

    @Test
    void healthPanelColoursByHealth() {
        TelemetrySnapshot critical = new TelemetrySnapshot(1L, SELF, false, null, null,
                RegionOwnership.empty(), EntityControl.empty(), new HealthStat(Health.CRITICAL, "isolated"));
        Panel p = ViewBuilder.healthPanel(critical);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_STATE).semantic())
                .isEqualTo(Semantic.CRITICAL);
    }

    @Test
    void fullViewIsNotEmptyAndOrdered() {
        DiagnosticsView v = ViewBuilder.full(snapshot());
        assertThat(v.isEmpty()).isFalse();
        assertThat(v.panels().get(0).titleKey()).isEqualTo(ViewBuilder.SESSION);
    }

    @Test
    void formatBytesUsesBinaryUnits() {
        assertThat(ViewBuilder.formatBytes(0)).isEqualTo("0 B");
        assertThat(ViewBuilder.formatBytes(512)).isEqualTo("512 B");
        // The [1024, 2048) band previously threw StringIndexOutOfBoundsException — pin it.
        assertThat(ViewBuilder.formatBytes(1024)).isEqualTo("1.0 KiB");
        assertThat(ViewBuilder.formatBytes(1500)).startsWith("1.5 KiB");
        assertThat(ViewBuilder.formatBytes(2047)).startsWith("2.0 KiB");
        assertThat(ViewBuilder.formatBytes(2048)).startsWith("2.0 KiB");
        // Exact binary boundaries used to mislabel the unit (1024 KiB instead of 1 MiB).
        assertThat(ViewBuilder.formatBytes(1048576L)).isEqualTo("1.0 MiB");
        assertThat(ViewBuilder.formatBytes(5L * 1024 * 1024)).startsWith("5.0 MiB");
    }

    @Test
    void formatRateUsesBinaryUnitsAcrossBands() {
        assertThat(ViewBuilder.formatRate(0)).isEqualTo("0 B/s");
        assertThat(ViewBuilder.formatRate(1024)).startsWith("1.0 KiB/s");
        assertThat(ViewBuilder.formatRate(1500)).startsWith("1.5 KiB/s");
        assertThat(ViewBuilder.formatRate(1048576.0)).startsWith("1.0 MiB/s");
    }

    @Test
    void serverPanelCarriesSelfRoleAndTxRxSemantics() {
        Panel p = ViewBuilder.serverPanel(snapshot());
        assertThat(p.titleKey()).isEqualTo(ViewBuilder.SERVER);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_ROLE).semantic()).isEqualTo(Semantic.SELF);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_MEMBERS).args())
                .containsExactly(String.valueOf(snapshot().session().memberCount()));
        // Rows: role, members, then the ▲ tx / ▼ rx marker rows (label-less → index by position).
        assertThat(p.rows().get(2).cells().get(0).semantic()).isEqualTo(Semantic.TX);
        assertThat(p.rows().get(3).cells().get(0).semantic()).isEqualTo(Semantic.RX);
    }

    @Test
    void regionsPanelShowsCountsWhenPopulated() {
        RegionId origin = new RegionId(DimensionKey.overworld(), 0, 0);
        RegionId east = new RegionId(DimensionKey.overworld(), 1, 0);
        RegionId nw = new RegionId(DimensionKey.overworld(), -1, -1);
        RegionOwnership own = new RegionOwnership(List.of(origin), List.of(east), List.of(nw), 64, Map.of());
        TelemetrySnapshot s = new TelemetrySnapshot(1L, SELF, false, null, null, own,
                EntityControl.empty(), HealthStat.healthy());
        Panel p = ViewBuilder.regionsPanel(s);
        // placeholder note absent when populated
        assertThat(keys(p)).doesNotContain(ViewBuilder.VAL_NO_REGIONS);
        Cell owned = findRowSecondCell(p, ViewBuilder.LBL_OWNED);
        assertThat(owned.key()).isEqualTo(ViewBuilder.VAL_REGION_COUNT);
        assertThat(owned.args()).containsExactly(1);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_VALIDATOR).semantic())
                .isEqualTo(Semantic.VALIDATING);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_REPLICA).semantic())
                .isEqualTo(Semantic.REPLICA);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_CHUNKS).args()).containsExactly("64");
    }

    @Test
    void entitiesPanelUsesSingularAndPluralWhenPopulated() {
        RegionId a = new RegionId(DimensionKey.overworld(), 0, 0);
        RegionId b = new RegionId(DimensionKey.overworld(), 1, 0);
        EntityControl ec = new EntityControl(Map.of(a, List.of(1L), b, List.of(2L, 3L)));
        TelemetrySnapshot s = new TelemetrySnapshot(1L, SELF, false, null, null,
                RegionOwnership.empty(), ec, HealthStat.healthy());
        Panel p = ViewBuilder.entitiesPanel(s);
        assertThat(findRowSecondCell(p, ViewBuilder.LBL_TOTAL).args()).containsExactly("3");
        // Plural is a key choice, not a concatenated suffix — a translator can express its own rule.
        assertThat(keys(p)).contains(ViewBuilder.VAL_ENTITY_COUNT_ONE,
                ViewBuilder.VAL_ENTITY_COUNT_MANY);
        assertThat(keys(p)).doesNotContain(ViewBuilder.VAL_NO_ENTITIES);
    }

    @Test
    void ownershipAndHealthSemanticMapsAreTotal() {
        for (OwnershipState s : OwnershipState.values()) {
            assertThat(ViewBuilder.ownershipSemantic(s)).isNotNull();
        }
        for (Health h : Health.values()) {
            assertThat(ViewBuilder.healthSemantic(h)).isNotNull();
        }
    }

    private static List<String> keys(Panel p) {
        return p.rows().stream().flatMap(r -> r.cells().stream()).map(Cell::key).toList();
    }

    private static List<String> argStrings(Panel p) {
        return p.rows().stream().flatMap(r -> r.cells().stream())
                .flatMap(c -> c.args().stream()).map(String::valueOf).toList();
    }

    /** Find the row whose first cell carries {@code labelKey}; return its second cell. */
    private static Cell findRowSecondCell(Panel p, String labelKey) {
        for (Row r : p.rows()) {
            if (!r.cells().isEmpty() && labelKey.equals(r.cells().get(0).key())) {
                return r.cells().size() > 1 ? r.cells().get(1) : r.cells().get(0);
            }
        }
        throw new AssertionError("no row with label key " + labelKey + " in " + keys(p));
    }
}
