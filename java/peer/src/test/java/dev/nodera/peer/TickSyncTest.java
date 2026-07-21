package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.metric.TickSkewMeter;
import dev.nodera.diagnostics.metric.TpsMeter;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

final class TickSyncTest {

    private static final int NO_SMOOTHING = 10_000;

    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        runtimes.forEach(PeerRuntime::stop);
    }

    @Test
    void locallyCertifiedCommitsProduceCanonicalProgressAndBothMetricSurfaces() {
        NodeId self = NodeIdentity.generate().nodeId();
        NodeId primaryA = NodeIdentity.generate().nodeId();
        NodeId primaryB = NodeIdentity.generate().nodeId();
        RegionId later = region("zeta", 2, -1);
        RegionId earlier = region("alpha", -3, 4);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        TickSkewMeter skew = new TickSkewMeter(NO_SMOOTHING);
        TpsMeter tps = new TpsMeter(NO_SMOOTHING);
        TickSync sync = new TickSync(self, skew, tps, now::get);

        assertThat(sync.onCertifiedCommit(later, RegionEpoch.INITIAL, primaryA, 100L)).isTrue();
        now.addAndGet(50_000_000L);
        assertThat(sync.onCertifiedCommit(earlier, RegionEpoch.INITIAL, primaryB, 96L)).isTrue();

        assertThat(sync.localProgress())
                .extracting(RegionProgress::region)
                .containsExactly(earlier, later);
        assertThat(sync.referenceTick(later)).hasValue(100L);
        assertThat(sync.referenceTick(earlier)).hasValue(96L);

        assertThat(skew.snapshot(self, later).orElseThrow().validatorReferenceTick()).isEqualTo(100L);
        assertThat(skew.validatorSkewBasisPoints(self, later)).isZero();
        assertThat(skew.regionSkewBasisPoints(primaryA, later)).isZero();
        assertThat(skew.regionSkewBasisPoints(primaryB, earlier))
                .isEqualTo(4L * TickSkewMeter.BASIS_POINTS_PER_TICK);
        assertThat(tps.snapshot(self, later).orElseThrow().acceptedCommitCount()).isEqualTo(1L);
    }

    @Test
    void certifiedNetworkReferenceDetectsLagWithOnlyOneLocalAssignment() {
        NodeId self = NodeIdentity.generate().nodeId();
        NodeId primary = NodeIdentity.generate().nodeId();
        RegionId region = region("overworld", 0, 0);
        TickSkewMeter skew = new TickSkewMeter(NO_SMOOTHING);
        TickSync sync = new TickSync(
                self, skew, new TpsMeter(NO_SMOOTHING), () -> 1_000_000_000L);

        sync.onCertifiedCommit(region, RegionEpoch.INITIAL, primary, 96L);
        assertThat(skew.regionSkewBasisPoints(primary, region)).isZero();

        assertThat(sync.onCertifiedNetworkReference(100L)).isTrue();
        assertThat(sync.onCertifiedNetworkReference(99L)).isFalse();
        assertThat(sync.certifiedNetworkReferenceTick()).isEqualTo(100L);
        assertThat(skew.regionSkewBasisPoints(primary, region))
                .isEqualTo(4L * TickSkewMeter.BASIS_POINTS_PER_TICK);
        assertThat(sync.localProgress())
                .extracting(RegionProgress::lastAppliedTick)
                .containsExactly(96L);
    }

    @Test
    void remoteProgressIsValidatedAndCannotAdvanceLocalReference() {
        NodeId self = NodeIdentity.generate().nodeId();
        NodeId peer = NodeIdentity.generate().nodeId();
        NodeId primary = NodeIdentity.generate().nodeId();
        NodeId wrongPrimary = NodeIdentity.generate().nodeId();
        RegionId region = region("overworld", 0, 0);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        TickSkewMeter skew = new TickSkewMeter(NO_SMOOTHING);
        TpsMeter tps = new TpsMeter(NO_SMOOTHING);
        TickSync sync = new TickSync(self, skew, tps, now::get);
        sync.onCertifiedCommit(region, RegionEpoch.INITIAL, primary, 100L);

        sync.onKeepAlive(keepAlive(peer, 1L,
                new RegionProgress(region, RegionEpoch.INITIAL, primary, 90L)));
        assertThat(skew.validatorSkewBasisPoints(peer, region))
                .isEqualTo(10L * TickSkewMeter.BASIS_POINTS_PER_TICK);
        assertThat(tps.snapshot(peer, region).orElseThrow().acceptedCommitCount()).isEqualTo(1L);

        // Repeated/stale values, a future epoch, and a conflicting primary cannot add samples.
        now.addAndGet(50_000_000L);
        sync.onKeepAlive(keepAlive(peer, 2L,
                new RegionProgress(region, RegionEpoch.INITIAL, primary, 90L)));
        sync.onKeepAlive(keepAlive(peer, 3L,
                new RegionProgress(region, RegionEpoch.INITIAL, primary, 89L)));
        sync.onKeepAlive(keepAlive(peer, 4L,
                new RegionProgress(region, RegionEpoch.INITIAL.bump(), primary, 95L)));
        sync.onKeepAlive(keepAlive(peer, 5L,
                new RegionProgress(region, RegionEpoch.INITIAL, wrongPrimary, 95L)));
        assertThat(skew.snapshot(peer, region).orElseThrow().validatorSampleCount()).isEqualTo(1L);
        assertThat(tps.snapshot(peer, region).orElseThrow().acceptedCommitCount()).isEqualTo(1L);

        // Even a validated advisory value ahead of us only affects the peer observation.
        sync.onKeepAlive(keepAlive(peer, 6L,
                new RegionProgress(region, RegionEpoch.INITIAL, primary, 1_000L)));
        TickSkewMeter.Snapshot remote = skew.snapshot(peer, region).orElseThrow();
        assertThat(remote.validatorAppliedTick()).isEqualTo(1_000L);
        assertThat(remote.validatorReferenceTick()).isEqualTo(100L);
        assertThat(remote.regionSampleCount()).isZero();
        assertThat(sync.referenceTick(region)).hasValue(100L);
        assertThat(sync.localProgress().getFirst().lastAppliedTick()).isEqualTo(100L);
    }

    @Test
    void currentCertifiedAssignmentGatesProgressAndEmptyV1HeartbeatIsHarmless() {
        NodeId self = NodeIdentity.generate().nodeId();
        NodeId peer = NodeIdentity.generate().nodeId();
        NodeId oldPrimary = NodeIdentity.generate().nodeId();
        NodeId currentPrimary = NodeIdentity.generate().nodeId();
        RegionId region = region("overworld", 1, 1);
        AtomicLong now = new AtomicLong(1_000_000_000L);
        TickSkewMeter skew = new TickSkewMeter(NO_SMOOTHING);
        TpsMeter tps = new TpsMeter(NO_SMOOTHING);
        TickSync sync = new TickSync(self, skew, tps, now::get);

        sync.onCertifiedCommit(region, RegionEpoch.INITIAL, oldPrimary, 100L);
        sync.onKeepAlive(new SessionKeepAlive(peer, 1L));
        assertThat(skew.snapshot(peer, region)).isEmpty();
        assertThat(tps.snapshot(peer, region)).isEmpty();

        sync.onKeepAlive(keepAlive(peer, 2L,
                new RegionProgress(region, RegionEpoch.INITIAL, oldPrimary, 1_000L)));
        sync.onCertifiedCommit(region, RegionEpoch.INITIAL.bump(), currentPrimary, 101L);

        // An advancing tick under the formerly valid assignment is still rejected after handoff.
        sync.onKeepAlive(keepAlive(peer, 3L,
                new RegionProgress(region, RegionEpoch.INITIAL, oldPrimary, 1_001L)));
        assertThat(skew.snapshot(peer, region)).isEmpty();

        now.addAndGet(50_000_000L);
        sync.onKeepAlive(keepAlive(peer, 4L,
                new RegionProgress(region, RegionEpoch.INITIAL.bump(), currentPrimary, 99L)));
        TickSkewMeter.Snapshot sample = skew.snapshot(peer, region).orElseThrow();
        assertThat(sample.validatorSampleCount()).isEqualTo(1L);
        assertThat(sample.validatorAppliedTick()).isEqualTo(99L);
        assertThat(sample.validatorReferenceTick()).isEqualTo(101L);
        assertThat(tps.snapshot(peer, region).orElseThrow().acceptedCommitCount()).isEqualTo(1L);
    }

    @Test
    void peerRuntimePropagatesCertifiedProgressOnHeartbeat() {
        LoopbackNetwork network = LoopbackNetwork.newNetwork();
        NodeIdentity bootstrapIdentity = NodeIdentity.generate();
        NodeIdentity peerIdentity = NodeIdentity.generate();
        RegionId region = region("overworld", 0, 0);
        RegionCommittee assignment = new RegionCommittee(
                region, RegionEpoch.INITIAL, bootstrapIdentity.nodeId(), List.of(), 1);
        AtomicLong now = new AtomicLong(1_000_000_000L);

        TickSync bootstrapSync = new TickSync(
                bootstrapIdentity.nodeId(), new TickSkewMeter(NO_SMOOTHING),
                new TpsMeter(NO_SMOOTHING), now::get);
        TickSkewMeter peerSkew = new TickSkewMeter(NO_SMOOTHING);
        TickSync peerSync = new TickSync(
                peerIdentity.nodeId(), peerSkew, new TpsMeter(NO_SMOOTHING), now::get);
        bootstrapSync.onCertifiedCommit(assignment, 90L);
        peerSync.onCertifiedCommit(assignment, 100L);

        Duration heartbeat = Duration.ofMillis(50);
        PeerRuntimeConfig config = new PeerRuntimeConfig(heartbeat, Duration.ofSeconds(1));
        LoopbackTransport bootstrapTransport = network.register(bootstrapIdentity.nodeId());
        LoopbackTransport peerTransport = network.register(peerIdentity.nodeId());
        PeerRuntime bootstrap = PeerRuntime.bootstrap(
                bootstrapIdentity, NodeCapabilities.initial(), bootstrapTransport, () -> "loopback",
                config, null, null, bootstrapSync);
        runtimes.add(bootstrap);
        PeerRuntime peer = PeerRuntime.peer(
                peerIdentity, NodeCapabilities.initial(), peerTransport, () -> "loopback",
                PeerAddress.of(bootstrapIdentity.nodeId(), "loopback"), config, null, null, peerSync);
        runtimes.add(peer);

        Await.until("peer receives bootstrap region progress", 5_000,
                () -> peerSkew.snapshot(bootstrapIdentity.nodeId(), region)
                        .map(sample -> sample.validatorSampleCount() > 0L)
                        .orElse(false));

        TickSkewMeter.Snapshot received =
                peerSkew.snapshot(bootstrapIdentity.nodeId(), region).orElseThrow();
        assertThat(received.validatorAppliedTick()).isEqualTo(90L);
        assertThat(received.validatorReferenceTick()).isEqualTo(100L);
        assertThat(received.validatorSkewBasisPoints())
                .isEqualTo(10L * TickSkewMeter.BASIS_POINTS_PER_TICK);
    }

    private static SessionKeepAlive keepAlive(NodeId from, long seq, RegionProgress progress) {
        return new SessionKeepAlive(from, seq, List.of(progress));
    }

    private static RegionId region(String path, int x, int z) {
        return new RegionId(DimensionKey.of("test", path), x, z);
    }
}
