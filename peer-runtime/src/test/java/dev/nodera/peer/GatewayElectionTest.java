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
}
