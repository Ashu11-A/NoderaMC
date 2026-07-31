//! Operator configuration (`nodera-rendezvous.toml`).
//!
//! Every bound here exists because the rendezvous/relay service is unauthenticated and
//! internet-facing: anyone can open a socket, register, and reserve. Registration and discovery are
//! cheap metadata (RENDEZVOUS.md §3.1); relay circuits carry real bandwidth and get the hard limits
//! (§4.2/§8.4). Defaults are conservative — an operator raises them knowingly.

use nodera_service::env::{EnvOverlay, EnvSource};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;

/// Prefix for the environment form of every key below.
///
/// A container is configured by its environment or it is not configurable at all, so every TOML key
/// has an exact environment twin: `NODERA_RENDEZVOUS_` plus the key, uppercased, and nothing else.
pub const ENV_PREFIX: &str = "NODERA_RENDEZVOUS_";

/// The full service configuration.
///
/// `Serialize` exists so a test can enumerate the keys of a serialized default and fail if any of
/// them has no environment override.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// Address to listen on.
    pub bind_addr: SocketAddr,
    /// How long a registration survives without a refresh (RENDEZVOUS.md §9.3). Kept at ~2× the
    /// refresh interval so one lost refresh does not evict a healthy peer.
    pub registration_ttl_seconds: u64,
    /// The refresh cadence the service advertises; peers refresh at about half this.
    pub refresh_interval_seconds: u32,
    /// How far a record's own `issuedAt` may deviate from the service clock before it is refused as
    /// stale/replayed.
    pub clock_skew_seconds: u64,
    /// Maximum records returned in one discovery page (RENDEZVOUS.md §8.5 — no full enumeration).
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
    /// Where to report this service's own counters (`tcp://host:port`).
    ///
    /// **Empty by default, and that means off.** What is sent is windowed counters and coarse
    /// NAT-pair classes — never an address, a node id, a namespace, or a pair identity
    /// (`docs/rendezvous/Task.4.md`).
    pub telemetry_endpoint: String,
    /// How often a window event is emitted.
    pub telemetry_interval_seconds: u64,

    // --- service directory + drain + update (rendezvous Task 5) ---
    /// Trackers to announce this rendezvous to, so peers discover it instead of being configured
    /// with it. **This is the key that makes the whole discovery lane work** — a rendezvous that
    /// announces to no tracker is reachable only by peers that already have its address.
    pub tracker_endpoints: Vec<String>,
    /// Routes to advertise. Empty falls back to `bind_addr`, which is wrong behind NAT: an operator
    /// on a public host must state the public name here.
    pub advertised_routes: Vec<String>,
    /// Where the service signing identity is kept. Created on first start and then preserved: a
    /// regenerated identity looks like a brand-new, unmeasured rendezvous to every peer.
    pub identity_file: PathBuf,
    /// How long a drain may wait for in-flight circuits before the restart proceeds anyway.
    ///
    /// The number that decides whether an update cuts a world transfer. Long enough to let a normal
    /// circuit finish; bounded so a stuck one cannot hang the process forever.
    pub drain_grace_seconds: u64,
    /// The soft ceiling on concurrent circuits this relay advertises. 0 means unstated.
    ///
    /// Advertised, not enforced here — the enforcement is the reservation limits. Publishing it lets
    /// a peer prefer a relay with headroom over one that is saturated.
    pub max_concurrent_circuits: u32,
    /// Release channel to update from. **Empty by default, and that means off.**
    pub update_channel: String,
    /// Base URL the release assets are fetched from.
    pub update_feed_base_url: String,
    /// How often to check for a newer published build.
    pub update_check_interval_seconds: u64,
    /// The Ed25519 key a release manifest must be signed with, 64 hex characters.
    ///
    /// Defaults to the key this build was compiled with. Set it only to trust a fork's or a
    /// mirror's releases instead of ours — an empty value means the update lane checks
    /// integrity but not provenance, which it says out loud on every check (L-81).
    pub update_release_public_key: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25601".parse().expect("valid default bind addr"),
            telemetry_endpoint: String::new(),
            telemetry_interval_seconds: 300,
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
            tracker_endpoints: Vec::new(),
            advertised_routes: Vec::new(),
            identity_file: PathBuf::from("nodera-rendezvous-identity.bin"),
            drain_grace_seconds: 30,
            max_concurrent_circuits: 0,
            update_channel: String::new(),
            update_feed_base_url: nodera_service::update::DEFAULT_FEED_BASE_URL.to_owned(),
            update_check_interval_seconds: 3_600,
            update_release_public_key: nodera_service::update::DEFAULT_RELEASE_PUBLIC_KEY
                .to_owned(),
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
    /// An environment variable was unusable, or named nothing this service has.
    #[error("invalid environment configuration: {0}")]
    Env(#[from] nodera_service::env::EnvError),
}

impl Config {
    /// Overlay `NODERA_RENDEZVOUS_*` onto an already-loaded configuration.
    ///
    /// Precedence is defaults, then the file, then this. Validation runs after, never before.
    pub fn apply_env(
        &mut self,
        env: &impl EnvSource,
    ) -> Result<nodera_service::env::Applied, ConfigError> {
        let mut overlay = EnvOverlay::new(ENV_PREFIX, env);
        // These three are read by the Java peer/worker (`HeadlessPeerMain`), which is a *client* of
        // rendezvous services. A shell holding both must be able to start both.
        overlay.ignore("NODERA_RENDEZVOUS_ENDPOINTS");
        overlay.ignore("NODERA_RENDEZVOUS_FANOUT");
        overlay.ignore("NODERA_RENDEZVOUS_SWEEP_SECONDS");

        overlay.set("bind_addr", &mut self.bind_addr);
        overlay.set(
            "registration_ttl_seconds",
            &mut self.registration_ttl_seconds,
        );
        overlay.set(
            "refresh_interval_seconds",
            &mut self.refresh_interval_seconds,
        );
        overlay.set("clock_skew_seconds", &mut self.clock_skew_seconds);
        overlay.set("discover_page_limit", &mut self.discover_page_limit);
        overlay.set(
            "max_records_per_namespace",
            &mut self.max_records_per_namespace,
        );
        overlay.set("max_namespaces", &mut self.max_namespaces);
        overlay.set("reservation_ttl_seconds", &mut self.reservation_ttl_seconds);
        overlay.set("reservation_max_bytes", &mut self.reservation_max_bytes);
        overlay.set(
            "reservation_max_duration_seconds",
            &mut self.reservation_max_duration_seconds,
        );
        overlay.set(
            "circuit_idle_timeout_seconds",
            &mut self.circuit_idle_timeout_seconds,
        );
        overlay.set("per_ip_request_quota", &mut self.per_ip_request_quota);
        overlay.set("max_frame_bytes", &mut self.max_frame_bytes);
        overlay.set(
            "reservation_hmac_key_hex",
            &mut self.reservation_hmac_key_hex,
        );
        overlay.set("telemetry_endpoint", &mut self.telemetry_endpoint);
        overlay.set(
            "telemetry_interval_seconds",
            &mut self.telemetry_interval_seconds,
        );
        overlay.set_list("tracker_endpoints", &mut self.tracker_endpoints);
        overlay.set_list("advertised_routes", &mut self.advertised_routes);
        overlay.set("identity_file", &mut self.identity_file);
        overlay.set("drain_grace_seconds", &mut self.drain_grace_seconds);
        overlay.set("max_concurrent_circuits", &mut self.max_concurrent_circuits);
        overlay.set("update_channel", &mut self.update_channel);
        overlay.set("update_feed_base_url", &mut self.update_feed_base_url);
        overlay.set(
            "update_check_interval_seconds",
            &mut self.update_check_interval_seconds,
        );
        overlay.set(
            "update_release_public_key",
            &mut self.update_release_public_key,
        );

        Ok(overlay.finish()?)
    }

    /// Every environment variable this service understands, sorted. For the operator reference.
    pub fn env_reference() -> Vec<String> {
        Config::default()
            .apply_env(&nodera_service::env::MapEnv::empty())
            .expect("an empty environment applies cleanly")
            .declared
    }

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
        if self.drain_grace_seconds == 0 {
            // A zero grace makes every restart a hard cut, which is the behaviour this whole lane
            // exists to remove; an operator wanting that can stop the process outright.
            return Err(ConfigError::Invalid(
                "drain_grace_seconds must be positive".to_owned(),
            ));
        }
        if !self.update_channel.trim().is_empty() {
            if self.update_check_interval_seconds == 0 {
                return Err(ConfigError::Invalid(
                    "update_check_interval_seconds must be positive when update_channel is set"
                        .to_owned(),
                ));
            }
            if !self.update_feed_base_url.starts_with("https://") {
                // The published digest is the only integrity check there is; over plaintext, whoever
                // is on the path chooses both the binary and the digest it is checked against.
                return Err(ConfigError::Invalid(
                    "update_feed_base_url must be https://".to_owned(),
                ));
            }
            if self.tracker_endpoints.is_empty() {
                // Updating without announcing means restarting without telling anyone where to go:
                // the drain notice reaches the peers holding circuits, but a peer that arrives during
                // the restart has no way to learn about the replacement.
                return Err(ConfigError::Invalid(
                    "update_channel requires tracker_endpoints, so a drain can publish replacements"
                        .to_owned(),
                ));
            }
        }
        Ok(())
    }

    /// The update lane's configuration for this binary.
    pub fn update_config(&self) -> nodera_service::update::UpdateConfig {
        nodera_service::update::UpdateConfig {
            channel: self.update_channel.clone(),
            feed_base_url: self.update_feed_base_url.clone(),
            binary_name: "nodera-rendezvous".to_owned(),
            check_interval_seconds: self.update_check_interval_seconds,
            drain_grace_seconds: self.drain_grace_seconds,
            release_public_key: self.update_release_public_key.clone(),
        }
    }

    /// The routes this rendezvous advertises, falling back to its bind address.
    pub fn routes(&self) -> Vec<String> {
        if self.advertised_routes.is_empty() {
            vec![self.bind_addr.to_string()]
        } else {
            self.advertised_routes.clone()
        }
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

    #[test]
    fn every_config_key_has_an_environment_override() {
        // The drift guard: a key reachable only from a file is a key a container cannot set.
        let serialized = toml::Value::try_from(Config::default()).expect("config serializes");
        let file_keys: Vec<String> = serialized
            .as_table()
            .expect("a table")
            .keys()
            .cloned()
            .collect();

        let declared = Config::env_reference();
        let missing: Vec<&String> = file_keys
            .iter()
            .filter(|key| !declared.contains(&format!("{ENV_PREFIX}{}", key.to_uppercase())))
            .collect();

        assert!(
            missing.is_empty(),
            "config keys with no env override: {missing:?}"
        );
        assert_eq!(declared.len(), file_keys.len(), "declared: {declared:?}");
    }

    #[test]
    fn the_environment_overrides_the_file_and_validation_still_runs_after() {
        use nodera_service::env::MapEnv;

        let mut config: Config = toml::from_str("bind_addr = \"127.0.0.1:25601\"\n").unwrap();
        let env = MapEnv::empty()
            .with("NODERA_RENDEZVOUS_BIND_ADDR", "0.0.0.0:7500")
            .with(
                "NODERA_RENDEZVOUS_TRACKER_ENDPOINTS",
                "tcp://t1:6969,tcp://t2:6969",
            )
            .with("NODERA_RENDEZVOUS_MAX_CONCURRENT_CIRCUITS", "128");
        let applied = config.apply_env(&env).unwrap();
        config.validate().unwrap();

        assert_eq!(config.bind_addr, "0.0.0.0:7500".parse().unwrap());
        assert_eq!(config.tracker_endpoints, ["tcp://t1:6969", "tcp://t2:6969"]);
        assert_eq!(config.max_concurrent_circuits, 128);
        assert_eq!(applied.applied.len(), 3);
    }

    #[test]
    fn an_environment_value_that_breaks_a_bound_is_still_refused() {
        use nodera_service::env::MapEnv;

        let mut config = Config::default();
        config
            .apply_env(&MapEnv::empty().with("NODERA_RENDEZVOUS_REGISTRATION_TTL_SECONDS", "1"))
            .unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn the_java_peers_rendezvous_variables_do_not_stop_this_service() {
        use nodera_service::env::MapEnv;

        // All three belong to the peer, and all three sit in this service's prefix.
        let mut config = Config::default();
        config
            .apply_env(
                &MapEnv::empty()
                    .with("NODERA_RENDEZVOUS_ENDPOINTS", "tcp://host:7500")
                    .with("NODERA_RENDEZVOUS_FANOUT", "3")
                    .with("NODERA_RENDEZVOUS_SWEEP_SECONDS", "60"),
            )
            .unwrap();
        assert_eq!(config, Config::default());
    }
}
