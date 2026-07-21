package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gateway election is a pure, deterministic, order-independent function — the property that
 * lets every surviving peer pick the same successor without coordination (Phase 6 continuity).
 */
final class GatewayElectionTest {

    private static PeerEntry entry(boolean bootstrap) {
        NodeId id = NodeId.random();
        return new PeerEntry(id, "127.0.0.1:0", NodeCapabilities.initial(), bootstrap);
    }

    private static PeerEntry entry(NodeCapabilities caps) {
        return new PeerEntry(NodeId.random(), "127.0.0.1:0", caps, false);
    }

    private static NodeCapabilities caps(int cores, long gibMemory, int latencyMs, double reliability) {
        return NodeCapabilities.of(cores, gibMemory << 30, latencyMs, reliability, 4, 8, true);
    }

    @Test
    void electionIsIndependentOfIterationOrder() {
        List<PeerEntry> members = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            members.add(entry(false));
        }
        NodeId reference = GatewayElection.elect(members, 3L);
        for (int shuffle = 0; shuffle < 20; shuffle++) {
            Collections.shuffle(members);
            assertThat(GatewayElection.elect(members, 3L))
                    .as("shuffle %d must elect the same gateway", shuffle)
                    .isEqualTo(reference);
        }
    }

    @Test
    void twoIndependentComputationsAgree() {
        List<PeerEntry> a = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            a.add(entry(false));
        }
        List<PeerEntry> b = new ArrayList<>(a);
        Collections.shuffle(b);
        assertThat(GatewayElection.elect(a, 9L)).isEqualTo(GatewayElection.elect(b, 9L));
    }

    @Test
    void aBootstrapPeerIsPreferredWhilePresent() {
        PeerEntry boot = entry(true);
        List<PeerEntry> members = new ArrayList<>(List.of(boot, entry(false), entry(false)));
        Collections.shuffle(members);
        assertThat(GatewayElection.elect(members, 0L)).isEqualTo(boot.nodeId());
    }

    @Test
    void withoutABootstrapAPlayerIsElected() {
        PeerEntry p1 = entry(false);
        PeerEntry p2 = entry(false);
        NodeId elected = GatewayElection.elect(List.of(p1, p2), 1L);
        assertThat(elected).isIn(p1.nodeId(), p2.nodeId());
    }

    @Test
    void differentEpochsCanReshuffleTheWinner() {
        // Over many epochs the winner is not always the same node (duty spreads on re-election).
        List<PeerEntry> members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            members.add(entry(false));
        }
        long distinctWinners = java.util.stream.LongStream.range(0, 50)
                .mapToObj(e -> GatewayElection.elect(members, e))
                .distinct()
                .count();
        assertThat(distinctWinners).isGreaterThan(1);
    }

    @Test
    void emptyMemberSetIsRejected() {
        assertThatThrownBy(() -> GatewayElection.elect(List.of(), 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Plan §3.5 capability-weighted rendezvous (retires L-29) ---

    @Test
    void capabilityWeightIsBoundedPureIntegerMath() {
        // Monotone and clamped: more cores/memory, lower latency, higher reliability ⇒ >= weight.
        assertThat(GatewayElection.capabilityWeight(caps(16, 32, 0, 1.0)))
                .isGreaterThan(GatewayElection.capabilityWeight(caps(1, 1, 200, 0.0)));
        assertThat(GatewayElection.capabilityWeight(caps(-1, -1, -5, -0.1)))
                .isEqualTo(GatewayElection.capabilityWeight(caps(0, 0, 0, 0.0)));
        // Out-of-range inputs clamp to the same weight as the saturated reference (latency 999 →
        // the floor bucket 200, so compare against latency 200, not 0).
        assertThat(GatewayElection.capabilityWeight(caps(99, 999, 999, 5.0)))
                .isEqualTo(GatewayElection.capabilityWeight(caps(16, 32, 200, 1.0)));
        assertThat(GatewayElection.capabilityWeight(caps(0, 1, 0, 1.0)))
                .isGreaterThan(GatewayElection.capabilityWeight(caps(0, 0, 0, 0.0)));
    }

    @Test
    void mostCapablePeerWinsRegardlessOfEpoch() {
        // A strongly-provisioned peer beats look-alike clients on every epoch (the best-provisioned
        // peer carries the session); rendezvous only spreads duty among EQUAL-weight peers.
        PeerEntry strong = entry(caps(16, 32, 1, 0.99));
        List<PeerEntry> members = new ArrayList<>(List.of(
                strong, entry(caps(2, 4, 50, 0.9)), entry(caps(2, 4, 50, 0.9))));
        for (long epoch = 0; epoch < 20; epoch++) {
            Collections.shuffle(members);
            assertThat(GatewayElection.elect(members, epoch)).isEqualTo(strong.nodeId());
        }
    }

    @Test
    void equalWeightPeersStillRotateAcrossEpochs() {
        // Among equals the rendezvous score spreads gateway duty on successive re-elections.
        List<PeerEntry> members = List.of(
                entry(caps(4, 8, 30, 0.95)),
                entry(caps(4, 8, 30, 0.95)),
                entry(caps(4, 8, 30, 0.95)),
                entry(caps(4, 8, 30, 0.95)));
        long distinctWinners = java.util.stream.LongStream.range(0, 50)
                .mapToObj(e -> GatewayElection.elect(members, e))
                .distinct()
                .count();
        assertThat(distinctWinners).isGreaterThan(1);
    }

    @Test
    void capabilityWeightBeatsBootstrapOnlyWhenAbsent() {
        // A bootstrap peer (dedicated server) still outranks a more capable player while alive.
        PeerEntry boot = new PeerEntry(NodeId.random(), "127.0.0.1:0", caps(1, 1, 100, 0.5), true);
        PeerEntry strong = entry(caps(16, 32, 1, 0.99));
        assertThat(GatewayElection.elect(List.of(boot, strong), 0L)).isEqualTo(boot.nodeId());
    }
}
