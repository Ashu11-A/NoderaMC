package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-stable golden vector and round-trip-stability checks for identity encodables
 * (Task 2 acceptance: canonical encoding must be deterministic and decode-stable).
 *
 * <p>The {@code NodeCapabilities} golden literal below is the exact canonical byte sequence for
 * {@link NodeCapabilities#initial()}, hand-derived from the format spec; if ANY encoding byte
 * changes, this test fails. {@link RegionLease}'s payload is large and UUID-bearing, so for it
 * we assert the strictly-deterministic invariants (encode twice = identical;
 * encode→decode→encode = identical) rather than a hand-transcribed literal.
 *
 * <p>Thread-context: single test thread.
 */
final class IdentityEncodeGoldenTest {

    /**
     * Golden canonical hex for {@link NodeCapabilities#initial()}:
     * {@code tag=0002 ver=0001 cores=00000004 mem=0000000100000000 latency=00000032
     * reliability(0.95)=3fee666666666666 maxPrimary=00000004 maxValidator=00000008
     * acceptsWorker=01 roles=00000000 (empty set, appended by Task 20)}.
     */
    private static final String NODE_CAPABILITIES_INITIAL_HEX =
            "00020001000000040000000100000000000000323fee66666666666600000004000000080100000000";

    @Test
    void nodeCapabilitiesInitialEncodesToGoldenHex() {
        NodeCapabilities c = NodeCapabilities.initial();
        CanonicalWriter w = new CanonicalWriter();
        c.encode(w);
        byte[] expected = Bytes.fromHex(NODE_CAPABILITIES_INITIAL_HEX).toArray();
        assertThat(w.toByteArray()).isEqualTo(expected);
        assertThat(w.size()).isEqualTo(41);
    }

    @Test
    void nodeCapabilitiesRoundTripIsStable() {
        NodeCapabilities c = NodeCapabilities.initial();
        CanonicalWriter w1 = new CanonicalWriter();
        c.encode(w1);
        NodeCapabilities decoded = NodeCapabilities.decode(new CanonicalReader(w1.toByteArray()));
        CanonicalWriter w2 = new CanonicalWriter();
        decoded.encode(w2);
        assertThat(decoded).isEqualTo(c);
        assertThat(w2.toByteArray()).isEqualTo(w1.toByteArray());
    }

    @Test
    void regionLeaseEncodeIsDeterministicAndRoundTripStable() {
        NodeId primary = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        NodeId v1 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        NodeId v2 = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        RegionLease lease = new RegionLease(
                new RegionId(DimensionKey.overworld(), 0, 0),
                RegionEpoch.INITIAL,
                primary,
                List.of(v1, v2),
                100L,
                300L);

        CanonicalWriter w1 = new CanonicalWriter();
        lease.encode(w1);
        CanonicalWriter w2 = new CanonicalWriter();
        lease.encode(w2);
        assertThat(w2.toByteArray()).isEqualTo(w1.toByteArray());

        RegionLease decoded = RegionLease.decode(new CanonicalReader(w1.toByteArray()));
        CanonicalWriter w3 = new CanonicalWriter();
        decoded.encode(w3);
        assertThat(decoded).isEqualTo(lease);
        assertThat(w3.toByteArray()).isEqualTo(w1.toByteArray());
    }
}
