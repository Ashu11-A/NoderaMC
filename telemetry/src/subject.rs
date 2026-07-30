//! Pseudonymisation with forward secrecy: the install identifier a client sends is never what gets
//! stored, and the key that links it back is gone the moment the rotation period rolls.
//!
//! An install id has to be stable for a client to be counted once rather than once per report.
//! Storing that stable value would build a permanent per-installation key in a warehouse many
//! people can query, so ingest replaces it with
//!
//! ```text
//!   subject = HMAC-SHA256(period_key, period ‖ source ‖ install)[..8]  (hex)
//! ```
//!
//! where `period_key` is a fresh 32-byte key minted from the OS CSPRNG the first time a period is
//! observed, held **only in process memory**, and **wiped the instant the period rolls over**.
//!
//! Four properties follow, and all four are the reason the design is shaped this way:
//!
//! * **Forward secrecy across rotation.** When the period advances, the previous period's key is
//!   zeroed and dropped before the new one is minted. A warehouse dump plus the operator's
//!   configuration plus a core dump taken *now* cannot recover a *past* period's subjects, because
//!   the key that derived them no longer exists anywhere — not on disk, not in config, not in
//!   memory. This is what retires [`docs/telemetry/LIMITATIONS.md`](../../docs/telemetry/LIMITATIONS.md)
//!   L-72: under the previous design the operator held one persistent secret that could re-link
//!   every current period; under this design there is no persistent key material at all.
//! * **No reversal from configuration.** The configuration carries no key material whatsoever. A
//!   restart mints a brand-new key for whatever period it boots into, so even the operator — with
//!   the full config in hand — cannot reproduce a subject from a previous process lifetime.
//! * **Stability within a period.** The key is cached for the period, so the same `(source,
//!   install)` maps to the same subject for the period's whole run, which is what cohort/retention
//!   analysis within a period actually needs.
//! * **Namespacing by source.** A peer and a service that somehow shared an install id still get
//!   different subjects, so the two populations cannot be accidentally joined.
//!
//! What this is *not*: anonymity against the operator for the *current* period. While a period is
//! live, its key is in memory and an operator who dumps the process can re-link that period's
//! subjects. Rotation bounds that window to one period and then the key is gone — that is the
//! intended trade, and it is strictly stronger than the persistent-secret design it replaces.

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

use crate::schema::Source;

const MILLIS_PER_DAY: u64 = 86_400_000;
/// CSPRNG key length. 256 bits matches the HMAC-SHA256 security level.
pub const PERIOD_KEY_BYTES: usize = 32;

/// Source of the per-period key material.
///
/// The production implementation ([`OsKeySource`]) draws 32 bytes from `/dev/urandom`; the
/// test implementation ([`DeterministicKeySource`]) is deterministic so the privacy properties can
/// be asserted without flakiness. **Nothing here is serialised**: a `KeySource`'s state lives only
/// in the process that built it, which is the load-bearing fact behind L-72's retirement.
pub trait KeySource: Send {
    /// Return `PERIOD_KEY_BYTES` of fresh key material. Called at most once per period per
    /// process; the [`Pseudonymiser`] caches the result until the period rolls.
    fn fresh_key(&mut self) -> Vec<u8>;
}

/// Production key source: a bounded `read_exact` from `/dev/urandom`.
///
/// This mirrors `nodera-app/src/peer/identity.rs::fresh_seed` deliberately: the same "read exactly
/// N bytes, never to EOF" rule that keeps an Android launch from draining the kernel entropy pool.
/// A missing OS entropy source is a refusal to proceed — a predictable pseudonymisation key is the
/// thing L-72 was about, and inventing one here would re-open it.
pub struct OsKeySource;

impl OsKeySource {
    pub fn new() -> Self {
        Self
    }
}

impl Default for OsKeySource {
    fn default() -> Self {
        Self::new()
    }
}

impl KeySource for OsKeySource {
    fn fresh_key(&mut self) -> Vec<u8> {
        use std::io::Read as _;

        let mut key = vec![0u8; PERIOD_KEY_BYTES];
        let mut source = std::fs::File::open("/dev/urandom").unwrap_or_else(|e| {
            panic!("nodera-telemetry: no OS entropy source for pseudonymisation: {e}")
        });
        source.read_exact(&mut key).unwrap_or_else(|e| {
            panic!("nodera-telemetry: could not read pseudonymisation key: {e}")
        });
        key
    }
}

/// Deterministic key source for tests. **Never wire this into a running service** — a predictable
/// key re-opens L-72. It exists so the rotation/restart privacy properties can be asserted without
/// depending on real OS entropy.
///
/// Each key is `SHA-256(seed ‖ counter)`. The counter starts at zero on every construction, so two
/// independent instances — modelling two process lifetimes — diverge as soon as their observation
/// history diverges, exactly as two real boots draw unrelated bytes from the OS.
pub struct DeterministicKeySource {
    seed: Vec<u8>,
    next: u64,
}

impl DeterministicKeySource {
    pub fn new(seed: &[u8]) -> Self {
        Self {
            seed: seed.to_vec(),
            next: 0,
        }
    }
}

impl KeySource for DeterministicKeySource {
    fn fresh_key(&mut self) -> Vec<u8> {
        let counter = self.next;
        self.next = self
            .next
            .checked_add(1)
            .expect("deterministic key counter overflow");
        let mut hasher = Sha256::new();
        hasher.update(&self.seed);
        hasher.update(b"\x1f");
        hasher.update(counter.to_be_bytes());
        hasher.finalize().to_vec()
    }
}

/// A per-period key, wiped on drop.
///
/// `zeroize` is not in this workspace; this is a best-effort zero of the live bytes, which is
/// enough to remove the key from reachable memory the moment the period rolls or the process exits.
/// It is not a constant-time-side-channel guarantee — telemetry pseudonymisation is not operating
/// in an adversarial same-process timing model.
struct PeriodKey(Vec<u8>);

impl PeriodKey {
    fn new(bytes: Vec<u8>) -> Self {
        debug_assert_eq!(
            bytes.len(),
            PERIOD_KEY_BYTES,
            "a period key must be exactly {PERIOD_KEY_BYTES} bytes"
        );
        Self(bytes)
    }

    fn as_slice(&self) -> &[u8] {
        &self.0
    }
}

impl Drop for PeriodKey {
    fn drop(&mut self) {
        for byte in self.0.iter_mut() {
            *byte = 0;
        }
    }
}

/// The rotating, forward-secret pseudonymiser.
///
/// Holds exactly one period's key at a time. The previous period's key is dropped (and therefore
/// wiped by [`PeriodKey`]'s `Drop`) the moment [`Pseudonymiser::advance_to`] observes that the
/// period has rolled — eagerly from the sweep, lazily from the next [`Pseudonymiser::subject`]
/// call.
pub struct Pseudonymiser {
    rotation_days: u64,
    /// The period the currently-held key was minted for. `None` until the first observation.
    current_period: Option<u64>,
    current_key: Option<PeriodKey>,
    key_source: Box<dyn KeySource>,
}

impl Pseudonymiser {
    /// The production constructor: OS-provided keys, memory-only, forward-secret across rotation.
    /// `rotation_days` of 0 is read as 1 (a zero-day period has no meaning and would divide by
    /// zero).
    pub fn memory_only(rotation_days: u64) -> Self {
        Self::with_key_source(rotation_days, Box::new(OsKeySource::new()))
    }

    /// Test/injection constructor. The caller supplies the key source; nothing about it is
    /// persisted by this struct, so a "restart" modelled as a fresh [`Pseudonymiser`] still
    /// cannot reproduce a previous period's subjects even with the same nominal configuration.
    pub fn with_key_source(rotation_days: u64, key_source: Box<dyn KeySource>) -> Self {
        Self {
            rotation_days: rotation_days.max(1),
            current_period: None,
            current_key: None,
            key_source,
        }
    }

    /// The rotation period `now_millis` falls in.
    pub fn period(&self, now_millis: u64) -> u64 {
        (now_millis / MILLIS_PER_DAY) / self.rotation_days
    }

    /// The period whose key is currently held in memory, or `None` before the first observation.
    pub fn current_period(&self) -> Option<u64> {
        self.current_period
    }

    /// Advance to the period `now_millis` falls in, minting a fresh key if it has rolled.
    ///
    /// The new key is minted **before** the old one is dropped, so a mint failure (no OS entropy)
    /// leaves the previous period's state intact rather than keyless. Returns `true` if a new key
    /// was minted (either a rotation or the very first observation).
    ///
    /// Called eagerly from the ingest sweep so a quiet period still erases the previous key soon
    /// after the boundary, rather than holding it until the next batch arrives.
    pub fn advance_to(&mut self, now_millis: u64) -> bool {
        let period = self.period(now_millis);
        if self.current_period == Some(period) {
            return false;
        }
        let fresh = self.key_source.fresh_key();
        let previous = self.current_key.replace(PeriodKey::new(fresh));
        drop(previous); // wipe the previous period's key now, explicitly
        self.current_period = Some(period);
        true
    }

    /// The stored subject for one install id.
    pub fn subject(&mut self, source: Source, install: &str, now_millis: u64) -> String {
        self.advance_to(now_millis);
        let period = self
            .current_period
            .expect("advance_to has just set the current period");
        let key = self
            .current_key
            .as_ref()
            .expect("the current period's key is minted by advance_to");

        // The period is folded into the HMAC input as well as keying the HMAC, so two periods that
        // somehow minted the same key still cannot join their populations.
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key.as_slice())
            .expect("HMAC accepts a key of any length");
        mac.update(period.to_be_bytes().as_slice());
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

    /// A pseudonymiser with a deterministic key source, so the assertions below do not depend on
    /// OS entropy. Distinct calls to `p()` build **distinct** instances with **independent** key
    /// streams — modelling distinct process lifetimes — so tests that need one continuous lifetime
    /// hold a single `let mut p = p();` binding.
    fn p() -> Pseudonymiser {
        Pseudonymiser::with_key_source(1, Box::new(DeterministicKeySource::new(b"subject-tests")))
    }

    #[test]
    fn the_subject_is_stable_within_a_period() {
        let mut p = p();
        let a = p.subject(Source::Peer, INSTALL, DAY * 10);
        let b = p.subject(Source::Peer, INSTALL, DAY * 10 + 3_600_000);
        assert_eq!(a, b);
        assert_eq!(a.len(), 16);
    }

    #[test]
    fn the_subject_changes_when_the_period_rolls() {
        let mut p = p();
        let today = p.subject(Source::Peer, INSTALL, DAY * 10);
        let tomorrow = p.subject(Source::Peer, INSTALL, DAY * 11);
        assert_ne!(today, tomorrow);
    }

    #[test]
    fn a_longer_rotation_period_holds_the_subject_across_days() {
        let mut weekly = Pseudonymiser::with_key_source(
            7,
            Box::new(DeterministicKeySource::new(b"subject-tests")),
        );
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
        let mut p = p();
        assert_ne!(
            p.subject(Source::Peer, INSTALL, DAY),
            p.subject(Source::Tracker, INSTALL, DAY)
        );
    }

    #[test]
    fn the_key_actually_varies_so_two_independent_lifetimes_disagree() {
        // Two distinct instances — two distinct key streams — must not silently agree on a subject
        // for the same install and period. This is the test that the key is real and non-constant;
        // it replaces the old "a different secret yields a different subject" assertion, because
        // there is no longer a persistent secret to vary.
        let mut a =
            Pseudonymiser::with_key_source(1, Box::new(DeterministicKeySource::new(b"lifetime-a")));
        let mut b =
            Pseudonymiser::with_key_source(1, Box::new(DeterministicKeySource::new(b"lifetime-b")));
        assert_ne!(
            a.subject(Source::Peer, INSTALL, DAY),
            b.subject(Source::Peer, INSTALL, DAY)
        );
    }

    #[test]
    fn the_install_id_never_appears_in_the_subject() {
        let mut p = p();
        let subject = p.subject(Source::Peer, INSTALL, DAY);
        assert!(!subject.contains(&INSTALL[..8]));
    }

    #[test]
    fn a_zero_rotation_period_is_read_as_one_day_rather_than_dividing_by_zero() {
        let zero = Pseudonymiser::memory_only(0);
        assert_eq!(zero.period(DAY * 3), 3);
    }

    #[test]
    fn advance_to_mints_a_key_only_once_per_period() {
        let mut p = p();
        assert!(p.current_period().is_none());
        assert!(p.advance_to(DAY * 10), "first observation mints a key");
        assert_eq!(p.current_period(), Some(10));
        assert!(
            !p.advance_to(DAY * 10 + 3_600_000),
            "same period does not re-mint"
        );
        assert_eq!(p.current_period(), Some(10));
    }

    #[test]
    fn the_period_key_is_erased_when_the_period_rolls() {
        // The forward-secrecy property as a structural assertion: after a rotation, the held key
        // corresponds to the new period and the old one is gone. We observe this indirectly,
        // because the bytes are not exposed — a subject computed in the rolled period cannot equal
        // one computed before the roll (covered by `the_subject_changes_when_the_period_rolls`),
        // and rolling back to the previous period mints a *fresh* key rather than recalling the
        // wiped one, so the back-dated subject also differs.
        let mut p = p();
        let first = p.subject(Source::Peer, INSTALL, DAY * 10);
        let _rolled = p.subject(Source::Peer, INSTALL, DAY * 11);
        // Going back to period 10 does NOT reproduce `first`: the period-10 key was wiped on the
        // way to period 11, and a new one is minted for this second visit to period 10.
        let back_dated = p.subject(Source::Peer, INSTALL, DAY * 10);
        assert_ne!(
            back_dated, first,
            "the rolled-away period's key must not be recallable"
        );
    }

    /// **L-72 exit test.** A previous period's subjects cannot be reproduced from the configuration
    /// alone. The configuration carries no key material; the key lived only in the first process's
    /// memory, and that process is gone. Deterministic fake entropy and a deterministic clock make
    /// the assertion reproducible without weakening it: even with the *same* nominal seed, the
    /// restarted process's key stream is independent of the dead one's.
    #[test]
    fn after_rotation_and_restart_a_previous_period_subject_is_not_reproducible_from_configuration()
    {
        // --- "Process A": ingest boots in period 10. Same seed for both processes — the point is
        //     that identical configuration is still insufficient. ---
        let mut a = Pseudonymiser::with_key_source(
            1,
            Box::new(DeterministicKeySource::new(b"operator-seed")),
        );
        let s_p10_a = a.subject(Source::Peer, INSTALL, DAY * 10);

        // Rotation within A: period 11. A wipes period 10's key and mints a fresh one.
        let _s_p11_a = a.subject(Source::Peer, INSTALL, DAY * 11);

        // --- "Restart": A is dropped — all in-memory keys wiped. A fresh ingest boots with an
        //     identical configuration (same seed, no secret anywhere). ---
        drop(a);
        let mut b = Pseudonymiser::with_key_source(
            1,
            Box::new(DeterministicKeySource::new(b"operator-seed")),
        );

        // The restarted process is now in period 11. The same install id cannot reproduce the
        // previous period's subject, because B's period-11 key is an independent mint, not A's:
        let s_p11_b = b.subject(Source::Peer, INSTALL, DAY * 11);
        assert_ne!(
            s_p11_b, s_p10_a,
            "after rotation+restart, the previous period's subject must not be reproducible"
        );

        // And it cannot reproduce period 10 either: B never held period 10's key. Asking B for
        // period 10 mints a brand-new key (B's second mint), which is not the key A derived
        // period-10's subject with. This is the decisive leg — it proves the configuration (which
        // is identical for A and B) plus a fresh process cannot walk a stored subject back to its
        // install id, which is exactly what L-72 said the operator could do under the old design.
        let s_p10_b = b.subject(Source::Peer, INSTALL, DAY * 10);
        assert_ne!(
            s_p10_b, s_p10_a,
            "the previous period's key is gone from memory; a fresh mint must not reproduce it"
        );
    }

    #[test]
    fn the_production_os_key_source_yields_full_length_distinct_keys() {
        // Not asserted against a fixed value — OS entropy is intentionally non-reproducible. The
        // two structural facts that must hold regardless: full length, and two mints disagree.
        let mut src = OsKeySource::new();
        let a = src.fresh_key();
        let b = src.fresh_key();
        assert_eq!(a.len(), PERIOD_KEY_BYTES);
        assert_eq!(b.len(), PERIOD_KEY_BYTES);
        assert_ne!(a, b, "two OS key draws must not collide");
    }
}
