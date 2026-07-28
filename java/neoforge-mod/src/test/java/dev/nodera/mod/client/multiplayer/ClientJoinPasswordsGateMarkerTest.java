package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.WorldKeyMaterial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marker that tells a refused join apart from a lost host.
 *
 * <p>The bug this pins, observed live: a password-gated world refuses a joiner during the
 * configuration phase, which produces the same {@code DisconnectedScreen} a host crash produces.
 * Continuity ran on {@code ScreenEvent.Opening} — before the password prompt attaches on
 * {@code Init.Post} — replaced the screen with the recovery flow, and held the player on
 * "Migrating world…" for the whole fetch timeout before failing with "no seeder online?". The
 * seeder was online and seeding forty pieces. The only thing missing was a password nobody had been
 * asked for, because the screen that would have asked had already been thrown away.
 *
 * <p>So the marker has to answer "did this connection end at the gate?" — not "did it end at the
 * gate without even trying?", which is false for the commonest case of all, a typo.
 */
final class ClientJoinPasswordsGateMarkerTest {

    /** A 32-hex-char world id: the answer path hex-decodes it. */
    private static final String WORLD = "abcdef0123456789abcdef0123456789";

    private static WorldKeyMaterial material() {
        // pbkdf2 rather than argon2id: this test is about the marker, and a memory-hard KDF would
        // spend a second of a unit test proving nothing it asserts. 100_000 is the floor
        // `Pbkdf2KeyDerivation` enforces — it refuses a weaker one rather than obliging a caller
        // who wants a fast password hash, which is the correct answer and not a test inconvenience.
        return WorldKeyMaterial.pbkdf2(Bytes.unsafeWrap(new byte[16]), 100_000);
    }

    private static Bytes nonce() {
        return Bytes.unsafeWrap(new byte[16]);
    }

    @BeforeEach
    void reset() {
        ClientJoinPasswords.clear();
    }

    @Test
    void aChallengeNobodyCanAnswerMarksTheGate() {
        assertThat(ClientJoinPasswords.pendingGateWorldId()).isNull();

        assertThat(ClientJoinPasswords.answer(WORLD, material(), nonce())).isEmpty();

        assertThat(ClientJoinPasswords.pendingGateWorldId()).isEqualTo(WORLD);
        assertThat(ClientJoinPasswords.unansweredWorldId()).isEqualTo(WORLD);
    }

    @Test
    void aWrongPasswordStillMarksTheGate() {
        ClientJoinPasswords.remember(WORLD, "not-the-password");

        // A proof IS produced, so nothing was "unanswered" — and the host refuses it anyway. This
        // is the case the narrower marker missed, and it is the one players hit most.
        assertThat(ClientJoinPasswords.answer(WORLD, material(), nonce())).isPresent();

        assertThat(ClientJoinPasswords.unansweredWorldId()).isNull();
        assertThat(ClientJoinPasswords.pendingGateWorldId())
                .as("a refused join must be distinguishable from a dead host")
                .isEqualTo(WORLD);
    }

    @Test
    void gettingIntoTheWorldClearsTheMarker() {
        ClientJoinPasswords.answer(WORLD, material(), nonce());
        assertThat(ClientJoinPasswords.pendingGateWorldId()).isNotNull();

        ClientJoinPasswords.passedGate();

        // Without this, the marker latched for the whole session and a genuine host loss an hour
        // later would have been read as a password refusal — recovery would never have run.
        assertThat(ClientJoinPasswords.pendingGateWorldId()).isNull();
        assertThat(ClientJoinPasswords.unansweredWorldId()).isNull();
    }
}
