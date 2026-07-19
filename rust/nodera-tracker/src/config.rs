//! Operator configuration (`nodera-tracker.toml`).
//!
//! Every bound here exists because the tracker is an unauthenticated, internet-facing service:
//! anyone can open a socket to it and announce. The defaults are deliberately conservative — an
//! operator raises them knowingly rather than discovering the ceiling under load.

use serde::Deserialize;
use std::net::SocketAddr;
use std::path::PathBuf;

/// The full service configuration.
#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// Address to listen on.
    pub bind_addr: SocketAddr,
    /// Interval handed back in every ack — the tracker paces announce traffic, not the peer.
    pub announce_interval_seconds: u32,
    /// How long a record survives without a refresh. Kept at ~2× the interval so one lost
    /// announce does not evict a healthy peer (`docs/torrent/trackers.md` §11).
    pub peer_ttl_seconds: u64,
    /// How far an announce's own timestamp may deviate from the tracker's clock before it is
    /// rejected as stale/replayed.
    pub announce_clock_skew_seconds: u64,
    /// Maximum number of worlds tracked at once; the least recently active is shed beyond it.
    pub max_worlds: usize,
    /// Maximum peers retained per world.
    pub max_peers_per_world: usize,
    /// Maximum peers returned in one query response.
    pub sample_size: usize,
    /// Seeders always included in a sample before the remaining slots are filled.
    pub seeder_floor: usize,
    /// Seeders a world needs to count as `HEALTHY` (Task 21's snapshot replication factor).
    pub healthy_seeder_floor: usize,
    /// Announces accepted per source IP per interval.
    pub per_ip_announce_quota: u32,
    /// Largest accepted frame.
    pub max_frame_bytes: usize,
    /// Optional directory for world display-name metadata; peer state is never persisted.
    pub persist_dir: Option<PathBuf>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25600".parse().expect("valid default bind addr"),
            announce_interval_seconds: 120,
            peer_ttl_seconds: 300,
            announce_clock_skew_seconds: 300,
            max_worlds: 10_000,
            max_peers_per_world: 5_000,
            sample_size: 50,
            seeder_floor: 10,
            healthy_seeder_floor: 5,
            per_ip_announce_quota: 60,
            max_frame_bytes: 256 * 1024,
            persist_dir: None,
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
        if self.announce_interval_seconds == 0 {
            return Err(ConfigError::Invalid(
                "announce_interval_seconds must be positive".to_owned(),
            ));
        }
        if self.peer_ttl_seconds < u64::from(self.announce_interval_seconds) {
            // A TTL below the interval expires every peer between its own announces: the world
            // list would flicker empty no matter how healthy the swarm is.
            return Err(ConfigError::Invalid(
                "peer_ttl_seconds must be >= announce_interval_seconds".to_owned(),
            ));
        }
        for (name, value) in [
            ("max_worlds", self.max_worlds),
            ("max_peers_per_world", self.max_peers_per_world),
            ("sample_size", self.sample_size),
            ("healthy_seeder_floor", self.healthy_seeder_floor),
            ("max_frame_bytes", self.max_frame_bytes),
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
        Ok(())
    }

    /// Peer TTL in milliseconds (the unit the registry works in).
    pub fn peer_ttl_millis(&self) -> u64 {
        self.peer_ttl_seconds.saturating_mul(1_000)
    }

    /// Accepted announce-timestamp skew in milliseconds.
    pub fn clock_skew_millis(&self) -> u64 {
        self.announce_clock_skew_seconds.saturating_mul(1_000)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid() {
        Config::default().validate().unwrap();
    }

    #[test]
    fn a_ttl_below_the_announce_interval_is_refused() {
        let config = Config {
            announce_interval_seconds: 120,
            peer_ttl_seconds: 60,
            ..Config::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn unknown_keys_are_refused_rather_than_silently_ignored() {
        // A typo'd key must not leave the operator believing a limit is in force.
        let err = toml::from_str::<Config>("bind_add = \"0.0.0.0:1\"\n").unwrap_err();
        assert!(err.to_string().contains("bind_add"));
    }

    #[test]
    fn parses_a_realistic_file() {
        let config: Config = toml::from_str(
            r#"
            bind_addr = "127.0.0.1:25600"
            announce_interval_seconds = 30
            peer_ttl_seconds = 90
            sample_size = 5
            healthy_seeder_floor = 2
            "#,
        )
        .unwrap();
        config.validate().unwrap();
        assert_eq!(config.sample_size, 5);
        assert_eq!(config.healthy_seeder_floor, 2);
        // Unset keys keep their defaults.
        assert_eq!(config.max_worlds, Config::default().max_worlds);
    }
}
