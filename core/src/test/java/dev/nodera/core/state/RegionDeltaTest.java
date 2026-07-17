package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegionDelta} canonicalisation and round-trip checks (Task 2). Block mutations are sorted
 * by {@code (y, z, x)} (via {@link NBlockPos#compareTo}) so equivalent deltas encode identically.
 */
final class RegionDeltaTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot ROOT = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));

    @Test
    void mutationsSortedByYZX() {
        BlockMutation m1 = mut(new NBlockPos(0, 64, 0));
        BlockMutation m2 = mut(new NBlockPos(0, 0, 5));
        BlockMutation m3 = mut(new NBlockPos(9, 0, 0));

        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1),
                List.of(m1, m2, m3), ROOT);

        assertThat(delta.blockMutations()).extracting(BlockMutation::pos)
                .containsExactly(new NBlockPos(9, 0, 0), new NBlockPos(0, 0, 5), new NBlockPos(0, 64, 0));
    }

    @Test
    void isEmptyTrueWhenNoMutations() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(), ROOT);
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void isEmptyFalseWhenMutationsPresent() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1),
                List.of(mut(new NBlockPos(1, 2, 3))), ROOT);
        assertThat(delta.isEmpty()).isFalse();
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(2), new SnapshotVersion(3),
                List.of(mut(new NBlockPos(1, 2, 3)), mut(new NBlockPos(4, 5, 6))), ROOT);

        RegionDelta decoded = RegionDelta.decode(new CanonicalReader(encode(delta)));
        assertThat(decoded).isEqualTo(delta);
    }

    @Test
    void byteStableAcrossInputOrder() {
        BlockMutation a = mut(new NBlockPos(0, 64, 0));
        BlockMutation b = mut(new NBlockPos(0, 0, 5));
        RegionDelta d1 = new RegionDelta(REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(a, b), ROOT);
        RegionDelta d2 = new RegionDelta(REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(b, a), ROOT);

        assertThat(encode(d1)).isEqualTo(encode(d2));
    }

    private static BlockMutation mut(NBlockPos pos) {
        return new BlockMutation(pos, 1, 2, 0);
    }

    private static byte[] encode(RegionDelta delta) {
        CanonicalWriter w = new CanonicalWriter();
        delta.encode(w);
        return w.toByteArray();
    }
}
