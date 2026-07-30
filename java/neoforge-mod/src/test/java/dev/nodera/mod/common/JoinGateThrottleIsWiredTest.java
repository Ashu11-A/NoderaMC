package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.JoinAttemptThrottle;
import dev.nodera.distribution.JoinPasswordGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JoinAttemptThrottle} had no production caller: it was written for L-39, tested on its own,
 * and reached by nothing. An unreached throttle is an unthrottled join path, and the live-join gate
 * is the one online guessing oracle a world password has — a challenge is single-use per
 * <i>connection</i>, so a client that simply reconnects got an unlimited supply of fresh nonces.
 *
 * <p>These assertions are about the <b>call site</b>, not the policy ({@code JoinAttemptThrottleTest}
 * already covers the policy). Every attempt below arrives on a new connection object, which is what
 * a reconnect looks like to the gate, so a gate that forgot to count against the joiner's address
 * would pass the policy tests and fail these.
 */
class JoinGateThrottleIsWiredTest {

    private static final Bytes WORLD = Bytes.unsafeWrap(new byte[]{9, 9, 9, 9});

    /** Not a valid MAC, and cheap: a wrong answer costs the host an HMAC, never a KDF. */
    private static final Bytes WRONG = Bytes.unsafeWrap(new byte[32]);

    private final HostJoinGate gate = HostJoinGate.get();

    @AfterEach
    void disarm() {
        gate.disarm();
    }

    private static Bytes joiner(String host, int port) {
        return JoinerIdentity.of(new InetSocketAddress(host, port));
    }

    /** One wrong attempt, on its own fresh connection, exactly as a reconnecting client makes it. */
    private HostJoinGate.Verdict attempt(Bytes joiner, Bytes answer, long nowMillis) {
        Object connection = new Object();
        gate.issue(connection, nowMillis);
        return gate.verify(connection, joiner, answer, nowMillis);
    }

    @Test
    void reconnectingDoesNotResetTheCounterSoTheGateStopsAnsweringGuesses() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes grinder = joiner("203.0.113.7", 40_000);

        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS + 1; i++) {
            assertThat(attempt(grinder, WRONG, 0L))
                    .as("attempt %s is judged, not throttled", i)
                    .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
        }

        assertThat(gate.isThrottled(grinder, 1L)).isTrue();
        assertThat(attempt(grinder, WRONG, 1L)).isEqualTo(HostJoinGate.Verdict.THROTTLED);
    }

    @Test
    void theLockoutIsBoundedRatherThanPermanent() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes grinder = joiner("203.0.113.7", 40_000);
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS + 1; i++) {
            attempt(grinder, WRONG, 0L);
        }

        assertThat(gate.isThrottled(grinder, JoinAttemptThrottle.BASE_LOCKOUT_MILLIS - 1)).isTrue();
        assertThat(gate.isThrottled(grinder, JoinAttemptThrottle.BASE_LOCKOUT_MILLIS)).isFalse();
    }

    @Test
    void aThrottledAttemptCannotParkAValidNonceThroughTheLockout() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes grinder = joiner("203.0.113.7", 40_000);
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS + 1; i++) {
            attempt(grinder, WRONG, 0L);
        }

        // A challenge issued during the lockout is consumed by the refusal, so it is not still
        // outstanding and spendable the moment the window closes.
        Object parked = new Object();
        gate.issue(parked, 1L);
        assertThat(gate.verify(parked, grinder, WRONG, 1L))
                .isEqualTo(HostJoinGate.Verdict.THROTTLED);
        assertThat(gate.verify(parked, grinder, WRONG,
                JoinAttemptThrottle.BASE_LOCKOUT_MILLIS)).isEqualTo(
                HostJoinGate.Verdict.NO_CHALLENGE);
    }

    @Test
    void oneAddressLockingItselfOutDoesNotLockOutAnybodyElse() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes grinder = joiner("203.0.113.7", 40_000);
        Bytes bystander = joiner("203.0.113.8", 40_000);
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS + 1; i++) {
            attempt(grinder, WRONG, 0L);
        }

        assertThat(gate.isThrottled(bystander, 1L)).isFalse();
        assertThat(attempt(bystander, WRONG, 1L))
                .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
    }

    @Test
    void aSuccessfulJoinClearsWhatWentBeforeIt() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes player = joiner("203.0.113.9", 40_000);
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS; i++) {
            attempt(player, WRONG, 0L);
        }

        Object connection = new Object();
        HostJoinGate.Challenge challenge = gate.issue(connection, 0L).orElseThrow();
        Bytes correct = JoinPasswordGate.proof(
                JoinPasswordGate.gateKey("pw".toCharArray(), challenge.material()),
                challenge.nonce(), Bytes.fromHex(challenge.worldIdHex()));
        assertThat(gate.verify(connection, player, correct, 0L))
                .isEqualTo(HostJoinGate.Verdict.PASS);

        // Three more typos are three more typos, not the second half of a lockout.
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS; i++) {
            assertThat(attempt(player, WRONG, 0L))
                    .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
        }
        assertThat(gate.isThrottled(player, 0L)).isFalse();
    }

    @Test
    void aNewHostSessionStartsEverybodyBackAtZero() {
        gate.arm(WORLD, "pw".toCharArray());
        Bytes grinder = joiner("203.0.113.7", 40_000);
        for (int i = 0; i < JoinAttemptThrottle.FREE_ATTEMPTS + 1; i++) {
            attempt(grinder, WRONG, 0L);
        }
        assertThat(gate.isThrottled(grinder, 1L)).isTrue();

        // The host re-shared: a new gate key is a new question, so an old lockout is not an answer
        // to it.
        gate.arm(WORLD, "pw".toCharArray());

        assertThat(gate.isThrottled(grinder, 1L)).isFalse();
    }

    @Test
    void anUnarmedWorldThrottlesNobody() {
        assertThat(gate.isThrottled(joiner("203.0.113.7", 40_000), 0L)).isFalse();
    }
}
