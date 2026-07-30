package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-peer event-relay accounting behind {@code /nodera debug relay} (live-TPS
 * investigation): local capture counters, per-peer forwarded/proposal/vote/commit counts, and
 * nanosecond averages must aggregate correctly and render a stable human summary.
 */
final class RelayMetricsTest {

    @Test
    void countsLocalAndPerPeerLanes() {
        RelayMetrics m = new RelayMetrics();
        NodeId alice = NodeId.random();
        NodeId bob = NodeId.random();

        m.recordLocalSubmitted();
        m.recordLocalProposed();
        m.recordForwardedTo(alice);
        m.recordForwardProcessed(alice, 2_000_000);   // 2 ms doing alice's work
        m.recordForwardProcessed(alice, 4_000_000);   // 4 ms
        m.recordProposalProcessed(bob, 10_000_000);   // 10 ms re-executing bob's proposal
        m.recordVote(bob);
        m.recordCommit(bob);

        RelayMetrics.Snapshot s = m.snapshot();
        assertEquals(1, s.localSubmitted());
        assertEquals(1, s.localProposed());
        assertEquals(2, s.peers().size());

        RelayMetrics.PeerRow aliceRow = s.peers().stream()
                .filter(r -> r.peer().equals(alice)).findFirst().orElseThrow();
        assertEquals(1, aliceRow.forwardedTo());
        assertEquals(2, aliceRow.forwardedFrom());
        assertEquals(6_000_000, aliceRow.forwardProcessNanos());

        RelayMetrics.PeerRow bobRow = s.peers().stream()
                .filter(r -> r.peer().equals(bob)).findFirst().orElseThrow();
        assertEquals(1, bobRow.proposalsFrom());
        assertEquals(10_000_000, bobRow.proposalProcessNanos());
        assertEquals(1, bobRow.votesFrom());
        assertEquals(1, bobRow.commitsFrom());
    }

    @Test
    void describeRendersAveragesInMilliseconds() {
        RelayMetrics m = new RelayMetrics();
        NodeId peer = NodeId.random();
        m.recordForwardProcessed(peer, 3_000_000); // one event, 3 ms
        String text = m.describe();
        assertTrue(text.startsWith("events: own-captured 0"), text);
        assertTrue(text.contains("did-their-work 1 (avg 3.00 ms)"), text);
    }

    @Test
    void negativeNanosClampToZeroAndEmptySnapshotIsStable() {
        RelayMetrics m = new RelayMetrics();
        NodeId peer = NodeId.random();
        m.recordForwardProcessed(peer, -5); // a clock hiccup must never go negative
        assertEquals(0, m.snapshot().peers().get(0).forwardProcessNanos());
        assertEquals("events: own-captured 0 (self-proposed 0)", new RelayMetrics().describe());
    }
}
