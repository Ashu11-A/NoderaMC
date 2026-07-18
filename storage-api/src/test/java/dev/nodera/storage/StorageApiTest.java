package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageApiTest {

    private final HashService hashes = new HashService();

    @Test
    void contentIdIsHashOfSizeAndCompression() {
        byte[] blob = "hello-nodera".getBytes();
        ContentId id = ContentId.of(hashes, blob);
        assertThat(id.hash()).isEqualTo(hashes.sha256(blob));
        assertThat(id.size()).isEqualTo(blob.length);
        assertThat(id.compression()).isEqualTo(Compression.NONE);
    }

    @Test
    void contentIdRejectsBadInput() {
        assertThatThrownBy(() -> new ContentId(null, 1, Compression.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentId(Bytes.empty(), -1, Compression.NONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContentId(Bytes.empty(), 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void genesisManifestHoldsFixedParameters() {
        StateRoot root = StateRoot.of(hashes.sha256("genesis".getBytes()));
        GenesisManifest m = new GenesisManifest(42L, 1, 99L, root);
        assertThat(m.worldSeed()).isEqualTo(42L);
        assertThat(m.genesisRoot()).isEqualTo(root);
        assertThatThrownBy(() -> new GenesisManifest(1, 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
