package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused compatibility and canonical-encoding coverage for the tag 23 version-2 upgrade. */
final class SessionKeepAliveCodecTest {

    private static final NodeId FROM = node(1);
    private static final NodeId PRIMARY = node(4);
    private static final RegionId OVERWORLD_ZERO =
            new RegionId(DimensionKey.overworld(), 0, 0);

    /** Hand-built legacy frame: tag 23, v1, sender node 1, sequence 2, and no further body. */
    private static final String LEGACY_V1_HEX =
            "00170001"
                    + "0001000100000000000000000000000000000001"
                    + "0000000000000002";

    /**
     * Hand-derived v2 frame: the v1 fields followed by one overworld (0,0), epoch 3, primary node 4,
     * last-applied tick 5 progress entry.
     */
    private static final String V2_GOLDEN_HEX =
            "00170002"
                    + "0001000100000000000000000000000000000001"
                    + "0000000000000002"
                    + "00000001"
                    + "000b0001000a0001000000096d696e656372616674"
                    + "000000096f766572776f726c640000000000000000"
                    + "000c00010000000000000003"
                    + "0001000100000000000000000000000000000004"
                    + "0000000000000005";

    @Test
    void decodesHandBuiltLegacyV1AsEmptyProgress() {
        SessionKeepAlive decoded =
                (SessionKeepAlive) MessageCodec.decode(Bytes.fromHex(LEGACY_V1_HEX).toArray());

        assertThat(decoded).isEqualTo(new SessionKeepAlive(FROM, 2L));
        assertThat(decoded.regionProgress()).isEmpty();
    }

    @Test
    void v2GoldenFrameMatchesExactCanonicalBytes() {
        SessionKeepAlive message = new SessionKeepAlive(FROM, 2L, List.of(
                new RegionProgress(OVERWORLD_ZERO, new RegionEpoch(3L), PRIMARY, 5L)));

        byte[] encoded = MessageCodec.encode(message);

        assertThat(Bytes.unsafeWrap(encoded).toHex()).isEqualTo(V2_GOLDEN_HEX);
        assertThat(encoded).hasSize(118);
    }

    @Test
    void v2RoundTripsAllProgressFields() {
        SessionKeepAlive original = new SessionKeepAlive(FROM, 19L, List.of(
                progress(new RegionId(DimensionKey.overworld(), 4, -2), 8L, node(9), 300L),
                progress(new RegionId(DimensionKey.of("minecraft", "the_nether"), -1, 7),
                        9L, node(10), 299L)));

        SessionKeepAlive decoded =
                (SessionKeepAlive) MessageCodec.decode(MessageCodec.encode(original));

        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.regionProgress()).hasSize(2);
    }

    @Test
    void progressIsImmutableAndCanonicallySortedByRegionId() {
        RegionProgress high = progress(new RegionId(DimensionKey.overworld(), 1, 0), 1, node(7), 9);
        RegionProgress low = progress(new RegionId(DimensionKey.overworld(), -1, 4), 1, node(7), 7);
        RegionProgress middle = progress(new RegionId(DimensionKey.overworld(), 0, -3), 1, node(7), 8);
        List<RegionProgress> input = new ArrayList<>(List.of(high, low, middle));

        SessionKeepAlive shuffled = new SessionKeepAlive(FROM, 3L, input);
        SessionKeepAlive alreadySorted =
                new SessionKeepAlive(FROM, 3L, List.of(low, middle, high));
        input.clear();

        assertThat(shuffled.regionProgress()).containsExactly(low, middle, high);
        assertThat(MessageCodec.encode(shuffled)).isEqualTo(MessageCodec.encode(alreadySorted));
        assertThatThrownBy(() -> shuffled.regionProgress().add(high))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateRegionsAndNegativeTicks() {
        RegionProgress first = progress(OVERWORLD_ZERO, 1L, node(2), 10L);
        RegionProgress contradictory = progress(OVERWORLD_ZERO, 2L, node(3), 11L);

        assertThatThrownBy(() -> new SessionKeepAlive(
                FROM, 4L, List.of(first, contradictory)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate region");
        assertThatThrownBy(() -> progress(OVERWORLD_ZERO, 1L, node(2), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastAppliedTick");
    }

    @Test
    void decoderRejectsDuplicateRegionsAndNegativeTicksInV2Frames() {
        byte[] golden = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
        byte[] duplicate = duplicateOnlyProgressEntry(golden);
        byte[] negativeTick = golden.clone();
        Arrays.fill(negativeTick, negativeTick.length - Long.BYTES, negativeTick.length, (byte) 0xff);

        assertThatThrownBy(() -> MessageCodec.decode(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate region");
        assertThatThrownBy(() -> MessageCodec.decode(negativeTick))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastAppliedTick");
    }

    @Test
    void rejectsUnsupportedVersionsAndTrailingDataForBothKeepAliveVersions() {
        byte[] unsupported = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
        unsupported[3] = 3;
        byte[] legacy = Bytes.fromHex(LEGACY_V1_HEX).toArray();
        byte[] legacyTrailing = Arrays.copyOf(legacy, legacy.length + 1);
        byte[] v2 = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
        byte[] v2Trailing = Arrays.copyOf(v2, v2.length + 1);

        assertThatThrownBy(() -> MessageCodec.decode(unsupported))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported SessionKeepAlive encoding version 3");
        assertThatThrownBy(() -> MessageCodec.decode(legacyTrailing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trailing");
        assertThatThrownBy(() -> MessageCodec.decode(v2Trailing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    void globalVersionAndTagRegistryRemainUnchanged() {
        assertThat(Bytes.unsafeWrap(MessageCodec.encode(new EchoTest(Bytes.empty()))).toHex())
                .isEqualTo("0011000100000000");
        assertThatThrownBy(() -> MessageCodec.decode(Bytes.fromHex("0011000200000000").toArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported message encoding version 2");
        assertThat(MessageCodec.ENCODING_VERSION).isEqualTo(1);
        assertThat(MessageCodec.TAG_SESSION_KEEP_ALIVE).isEqualTo(23);
        assertThat(MessageCodec.NEXT_TAG).isEqualTo(53);
        assertThat(MessageCodec.KNOWN_TAGS).hasSize(53).doesNotHaveDuplicates();
    }

    private static RegionProgress progress(
            RegionId region, long epoch, NodeId primary, long lastAppliedTick) {
        return new RegionProgress(region, new RegionEpoch(epoch), primary, lastAppliedTick);
    }

    private static NodeId node(long value) {
        return new NodeId(new UUID(0L, value));
    }

    private static byte[] duplicateOnlyProgressEntry(byte[] singleEntryFrame) {
        int listCountOffset = 4 + 20 + Long.BYTES;
        int entryOffset = listCountOffset + Integer.BYTES;
        int entryLength = singleEntryFrame.length - entryOffset;
        byte[] duplicate = Arrays.copyOf(singleEntryFrame, singleEntryFrame.length + entryLength);
        duplicate[listCountOffset + Integer.BYTES - 1] = 2;
        System.arraycopy(singleEntryFrame, entryOffset, duplicate, singleEntryFrame.length, entryLength);
        return duplicate;
    }
}
