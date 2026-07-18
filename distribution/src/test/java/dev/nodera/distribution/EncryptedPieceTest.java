package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.crypto.symmetric.ContentCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Canonical and structural checks for Task 23's ciphertext wrapper. */
final class EncryptedPieceTest {

    private static final Bytes NONCE = Bytes.fromHex("00112233445566778899aabb");
    private static final Bytes CIPHERTEXT = Bytes.fromHex("00".repeat(ContentCipher.TAG_BITS / Byte.SIZE));

    @Test
    void roundTripsCanonicallyWithAppendOnlyTypeTag() {
        EncryptedPiece original = new EncryptedPiece(NONCE, CIPHERTEXT);
        CanonicalWriter writer = new CanonicalWriter();
        original.encode(writer);

        CanonicalReader peek = new CanonicalReader(writer.toByteArray());
        assertThat(peek.readU16()).isEqualTo(TypeTags.ENCRYPTED_PIECE);
        assertThat(EncryptedPiece.decode(new CanonicalReader(writer.toByteArray())))
                .isEqualTo(original);
    }

    @Test
    void ciphertextHashDoesNotRequireOrCoverThePublicNonce() {
        EncryptedPiece first = new EncryptedPiece(NONCE, CIPHERTEXT);
        EncryptedPiece second = new EncryptedPiece(
                Bytes.fromHex("ffeeddccbbaa998877665544"), CIPHERTEXT);

        assertThat(first.ciphertextHash()).isEqualTo(second.ciphertextHash());
    }

    @Test
    void rejectsInvalidNonceAndTruncatedTag() {
        assertThatThrownBy(() -> new EncryptedPiece(Bytes.fromHex("00"), CIPHERTEXT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EncryptedPiece(NONCE, Bytes.fromHex("00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
