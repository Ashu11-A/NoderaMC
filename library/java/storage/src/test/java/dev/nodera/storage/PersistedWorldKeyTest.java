package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.SignatureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The world's own signing key — the material that makes one peer a world's administrator. */
final class PersistedWorldKeyTest {

    private static Bytes worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes());
    }

    @Test
    @DisplayName("a generated key signs bytes its own public key verifies")
    void signAndVerify() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        Bytes data = Bytes.unsafeWrap("administer this".getBytes());

        Bytes signature = key.sign(data);

        assertThat(new SignatureService().verify(key.x509Public(), data, signature)).isTrue();
    }

    @Test
    @DisplayName("a key restored from disk still signs the same way")
    void roundTripKeepsTheKeyUsable() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        CanonicalWriter w = new CanonicalWriter();
        key.encode(w);

        PersistedWorldKey back = PersistedWorldKey.decode(new CanonicalReader(w.toBytes()));

        assertThat(back).isEqualTo(key);
        Bytes data = Bytes.unsafeWrap("after a restart".getBytes());
        assertThat(new SignatureService().verify(back.x509Public(), data, back.sign(data))).isTrue();
    }

    @Test
    @DisplayName("two worlds never share a key")
    void everyGenerationIsDistinct() {
        assertThat(PersistedWorldKey.generate(worldId("a")).x509Public())
                .isNotEqualTo(PersistedWorldKey.generate(worldId("a")).x509Public());
    }

    @Test
    @DisplayName("the private key never appears in a log line")
    void toStringRedactsTheSecret() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));

        // This type is written to disk and referenced in warnings; a toString that rendered the key
        // would put it in every log that mentions the world.
        assertThat(key.toString()).contains("redacted");
        assertThat(key.toString()).doesNotContain(key.pkcs8Private().toHex());
    }

    @Test
    @DisplayName("empty key material is refused")
    void emptyMaterialIsRejected() {
        assertThatThrownBy(() -> new PersistedWorldKey(worldId("w"), Bytes.empty(),
                Bytes.unsafeWrap(new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decoding something that is not a world key is an error, not an empty key")
    void wrongTagIsRefused() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(1).writeU16(1);

        assertThatThrownBy(() -> PersistedWorldKey.decode(new CanonicalReader(w.toBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORLD_KEY_SECRET");
    }
}
