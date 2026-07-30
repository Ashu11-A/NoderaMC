package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L-39 KDF selection: production prefers Argon2id; both sides derive identically. */
final class PasswordKeyDerivationsTest {

    @Test
    void productionSelectsArgon2WhenBouncyCastleIsPresent() {
        // The test classpath pins bcprov, so the production default MUST be Argon2id here —
        // a silent PBKDF2 downgrade on a full classpath would weaken every new world.
        assertThat(PasswordKeyDerivations.argon2Available()).isTrue();
        assertThat(PasswordKeyDerivations.production())
                .isInstanceOf(Argon2KeyDerivation.class);
    }

    @Test
    void selectedKdfDerivesDeterministically() {
        PasswordKeyDerivation kdf = PasswordKeyDerivations.production();
        Bytes salt = Bytes.fromHex("00112233445566778899aabbccddeeff");
        var first = kdf.derive("hunter2".toCharArray(), salt, 3);
        var second = kdf.derive("hunter2".toCharArray(), salt, 3);
        assertThat(first.rawBytes()).isEqualTo(second.rawBytes());
        assertThat(kdf.derive("hunter3".toCharArray(), salt, 3).rawBytes())
                .isNotEqualTo(first.rawBytes());
    }
}
