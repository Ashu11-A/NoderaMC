package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Argon2id KDF (Task 23; L-39). Memory-hard, BouncyCastle-backed. Same properties as the PBKDF2
 * test: same inputs ⇒ same key; different salt ⇒ different key; cost bounds enforced.
 *
 * <p>Thread-context: single test thread.
 */
final class Argon2KeyDerivationTest {

    private static final Bytes SALT = Bytes.fromHex("0011223344556677889900aabbccddee");

    private static Argon2KeyDerivation fast() {
        // Minimum-cost instance keeps the test fast while still exercising the real Argon2id path.
        return new Argon2KeyDerivation(Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MIN_PARALLELISM);
    }

    @Test
    void kdfIdIsArgon2id() {
        assertThat(fast().kdfId()).isEqualTo("argon2id");
    }

    @Test
    void sameInputsProduceTheSameKey() {
        Argon2KeyDerivation kdf = fast();
        char[] pw = "hunter2".toCharArray();
        ContentKey a = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
        ContentKey b = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
        assertThat(a.rawBytes()).isEqualTo(b.rawBytes());
        assertThat(a.rawBytes().length()).isEqualTo(ContentKey.KEY_BYTES);
    }

    @Test
    void differentSaltProducesDifferentKey() {
        Argon2KeyDerivation kdf = fast();
        char[] pw = "hunter2".toCharArray();
        ContentKey a = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
        ContentKey b = kdf.derive(pw, Bytes.fromHex("ffeeddccbbaa00998877665544332211"),
                Argon2KeyDerivation.MIN_ITERATIONS);
        assertThat(a.rawBytes()).isNotEqualTo(b.rawBytes());
    }

    @Test
    void argon2AndPbkdf2KeysDifferForTheSamePassword() {
        // Different KDFs must not agree (else one could substitute for the other silently).
        char[] pw = "hunter2".toCharArray();
        ContentKey argon = fast().derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
        ContentKey pbkdf = new dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation()
                .derive(pw, SALT, dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation.MIN_ITERATIONS);
        assertThat(argon.rawBytes()).isNotEqualTo(pbkdf.rawBytes());
    }

    @Test
    void unicodePasswordMatchesItsUtf8Encoding() {
        String password = "päss🔐";
        ContentKey actual = fast().derive(
                password.toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);

        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(Argon2KeyDerivation.MIN_MEMORY_KIB)
                .withIterations(Argon2KeyDerivation.MIN_ITERATIONS)
                .withParallelism(Argon2KeyDerivation.MIN_PARALLELISM)
                .withSalt(SALT.toArray())
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] expected = new byte[ContentKey.KEY_BYTES];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), expected);

        assertThat(actual.rawBytes().toArray()).isEqualTo(expected);
    }

    @Test
    void canonicallyEquivalentUnicodePasswordsRemainDistinct() {
        Argon2KeyDerivation kdf = fast();
        ContentKey composed = kdf.derive(
                "café".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);
        ContentKey decomposed = kdf.derive(
                "café".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);

        assertThat(composed.rawBytes()).isNotEqualTo(decomposed.rawBytes());
    }

    @Test
    void malformedUtf16PasswordsAreRejected() {
        assertThatThrownBy(() -> fast().derive(
                new char[]{'a', '\ud800'}, SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed UTF-16");
        assertThatThrownBy(() -> fast().derive(
                new char[]{'a', '\udc00'}, SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed UTF-16");
    }

    @Test
    void rejectsCostsOutsideMinimumAndMaximumBounds() {
        Argon2KeyDerivation maximum = new Argon2KeyDerivation(
                Argon2KeyDerivation.MAX_MEMORY_KIB, Argon2KeyDerivation.MAX_PARALLELISM);
        assertThat(maximum.memoryKib()).isEqualTo(Argon2KeyDerivation.MAX_MEMORY_KIB);
        assertThat(maximum.parallelism()).isEqualTo(Argon2KeyDerivation.MAX_PARALLELISM);

        assertThatThrownBy(() -> new Argon2KeyDerivation(
                Argon2KeyDerivation.MIN_MEMORY_KIB - 1, Argon2KeyDerivation.MIN_PARALLELISM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2KeyDerivation(
                Argon2KeyDerivation.MAX_MEMORY_KIB + 1, Argon2KeyDerivation.MIN_PARALLELISM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2KeyDerivation(
                Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MIN_PARALLELISM - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2KeyDerivation(
                Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MAX_PARALLELISM + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fast().derive(
                "pw".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS - 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fast().derive(
                "pw".toCharArray(), SALT, Argon2KeyDerivation.MAX_ITERATIONS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyAndOversizedPasswords() {
        assertThatThrownBy(() -> fast().derive(
                new char[0], SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fast().derive(
                new char[NoderaConstants.PASSWORD_KDF_MAX_PASSWORD_CHARS + 1], SALT,
                Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSaltOutsideBounds() {
        assertThatThrownBy(() -> fast().derive(
                "pw".toCharArray(), Bytes.fromHex("00".repeat(15)),
                Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fast().derive(
                "pw".toCharArray(), Bytes.fromHex("00".repeat(65)),
                Argon2KeyDerivation.MIN_ITERATIONS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsFromValidatedWorldKeyMetadata() {
        WorldKeyMaterial material = new WorldKeyMaterial(
                PasswordKeyDerivation.ARGON2ID,
                SALT,
                Argon2KeyDerivation.MIN_MEMORY_KIB,
                Argon2KeyDerivation.MIN_ITERATIONS,
                Argon2KeyDerivation.MAX_PARALLELISM);

        Argon2KeyDerivation kdf = Argon2KeyDerivation.from(material);

        assertThat(kdf.memoryKib()).isEqualTo(Argon2KeyDerivation.MIN_MEMORY_KIB);
        assertThat(kdf.parallelism()).isEqualTo(Argon2KeyDerivation.MAX_PARALLELISM);
        assertThatThrownBy(() -> Argon2KeyDerivation.from(new WorldKeyMaterial(
                PasswordKeyDerivation.PBKDF2,
                SALT,
                Argon2KeyDerivation.MIN_MEMORY_KIB,
                Argon2KeyDerivation.MIN_ITERATIONS,
                Argon2KeyDerivation.MIN_PARALLELISM)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported kdf");
        assertThatThrownBy(() -> new WorldKeyMaterial(
                PasswordKeyDerivation.ARGON2ID,
                SALT,
                (long) Argon2KeyDerivation.MAX_MEMORY_KIB + 1,
                Argon2KeyDerivation.MIN_ITERATIONS,
                Argon2KeyDerivation.MIN_PARALLELISM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryKib");
        assertThatThrownBy(() -> Argon2KeyDerivation.from(new WorldKeyMaterial(
                PasswordKeyDerivation.ARGON2ID,
                SALT,
                Argon2KeyDerivation.MIN_MEMORY_KIB,
                Argon2KeyDerivation.MAX_ITERATIONS + 1,
                Argon2KeyDerivation.MIN_PARALLELISM)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
