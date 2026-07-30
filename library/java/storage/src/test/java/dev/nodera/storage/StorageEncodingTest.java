package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Canonical round-trips for the Task 9 persistence types (tags 81–83) — the disk format of the
 * archival tier and the wire form of the future checkpoint-sync messages.
 */
final class StorageEncodingTest {

    private static final StateRoot ROOT = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));

    @Test
    void contentIdRoundTripsBothCompressions() {
        for (Compression c : Compression.values()) {
            ContentId id = new ContentId(ROOT.hash(), 12345L, c);
            ContentId decoded = ContentId.decode(new CanonicalReader(encode(id)));
            assertThat(decoded).isEqualTo(id);
        }
    }

    @Test
    void checkpointRoundTripsIncludingGenesisSentinels() {
        Checkpoint checkpoint = new Checkpoint(
                new RegionId(DimensionKey.overworld(), -2, 7),
                new SnapshotVersion(42),
                ROOT,
                new ContentId(ROOT.hash(), 9L, Compression.ZSTD),
                1234L,
                -1L,           // genesis: replay resumes at 0
                Bytes.empty()); // genesis: no finalising certificate
        Checkpoint decoded = Checkpoint.decode(new CanonicalReader(encode(checkpoint)));
        assertThat(decoded).isEqualTo(checkpoint);
        assertThat(decoded.lastEventId()).isEqualTo(-1L);
    }

    @Test
    void genesisManifestRoundTripsNegativeSeed() {
        GenesisManifest genesis = new GenesisManifest(-987654321L, 3, 0xDEADBEEFCAFEL, ROOT);
        GenesisManifest decoded = GenesisManifest.decode(new CanonicalReader(encode(genesis)));
        assertThat(decoded).isEqualTo(genesis);
    }

    private static byte[] encode(dev.nodera.core.crypto.Encodable e) {
        CanonicalWriter w = new CanonicalWriter();
        e.encode(w);
        return w.toBytes().toArray();
    }
}
