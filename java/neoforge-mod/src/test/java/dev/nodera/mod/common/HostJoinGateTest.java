package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.JoinPasswordGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host half of the live-join password gate (L-52), Minecraft-free: connections are opaque keys,
 * so the whole admission rule is asserted here rather than only in a live session.
 */
class HostJoinGateTest {

    private static final Bytes WORLD = Bytes.unsafeWrap(new byte[]{7, 7, 7, 7});

    private final HostJoinGate gate = HostJoinGate.get();

    @AfterEach
    void disarm() {
        gate.disarm();
    }

    /** The joiner's half: derive the key from the password and answer the issued challenge. */
    private static Bytes answer(HostJoinGate.Challenge challenge, String password) {
        return JoinPasswordGate.proof(
                JoinPasswordGate.gateKey(password.toCharArray(), challenge.material()),
                challenge.nonce(), Bytes.fromHex(challenge.worldIdHex()));
    }

    @Test
    void aWorldWithNoPasswordGatesNothing() {
        gate.arm(WORLD, new char[0]);

        assertThat(gate.isArmed()).isFalse();
        assertThat(gate.issue(new Object(), 0L)).isEmpty();
    }

    @Test
    void theRightPasswordPassesAndAWrongOneIsRefused() {
        gate.arm(WORLD, "swordfish".toCharArray());
        Object right = new Object();
        Object wrong = new Object();

        HostJoinGate.Challenge toRight = gate.issue(right, 1_000L).orElseThrow();
        HostJoinGate.Challenge toWrong = gate.issue(wrong, 1_000L).orElseThrow();

        assertThat(gate.verify(right, null, answer(toRight, "swordfish"), 1_100L))
                .isEqualTo(HostJoinGate.Verdict.PASS);
        assertThat(gate.verify(wrong, null, answer(toWrong, "swordfisl"), 1_100L))
                .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
    }

    @Test
    void aJoinerWithNoPasswordAtAllIsRefusedRatherThanLetIn() {
        gate.arm(WORLD, "pw".toCharArray());
        Object connection = new Object();
        gate.issue(connection, 0L);

        // The client answers "I have no password" with an empty proof; that is a refusal.
        assertThat(gate.verify(connection, null, Bytes.empty(), 10L))
                .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
    }

    @Test
    void everyConnectionGetsItsOwnNonceAndAnAnswerOnlyOpensItsOwn() {
        gate.arm(WORLD, "pw".toCharArray());
        Object first = new Object();
        Object second = new Object();

        HostJoinGate.Challenge toFirst = gate.issue(first, 0L).orElseThrow();
        HostJoinGate.Challenge toSecond = gate.issue(second, 0L).orElseThrow();

        assertThat(toFirst.nonce()).isNotEqualTo(toSecond.nonce());
        // A correct answer for connection 1, replayed on connection 2, must not open connection 2.
        assertThat(gate.verify(second, null, answer(toFirst, "pw"), 10L))
                .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
    }

    @Test
    void aChallengeIsSingleUseSoASecondAnswerOnTheSameConnectionFindsNothing() {
        gate.arm(WORLD, "pw".toCharArray());
        Object connection = new Object();
        HostJoinGate.Challenge challenge = gate.issue(connection, 0L).orElseThrow();
        Bytes correct = answer(challenge, "pw");

        assertThat(gate.verify(connection, null, correct, 10L)).isEqualTo(HostJoinGate.Verdict.PASS);
        assertThat(gate.verify(connection, null, correct, 20L))
                .isEqualTo(HostJoinGate.Verdict.NO_CHALLENGE);
    }

    @Test
    void anExpiredChallengeIsRefusedEvenWithTheRightPassword() {
        gate.arm(WORLD, "pw".toCharArray());
        Object connection = new Object();
        HostJoinGate.Challenge challenge = gate.issue(connection, 0L).orElseThrow();

        assertThat(gate.verify(connection, null, answer(challenge, "pw"),
                HostJoinGate.TTL_MILLIS + 1)).isEqualTo(HostJoinGate.Verdict.NO_CHALLENGE);
    }

    @Test
    void anAnswerWithNoOutstandingChallengeIsRefused() {
        gate.arm(WORLD, "pw".toCharArray());

        assertThat(gate.verify(new Object(), null, Bytes.unsafeWrap(new byte[32]), 0L))
                .isEqualTo(HostJoinGate.Verdict.NO_CHALLENGE);
    }

    @Test
    void outstandingChallengesAreBoundedAndExpireSoAFloodCannotGrowTheStore() {
        gate.arm(WORLD, "pw".toCharArray());
        for (int i = 0; i < HostJoinGate.MAX_OUTSTANDING * 2; i++) {
            gate.issue(new Object(), 0L);
        }

        assertThat(gate.outstanding()).isLessThanOrEqualTo(HostJoinGate.MAX_OUTSTANDING);

        // And time alone clears them: one issue past the TTL sweeps every stale entry.
        gate.issue(new Object(), HostJoinGate.TTL_MILLIS + 1);
        assertThat(gate.outstanding()).isEqualTo(1);
    }

    @Test
    void aSealedGateRefusesEveryJoinRatherThanSilentlyOpeningTheWorld() {
        // Arming failed (a KDF that could not run). The world is password-protected, so the honest
        // answer is "nobody gets in", never "the password no longer applies".
        gate.seal();

        assertThat(gate.isArmed()).as("a sealed world still runs the gate step").isTrue();
        assertThat(gate.isSealed()).isTrue();
        assertThat(gate.issue(new Object(), 0L)).isEmpty();
        assertThat(gate.verify(new Object(), null, Bytes.unsafeWrap(new byte[32]), 0L))
                .isEqualTo(HostJoinGate.Verdict.WRONG_PASSWORD);
    }

    @Test
    void disarmingClearsBothTheKeyAndTheSeal() {
        gate.arm(WORLD, "pw".toCharArray());
        gate.issue(new Object(), 0L);
        gate.disarm();

        assertThat(gate.isArmed()).isFalse();
        assertThat(gate.isSealed()).isFalse();
        assertThat(gate.outstanding()).isZero();
        assertThat(gate.verify(new Object(), null, Bytes.empty(), 0L))
                .isEqualTo(HostJoinGate.Verdict.NOT_GATED);
    }

    @Test
    void reArmingOnANewHostSessionInvalidatesTheOldSessionsChallenges() {
        gate.arm(WORLD, "pw".toCharArray());
        Object connection = new Object();
        HostJoinGate.Challenge stale = gate.issue(connection, 0L).orElseThrow();

        gate.arm(WORLD, "pw".toCharArray());   // the host re-shared: fresh salt, fresh key

        assertThat(gate.verify(connection, null, answer(stale, "pw"), 10L))
                .isEqualTo(HostJoinGate.Verdict.NO_CHALLENGE);
    }
}
