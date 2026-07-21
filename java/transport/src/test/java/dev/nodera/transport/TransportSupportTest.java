package dev.nodera.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shared transport support types: Frames (promoted, issue #30) and Reachability. */
class TransportSupportTest {

    @Test
    void framesRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frames.write(out, new byte[] {1, 2, 3});
        Optional<byte[]> read = Frames.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(read).hasValueSatisfying(b -> assertThat(b).containsExactly(1, 2, 3));
    }

    @Test
    void framesReportCleanEndOfStream() throws Exception {
        assertThat(Frames.read(new ByteArrayInputStream(new byte[0]))).isEmpty();
    }

    @Test
    void framesRejectOversizeAnnouncement() {
        byte[] hostile = {0x7F, -1, -1, -1};
        assertThatThrownBy(() -> Frames.read(new ByteArrayInputStream(hostile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void reachabilityDetectsOpenAndClosedPorts() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            assertThat(Reachability.probe("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2)))
                    .isTrue();
        }
        try (ServerSocket toClose = new ServerSocket(0)) {
            int freed = toClose.getLocalPort();
            toClose.close();
            assertThat(Reachability.probe("127.0.0.1", freed, Duration.ofMillis(500))).isFalse();
        }
    }

    @Test
    void reachabilityRejectsBadArguments() {
        assertThatThrownBy(() -> Reachability.probe(" ", 80, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reachability.probe("localhost", 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reachability.probe("localhost", 80, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
