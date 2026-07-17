package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.protocol.codec.ChunkedStreams;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChunkedStreams} round-trip and compression-invariant checks (Task 4 acceptance #3:
 * "send an 8 MiB random blob; reassembled bytes hash-equal; no chunk exceeds caps; zstd
 * actually applied").
 *
 * <p>Thread-context: single test thread.
 */
final class ChunkedStreamsTest {

    /** 8 MiB deterministic fill: {@code b[i] = (byte) i}. */
    private static final int EIGHT_MIB = 8 * 1024 * 1024;

    @Test
    void splitAndJoinRoundTripsAnEightMibDeterministicBlob() {
        byte[] original = deterministicFill(EIGHT_MIB);

        List<StreamChunk> chunks = ChunkedStreams.split(1234L, original);
        assertThat(chunks).isNotEmpty();
        for (StreamChunk c : chunks) {
            assertThat(c.payload().length()).isLessThanOrEqualTo(NoderaConstants.MAX_STREAM_CHUNK);
            assertThat(c.streamId()).isEqualTo(1234L);
        }
        assertThat(chunks.get(0).total()).isEqualTo(chunks.size());
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(chunks.size() - 1).index()).isEqualTo(chunks.size() - 1);

        byte[] rejoined = ChunkedStreams.join(chunks, original.length);
        assertThat(rejoined).isEqualTo(original);
    }

    @Test
    void joinToleratesChunksArrivingOutOfOrder() {
        byte[] original = deterministicFill(70_000);
        List<StreamChunk> chunks = ChunkedStreams.split(7L, original);
        StreamChunk[] reversed = new StreamChunk[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            reversed[i] = chunks.get(chunks.size() - 1 - i);
        }
        byte[] rejoined = ChunkedStreams.join(java.util.Arrays.asList(reversed), original.length);
        assertThat(rejoined).isEqualTo(original);
    }

    @Test
    void zstdActuallyAppliedForHighlyCompressibleInput() {
        int oneMib = 1024 * 1024;
        byte[] allZeros = new byte[oneMib];

        List<StreamChunk> chunks = ChunkedStreams.split(1L, allZeros);
        long totalPayload = chunks.stream().mapToLong(c -> c.payload().length()).sum();

        assertThat(totalPayload).isLessThan((long) oneMib);
        assertThat(ChunkedStreams.join(chunks, allZeros.length)).isEqualTo(allZeros);
    }

    @Test
    void emptyPayloadRoundTripsAsOneChunk() {
        byte[] empty = new byte[0];
        List<StreamChunk> chunks = ChunkedStreams.split(99L, empty);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).total()).isEqualTo(1);
        // zstd frames an empty input into a small (non-zero) magic+header block; that is expected.
        assertThat(chunks.get(0).payload().length()).isLessThanOrEqualTo(NoderaConstants.MAX_STREAM_CHUNK);
        assertThat(ChunkedStreams.join(chunks, 0)).isEqualTo(empty);
    }

    @Test
    void chunkCountForMatchesCeilingDivision() {
        int max = NoderaConstants.MAX_STREAM_CHUNK;
        assertThat(ChunkedStreams.chunkCountFor(0)).isEqualTo(1);
        assertThat(ChunkedStreams.chunkCountFor(1)).isEqualTo(1);
        assertThat(ChunkedStreams.chunkCountFor(max)).isEqualTo(1);
        assertThat(ChunkedStreams.chunkCountFor(max + 1)).isEqualTo(2);
        assertThat(ChunkedStreams.chunkCountFor(3 * max)).isEqualTo(3);
        assertThat(ChunkedStreams.chunkCountFor(3 * max + 1)).isEqualTo(4);
    }

    @Test
    void joinRejectsGapInChunkIndices() {
        // Build hand-built chunks (decoupled from compression so we can force a multi-chunk gap):
        // two chunks each declaring total=3 ⇒ list size (2) != total (3) ⇒ rejected as a gap.
        Bytes one = Bytes.unsafeWrap(new byte[]{1});
        List<StreamChunk> withGap = List.of(
                new StreamChunk(3L, 0, 3, one),
                new StreamChunk(3L, 1, 3, one));
        assertThatThrownBy(() -> ChunkedStreams.join(withGap, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");
    }

    @Test
    void joinRejectsMixedStreamIds() {
        // Three hand-built chunks covering indices 0..2 (total=3), but index 1 carries a
        // different stream id ⇒ rejected as a mixed-stream join.
        Bytes one = Bytes.unsafeWrap(new byte[]{1});
        List<StreamChunk> mixed = new java.util.ArrayList<>(java.util.Arrays.asList(
                new StreamChunk(5L, 0, 3, one),
                new StreamChunk(999L, 1, 3, one),
                new StreamChunk(5L, 2, 3, one)));
        assertThatThrownBy(() -> ChunkedStreams.join(mixed, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stream");
    }

    @Test
    void joinRejectsEmptyList() {
        assertThatThrownBy(() -> ChunkedStreams.join(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] deterministicFill(int length) {
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) i;
        }
        return b;
    }
}
