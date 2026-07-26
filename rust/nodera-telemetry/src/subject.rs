//! Pseudonymisation: the install identifier a client sends is never what gets stored.
//!
//! An install id has to be stable for a client to be counted once rather than once per report.
//! Storing that stable value would build a permanent per-installation key in a warehouse many
//! people can query, so ingest replaces it with
//!
//! ```text
//!   subject = HMAC-SHA256(secret ‖ epoch_day / rotation_days, source ‖ install)[..8]  (hex)
//! ```
//!
//! Three properties follow, and all three are the reason the design is shaped this way:
//!
//! * **Rotation.** The key changes every rotation period, so a subject cannot be followed across
//!   periods by anyone reading the warehouse — including the analysts who legitimately query it.
//!   Retention/return analysis is possible *within* a period and by cohort across periods, which
//!   is what the questions in `docs/telemetry/Task.3.md` actually need.
//! * **No reversal without the secret.** The secret lives only in the ingest process's
//!   configuration, never in the warehouse. A dump of the warehouse cannot be walked back to
//!   install ids.
//! * **Namespacing by source.** A peer and a service that somehow shared an install id still get
//!   different subjects, so the two populations cannot be accidentally joined.
//!
//! What this is *not*: anonymity against the operator, who holds the secret and could recompute a
//! period's mapping while that period is current. That is stated plainly in
//! `docs/telemetry/LIMITATIONS.md` rather than papered over.

use hmac::{Hmac, Mac};
use sha2::Sha256;

use crate::schema::Source;

const MILLIS_PER_DAY: u64 = 86_400_000;

/// The rotating pseudonymiser.
#[derive(Debug, Clone)]
pub struct Pseudonymiser {
    secret: Vec<u8>,
    rotation_days: u64,
}

impl Pseudonymiser {
    /// `rotation_days` of 0 is treated as 1: a rotation period of zero days has no meaning and
    /// would divide by zero, and the safest reading of "0" is the shortest real period.
    pub fn new(secret: &str, rotation_days: u64) -> Self {
        Self {
            secret: secret.as_bytes().to_vec(),
            rotation_days: rotation_days.max(1),
        }
    }

    /// The rotation period `now_millis` falls in.
    pub fn period(&self, now_millis: u64) -> u64 {
        (now_millis / MILLIS_PER_DAY) / self.rotation_days
    }

    /// The stored subject for one install id.
    pub fn subject(&self, source: Source, install: &str, now_millis: u64) -> String {
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&self.secret)
            .expect("HMAC accepts a key of any length");
        mac.update(self.period(now_millis).to_be_bytes().as_slice());
        mac.update(b"\x1f");
        mac.update(source.as_str().as_bytes());
        mac.update(b"\x1f");
        mac.update(install.as_bytes());
        let digest = mac.finalize().into_bytes();
        digest[..8].iter().map(|b| format!("{b:02x}")).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const INSTALL: &str = "0123456789abcdef0123456789abcdef";
    const DAY: u64 = MILLIS_PER_DAY;

    fn p() -> Pseudonymiser {
        Pseudonymiser::new("test-secret", 1)
    }

    #[test]
    fn the_subject_is_stable_within_a_period() {
        let a = p().subject(Source::Peer, INSTALL, DAY * 10);
        let b = p().subject(Source::Peer, INSTALL, DAY * 10 + 3_600_000);
        assert_eq!(a, b);
        assert_eq!(a.len(), 16);
    }

    #[test]
    fn the_subject_changes_when_the_period_rolls() {
        let today = p().subject(Source::Peer, INSTALL, DAY * 10);
        let tomorrow = p().subject(Source::Peer, INSTALL, DAY * 11);
        assert_ne!(today, tomorrow);
    }

    #[test]
    fn a_longer_rotation_period_holds_the_subject_across_days() {
        let weekly = Pseudonymiser::new("test-secret", 7);
        assert_eq!(
            weekly.subject(Source::Peer, INSTALL, DAY * 7),
            weekly.subject(Source::Peer, INSTALL, DAY * 13)
        );
        assert_ne!(
            weekly.subject(Source::Peer, INSTALL, DAY * 7),
            weekly.subject(Source::Peer, INSTALL, DAY * 14)
        );
    }

    #[test]
    fn sources_are_namespaced_apart() {
        assert_ne!(
            p().subject(Source::Peer, INSTALL, DAY),
            p().subject(Source::Tracker, INSTALL, DAY)
        );
    }

    #[test]
    fn a_different_secret_yields_a_different_subject() {
        assert_ne!(
            Pseudonymiser::new("secret-a", 1).subject(Source::Peer, INSTALL, DAY),
            Pseudonymiser::new("secret-b", 1).subject(Source::Peer, INSTALL, DAY)
        );
    }

    #[test]
    fn the_install_id_never_appears_in_the_subject() {
        let subject = p().subject(Source::Peer, INSTALL, DAY);
        assert!(!subject.contains(&INSTALL[..8]));
    }

    #[test]
    fn a_zero_rotation_period_is_read_as_one_day_rather_than_dividing_by_zero() {
        let zero = Pseudonymiser::new("s", 0);
        assert_eq!(zero.period(DAY * 3), 3);
    }
}
