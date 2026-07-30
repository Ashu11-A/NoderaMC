package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.symmetric.ContentCipher;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Structural and root-binding checks for encrypted region layouts. */
final class EncryptedRegionTest {

    private static final Bytes SALT = Bytes.fromHex("00112233445566778899aabbccddeeff");

    private static RegionSnapshotSplitter.Layout layout() {
        return RegionSnapshotSplitter.split(
                DistFixtures.variedSnapshot(
                        DistFixtures.region(7, -2), new SnapshotVersion(9L), 90L),
                512);
    }

    private static ContentKey key(byte value) {
        byte[] raw = new byte[ContentKey.KEY_BYTES];
        java.util.Arrays.fill(raw, value);
        return ContentKey.of(raw);
    }

    @Test
    void encryptionIsDeterministicAndDecryptsToCommittedPlaintext() {
        RegionSnapshotSplitter.Layout layout = layout();
        WorldKeyMaterial material = WorldKeyMaterial.defaultArgon2id(SALT);

        EncryptedRegion first = EncryptedRegion.encrypt(layout, key((byte) 1), material);
        EncryptedRegion second = EncryptedRegion.encrypt(layout, key((byte) 1), material);

        assertThat(first).isEqualTo(second);
        assertThat(first.manifest().regionRoot()).isEqualTo(layout.manifest().regionRoot());
        assertThat(first.manifest().blob().hash()).isNotEqualTo(layout.manifest().blob().hash());
        assertThat(first.decrypt(key((byte) 1))).contains(layout.blob());
        assertThat(first.decrypt(key((byte) 2))).isEmpty();
    }

    @Test
    void constructorRejectsTransportedNonceSubstitution() {
        EncryptedRegion valid = EncryptedRegion.encrypt(
                layout(), key((byte) 1), WorldKeyMaterial.defaultArgon2id(SALT));
        List<EncryptedPiece> changed = new ArrayList<>(valid.pieces());
        EncryptedPiece first = changed.get(0);
        byte[] nonce = first.nonce().toArray();
        nonce[0] ^= 1;
        changed.set(0, new EncryptedPiece(Bytes.unsafeWrap(nonce), first.ciphertext()));

        assertThatThrownBy(() -> new EncryptedRegion(valid.manifest(), changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-canonical nonce");
    }

    @Test
    void downloadedBlobIsSplitUsingManifestAndTamperFailsBeforeDecrypt() {
        EncryptedRegion valid = EncryptedRegion.encrypt(
                layout(), key((byte) 1), WorldKeyMaterial.defaultArgon2id(SALT));
        EncryptedRegion rebuilt = EncryptedRegion.fromCiphertext(
                valid.manifest(), valid.ciphertextBlob());
        assertThat(rebuilt).isEqualTo(valid);

        byte[] tampered = valid.ciphertextBlob().toArray();
        tampered[tampered.length - ContentCipher.TAG_BITS / Byte.SIZE] ^= 1;
        assertThat(EncryptedRegion.decrypt(
                valid.manifest(), Bytes.unsafeWrap(tampered), key((byte) 1))).isEmpty();
    }
}
