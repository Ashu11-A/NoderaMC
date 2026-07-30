package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-38's named exit test: the coordinated 24-h decommission lifecycle — countdown starts on a
 * zero-seeder observation, is network-coordinated to the EARLIEST proposed deadline, cancels the
 * moment a seeder returns, and drops at expiry. (The tracker's countdown SURFACE over the real
 * binary is proven by {@code TrackerServiceIT}'s dead-world listing; the announce now carries
 * this policy's deadline — {@code WorldHostingService.announce} — so every tracker/UI shows the
 * same network-visible deadline.)
 */
final class RetentionIT {

    private static final Bytes WORLD = Bytes.fromHex("aa17");

    @Test
    void zeroSeederCountdownCancelsOnSeederReturn() {
        RetentionPolicy policy = new RetentionPolicy(1_000L);
        long t0 = 1_000_000L;

        assertThat(policy.observe(WORLD, 2, t0).countingDown()).isFalse();
        RetentionPolicy.WorldRetention counting = policy.observe(WORLD, 0, t0);
        assertThat(counting.countingDown()).isTrue();
        assertThat(counting.deadlineEpochMillis()).isEqualTo(t0 + 1_000L);
        // The announce surfaces the running deadline (network-visible).
        assertThat(policy.state(WORLD).deadlineEpochMillis()).isEqualTo(t0 + 1_000L);

        // A seeder returns before expiry: countdown cancels, deadline clears from announces.
        RetentionPolicy.WorldRetention cancelled = policy.observe(WORLD, 1, t0 + 500);
        assertThat(cancelled.countingDown()).isFalse();
        assertThat(policy.state(WORLD).deadlineEpochMillis()).isZero();
    }

    @Test
    void coordinatedEarliestDeadlineWinsAcrossPeers() {
        RetentionPolicy policy = new RetentionPolicy(10_000L);
        long t0 = 5_000_000L;
        policy.observe(WORLD, 0, t0);

        // Another peer proposes an EARLIER deadline (it observed zero seeders first): the
        // coordination rule adopts the earliest — no peer can extend a world's life by
        // proposing later.
        RetentionPolicy.WorldRetention adopted =
                policy.proposeDeadline(WORLD, t0 + 2_000L, t0 + 100);
        assertThat(adopted.deadlineEpochMillis()).isEqualTo(t0 + 2_000L);
        RetentionPolicy.WorldRetention ignoredLater =
                policy.proposeDeadline(WORLD, t0 + 50_000L, t0 + 200);
        assertThat(ignoredLater.deadlineEpochMillis()).isEqualTo(t0 + 2_000L);
    }

    @Test
    void expiryDropsTheWorld() {
        RetentionPolicy policy = new RetentionPolicy(1_000L);
        long t0 = 9_000_000L;
        policy.observe(WORLD, 0, t0);

        RetentionPolicy.WorldRetention expired = policy.observe(WORLD, 0, t0 + 1_001L);
        assertThat(expired.state()).isEqualTo(RetentionPolicy.State.DROPPED);

        // Drop-at-expiry: the world is forgotten; a fresh observation restarts from MONITORED
        // (a re-seeded world is a new retention life, not a resurrected countdown).
        policy.forget(WORLD);
        assertThat(policy.state(WORLD).state()).isEqualTo(RetentionPolicy.State.MONITORED);
        assertThat(policy.observe(WORLD, 1, t0 + 2_000L).countingDown()).isFalse();
    }
}
