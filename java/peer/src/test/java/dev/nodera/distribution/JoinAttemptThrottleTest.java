package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L-39 throttle: online password grinding hits exponential lockouts; success clears. */
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
