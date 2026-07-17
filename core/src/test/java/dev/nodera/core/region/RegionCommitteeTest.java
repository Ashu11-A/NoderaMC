package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegionCommittee}: membership, size, MVP factory, canonical round-trip and
 * validator-order canonicalization.
 *
 * <p>Thread-context: single test thread.
 */
final class RegionCommitteeTest {

    private final NodeId primary = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private final NodeId v1 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final NodeId v2 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    private final NodeId stranger = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000009"));

    private RegionCommittee sample() {
        return RegionCommittee.mvp(
                new RegionId(DimensionKey.overworld(), 0, 0),
                RegionEpoch.INITIAL,
                primary,
                List.of(v1, v2));
    }

    @Test
    void isMemberRecognizesPrimaryAndValidatorsOnly() {
        RegionCommittee c = sample();
        assertThat(c.isMember(primary)).isTrue();
        assertThat(c.isMember(v1)).isTrue();
        assertThat(c.isMember(v2)).isTrue();
        assertThat(c.isMember(stranger)).isFalse();
    }

    @Test
    void sizeIsPrimaryPlusValidators() {
        RegionCommittee c = sample();
        assertThat(c.size()).isEqualTo(3);
    }

    @Test
    void mvpSetsQuorumThresholdToMvpRequired() {
        RegionCommittee c = sample();
        assertThat(c.quorumThreshold()).isEqualTo(NoderaConstants.QUORUM_MVP_REQUIRED);
        assertThat(c.quorumThreshold()).isEqualTo(2);
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionCommittee c = sample();
        CanonicalWriter w = new CanonicalWriter();
        c.encode(w);
        RegionCommittee decoded = RegionCommittee.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(c);
    }

    @Test
    void swappedValidatorOrderEncodesToIdenticalBytes() {
        RegionCommittee a = new RegionCommittee(
                new RegionId(DimensionKey.overworld(), 0, 0), RegionEpoch.INITIAL,
                primary, List.of(v1, v2), 2);
        RegionCommittee b = new RegionCommittee(
                new RegionId(DimensionKey.overworld(), 0, 0), RegionEpoch.INITIAL,
                primary, List.of(v2, v1), 2);
        CanonicalWriter wa = new CanonicalWriter();
        a.encode(wa);
        CanonicalWriter wb = new CanonicalWriter();
        b.encode(wb);
        assertThat(wa.toByteArray()).isEqualTo(wb.toByteArray());
    }

    @Test
    void roundTripStability() {
        RegionCommittee c = sample();
        CanonicalWriter w1 = new CanonicalWriter();
        c.encode(w1);
        RegionCommittee decoded = RegionCommittee.decode(new CanonicalReader(w1.toByteArray()));
        CanonicalWriter w2 = new CanonicalWriter();
        decoded.encode(w2);
        assertThat(w2.toByteArray()).isEqualTo(w1.toByteArray());
    }
}
