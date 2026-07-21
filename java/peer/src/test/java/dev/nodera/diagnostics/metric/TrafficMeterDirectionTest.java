package dev.nodera.diagnostics.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Direction-keyed TrafficMeter API (wires the previously unreferenced Direction enum). */
class TrafficMeterDirectionTest {

    @Test
    void directionKeyedRecordsMatchTheLegacyAccessors() {
        TrafficMeter meter = new TrafficMeter();
        meter.record(Direction.TX, 100);
        meter.record(Direction.TX, 50);
        meter.record(Direction.RX, 7);

        assertThat(meter.bytes(Direction.TX)).isEqualTo(150).isEqualTo(meter.bytesTx());
        assertThat(meter.bytes(Direction.RX)).isEqualTo(7).isEqualTo(meter.bytesRx());
        assertThat(meter.frames(Direction.TX)).isEqualTo(2).isEqualTo(meter.framesTx());
        assertThat(meter.frames(Direction.RX)).isEqualTo(1).isEqualTo(meter.framesRx());
    }
}
