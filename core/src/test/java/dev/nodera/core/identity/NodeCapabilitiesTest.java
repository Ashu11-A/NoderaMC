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
        NodeCapabilities c = new NodeCapabilities(8, 16L * 1024 * 1024 * 1024, 30, 0.99, 2, 4, false);
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
}
