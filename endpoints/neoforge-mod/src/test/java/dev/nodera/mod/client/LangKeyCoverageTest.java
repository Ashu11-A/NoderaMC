package dev.nodera.mod.client;

import dev.nodera.testkit.harness.LayoutManifest;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.diagnostics.model.EntityControl;
import dev.nodera.diagnostics.model.HealthStat;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.PeerLink;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PublicWorldBadgeView;
import dev.nodera.diagnostics.view.RendezvousStatusView;
import dev.nodera.diagnostics.view.Row;
import dev.nodera.diagnostics.view.TorrentWorldListView;
import dev.nodera.diagnostics.view.TrackerStatusView;
import dev.nodera.diagnostics.view.ViewBuilder;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-GUI-5: the {@code diagnostics} view models are Minecraft-free, so nothing in their own module
 * can check that the keys they emit are ones the mod can actually resolve. This test — which sits on
 * the side that owns {@code en_us.json} — closes that seam.
 *
 * <p>It asserts three things, all of which fail on a re-introduced hardcoded string rather than only
 * on today's wording: every key any view model emits exists in the lang file; every constant the
 * view models publish as a key exists in the lang file; and each key's placeholder count matches the
 * number of arguments the view model hands it, so an argument cannot silently go missing.
 */
final class LangKeyCoverageTest {

    private static final NodeId SELF = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final NodeId OTHER = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    /** {@code "key": "value"} pairs; the lang file is a flat one-pair-per-line object. */
    private static final Pattern PAIR = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private static Map<String, String> lang() {
        try (InputStream in = LangKeyCoverageTest.class.getClassLoader()
                .getResourceAsStream("assets/nodera/lang/en_us.json")) {
            assertThat(in).as("en_us.json on the test classpath").isNotNull();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> entries = new HashMap<>();
            Matcher m = PAIR.matcher(json);
            while (m.find()) {
                entries.put(m.group(1), m.group(2));
            }
            assertThat(entries).as("parsed lang entries").isNotEmpty();
            return entries;
        } catch (Exception e) {
            throw new AssertionError("cannot read en_us.json", e);
        }
    }

    /** {@code %s} placeholders in a lang value ({@code %%} is an escaped percent, not one). */
    private static int placeholders(String value) {
        int count = 0;
        for (int i = 0; i < value.length() - 1; i++) {
            char next = value.charAt(i + 1);
            if (value.charAt(i) == '%') {
                if (next == '%') {
                    i++;
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    // ---- fixtures covering every branch of every view model ---------------------------------

    private static TelemetrySnapshot populatedSnapshot() {
        SessionInfo ses = new SessionInfo(7L, SELF, true, 2, "bootstrap",
                List.of(new PeerLink(SELF, "127.0.0.1:25566", true, "gateway", -1L, 0L, true),
                        new PeerLink(OTHER, "127.0.0.1:25568", false, "peer", 120L, 4L, false)));
        NetStats net = new NetStats(2048, 4096, 5, 9, 100, 200, 1, 2,
                Map.of("SessionKeepAlive", new long[]{3, 5}));
        RegionId origin = new RegionId(DimensionKey.overworld(), 0, 0);
        RegionId east = new RegionId(DimensionKey.overworld(), 1, 0);
        RegionId nw = new RegionId(DimensionKey.overworld(), -1, -1);
        RegionOwnership regions = new RegionOwnership(List.of(origin), List.of(east), List.of(nw),
                64, Map.of(
                        origin, new RegionOwnership.LeaseInfo(1L, 100L,
                                RegionOwnership.Certification.CERTIFIED, 3, 2),
                        east, new RegionOwnership.LeaseInfo(1L, 100L,
                                RegionOwnership.Certification.PENDING, 3, 0),
                        nw, new RegionOwnership.LeaseInfo(1L, 100L,
                                RegionOwnership.Certification.SOLO, 1, 1)));
        EntityControl entities = new EntityControl(Map.of(origin, List.of(1L), east, List.of(2L, 3L)));
        return new TelemetrySnapshot(100L, SELF, true, ses, net, regions, entities,
                HealthStat.healthy());
    }

    private static TelemetrySnapshot emptySnapshot() {
        SessionInfo ses = new SessionInfo(1L, null, false, 0, "peer", List.of());
        return new TelemetrySnapshot(1L, SELF, false, ses,
                new NetStats(0, 0, 0, 0, 0, 0, 0, 0, Map.of()),
                RegionOwnership.empty(), EntityControl.empty(),
                new HealthStat(dev.nodera.diagnostics.state.Health.CRITICAL, "isolated"));
    }

    private static List<Panel> everyPanel() {
        List<Panel> panels = new ArrayList<>();
        for (TelemetrySnapshot s : List.of(populatedSnapshot(), emptySnapshot())) {
            panels.addAll(ViewBuilder.full(s).panels());
            panels.add(ViewBuilder.serverPanel(s));
            panels.add(ViewBuilder.zonePanel(s, DimensionKey.overworld(), 0, 0));
        }
        panels.add(TrackerStatusView.panel(List.of()));
        panels.add(TrackerStatusView.panel(List.of(
                new TrackerStatusView.TrackerEndpointStatus("a:1", true, 4, 12),
                new TrackerStatusView.TrackerEndpointStatus("b:2", false, -1, 125),
                new TrackerStatusView.TrackerEndpointStatus("c:3", false, 0, -1))));
        panels.add(RendezvousStatusView.panel(List.of()));
        List<RendezvousStatusView.RendezvousEndpointStatus> rendezvous = new ArrayList<>();
        for (RendezvousStatusView.PathKind kind : RendezvousStatusView.PathKind.values()) {
            rendezvous.add(new RendezvousStatusView.RendezvousEndpointStatus(
                    "r:1", true, 2, 5L * 1024 * 1024, kind));
        }
        rendezvous.add(new RendezvousStatusView.RendezvousEndpointStatus("r:2", false, -1, 0, null));
        panels.add(RendezvousStatusView.panel(rendezvous));
        List<TorrentWorldListView.TorrentWorldEntry> worlds = new ArrayList<>();
        for (WorldHealth health : WorldHealth.values()) {
            worlds.add(new TorrentWorldListView.TorrentWorldEntry(
                    "W" + health, 7, 12_345, 9750, health, 86_340, "Steve"));
            worlds.add(new TorrentWorldListView.TorrentWorldEntry(
                    "U" + health, -1, 0, 0, health, -1, ""));
        }
        panels.add(TorrentWorldListView.panel(worlds, ""));
        return panels;
    }

    private static List<Cell> everyCell() {
        List<Cell> cells = new ArrayList<>();
        for (Panel panel : everyPanel()) {
            for (Row row : panel.rows()) {
                cells.addAll(row.cells());
            }
        }
        // Cells the screens build outside a panel.
        cells.add(PublicWorldBadgeView.badgeCell(-1));
        cells.add(PublicWorldBadgeView.badgeCell(3));
        cells.add(PublicWorldBadgeView.summaryCell(
                List.of(new PublicWorldBadgeView.PublicWorldStatus("A", true, 1))));
        cells.add(PublicWorldBadgeView.summaryCell(List.of(
                new PublicWorldBadgeView.PublicWorldStatus("A", true, 1),
                new PublicWorldBadgeView.PublicWorldStatus("B", true, 2))));
        cells.add(PieceMapView.aggregates(PieceMapView.map("New World",
                List.of(PieceMapView.PieceState.HELD, PieceMapView.PieceState.MISSING), 1, 2)));
        cells.add(TorrentWorldListView.playersCell(0, Semantic.NEUTRAL));
        cells.add(TorrentWorldListView.playersCell(-1, Semantic.NEUTRAL));
        return cells;
    }

    // ---- the assertions ---------------------------------------------------------------------

    @Test
    void everyKeyAViewModelEmitsExistsInTheLangFile() {
        Map<String, String> lang = lang();
        List<String> missing = new ArrayList<>();
        for (Cell cell : everyCell()) {
            if (!lang.containsKey(cell.key())) {
                missing.add(cell.key());
            }
        }
        for (Panel panel : everyPanel()) {
            if (!lang.containsKey(panel.titleKey())) {
                missing.add(panel.titleKey());
            }
        }
        assertThat(missing).as("keys emitted by a view model with no en_us.json entry").isEmpty();
    }

    @Test
    void everyKeyCarriesExactlyAsManyArgumentsAsItHasPlaceholders() {
        Map<String, String> lang = lang();
        List<String> mismatched = new ArrayList<>();
        for (Cell cell : everyCell()) {
            if (!lang.containsKey(cell.key())) {
                continue; // reported by the coverage test
            }
            int expected = placeholders(lang.get(cell.key()));
            if (expected != cell.args().size()) {
                mismatched.add(cell.key() + ": lang wants " + expected
                        + " argument(s), view model supplied " + cell.args().size());
            }
        }
        assertThat(mismatched).as("key/argument arity drift").isEmpty();
    }

    @Test
    void everyKeyConstantPublishedByAViewModelResolves() throws Exception {
        Map<String, String> lang = lang();
        List<Class<?>> models = List.of(ViewBuilder.class, TrackerStatusView.class,
                RendezvousStatusView.class, TorrentWorldListView.class,
                PublicWorldBadgeView.class, PieceMapView.class, Cell.class);
        List<String> missing = new ArrayList<>();
        for (Class<?> model : models) {
            for (Field f : model.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                String value = (String) f.get(null);
                if (value == null || !value.startsWith("nodera.")) {
                    continue;
                }
                if (value.endsWith(".")) {
                    continue; // a prefix; its children are covered by the emitted-cell tests
                }
                if (!lang.containsKey(value)) {
                    missing.add(model.getSimpleName() + "." + f.getName() + " → " + value);
                }
            }
        }
        assertThat(missing).as("published key constants with no en_us.json entry").isEmpty();
    }

    /**
     * The HUD renderers cannot be loaded here (they touch Minecraft classes the unit-test classpath
     * does not carry), so their keys are read out of their own source instead — which also catches a
     * key typed straight into a {@code translatable} call without a constant.
     */
    @Test
    void everyKeyTheHudSourcesNameResolves() throws java.io.IOException {
        Map<String, String> lang = lang();
        Pattern keyLiteral = Pattern.compile("\"(nodera\\.(?:hud|bossbar|alert)[a-z0-9_.]*)\"");
        java.nio.file.Path pkg = hudPackage();
        List<String> missing = new ArrayList<>();
        int found = 0;
        for (String name : List.of("TabListRenderer.java", "BossBarManager.java",
                "ActionBarNotifier.java")) {
            String source = java.nio.file.Files.readString(pkg.resolve(name), StandardCharsets.UTF_8);
            Matcher m = keyLiteral.matcher(source);
            while (m.find()) {
                found++;
                if (!lang.containsKey(m.group(1))) {
                    missing.add(name + " → " + m.group(1));
                }
            }
        }
        assertThat(found).as("HUD keys discovered in source").isGreaterThanOrEqualTo(19);
        assertThat(missing).as("HUD keys with no en_us.json entry").isEmpty();
    }

    private static java.nio.file.Path hudPackage() {
        java.nio.file.Path pkg = LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod/debug/render");
        if (!java.nio.file.Files.isDirectory(pkg)) {
            throw new AssertionError("cannot locate the debug render package sources at " + pkg);
        }
        return pkg;
    }

    @Test
    void everyEnumDerivedKeyResolves() {
        Map<String, String> lang = lang();
        List<String> missing = new ArrayList<>();
        for (RendezvousStatusView.PathKind kind : RendezvousStatusView.PathKind.values()) {
            if (!lang.containsKey(RendezvousStatusView.pathKey(kind))) {
                missing.add(RendezvousStatusView.pathKey(kind));
            }
        }
        for (WorldHealth health : WorldHealth.values()) {
            if (!lang.containsKey(TorrentWorldListView.healthKey(health))) {
                missing.add(TorrentWorldListView.healthKey(health));
            }
        }
        for (PieceMapView.PieceState state : PieceMapView.PieceState.values()) {
            if (!lang.containsKey(PieceMapView.stateKey(state))) {
                missing.add(PieceMapView.stateKey(state));
            }
        }
        assertThat(missing).as("enum-derived keys with no en_us.json entry").isEmpty();
    }
}
