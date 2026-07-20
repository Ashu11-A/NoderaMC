package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.PunchSync;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure hole-punch coordination logic: go-signal wait and candidate selection (Task 29). */
final class HolePunchCoordinatorTest {

    private static final NodeId A =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final NodeId B =
            new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    @Test
    void waitMillisIsTheTimeUntilTheGoSignalAndNeverNegative() {
        assertThat(HolePunchCoordinator.waitMillis(10_500, 10_000)).isEqualTo(500);
        assertThat(HolePunchCoordinator.waitMillis(10_000, 10_500)).isZero();
    }

    @Test
    void targetCandidatePicksTheBestDirectObservedAddress() {
        List<PeerCandidate> observed = List.of(
                new PeerCandidate(CandidateKind.RELAY, "relay:25601", 100),
                new PeerCandidate(CandidateKind.SERVER_REFLEXIVE, "198.51.100.7:40000", 50),
                new PeerCandidate(CandidateKind.PUBLIC, "203.0.113.9:25566", 80));
        assertThat(HolePunchCoordinator.targetCandidate(observed))
                .get()
                .extracting(PeerCandidate::kind)
                .isEqualTo(CandidateKind.PUBLIC);
    }

    @Test
    void buildSyncCarriesTheNamespaceAndObservedCandidates() {
        UUID net = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        Bytes world = Bytes.fromHex("cafe");
        List<PeerCandidate> observed = List.of(
                new PeerCandidate(CandidateKind.SERVER_REFLEXIVE, "198.51.100.7:40000", 50));
        PunchSync sync = HolePunchCoordinator.buildSync(net, world, A, B, observed, 0L);
        assertThat(sync.source()).isEqualTo(A);
        assertThat(sync.target()).isEqualTo(B);
        assertThat(sync.observedCandidates()).isEqualTo(observed);
        assertThat(sync.goSignalEpochMillis()).isZero();
    }
}
