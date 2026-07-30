package dev.nodera.core.crypto.symmetric;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PBKDF2 password KDF (Task 23; L-39). Same inputs ⇒ same key; different salt ⇒ different key; the
 * cost floor is enforced.
 *
 * <p>Thread-context: single test thread.
 */
final class Pbkdf2KeyDerivationTest {

    private static final Bytes SALT = Bytes.fromHex("0011223344556677889900aabbccddee");

    @Test
    void sameInputsProduceTheSameKey() {
        Pbkdf2KeyDerivation kdf = Pbkdf2KeyDerivation.defaultInstance();
        char[] pw = "correct horse battery staple".toCharArray();
        ContentKey a = kdf.derive(pw, SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        ContentKey b = kdf.derive(pw, SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        assertThat(a.rawBytes()).isEqualTo(b.rawBytes());
    }

    @Test
    void differentSaltProducesDifferentKey() {
        Pbkdf2KeyDerivation kdf = Pbkdf2KeyDerivation.defaultInstance();
        char[] pw = "password".toCharArray();
        ContentKey a = kdf.derive(pw, SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        ContentKey b = kdf.derive(pw, Bytes.fromHex("ffeeddccbbaa00998877665544332211"),
                Pbkdf2KeyDerivation.MIN_ITERATIONS);
        assertThat(a.rawBytes()).isNotEqualTo(b.rawBytes());
    }

    @Test
    void differentPasswordProducesDifferentKey() {
        Pbkdf2KeyDerivation kdf = Pbkdf2KeyDerivation.defaultInstance();
        ContentKey a = kdf.derive("password1".toCharArray(), SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        ContentKey b = kdf.derive("password2".toCharArray(), SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        assertThat(a.rawBytes()).isNotEqualTo(b.rawBytes());
    }

    @Test
    void enforcesTheDocumentedCostAndInputBounds() {
        Pbkdf2KeyDerivation kdf = Pbkdf2KeyDerivation.defaultInstance();
        assertThatThrownBy(() -> kdf.derive("pw".toCharArray(), SALT, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kdf.derive("pw".toCharArray(), SALT,
                Pbkdf2KeyDerivation.MAX_ITERATIONS + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kdf.derive(new char[0], SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kdf.derive("pw".toCharArray(), Bytes.fromHex("0011223344556677"),
                Pbkdf2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kdf.derive("pw".toCharArray(), new Bytes(new byte[65]),
                Pbkdf2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivedKeyIsAESLengthAndUsable() {
        ContentKey key = Pbkdf2KeyDerivation.defaultInstance()
                .derive("pw".toCharArray(), SALT, Pbkdf2KeyDerivation.MIN_ITERATIONS);
        assertThat(key.rawBytes().length()).isEqualTo(ContentKey.KEY_BYTES);
        assertThat(key.toString()).contains("redacted");
    }
}
