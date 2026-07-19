//! World health and the retention countdown surface.
//!
//! This mirrors the rule the Java `TrackerService` established (Task 20/22) exactly, because the
//! multiplayer GUI (Task 26) already renders it: red vs gray is a user-visible promise.

use nodera_codec::types::WorldHealth;

/// Classify a world.
///
/// A world with zero seeders is **not** instantly `DEAD`: that would paint a host's world gray the
/// moment they reboot. It is `DEGRADED` with a visible countdown, and only an *expired* retention
/// deadline makes it `DEAD`. The tracker only *surfaces* the countdown — the peers'
/// `RetentionPolicy` still owns the actual drop, so authority stays in the network.
pub fn classify(
    seeder_count: usize,
    healthy_seeder_floor: usize,
    retention_deadline_epoch_millis: u64,
    now_millis: u64,
) -> WorldHealth {
    if seeder_count >= healthy_seeder_floor {
        return WorldHealth::Healthy;
    }
    if seeder_count == 0
        && retention_deadline_epoch_millis > 0
        && now_millis >= retention_deadline_epoch_millis
    {
        return WorldHealth::Dead;
    }
    WorldHealth::Degraded
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enough_seeders_is_healthy() {
        assert_eq!(classify(5, 5, 0, 0), WorldHealth::Healthy);
        assert_eq!(classify(9, 5, 1, 10_000), WorldHealth::Healthy);
    }

    #[test]
    fn under_replicated_is_degraded_not_dead() {
        assert_eq!(classify(1, 5, 0, 0), WorldHealth::Degraded);
    }

    #[test]
    fn zero_seeders_inside_the_window_is_degraded_with_a_countdown() {
        // Host rebooting: the deadline exists but has not passed.
        assert_eq!(classify(0, 5, 10_000, 9_999), WorldHealth::Degraded);
    }

    #[test]
    fn zero_seeders_past_an_expired_deadline_is_dead() {
        assert_eq!(classify(0, 5, 10_000, 10_000), WorldHealth::Dead);
    }

    #[test]
    fn zero_seeders_without_a_countdown_is_degraded() {
        // No deadline means no retention decision has been made; a verdict would be invented.
        assert_eq!(classify(0, 5, 0, u64::MAX), WorldHealth::Degraded);
    }

    #[test]
    fn a_seeder_returning_cancels_death_even_past_the_deadline() {
        assert_eq!(classify(1, 5, 10_000, 20_000), WorldHealth::Degraded);
    }
}
