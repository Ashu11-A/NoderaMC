package dev.nodera.core.region;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RegionLease}: membership, expiry, canonical round-trip and defensive-copy guarantees.
 *
 * <p>Thread-context: single test thread.
 */
final class RegionLeaseTest {

    private final NodeId primary = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private final NodeId v1 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private final NodeId v2 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
    private final NodeId stranger = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000009"));

    private RegionLease sample() {
        return new RegionLease(
                new RegionId(DimensionKey.overworld(), 0, 0),
                RegionEpoch.INITIAL,
                primary,
                List.of(v1, v2),
                100L,
                300L);
    }

    @Test
    void containsPrimaryAndValidatorsButNotStrangers() {
        RegionLease lease = sample();
        assertThat(lease.contains(primary)).isTrue();
        assertThat(lease.contains(v1)).isTrue();
        assertThat(lease.contains(v2)).isTrue();
        assertThat(lease.contains(stranger)).isFalse();
    }

    @Test
    void isExpiredAtExpiresOnTheBoundaryTick() {
        RegionLease lease = sample();
        assertThat(lease.isExpiredAt(99L)).isFalse();
        assertThat(lease.isExpiredAt(299L)).isFalse();
        assertThat(lease.isExpiredAt(300L)).isTrue();
        assertThat(lease.isExpiredAt(301L)).isTrue();
    }

    @Test
    void encodeDecodeRoundTripEquals() {
        RegionLease lease = sample();
        CanonicalWriter w = new CanonicalWriter();
        lease.encode(w);
        RegionLease decoded = RegionLease.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(lease);
    }

    @Test
    void validatorsListIsUnmodifiable() {
        RegionLease lease = sample();
        assertThatThrownBy(() -> lease.validators().add(stranger))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roundTripStability() {
        RegionLease lease = sample();
        CanonicalWriter w1 = new CanonicalWriter();
        lease.encode(w1);
        RegionLease decoded = RegionLease.decode(new CanonicalReader(w1.toByteArray()));
        CanonicalWriter w2 = new CanonicalWriter();
        decoded.encode(w2);
        assertThat(w2.toByteArray()).isEqualTo(w1.toByteArray());
    }

    @Test
    void swappedValidatorOrderEncodesIdentically() {
        RegionLease a = new RegionLease(
                new RegionId(DimensionKey.overworld(), 0, 0), RegionEpoch.INITIAL,
                primary, List.of(v1, v2), 100L, 300L);
        RegionLease b = new RegionLease(
                new RegionId(DimensionKey.overworld(), 0, 0), RegionEpoch.INITIAL,
                primary, List.of(v2, v1), 100L, 300L);
        CanonicalWriter wa = new CanonicalWriter();
        a.encode(wa);
        CanonicalWriter wb = new CanonicalWriter();
        b.encode(wb);
        assertThat(wa.toByteArray()).isEqualTo(wb.toByteArray());
    }
}
