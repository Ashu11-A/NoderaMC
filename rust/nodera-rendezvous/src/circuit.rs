//! Circuit metering: the byte/duration/idle accounting that bounds a bridged relay circuit.
//!
//! The bridging itself (splicing two sockets) lives in [`crate::wire`]; the accounting that decides
//! *when a circuit must be torn down* lives here and takes the clock as a parameter, so every limit
//! is unit-testable without a network. A circuit is a paired copy-loop with per-direction byte
//! counters checked against the reservation (rendezvous.md §4.5/§8.4).

/// Why a circuit was torn down. Both sides get a reason code (rendezvous.md §4.5).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TeardownReason {
    /// One side closed its half of the circuit — the normal end of a session.
    RemoteClosed,
    /// The reservation's byte ceiling was reached.
    ByteLimit,
    /// The reservation's wall-clock ceiling was reached.
    DurationLimit,
    /// No bytes flowed either way within the idle timeout.
    IdleTimeout,
    /// A transport error broke the circuit.
    Error,
}

impl TeardownReason {
    /// The stable code carried in logs.
    pub fn code(self) -> &'static str {
        match self {
            Self::RemoteClosed => "remote-closed",
            Self::ByteLimit => "byte-limit",
            Self::DurationLimit => "duration-limit",
            Self::IdleTimeout => "idle-timeout",
            Self::Error => "error",
        }
    }
}

/// The limits a circuit is metered against (from the peer's reservation).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CircuitLimits {
    /// Total bytes (both directions summed) before teardown.
    pub max_bytes: u64,
    /// Wall-clock lifetime before teardown (millis).
    pub max_duration_millis: u64,
    /// Idle time before teardown (millis) — no bytes either way.
    pub idle_timeout_millis: u64,
}

/// Tracks a live circuit's byte total and activity clock.
#[derive(Debug, Clone, Copy)]
pub struct CircuitMeter {
    limits: CircuitLimits,
    started_millis: u64,
    last_activity_millis: u64,
    bytes_transferred: u64,
}

impl CircuitMeter {
    /// Start metering at `now_millis`.
    pub fn new(limits: CircuitLimits, now_millis: u64) -> Self {
        Self {
            limits,
            started_millis: now_millis,
            last_activity_millis: now_millis,
            bytes_transferred: 0,
        }
    }

    /// Total bytes transferred so far (both directions).
    pub fn bytes_transferred(&self) -> u64 {
        self.bytes_transferred
    }

    /// Record `n` bytes crossing (either direction) at `now_millis`.
    ///
    /// Returns [`TeardownReason::ByteLimit`] when the transfer reaches the reservation's ceiling —
    /// the caller stops the circuit and reports the reason to both sides.
    pub fn record(&mut self, n: u64, now_millis: u64) -> Option<TeardownReason> {
        self.bytes_transferred = self.bytes_transferred.saturating_add(n);
        self.last_activity_millis = now_millis;
        if self.bytes_transferred >= self.limits.max_bytes {
            Some(TeardownReason::ByteLimit)
        } else {
            None
        }
    }

    /// Check the time-based limits at `now_millis`, independent of any byte transfer.
    ///
    /// Returns a reason when the circuit has outlived its duration ceiling or been idle past the
    /// idle timeout — the caller polls this on a timer so a silent circuit still gets reclaimed.
    pub fn check_time(&self, now_millis: u64) -> Option<TeardownReason> {
        if now_millis.saturating_sub(self.started_millis) >= self.limits.max_duration_millis {
            return Some(TeardownReason::DurationLimit);
        }
        if now_millis.saturating_sub(self.last_activity_millis) >= self.limits.idle_timeout_millis {
            return Some(TeardownReason::IdleTimeout);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn limits() -> CircuitLimits {
        CircuitLimits {
            max_bytes: 1_000,
            max_duration_millis: 10_000,
            idle_timeout_millis: 2_000,
        }
    }

    #[test]
    fn bytes_below_the_ceiling_keep_the_circuit_open() {
        let mut meter = CircuitMeter::new(limits(), 0);
        assert_eq!(meter.record(400, 100), None);
        assert_eq!(meter.record(400, 200), None);
        assert_eq!(meter.bytes_transferred(), 800);
    }

    #[test]
    fn reaching_the_byte_ceiling_tears_down() {
        let mut meter = CircuitMeter::new(limits(), 0);
        meter.record(600, 100);
        assert_eq!(meter.record(400, 200), Some(TeardownReason::ByteLimit));
    }

    #[test]
    fn outliving_the_duration_tears_down() {
        // A generous idle timeout so the duration ceiling is what fires, in isolation.
        let limits = CircuitLimits {
            idle_timeout_millis: 100_000,
            ..limits()
        };
        let meter = CircuitMeter::new(limits, 0);
        assert_eq!(meter.check_time(9_999), None);
        assert_eq!(
            meter.check_time(10_000),
            Some(TeardownReason::DurationLimit)
        );
    }

    #[test]
    fn idling_past_the_timeout_tears_down() {
        let mut meter = CircuitMeter::new(limits(), 0);
        meter.record(1, 1_000);
        assert_eq!(meter.check_time(2_999), None);
        assert_eq!(meter.check_time(3_000), Some(TeardownReason::IdleTimeout));
    }

    #[test]
    fn activity_resets_the_idle_clock() {
        let mut meter = CircuitMeter::new(limits(), 0);
        meter.record(1, 1_000);
        meter.record(1, 2_500); // fresh activity before the 3_000 idle deadline
        assert_eq!(meter.check_time(3_000), None);
    }

    #[test]
    fn reason_codes_are_stable() {
        assert_eq!(TeardownReason::ByteLimit.code(), "byte-limit");
        assert_eq!(TeardownReason::IdleTimeout.code(), "idle-timeout");
        assert_eq!(TeardownReason::RemoteClosed.code(), "remote-closed");
    }
}
