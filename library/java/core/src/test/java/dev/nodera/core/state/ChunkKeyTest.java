package dev.nodera.core.state;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ChunkKeyTest {

    @Property(tries = 10_000)
    void packRoundTripsAllSignedChunkCoordinates(
            @ForAll int chunkX, @ForAll int chunkZ) {
        long key = ChunkKey.pack(chunkX, chunkZ);

        assertThat(ChunkKey.unpackX(key)).isEqualTo(chunkX);
        assertThat(ChunkKey.unpackZ(key)).isEqualTo(chunkZ);
    }

    @Test
    void packRetainsExactUnsignedBitLayout() {
        assertThat(ChunkKey.pack(Integer.MIN_VALUE, Integer.MAX_VALUE))
                .isEqualTo(0x8000_0000_7FFF_FFFFL);
        assertThat(ChunkKey.pack(Integer.MAX_VALUE, Integer.MIN_VALUE))
                .isEqualTo(0x7FFF_FFFF_8000_0000L);
    }
}
