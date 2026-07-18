package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NodeCapabilities}: default factory and canonical round-trip.
 *
 * <p>Thread-context: single test thread.
 */
final class NodeCapabilitiesTest {

    @Test
    void initialReturnsExpectedDefaults() {
        NodeCapabilities c = NodeCapabilities.initial();
        assertThat(c.logicalCores()).isEqualTo(4);
        assertThat(c.memoryBytes()).isEqualTo(4L * 1024 * 1024 * 1024);
        assertThat(c.latencyMs()).isEqualTo(50);
        assertThat(c.reliability()).isEqualTo(0.95);
        assertThat(c.maxPrimaryRegions()).isEqualTo(4);
        assertThat(c.maxValidatorRegions()).isEqualTo(8);
        assertThat(c.acceptsWorker()).isTrue();
    }

    @Test
    void encodeDecodeRoundTripEquals() {
        NodeCapabilities c = NodeCapabilities.of(8, 16L * 1024 * 1024 * 1024, 30, 0.99, 2, 4, false);
        CanonicalWriter w = new CanonicalWriter();
        c.encode(w);
        NodeCapabilities decoded = NodeCapabilities.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(c);
    }

    @Test
    void encodeIsDeterministicAcrossInstances() {
        NodeCapabilities a = NodeCapabilities.initial();
        NodeCapabilities b = NodeCapabilities.initial();
        CanonicalWriter wa = new CanonicalWriter();
        a.encode(wa);
        CanonicalWriter wb = new CanonicalWriter();
        b.encode(wb);
        assertThat(wa.toByteArray()).isEqualTo(wb.toByteArray());
    }

    @Test
    void rolesRoundTripAndAreCanonicalRegardlessOfInputOrder() {
        // A Set's iteration order is unspecified; the encoder must sort by frozen ordinal so two
        // peers declaring the same roles produce byte-identical wire forms.
        NodeCapabilities reverse = NodeCapabilities.initial().withRoles(java.util.EnumSet.of(
                PeerRole.PARTIAL_ARCHIVE, PeerRole.FULL_ARCHIVE, PeerRole.RELAY));
        NodeCapabilities sorted = NodeCapabilities.initial().withRoles(java.util.EnumSet.of(
                PeerRole.RELAY, PeerRole.PARTIAL_ARCHIVE, PeerRole.FULL_ARCHIVE));

        CanonicalWriter wa = new CanonicalWriter();
        reverse.encode(wa);
        CanonicalWriter wb = new CanonicalWriter();
        sorted.encode(wb);
        assertThat(wa.toByteArray()).isEqualTo(wb.toByteArray());

        NodeCapabilities decoded = NodeCapabilities.decode(new CanonicalReader(wa.toByteArray()));
        assertThat(decoded).isEqualTo(sorted);
        assertThat(decoded.roles()).containsExactly(
                PeerRole.RELAY, PeerRole.PARTIAL_ARCHIVE, PeerRole.FULL_ARCHIVE);
        assertThat(decoded.hasRole(PeerRole.FULL_ARCHIVE)).isTrue();
        assertThat(decoded.hasRole(PeerRole.WORLD_SEEDER)).isFalse();
    }

    @Test
    void withRolesReturnsACopyLeavingTheOriginalUntouched() {
        NodeCapabilities base = NodeCapabilities.initial();
        NodeCapabilities roled = base.withRoles(java.util.EnumSet.of(PeerRole.BOOTSTRAP));
        assertThat(base.roles()).isEmpty();
        assertThat(roled.roles()).containsExactly(PeerRole.BOOTSTRAP);
    }
}
