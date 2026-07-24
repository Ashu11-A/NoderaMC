package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 23 / L-39 — the worker SEED/join halves headlessly: an archive snapshot seeded under a
 * world password moves ONLY ciphertext (piece hashes, ContentId, and blob all cover ciphertext),
 * the KDF parameters travel publicly in the manifest, the right password recovers the exact
 * plaintext, a wrong password fails the AES-GCM tag and yields empty — never a garbage archive —
 * and tampered ciphertext is rejected.
 */
final class EncryptedArchiveTest {

    private static final HashService HASHES = new HashService();

    private static byte[] archiveBlob() {
        byte[] blob = new byte[300_000]; // > 1 archive piece
        new SecureRandom(new byte[]{42}).nextBytes(blob);
        return blob;
    }

    private static Bytes salt() {
        byte[] salt = new byte[dev.nodera.core.NoderaConstants.PASSWORD_KDF_SALT_BYTES];
        Arrays.fill(salt, (byte) 7);
        return Bytes.unsafeWrap(salt);
    }

    @Test
    void rightPasswordRoundTripsAndSeedersOnlyEverSeeCiphertext() {
        byte[] plain = archiveBlob();
        EncryptedRegion sealed = WorldArchive.encryptArchive(
                3, plain, "correct horse".toCharArray(), salt());

        PieceManifest manifest = sealed.manifest();
        assertThat(manifest.encrypted()).isTrue();
        assertThat(manifest.keyMaterial()).isNotNull();
        assertThat(manifest.regionRoot().hash())
                .as("the plaintext identity stays pinned in regionRoot")
                .isEqualTo(HASHES.sha256(plain));
        byte[] ciphertext = sealed.ciphertextBlob().toArray();
        assertThat(Arrays.equals(ciphertext, plain))
                .as("the stored/served blob is ciphertext, not the save")
                .isFalse();
        for (int i = 0; i < manifest.pieceCount(); i++) {
            assertThat(manifest.piece(i).pieceHash())
                    .as("piece hashes cover ciphertext (keyless seeders verify without the key)")
                    .isEqualTo(HASHES.sha256(sealed.pieces().get(i).ciphertext()));
        }

        Optional<byte[]> decrypted = WorldArchive.decryptArchive(
                manifest, ciphertext, "correct horse".toCharArray());
        assertThat(decrypted).isPresent();
        assertThat(decrypted.get()).isEqualTo(plain);
    }

    @Test
    void wrongPasswordYieldsEmptyNeverGarbage() {
        byte[] plain = archiveBlob();
        EncryptedRegion sealed = WorldArchive.encryptArchive(
                1, plain, "right".toCharArray(), salt());
        assertThat(WorldArchive.decryptArchive(
                sealed.manifest(), sealed.ciphertextBlob().toArray(), "wrong".toCharArray()))
                .isEmpty();
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] plain = archiveBlob();
        EncryptedRegion sealed = WorldArchive.encryptArchive(
                1, plain, "pw-secret".toCharArray(), salt());
        byte[] tampered = sealed.ciphertextBlob().toArray();
        tampered[tampered.length / 2] ^= 0x01;
        assertThat(WorldArchive.decryptArchive(
                sealed.manifest(), tampered, "pw-secret".toCharArray()))
                .as("a flipped ciphertext bit fails the GCM tag")
                .isEmpty();
    }

    @Test
    void decryptRefusesAPlaintextManifest() {
        byte[] plain = archiveBlob();
        PieceManifest plainManifest = WorldArchive.manifestFor(1, plain);
        assertThatThrownBy(() -> WorldArchive.decryptArchive(
                plainManifest, plain, "any".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an encrypted archive");
    }
}
