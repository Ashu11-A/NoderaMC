package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.metric.MessageCounters;
import dev.nodera.diagnostics.metric.TrafficMeter;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.source.EntityControlProvider;
import dev.nodera.diagnostics.source.RegionOwnershipProvider;
import dev.nodera.peer.metric.MeteredPeerTransport;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 18 acceptance #2: after a keep-alive exchange over the in-JVM {@link LoopbackTransport}, the
 * {@link DiagnosticsCollector}'s {@link TelemetrySnapshot} shows real traffic (TX + RX bytes/frames),
 * {@code SessionKeepAlive} in the per-type breakdown, and the correct member/gateway/epoch — all
 * captured through the {@link MeteredPeerTransport} decorator + {@code PeerRuntime} per-type
 * counters with no Minecraft in the loop.
 */
final class DiagnosticsIT {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    @Test
    void snapshotReflectsRealTrafficAndSessionOverLoopback() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();

        NodeIdentity bootId = NodeIdentity.generate();
        NodeIdentity p1Id = NodeIdentity.generate();

        // Bootstrap: metered transport + counters wired into the runtime and a collector.
        TrafficMeter bootMeter = new TrafficMeter();
        MessageCounters bootCounts = new MessageCounters();
        MeteredPeerTransport bootTx = new MeteredPeerTransport(net.register(bootId.nodeId()), bootMeter);
        PeerRuntime boot = PeerRuntime.bootstrap(bootId, NodeCapabilities.initial(),
                bootTx, () -> "loopback", fast, new RecordingListener(), bootCounts);
        runtimes.add(boot);
        DiagnosticsCollector bootCol = new DiagnosticsCollector(bootMeter, bootCounts)
                .register(boot)
                .register(RegionOwnershipProvider.stub())
                .register(EntityControlProvider.stub());

        // Player peer: same wiring.
        TrafficMeter p1Meter = new TrafficMeter();
        MessageCounters p1Counts = new MessageCounters();
        MeteredPeerTransport p1Tx = new MeteredPeerTransport(net.register(p1Id.nodeId()), p1Meter);
        PeerAddress bootAddr = PeerAddress.of(bootId.nodeId(), "loopback");
        PeerRuntime p1 = PeerRuntime.peer(p1Id, NodeCapabilities.initial(),
                p1Tx, () -> "loopback", bootAddr, fast, new RecordingListener(), p1Counts);
        runtimes.add(p1);
        DiagnosticsCollector p1Col = new DiagnosticsCollector(p1Meter, p1Counts)
                .register(p1)
                .register(RegionOwnershipProvider.stub())
                .register(EntityControlProvider.stub());

        // 1. Mesh forms: both see 2 members, bootstrap is gateway.
        Await.until("2-member session", 5_000,
                () -> p1.sessionView().size() == 2 && boot.sessionView().size() == 2);
        Await.until("bootstrap is gateway", 5_000,
                () -> bootId.nodeId().equals(p1.gatewayId()));

        // 1b. Deterministic handshake: each side sent its single membership message exactly once
        //     through the metered sendTo choke point (locks per-type TX counting beyond the IT's
        //     later keep-alive >0 checks — catches a missing or double count on PeerJoin/MembershipUpdate).
        assertThat(p1Counts.tx("PeerJoin")).isEqualTo(1);
        assertThat(bootCounts.tx("MembershipUpdate")).isEqualTo(1);

        // 2. Real keep-alives flow both ways (independent of any vanilla connection).
        Await.until("keep-alives flow both ways", 5_000, () ->
                p1Counts.rx("SessionKeepAlive") > 0 && p1Counts.tx("SessionKeepAlive") > 0
                        && bootCounts.tx("SessionKeepAlive") > 0 && bootCounts.rx("SessionKeepAlive") > 0);

        // 3. The player's snapshot reflects the captured traffic + session truth.
        TelemetrySnapshot s = p1Col.sample(0L, System.nanoTime(), p1Id.nodeId(), false);

        assertThat(s.net().bytesTx()).isPositive();
        assertThat(s.net().bytesRx()).isPositive();
        assertThat(s.net().framesTx()).isPositive();
        assertThat(s.net().framesRx()).isPositive();
        assertThat(s.net().byType()).containsKey("SessionKeepAlive");
        assertThat(s.net().byType().get("SessionKeepAlive")[0]).isPositive(); // tx
        assertThat(s.net().byType().get("SessionKeepAlive")[1]).isPositive(); // rx

        // 4. Session truth: gateway is the bootstrap, member count ≥ 2, peers list populated.
        assertThat(s.session().gatewayId()).isEqualTo(bootId.nodeId());
        assertThat(s.session().memberCount()).isGreaterThanOrEqualTo(2);
        assertThat(s.session().epoch()).isGreaterThanOrEqualTo(0L);
        assertThat(s.session().peers()).extracting("id").contains(bootId.nodeId(), p1Id.nodeId());

        // 5. Bootstrap snapshot has its own traffic too (server HUD path).
        TelemetrySnapshot bootSnap = bootCol.sample(0L, System.nanoTime(), bootId.nodeId(), true);
        assertThat(bootSnap.net().bytesRx()).isPositive();
        assertThat(bootSnap.session().peers()).extracting("id").contains(p1Id.nodeId());
    }
}
