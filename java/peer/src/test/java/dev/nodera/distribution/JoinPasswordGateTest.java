package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live-join password gate (L-52): the world password stops being a property of the archive
 * only and starts deciding who may connect.
 *
 * <p>Every assertion here is the headless half of the exit test — the mod carries these two values
 * over a configuration-phase payload and disconnects on a false verdict.
 */
class JoinPasswordGateTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Bytes worldId = Bytes.unsafeWrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

    @Test
    void theRightPasswordPassesAndAWrongOneDoesNot() {
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);
        Bytes nonce = JoinPasswordGate.newNonce(RANDOM);
        Bytes hostKey = JoinPasswordGate.gateKey("correct horse".toCharArray(), material);

        Bytes right = JoinPasswordGate.proof(
                JoinPasswordGate.gateKey("correct horse".toCharArray(), material), nonce, worldId);
        Bytes wrong = JoinPasswordGate.proof(
                JoinPasswordGate.gateKey("correct horst".toCharArray(), material), nonce, worldId);

        assertThat(JoinPasswordGate.verify(hostKey, nonce, worldId, right)).isTrue();
        assertThat(JoinPasswordGate.verify(hostKey, nonce, worldId, wrong)).isFalse();
    }

    @Test
    void aProofIsBoundToItsOwnNonceSoARecordedAnswerIsUselessOnTheNextConnection() {
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);
        Bytes key = JoinPasswordGate.gateKey("pw".toCharArray(), material);
        Bytes first = JoinPasswordGate.newNonce(RANDOM);
        Bytes second = JoinPasswordGate.newNonce(RANDOM);

        Bytes answerToFirst = JoinPasswordGate.proof(key, first, worldId);

        assertThat(JoinPasswordGate.verify(key, first, worldId, answerToFirst)).isTrue();
        assertThat(JoinPasswordGate.verify(key, second, worldId, answerToFirst))
                .as("a replayed proof from an earlier connection must not open the next one")
                .isFalse();
    }

    @Test
    void aProofIsBoundToItsWorldSoAHostCannotRelayItsChallengeIntoAnotherWorld() {
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);
        Bytes key = JoinPasswordGate.gateKey("shared".toCharArray(), material);
        Bytes nonce = JoinPasswordGate.newNonce(RANDOM);
        Bytes otherWorld = Bytes.unsafeWrap(new byte[]{9, 9, 9, 9});

        Bytes forThisWorld = JoinPasswordGate.proof(key, nonce, worldId);

        assertThat(JoinPasswordGate.verify(key, nonce, otherWorld, forThisWorld))
                .as("world A's host must not be able to relay its challenge into world B's gate")
                .isFalse();
    }

    @Test
    void aMissingOrMalformedAnswerIsRefusedRatherThanThrowing() {
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);
        Bytes key = JoinPasswordGate.gateKey("pw".toCharArray(), material);
        Bytes nonce = JoinPasswordGate.newNonce(RANDOM);

        // A joiner controls this value: no answer at all, an empty one, and a truncated one are all
        // ordinary refusals — never an exception the caller has to catch to stay closed.
        assertThat(JoinPasswordGate.verify(key, nonce, worldId, null)).isFalse();
        assertThat(JoinPasswordGate.verify(key, nonce, worldId, Bytes.empty())).isFalse();
        assertThat(JoinPasswordGate.verify(key, nonce, worldId,
                Bytes.unsafeWrap(new byte[]{0, 0, 0, 0}))).isFalse();
    }

    @Test
    void freshKeyMaterialNeverRepeatsItsSaltSoTwoHostSessionsDeriveDifferentKeys() {
        WorldKeyMaterial first = JoinPasswordGate.newKeyMaterial(RANDOM);
        WorldKeyMaterial second = JoinPasswordGate.newKeyMaterial(RANDOM);

        assertThat(first.salt()).isNotEqualTo(second.salt());
        assertThat(JoinPasswordGate.gateKey("pw".toCharArray(), first))
                .isNotEqualTo(JoinPasswordGate.gateKey("pw".toCharArray(), second));
    }

    @Test
    void theGateKeyDerivesIdenticallyFromMaterialAloneWhicheverSideDerivesIt() {
        // The whole protocol rests on this: the host derives once at host start, every joiner
        // derives from the same public material, and nothing but the password is shared.
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);

        assertThat(JoinPasswordGate.gateKey("hunter2".toCharArray(), material))
                .isEqualTo(JoinPasswordGate.gateKey("hunter2".toCharArray(), material));
    }

    @Test
    void anEmptyPasswordIsRejectedRatherThanDerivingAGateEveryoneCouldPass() {
        WorldKeyMaterial material = JoinPasswordGate.newKeyMaterial(RANDOM);

        assertThatThrownBy(() -> JoinPasswordGate.gateKey(new char[0], material))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPbkdf2WorldIsGatedByPbkdf2RegardlessOfWhatThisBuildPrefers() {
        // KDF selection follows the material, never the local preference — otherwise an Argon2
        // host and a PBKDF2 joiner would derive different keys from the SAME correct password and
        // the gate would refuse an honest player.
        WorldKeyMaterial pbkdf2 = WorldKeyMaterial.pbkdf2(
                JoinPasswordGate.newKeyMaterial(RANDOM).salt(),
                dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation.DEFAULT_ITERATIONS);
        Bytes nonce = JoinPasswordGate.newNonce(RANDOM);
        Bytes key = JoinPasswordGate.gateKey("pw".toCharArray(), pbkdf2);

        assertThat(JoinPasswordGate.verify(key, nonce, worldId,
                JoinPasswordGate.proof(JoinPasswordGate.gateKey("pw".toCharArray(), pbkdf2),
                        nonce, worldId))).isTrue();
    }
}
