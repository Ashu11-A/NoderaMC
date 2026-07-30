package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuorumCertificate} vote-canonicalisation and round-trip checks (Task 2). Votes are sorted
 * by voter {@link UUID} so two certificates that collected the same votes in different orders
 * encode identical bytes — certificate identity is defined by the encoded bytes.
 */
final class QuorumCertificateTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot ROOT_A = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));
    private static final StateRoot ROOT_B = StateRoot.of(Bytes.fromHex(
            "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100"));

    @Test
    void votesSortedByVoterUuid() {
        SignedVote late = vote("00000000-0000-0000-0000-0000000000ff");
        SignedVote early = vote("00000000-0000-0000-0000-000000000001");

        QuorumCertificate qc = new QuorumCertificate(
                REGION, new RegionEpoch(0), new SnapshotVersion(1),
                ROOT_A, ROOT_B, List.of(late, early));

        assertThat(qc.votes()).extracting(v -> v.voter().value())
                .containsExactly(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-0000000000ff"));
    }

    @Test
    void swappedVoteOrderProducesIdenticalBytes() {
        SignedVote a = vote("00000000-0000-0000-0000-0000000000ff");
        SignedVote b = vote("00000000-0000-0000-0000-000000000001");

        QuorumCertificate qc1 = new QuorumCertificate(
                REGION, new RegionEpoch(0), new SnapshotVersion(1), ROOT_A, ROOT_B, List.of(a, b));
        QuorumCertificate qc2 = new QuorumCertificate(
                REGION, new RegionEpoch(0), new SnapshotVersion(1), ROOT_A, ROOT_B, List.of(b, a));

        assertThat(encode(qc1)).isEqualTo(encode(qc2));
    }

    @Test
    void voteCount() {
        QuorumCertificate qc = new QuorumCertificate(
                REGION, new RegionEpoch(0), new SnapshotVersion(1), ROOT_A, ROOT_B,
                List.of(vote("00000000-0000-0000-0000-000000000001"),
                        vote("00000000-0000-0000-0000-000000000002")));
        assertThat(qc.voteCount()).isEqualTo(2);
    }

    @Test
    void encodeDecodeRoundTrip() {
        QuorumCertificate qc = new QuorumCertificate(
                REGION, new RegionEpoch(4), new SnapshotVersion(9), ROOT_A, ROOT_B,
                List.of(vote("00000000-0000-0000-0000-000000000001"),
                        vote("00000000-0000-0000-0000-0000000000aa")));

        QuorumCertificate decoded = QuorumCertificate.decode(new CanonicalReader(encode(qc)));
        assertThat(decoded).isEqualTo(qc);
    }

    private static SignedVote vote(String uuid) {
        return new SignedVote(
                new NodeId(UUID.fromString(uuid)), ROOT_B, VoteDecision.ACCEPT, Bytes.fromHex("cafe"));
    }

    private static byte[] encode(QuorumCertificate qc) {
        CanonicalWriter w = new CanonicalWriter();
        qc.encode(w);
        return w.toByteArray();
    }
}
