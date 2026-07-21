package dev.nodera.transport.rendezvous;

import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Direct-candidate ordering and dialing (Task 29). */
final class CandidateDialerTest {

    @Test
    void directCandidatesExcludeRelayAndSortByPriorityDescending() {
        List<PeerCandidate> candidates = List.of(
                new PeerCandidate(CandidateKind.RELAY, "relay:25601", 100),
                new PeerCandidate(CandidateKind.HOST, "10.0.0.4:25566", 40),
                new PeerCandidate(CandidateKind.PUBLIC, "198.51.100.7:25566", 90));
        List<PeerCandidate> direct = CandidateDialer.directCandidates(candidates);
        assertThat(direct).extracting(PeerCandidate::kind)
                .containsExactly(CandidateKind.PUBLIC, CandidateKind.HOST);
    }

    @Test
    void dialConnectsToAReachableCandidate() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            String route = "127.0.0.1:" + server.getLocalPort();
            CandidateDialer dialer = new CandidateDialer(Duration.ofSeconds(2));
            Optional<Socket> socket = dialer.dial(List.of(
                    new PeerCandidate(CandidateKind.HOST, route, 100)));
            assertThat(socket).isPresent();
            socket.get().close();
        }
    }

    @Test
    void dialReturnsEmptyWhenNoDirectCandidateIsReachable() {
        CandidateDialer dialer = new CandidateDialer(Duration.ofMillis(200));
        // A relay-only candidate list has nothing to dial directly; a bogus host is unreachable.
        Optional<Socket> relayOnly = dialer.dial(List.of(
                new PeerCandidate(CandidateKind.RELAY, "relay:25601", 1)));
        assertThat(relayOnly).isEmpty();
    }
}
