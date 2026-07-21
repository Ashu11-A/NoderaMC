package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 24-hour retention countdown (Task 22; L-38). The scenarios that matter: zero seeders start a
 * visible countdown, a returning seeder cancels it, expiry drops the world, and coordination adopts
 * the earliest announced deadline so the whole network counts down in lockstep.
 *
 * <p>Thread-context: single test thread.
 */
final class RetentionPolicyTest {

    private static final Bytes WORLD = Bytes.fromHex("1122334455667788");
    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    void zeroSeedersStartAVisibleCountdown() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        RetentionPolicy.WorldRetention r = policy.observe(WORLD, 0, 1_000_000L);

        assertThat(r.state()).isEqualTo(RetentionPolicy.State.COUNTDOWN);
        assertThat(r.countingDown()).isTrue();
        assertThat(r.deadlineEpochMillis()).isEqualTo(1_000_000L + DAY);
    }

    @Test
    void aSeederReturningCancelsTheCountdown() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        policy.observe(WORLD, 0, 1_000_000L);
        assertThat(policy.state(WORLD).countingDown()).isTrue();

        RetentionPolicy.WorldRetention r = policy.observe(WORLD, 3, 2_000_000L);

        assertThat(r.state()).isEqualTo(RetentionPolicy.State.MONITORED);
        assertThat(r.countingDown()).isFalse();
        assertThat(policy.state(WORLD).state()).isEqualTo(RetentionPolicy.State.MONITORED);
    }

    @Test
    void theWorldDropsAtCountdownExpiry() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        long started = policy.observe(WORLD, 0, 1_000_000L).deadlineEpochMillis();

        // Before the deadline: still counting down.
        assertThat(policy.observe(WORLD, 0, started - 1).state())
                .isEqualTo(RetentionPolicy.State.COUNTDOWN);
        // At/after the deadline: dropped.
        assertThat(policy.observe(WORLD, 0, started).state())
                .isEqualTo(RetentionPolicy.State.DROPPED);
        assertThat(policy.observe(WORLD, 0, started + 9999).state())
                .isEqualTo(RetentionPolicy.State.DROPPED);
    }

    @Test
    void aMonitoredWorldWithSeedersHasNoCountdown() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        RetentionPolicy.WorldRetention r = policy.observe(WORLD, 5, 0L);
        assertThat(r.state()).isEqualTo(RetentionPolicy.State.MONITORED);
        assertThat(r.countingDown()).isFalse();
    }

    @Test
    void coordinationAdoptsTheEarliestAnnouncedDeadline() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        // Peer A observes zero seeders first and starts a clock at t=100, deadline = 100 + DAY.
        long earliest = policy.observe(WORLD, 0, 100L).deadlineEpochMillis();

        // Peer B hears about it later (t=2000) but proposes a LATER deadline; the policy keeps A's.
        RetentionPolicy.WorldRetention r = policy.proposeDeadline(WORLD, 2000L + DAY, 2000L);
        assertThat(r.deadlineEpochMillis()).isEqualTo(earliest);

        // Peer C proposes an EARLIER deadline than A's (it observed even sooner); the policy adopts
        // the earliest — one agreed number across the network.
        long earlier = 50L + DAY;
        RetentionPolicy.WorldRetention adopted = policy.proposeDeadline(WORLD, earlier, 3000L);
        assertThat(adopted.deadlineEpochMillis()).isEqualTo(earlier);
    }

    @Test
    void anUnseenWorldIsMonitored() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        assertThat(policy.state(WORLD).state()).isEqualTo(RetentionPolicy.State.MONITORED);
    }

    @Test
    void forgetClearsACountdown() {
        RetentionPolicy policy = new RetentionPolicy(DAY);
        policy.observe(WORLD, 0, 0L);
        policy.forget(WORLD);
        assertThat(policy.state(WORLD).state()).isEqualTo(RetentionPolicy.State.MONITORED);
    }

    @Test
    void rejectsNonPositiveConfigAndNegativeSeeders() {
        assertThatThrownBy(() -> new RetentionPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
        RetentionPolicy policy = new RetentionPolicy(DAY);
        assertThatThrownBy(() -> policy.observe(WORLD, -1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.proposeDeadline(WORLD, 0, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
