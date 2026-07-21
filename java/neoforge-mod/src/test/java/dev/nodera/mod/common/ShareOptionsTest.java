package dev.nodera.mod.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Headless tests for the Task 30 {@link ShareOptions} value type (Minecraft-free). Covers the
 * password→encryption implication, the defaults a dedicated/player host opens with, immutable
 * copy-with helpers, and the discipline that the password never appears in {@code toString}.
 */
final class ShareOptionsTest {

    @Test
    void emptyOrBlankPasswordMeansPlaintext() {
        assertThat(new ShareOptions("", true, true, 5).encryptionEnabled()).isFalse();
        assertThat(new ShareOptions("   ", true, true, 5).encryptionEnabled()).isFalse();
        assertThat(new ShareOptions(null, true, true, 5).password()).isEmpty();
    }

    @Test
    void nonBlankPasswordTurnsOnEncryption() {
        assertThat(new ShareOptions("hunter2", false, false, 3).encryptionEnabled()).isTrue();
    }

    @Test
    void dedicatedDefaultIsPublicPlaintextSeeder() {
        ShareOptions d = ShareOptions.dedicatedDefault();
        assertThat(d.encryptionEnabled()).isFalse();
        assertThat(d.delegateRegions()).isTrue();
        assertThat(d.listedOnTracker()).isTrue();
        assertThat(d.replicationHint()).isEqualTo(5);
    }

    @Test
    void withPasswordChangesOnlyThePassword() {
        ShareOptions base = new ShareOptions("", true, false, 4);
        ShareOptions changed = base.withPassword("secret");
        assertThat(changed.password()).isEqualTo("secret");
        assertThat(changed.encryptionEnabled()).isTrue();
        assertThat(changed.delegateRegions()).isTrue();
        assertThat(changed.listedOnTracker()).isFalse();
        assertThat(changed.replicationHint()).isEqualTo(4);
        // original untouched (immutable)
        assertThat(base.encryptionEnabled()).isFalse();
    }

    @Test
    void copyHelpersFlipTheirOwnFlag() {
        ShareOptions base = ShareOptions.playerDefault();
        assertThat(base.withListedOnTracker(false).listedOnTracker()).isFalse();
        assertThat(base.withDelegateRegions(false).delegateRegions()).isFalse();
    }

    @Test
    void replicationHintMustBePositive() {
        assertThatThrownBy(() -> new ShareOptions("", true, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverLeaksThePassword() {
        assertThat(new ShareOptions("topsecret", true, true, 5).toString())
                .doesNotContain("topsecret")
                .contains("encryption=true");
    }

    @Test
    void equalityIncludesEveryField() {
        assertThat(new ShareOptions("p", true, true, 5))
                .isEqualTo(new ShareOptions("p", true, true, 5))
                .isNotEqualTo(new ShareOptions("q", true, true, 5));
    }
}
