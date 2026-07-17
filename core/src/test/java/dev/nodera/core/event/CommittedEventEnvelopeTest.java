package dev.nodera.core.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommittedEventEnvelope} polymorphic dispatch and round-trip checks (Task 2). The embedded
 * {@link RegionEvent} is decoded by tag-dispatch; an unknown tag must throw.
 */
final class CommittedEventEnvelopeTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot PREV = StateRoot.zero();
    private static final StateRoot NEXT = StateRoot.of(Bytes.fromHex(
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"));

    @Test
    void encodeDecodeRoundTripsBlockChangedEvent() {
        CommittedEventEnvelope env = new CommittedEventEnvelope(
                REGION, new RegionEpoch(1), new SnapshotVersion(5), 100L, 42L,
                new BlockChangedEvent(new NBlockPos(1, 2, 3), 4, 5),
                PREV, NEXT, Bytes.fromHex("deadbeef"));

        CommittedEventEnvelope decoded = CommittedEventEnvelope.decode(new CanonicalReader(encode(env)));

        assertThat(decoded).isEqualTo(env);
        assertThat(decoded.event()).isInstanceOf(BlockChangedEvent.class);
    }

    @Test
    void decodeThrowsOnUnknownEventTag() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.COMMITTED_EVENT_ENV).writeU16(Encodable.ENCODING_VERSION);
        REGION.encode(w);
        new RegionEpoch(0).encode(w);
        new SnapshotVersion(0).encode(w);
        w.writeU64(0L).writeU64(0L);
        w.writeU16(9999).writeU16(1);
        StateRoot.zero().encode(w);
        StateRoot.zero().encode(w);
        w.writeBytes(new byte[0]);

        assertThatThrownBy(() -> CommittedEventEnvelope.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown RegionEvent");
    }

    private static byte[] encode(CommittedEventEnvelope env) {
        CanonicalWriter w = new CanonicalWriter();
        env.encode(w);
        return w.toByteArray();
    }
}
