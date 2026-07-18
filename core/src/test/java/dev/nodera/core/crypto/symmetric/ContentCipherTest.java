package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-GCM content cipher (Task 23; L-39). The properties that must hold: round-trip, tamper
 * rejection (auth tag), wrong-key rejection (no distinct failure mode), deterministic nonce
 * uniqueness across pieces and across manifests.
 *
 * <p>Thread-context: single test thread.
 */
final class ContentCipherTest {

    private static StateRoot root(int seed) {
        byte[] h = new byte[32];
        for (int i = 0; i < h.length; i++) {
            h[i] = (byte) (seed * 31 + i);
        }
        return StateRoot.of(Bytes.unsafeWrap(h));
    }

    private static ContentKey key(byte b) {
        byte[] raw = new byte[ContentKey.KEY_BYTES];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = b;
        }
        return ContentKey.of(raw);
    }

    @Test
    void encryptDecryptRoundTrips() {
        ContentKey k = key((byte) 1);
        Bytes plaintext = Bytes.fromHex("deadbeefcafebabe1122334455667788");
        Bytes nonce = ContentCipher.nonceFor(root(7), new SnapshotVersion(3L), 0);

        Bytes ciphertext = ContentCipher.encrypt(k, nonce, plaintext);
        assertThat(ciphertext.length()).isEqualTo(plaintext.length() + ContentCipher.TAG_BITS / 8);

        Bytes decrypted = ContentCipher.decrypt(k, nonce, ciphertext).orElseThrow();
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void samePlaintextProducesSameCiphertextConvergently() {
        ContentKey k = key((byte) 1);
        Bytes plaintext = Bytes.fromHex("aabbccdd");
        Bytes nonce = ContentCipher.nonceFor(root(7), new SnapshotVersion(3L), 0);

        // Deterministic nonce ⇒ same plaintext ⇒ same ciphertext ⇒ same ContentId (dedup survives).
        Bytes a = ContentCipher.encrypt(k, nonce, plaintext);
        Bytes b = ContentCipher.encrypt(k, nonce, plaintext);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        ContentKey k = key((byte) 1);
        Bytes plaintext = Bytes.fromHex("00112233445566778899aabbccddeeff");
        Bytes nonce = ContentCipher.nonceFor(root(7), new SnapshotVersion(3L), 1);

        byte[] raw = ContentCipher.encrypt(k, nonce, plaintext).toArray();
        raw[raw.length - 1] ^= 0x01;   // flip a bit in the auth tag
        Bytes tampered = Bytes.unsafeWrap(raw);

        assertThat(ContentCipher.decrypt(k, nonce, tampered)).isEmpty();
    }

    @Test
    void wrongKeyIsRejectedIndistinguishably() {
        Bytes plaintext = Bytes.fromHex("00112233445566778899aabbccddeeff");
        Bytes nonce = ContentCipher.nonceFor(root(7), new SnapshotVersion(3L), 1);
        Bytes ciphertext = ContentCipher.encrypt(key((byte) 1), nonce, plaintext);

        // A wrong key yields empty (auth-tag mismatch), the same failure mode as tamper — so a
        // wrong password is indistinguishable from corruption to an attacker watching decrypts.
        assertThat(ContentCipher.decrypt(key((byte) 2), nonce, ciphertext)).isEmpty();
    }

    @Test
    void nonceIsUniqueAcrossPiecesAndDeterministic() {
        StateRoot r = root(42);
        SnapshotVersion v = new SnapshotVersion(5L);
        Set<Bytes> nonces = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertThat(nonces.add(ContentCipher.nonceFor(r, v, i))).isTrue();
        }
        // Same triple ⇒ same nonce (reproducible across peers).
        assertThat(ContentCipher.nonceFor(r, v, 7)).isEqualTo(ContentCipher.nonceFor(r, v, 7));
    }

    @Test
    void nonceDiffersAcrossRegionsVersionsAndPieces() {
        StateRoot r1 = root(1);
        StateRoot r2 = root(2);
        SnapshotVersion v1 = new SnapshotVersion(1L);
        SnapshotVersion v2 = new SnapshotVersion(2L);
        // Every distinct (region, version, index) triple yields a distinct nonce.
        assertThat(ContentCipher.nonceFor(r1, v1, 0)).isNotEqualTo(ContentCipher.nonceFor(r2, v1, 0));
        assertThat(ContentCipher.nonceFor(r1, v1, 0)).isNotEqualTo(ContentCipher.nonceFor(r1, v2, 0));
        assertThat(ContentCipher.nonceFor(r1, v1, 0)).isNotEqualTo(ContentCipher.nonceFor(r1, v1, 1));
    }

    @Test
    void noncePropertyHoldsAcrossManifestsUnderOneKey() {
        Set<Bytes> nonces = new HashSet<>();
        for (int rootSeed = 0; rootSeed < 32; rootSeed++) {
            for (long version = 0; version < 8; version++) {
                for (int piece = 0; piece < 16; piece++) {
                    Bytes nonce = ContentCipher.nonceFor(
                            root(rootSeed), new SnapshotVersion(version), piece);
                    assertThat(nonce.length()).isEqualTo(ContentCipher.NONCE_BYTES);
                    assertThat(nonces.add(nonce)).isTrue();
                }
            }
        }
        assertThat(nonces).hasSize(32 * 8 * 16);
    }

    @Test
    void rejectsBadInputs() {
        ContentKey k = key((byte) 1);
        assertThatThrownBy(() -> ContentCipher.nonceFor(null, new SnapshotVersion(0L), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContentCipher.encrypt(k, Bytes.fromHex("00"), Bytes.fromHex("ab")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contentKeyRejectsWrongLength() {
        assertThatThrownBy(() -> ContentKey.of(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
