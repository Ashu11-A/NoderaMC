//! Operator configuration (`nodera-rendezvous.toml`).
//!
//! Every bound here exists because the rendezvous/relay service is unauthenticated and
//! internet-facing: anyone can open a socket, register, and reserve. Registration and discovery are
//! cheap metadata (rendezvous.md §3.1); relay circuits carry real bandwidth and get the hard limits
//! (§4.2/§8.4). Defaults are conservative — an operator raises them knowingly.

use serde::Deserialize;
use std::net::SocketAddr;

/// The full service configuration.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// Address to listen on.
    pub bind_addr: SocketAddr,
    /// How long a registration survives without a refresh (rendezvous.md §9.3). Kept at ~2× the
    /// refresh interval so one lost refresh does not evict a healthy peer.
    pub registration_ttl_seconds: u64,
    /// The refresh cadence the service advertises; peers refresh at about half this.
    pub refresh_interval_seconds: u32,
    /// How far a record's own `issuedAt` may deviate from the service clock before it is refused as
    /// stale/replayed.
    pub clock_skew_seconds: u64,
    /// Maximum records returned in one discovery page (rendezvous.md §8.5 — no full enumeration).
    pub discover_page_limit: usize,
    /// Maximum records retained per `(network, world)` namespace.
    pub max_records_per_namespace: usize,
    /// Maximum namespaces tracked at once; the least recently active is shed beyond it.
    pub max_namespaces: usize,
    /// How long a relay reservation is valid (millis-resolution; §4.2).
    pub reservation_ttl_seconds: u64,
    /// Byte ceiling for a single relay circuit (§8.4).
    pub reservation_max_bytes: u64,
    /// Wall-clock ceiling for a single relay circuit (seconds; §8.4).
    pub reservation_max_duration_seconds: u64,
    /// Idle timeout for a bridged circuit (seconds) — no bytes either way for this long tears it
    /// down (§8.4 idle timeouts).
    pub circuit_idle_timeout_seconds: u64,
    /// Register/reserve requests accepted per source IP per refresh interval (§8.3/§8.4).
    pub per_ip_request_quota: u32,
    /// Largest accepted control frame.
    pub max_frame_bytes: usize,
    /// The HMAC key seed for reservation proofs, as a hex string. Empty means "derive an ephemeral
    /// key at boot" — fine for a single process; set it to keep proofs valid across a restart.
    pub reservation_hmac_key_hex: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25601".parse().expect("valid default bind addr"),
            registration_ttl_seconds: 300,
            refresh_interval_seconds: 120,
            clock_skew_seconds: 300,
            discover_page_limit: 50,
            max_records_per_namespace: 5_000,
            max_namespaces: 10_000,
            reservation_ttl_seconds: 300,
            reservation_max_bytes: 64 * 1024 * 1024,
            reservation_max_duration_seconds: 600,
            circuit_idle_timeout_seconds: 60,
            per_ip_request_quota: 120,
            max_frame_bytes: 256 * 1024,
            reservation_hmac_key_hex: String::new(),
        }
    }
}

/// Why a configuration was refused.
#[derive(Debug, thiserror::Error)]
pub enum ConfigError {
    /// The file could not be read.
    #[error("cannot read config {path}: {source}")]
    Io {
        /// Path that failed.
        path: String,
        /// Underlying IO error.
        #[source]
        source: std::io::Error,
    },
    /// The file was not valid TOML, or had unknown/mistyped keys.
    #[error("invalid config {path}: {source}")]
    Parse {
        /// Path that failed.
        path: String,
        /// Underlying parse error.
        #[source]
        source: toml::de::Error,
    },
    /// A value was structurally valid but unusable.
    #[error("invalid config value: {0}")]
    Invalid(String),
}

impl Config {
    /// Load and validate a config file.
    pub fn load(path: &std::path::Path) -> Result<Self, ConfigError> {
        let text = std::fs::read_to_string(path).map_err(|source| ConfigError::Io {
            path: path.display().to_string(),
            source,
        })?;
        let config: Config = toml::from_str(&text).map_err(|source| ConfigError::Parse {
            path: path.display().to_string(),
            source,
        })?;
        config.validate()?;
        Ok(config)
    }

    /// Reject values that would disable a bound rather than tune it.
    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.refresh_interval_seconds == 0 {
            return Err(ConfigError::Invalid(
                "refresh_interval_seconds must be positive".to_owned(),
            ));
        }
        if self.registration_ttl_seconds < u64::from(self.refresh_interval_seconds) {
            return Err(ConfigError::Invalid(
                "registration_ttl_seconds must be >= refresh_interval_seconds".to_owned(),
            ));
        }
        for (name, value) in [
            ("discover_page_limit", self.discover_page_limit),
            ("max_records_per_namespace", self.max_records_per_namespace),
            ("max_namespaces", self.max_namespaces),
            ("max_frame_bytes", self.max_frame_bytes),
        ] {
            if value == 0 {
                return Err(ConfigError::Invalid(format!("{name} must be positive")));
            }
        }
        for (name, value) in [
            ("reservation_ttl_seconds", self.reservation_ttl_seconds),
            ("reservation_max_bytes", self.reservation_max_bytes),
            (
                "reservation_max_duration_seconds",
                self.reservation_max_duration_seconds,
            ),
            (
                "circuit_idle_timeout_seconds",
                self.circuit_idle_timeout_seconds,
            ),
        ] {
            if value == 0 {
                return Err(ConfigError::Invalid(format!("{name} must be positive")));
            }
        }
        if self.max_frame_bytes > nodera_codec::framing::MAX_FRAME_BYTES {
            return Err(ConfigError::Invalid(format!(
                "max_frame_bytes exceeds the protocol cap of {}",
                nodera_codec::framing::MAX_FRAME_BYTES
            )));
        }
        if !self.reservation_hmac_key_hex.is_empty()
            && decode_hex(&self.reservation_hmac_key_hex).is_none()
        {
            return Err(ConfigError::Invalid(
                "reservation_hmac_key_hex must be valid, even-length hex".to_owned(),
            ));
        }
        Ok(())
    }

    /// Registration TTL in milliseconds (the unit the registry works in).
    pub fn registration_ttl_millis(&self) -> u64 {
        self.registration_ttl_seconds.saturating_mul(1_000)
    }

    /// Accepted record-timestamp skew in milliseconds.
    pub fn clock_skew_millis(&self) -> u64 {
        self.clock_skew_seconds.saturating_mul(1_000)
    }

    /// Reservation TTL in milliseconds.
    pub fn reservation_ttl_millis(&self) -> u64 {
        self.reservation_ttl_seconds.saturating_mul(1_000)
    }

    /// Reservation duration ceiling in milliseconds.
    pub fn reservation_max_duration_millis(&self) -> u64 {
        self.reservation_max_duration_seconds.saturating_mul(1_000)
    }

    /// The configured HMAC key bytes, or `None` when an ephemeral key should be minted.
    pub fn reservation_hmac_key(&self) -> Option<Vec<u8>> {
        if self.reservation_hmac_key_hex.is_empty() {
            None
        } else {
            decode_hex(&self.reservation_hmac_key_hex)
        }
    }
}

/// Decode an even-length hex string, or `None` if malformed.
fn decode_hex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid() {
        Config::default().validate().unwrap();
    }

    #[test]
    fn a_ttl_below_the_refresh_interval_is_refused() {
        let config = Config {
            refresh_interval_seconds: 120,
            registration_ttl_seconds: 60,
            ..Config::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn unknown_keys_are_refused_rather_than_silently_ignored() {
        let err = toml::from_str::<Config>("bind_add = \"0.0.0.0:1\"\n").unwrap_err();
        assert!(err.to_string().contains("bind_add"));
    }

    #[test]
    fn a_bad_hmac_key_is_refused() {
        let config = Config {
            reservation_hmac_key_hex: "zz".to_owned(),
            ..Config::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn a_hex_hmac_key_decodes() {
        let config = Config {
            reservation_hmac_key_hex: "00ff10".to_owned(),
            ..Config::default()
        };
        config.validate().unwrap();
        assert_eq!(config.reservation_hmac_key(), Some(vec![0x00, 0xFF, 0x10]));
    }

    #[test]
    fn parses_a_realistic_file() {
        let config: Config = toml::from_str(
            r#"
            bind_addr = "127.0.0.1:25601"
            registration_ttl_seconds = 90
            refresh_interval_seconds = 30
            discover_page_limit = 5
            reservation_max_bytes = 1048576
            "#,
        )
        .unwrap();
        config.validate().unwrap();
        assert_eq!(config.discover_page_limit, 5);
        assert_eq!(config.reservation_max_bytes, 1_048_576);
        assert_eq!(config.max_namespaces, Config::default().max_namespaces);
    }
}
