package dev.nodera.diagnostics.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrafficMeter} counter + snapshot math (Task 18 acceptance #1).
 */
final class TrafficMeterTest {

    @Test
    void countsBytesAndFramesPerDirection() {
        TrafficMeter m = new TrafficMeter();
        m.recordTx(100);
        m.recordTx(50);
        m.recordRx(200);

        assertThat(m.bytesTx()).isEqualTo(150);
        assertThat(m.framesTx()).isEqualTo(2);
        assertThat(m.bytesRx()).isEqualTo(200);
        assertThat(m.framesRx()).isEqualTo(1);
    }

    @Test
    void snapshotIsConsistentWithAccessors() {
        TrafficMeter m = new TrafficMeter();
        m.recordTx(10);
        m.recordRx(20);
        m.recordRx(30);

        TrafficMeter.Snapshot s = m.snapshot();
        assertThat(s.bytesTx()).isEqualTo(m.bytesTx()).isEqualTo(10);
        assertThat(s.bytesRx()).isEqualTo(m.bytesRx()).isEqualTo(50);
        assertThat(s.framesTx()).isEqualTo(1);
        assertThat(s.framesRx()).isEqualTo(2);
    }
}
