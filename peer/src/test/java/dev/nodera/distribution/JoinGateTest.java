package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who is let into a world, and how fast they may keep asking.
 *
 * <p>Two sibling classes over one subject: the password verdict itself, and the throttle that makes
 * getting it wrong expensive. They were two files with the same four-line import block, and the
 * gate is only a gate if both halves hold — a correct verdict an attacker may retry a million times
 * a second is not one.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class JoinGateTest {

    /**
     * The live-join password gate (L-52): the world password stops being a property of the archive
     * only and starts deciding who may connect.
     *
     * <p>Every assertion here is the headless half of the exit test — the mod carries these two values
     * over a configuration-phase payload and disconnects on a false verdict.
     */
    @Nested
    final class JoinPasswordGateTest {
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

    /** L-39 throttle: online password grinding hits exponential lockouts; success clears. */
    @Nested
    final class JoinAttemptThrottleTest {
        private static final Bytes WORLD = Bytes.fromHex("beef");
        private static final Bytes PEER = Bytes.fromHex("aa01");
        private static final Bytes OTHER_PEER = Bytes.fromHex("aa02");

        @Test
        void freeAttemptsThenExponentialLockout() {
            JoinAttemptThrottle throttle = new JoinAttemptThrottle();
            long t = 1_000_000L;

            for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS; i++) {
                assertThat(throttle.mayAttempt(WORLD, PEER, t)).isTrue();
                assertThat(throttle.recordFailure(WORLD, PEER, t)).isLessThanOrEqualTo(t);
            }
            // Failure 4: first lockout window.
            long lock1 = throttle.recordFailure(WORLD, PEER, t);
            assertThat(lock1).isEqualTo(t + JoinAttemptThrottle.BASE_LOCKOUT_MILLIS);
            assertThat(throttle.mayAttempt(WORLD, PEER, t + 1)).isFalse();
            assertThat(throttle.mayAttempt(WORLD, PEER, lock1)).isTrue();

            // Failure 5: the window doubles.
            long lock2 = throttle.recordFailure(WORLD, PEER, lock1);
            assertThat(lock2).isEqualTo(lock1 + 2 * JoinAttemptThrottle.BASE_LOCKOUT_MILLIS);
        }

        @Test
        void lockoutIsBoundedNeverPermanent() {
            JoinAttemptThrottle throttle = new JoinAttemptThrottle();
            long t = 5_000_000L;
            long until = 0;
            for (int i = 0; i < 60; i++) {
                until = throttle.recordFailure(WORLD, PEER, t);
            }
            assertThat(until - t).isEqualTo(JoinAttemptThrottle.MAX_LOCKOUT_MILLIS);
        }

        @Test
        void successClearsAndPeersAreIndependent() {
            JoinAttemptThrottle throttle = new JoinAttemptThrottle();
            long t = 9_000_000L;
            for (int i = 0; i < 10; i++) {
                throttle.recordFailure(WORLD, PEER, t);
            }
            assertThat(throttle.mayAttempt(WORLD, PEER, t + 1)).isFalse();
            assertThat(throttle.mayAttempt(WORLD, OTHER_PEER, t + 1))
                    .as("another peer's attempts are unaffected").isTrue();

            throttle.recordSuccess(WORLD, PEER);
            assertThat(throttle.mayAttempt(WORLD, PEER, t + 1))
                    .as("a successful join clears the history").isTrue();
        }
    }
}
