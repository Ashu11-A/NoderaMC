package dev.nodera.diagnostics;

import dev.nodera.core.identity.NodeId;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.model.NetStats;
import dev.nodera.diagnostics.model.SessionInfo;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.source.DiagnosticsSource;
import dev.nodera.diagnostics.source.SnapshotBuilder;
import dev.nodera.diagnostics.state.Health;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiagnosticsCollector} aggregation + rate derivation + health (Task 18 acceptance #1/#2).
 */
final class DiagnosticsCollectorTest {

    private static final long SEC = 1_000_000_000L;
    private static final NodeId SELF = new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));

    @Test
    void samplesCumulativeTrafficAndDerivesRates() {
        TrafficMeter meter = new TrafficMeter();
        MessageCounters counters = new MessageCounters();
        counters.recordTx("SessionKeepAlive");
        counters.recordRx("SessionKeepAlive");

        DiagnosticsCollector c = new DiagnosticsCollector(meter, counters);
        c.register(sessionSource());

        // First sample: no traffic yet recorded into meter (counters have a KeepAlive tx+rx).
        TelemetrySnapshot s0 = c.sample(0L, 0L, SELF, true);
        assertThat(s0.net().byType()).containsKey("SessionKeepAlive");

        // Some traffic flows between ticks.
        meter.recordTx(1000);
        meter.recordRx(2000);
        meter.recordTx(500);

        TelemetrySnapshot s1 = c.sample(20L, SEC, SELF, true);
        NetStats net = s1.net();
        assertThat(net.bytesTx()).isEqualTo(1500);
        assertThat(net.bytesRx()).isEqualTo(2000);
        assertThat(net.framesTx()).isEqualTo(2);
        assertThat(net.framesRx()).isEqualTo(1);
        // 1500 B over the 1 s window → ~1500 B/s (tolerant of clamping).
        assertThat(net.bytesPerSecTx()).isBetween(1000.0, 3000.0);
        assertThat(net.msgsPerSecTx()).isPositive();
    }

    @Test
    void healthIsCriticalWithoutGatewayAndHealthyAtQuorum() {
        TrafficMeter meter = new TrafficMeter();
        MessageCounters counters = new MessageCounters();

        // No gateway → CRITICAL.
        DiagnosticsCollector c = new DiagnosticsCollector(meter, counters);
        c.register((DiagnosticsSource) b -> b.session(
                new SessionInfo(0L, null, false, 2, "peer", List.of())));
        TelemetrySnapshot s = c.sample(0L, 0L, SELF, false);
        assertThat(s.health().state()).isEqualTo(Health.CRITICAL);

        // Three members + gateway → HEALTHY.
        c = new DiagnosticsCollector(meter, counters);
        c.register((DiagnosticsSource) b -> b.session(
                new SessionInfo(1L, SELF, true, 3, "gateway", List.of())));
        s = c.sample(0L, 0L, SELF, false);
        assertThat(s.health().state()).isEqualTo(Health.HEALTHY);
    }

    @Test
    void latestIsNullBeforeFirstSample() {
        DiagnosticsCollector c = new DiagnosticsCollector(new TrafficMeter(), new MessageCounters());
        assertThat(c.latest()).isNull();
        c.sample(0L, 0L, SELF, true);
        assertThat(c.latest()).isNotNull();
    }

    private static DiagnosticsSource sessionSource() {
        return (DiagnosticsSource) b -> b.session(
                new SessionInfo(1L, SELF, true, 3, "bootstrap", List.of()));
    }
}
