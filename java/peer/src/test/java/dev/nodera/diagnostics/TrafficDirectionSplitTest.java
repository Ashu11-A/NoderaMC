package dev.nodera.diagnostics;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.metric.Direction;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.source.DiagnosticsSource;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.ViewBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #47.2 — "download and upload rates render identical".
 *
 * <p>Audited end to end: {@code MeteredPeerTransport} counts {@link Direction#TX} in {@code send}
 * and {@link Direction#RX} in the handler it wraps, {@link DiagnosticsCollector} keeps a separate
 * {@code RateWindow} per direction, and the surfaces read {@code bytesPerSecTx} / {@code
 * bytesPerSecRx} respectively. There is no shared field and no single sample delta reused for both
 * — this pins that, so a future refactor cannot quietly reintroduce the symmetric render.
 *
 * <p>What the reporter saw is real but is not this bug: on a two-peer continuity mesh nearly all
 * traffic is request/response gossip of the same shape, so each keep-alive sent is matched by one
 * received and the two figures genuinely coincide. Under asymmetric traffic — which is what a
 * content transfer is — they diverge, as asserted below.
 */
final class TrafficDirectionSplitTest {

    private static final long SEC = 1_000_000_000L;
    private static final NodeId SELF = new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));

    @Test
    void asymmetricTrafficRendersAsymmetricRatesInBothBytesAndFrames() {
        TrafficMeter meter = new TrafficMeter();
        DiagnosticsCollector collector = new DiagnosticsCollector(meter, new MessageCounters());
        collector.register((DiagnosticsSource) b -> b.session(
                new SessionInfo(1L, SELF, true, 3, "gateway", List.of())));
        collector.sample(0L, 0L, SELF, true);

        // A download: many large frames in, a handful of small requests out.
        for (int i = 0; i < 40; i++) {
            meter.record(Direction.RX, 64 * 1024);
        }
        for (int i = 0; i < 4; i++) {
            meter.record(Direction.TX, 96);
        }

        TelemetrySnapshot snapshot = collector.sample(20L, SEC, SELF, true);
        NetStats net = snapshot.net();
        assertThat(net.bytesRx()).isEqualTo(40L * 64 * 1024);
        assertThat(net.bytesTx()).isEqualTo(4L * 96);
        assertThat(net.bytesPerSecRx())
                .as("a download is not symmetric with its own requests")
                .isGreaterThan(net.bytesPerSecTx() * 100);
        assertThat(net.msgsPerSecRx()).isGreaterThan(net.msgsPerSecTx());

        // And the surface keeps them apart: ▲ reads TX, ▼ reads RX.
        Panel panel = ViewBuilder.netPanel(snapshot, null);
        assertThat(rateOf(panel, "\u25b2 tx"))
                .as("the two directions never render the same figure")
                .isNotEqualTo(rateOf(panel, "\u25bc rx"));
    }

    @Test
    void anUploadInvertsTheSamePairRatherThanMirroringIt() {
        TrafficMeter meter = new TrafficMeter();
        DiagnosticsCollector collector = new DiagnosticsCollector(meter, new MessageCounters());
        collector.register((DiagnosticsSource) b -> b.session(
                new SessionInfo(1L, SELF, true, 3, "gateway", List.of())));
        collector.sample(0L, 0L, SELF, true);

        for (int i = 0; i < 40; i++) {
            meter.record(Direction.TX, 64 * 1024);
        }
        meter.record(Direction.RX, 96);

        NetStats net = collector.sample(20L, SEC, SELF, true).net();
        assertThat(net.bytesPerSecTx()).isGreaterThan(net.bytesPerSecRx() * 100);
    }

    private static String rateOf(Panel panel, String row) {
        return panel.rows().stream()
                .filter(r -> r.cells().get(0).text().equals(row))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + row + "' row"))
                .cells().get(2).text();
    }
}
