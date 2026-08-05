//! Operator configuration (`nodera-tracker.toml`).
//!
//! Every bound here exists because the tracker is an unauthenticated, internet-facing service:
//! anyone can open a socket to it and announce. The defaults are deliberately conservative — an
//! operator raises them knowingly rather than discovering the ceiling under load.

use nodera_service::env::{EnvOverlay, EnvSource};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;

/// Prefix for the environment form of every key below.
///
/// A container is configured by its environment or it is not configurable at all, so every TOML key
/// has an exact environment twin: `NODERA_TRACKER_` plus the key, uppercased, and nothing else.
/// One name per setting in two syntaxes beats two names that drift.
pub const ENV_PREFIX: &str = "NODERA_TRACKER_";

/// The full service configuration.
///
/// `Serialize` exists for one reason: a test enumerates the keys of a serialized default and fails
/// if any of them has no environment override. Adding a config key and forgetting the container is
/// otherwise a silent gap that only shows up in production.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// Address to listen on.
    pub bind_addr: SocketAddr,
    /// Interval handed back in every ack — the tracker paces announce traffic, not the peer.
    pub announce_interval_seconds: u32,
    /// How long a record survives without a refresh. Kept at ~2× the interval so one lost
    /// announce does not evict a healthy peer (`docs/tracker/REFERENCE.md`, "Records, sampling and
    /// expiry").
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
    /// Also serve the same request family over UDP on `bind_addr` (`docs/tracker/REFERENCE.md`,
    /// "Surfaces").
    ///
    /// UDP costs one round trip instead of a TCP handshake, which matters for a peer sweeping many
    /// trackers on a cadence. It is a *second* surface, never a replacement: anything that does not
    /// fit the datagram bounds below is served over TCP.
    pub udp_enabled: bool,
    /// Largest accepted UDP request datagram. Deliberately far below `max_frame_bytes`: a datagram
    /// cannot be reassembled incrementally, and a large announce belongs on TCP.
    pub udp_max_request_bytes: usize,
    /// Largest UDP reply the tracker will emit. A larger answer is dropped rather than truncated —
    /// a truncated canonical frame is undecodable, so silence is the honest outcome and the peer
    /// retries over TCP.
    pub udp_max_reply_bytes: usize,
    /// Reply-to-request size ratio ceiling for UDP (`docs/tracker/REFERENCE.md`, "Surfaces").
    ///
    /// UDP source addresses are forgeable, so an unbounded answer would make this service a
    /// reflection amplifier pointed at whoever an attacker names. Capping the ratio bounds the gain
    /// an attacker can buy per spoofed byte; peers needing a bigger answer use TCP, where the
    /// handshake already proves the source address.
    pub udp_max_amplification: usize,
    /// Where to report this service's own counters (`tcp://host:port`).
    ///
    /// **Empty by default, and that means off.** Running this binary is agreement to run a tracker,
    /// not agreement to report to anyone; an operator opts in by setting an endpoint. What is sent
    /// is windowed counters — never a peer, a world, or an address (`docs/tracker/Task.4.md`).
    pub telemetry_endpoint: String,
    /// How often a window event is emitted.
    pub telemetry_interval_seconds: u64,

    // --- service directory (tracker Task 5) ---
    /// Maximum services (rendezvous + trackers) listed at once.
    ///
    /// Far smaller than `max_worlds`: a network has a handful of infrastructure hosts and thousands
    /// of worlds, so a directory that grew to world scale would be evidence of abuse rather than
    /// success.
    pub max_services: usize,
    /// Rows returned in one directory answer when the caller does not ask for fewer.
    pub service_directory_page_limit: usize,
    /// How long a peer's measurement still counts toward a service's score.
    ///
    /// Long enough that a peer probing every few minutes always has a live contribution, short
    /// enough that yesterday's outage does not still be shaping today's routing.
    pub service_report_max_age_seconds: u64,
    /// Distinct reporters retained per service.
    ///
    /// This is the width of the evidence, and therefore how many identities an attacker needs to
    /// control before the median moves. It bounds memory too, but that is the lesser reason.
    pub service_report_max_reporters: usize,
    /// Score reports accepted per source IP per announce interval.
    pub per_ip_report_quota: u32,

    // --- self-update (tracker Task 5) ---
    /// Release channel to update from. **Empty by default, and that means off.**
    ///
    /// Running this binary is agreement to run a tracker, not agreement to let it replace its own
    /// executable. The project's own deployments set `"latest"`.
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
    /// How long in-flight work may hold up a drain before the restart proceeds anyway.
    pub drain_grace_seconds: u64,
    /// Trackers this tracker announces *itself* to, so peers can discover it the same way they
    /// discover a rendezvous. Empty is normal for a single-tracker deployment.
    pub peer_tracker_endpoints: Vec<String>,
    /// Routes this tracker advertises in its own record. Empty falls back to `bind_addr`, which is
    /// wrong behind NAT — an operator on a public host should state the public name.
    pub advertised_routes: Vec<String>,
    /// Where the service signing identity is kept. Relative paths resolve against the working
    /// directory; the file is created on first start and must then be preserved.
    pub identity_file: PathBuf,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25600".parse().expect("valid default bind addr"),
            telemetry_endpoint: String::new(),
            telemetry_interval_seconds: 300,
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
            udp_enabled: true,
            udp_max_request_bytes: 8 * 1024,
            udp_max_reply_bytes: 32 * 1024,
            udp_max_amplification: 4,
            max_services: 256,
            service_directory_page_limit: 32,
            service_report_max_age_seconds: 900,
            service_report_max_reporters: 32,
            per_ip_report_quota: 30,
            update_channel: String::new(),
            update_feed_base_url: nodera_service::update::DEFAULT_FEED_BASE_URL.to_owned(),
            update_check_interval_seconds: 3_600,
            update_release_public_key: nodera_service::update::DEFAULT_RELEASE_PUBLIC_KEY
                .to_owned(),
            drain_grace_seconds: 30,
            peer_tracker_endpoints: Vec::new(),
            advertised_routes: Vec::new(),
            identity_file: PathBuf::from("nodera-tracker-identity.bin"),
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

    /// Overlay `NODERA_TRACKER_*` onto an already-loaded configuration.
    ///
    /// Precedence is defaults, then the file, then this — the environment is the layer the
    /// orchestrator controls, so it has to be able to override the layer a human committed.
    /// Validation runs *after* this, never before: an environment that fixes an out-of-range file
    /// value must be allowed to.
    pub fn apply_env(
        &mut self,
        env: &impl EnvSource,
    ) -> Result<nodera_service::env::Applied, ConfigError> {
        let mut overlay = EnvOverlay::new(ENV_PREFIX, env);
        // Read by the Java peer/worker (`HeadlessPeerMain`), which is a client of trackers, not a
        // tracker. A shell holding both must be able to start both.
        overlay.ignore("NODERA_TRACKER_ENDPOINTS");

        overlay.set("bind_addr", &mut self.bind_addr);
        overlay.set(
            "announce_interval_seconds",
            &mut self.announce_interval_seconds,
        );
        overlay.set("peer_ttl_seconds", &mut self.peer_ttl_seconds);
        overlay.set(
            "announce_clock_skew_seconds",
            &mut self.announce_clock_skew_seconds,
        );
        overlay.set("max_worlds", &mut self.max_worlds);
        overlay.set("max_peers_per_world", &mut self.max_peers_per_world);
        overlay.set("sample_size", &mut self.sample_size);
        overlay.set("seeder_floor", &mut self.seeder_floor);
        overlay.set("healthy_seeder_floor", &mut self.healthy_seeder_floor);
        overlay.set("per_ip_announce_quota", &mut self.per_ip_announce_quota);
        overlay.set("max_frame_bytes", &mut self.max_frame_bytes);
        overlay.set_optional_path("persist_dir", &mut self.persist_dir);
        overlay.set("udp_enabled", &mut self.udp_enabled);
        overlay.set("udp_max_request_bytes", &mut self.udp_max_request_bytes);
        overlay.set("udp_max_reply_bytes", &mut self.udp_max_reply_bytes);
        overlay.set("udp_max_amplification", &mut self.udp_max_amplification);
        overlay.set("telemetry_endpoint", &mut self.telemetry_endpoint);
        overlay.set(
            "telemetry_interval_seconds",
            &mut self.telemetry_interval_seconds,
        );
        overlay.set("max_services", &mut self.max_services);
        overlay.set(
            "service_directory_page_limit",
            &mut self.service_directory_page_limit,
        );
        overlay.set(
            "service_report_max_age_seconds",
            &mut self.service_report_max_age_seconds,
        );
        overlay.set(
            "service_report_max_reporters",
            &mut self.service_report_max_reporters,
        );
        overlay.set("per_ip_report_quota", &mut self.per_ip_report_quota);
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
        overlay.set("drain_grace_seconds", &mut self.drain_grace_seconds);
        overlay.set_list("peer_tracker_endpoints", &mut self.peer_tracker_endpoints);
        overlay.set_list("advertised_routes", &mut self.advertised_routes);
        overlay.set("identity_file", &mut self.identity_file);

        Ok(overlay.finish()?)
    }

    /// Every environment variable this service understands, sorted. For the operator reference.
    pub fn env_reference() -> Vec<String> {
        Config::default()
            .apply_env(&nodera_service::env::MapEnv::empty())
            .expect("an empty environment applies cleanly")
            .declared
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
        if self.udp_enabled {
            for (name, value) in [
                ("udp_max_request_bytes", self.udp_max_request_bytes),
                ("udp_max_reply_bytes", self.udp_max_reply_bytes),
                ("udp_max_amplification", self.udp_max_amplification),
            ] {
                if value == 0 {
                    return Err(ConfigError::Invalid(format!("{name} must be positive")));
                }
            }
            if self.udp_max_request_bytes > self.max_frame_bytes {
                return Err(ConfigError::Invalid(
                    "udp_max_request_bytes must not exceed max_frame_bytes".to_owned(),
                ));
            }
        }
        for (name, value) in [
            ("max_services", self.max_services),
            (
                "service_directory_page_limit",
                self.service_directory_page_limit,
            ),
            (
                "service_report_max_reporters",
                self.service_report_max_reporters,
            ),
        ] {
            if value == 0 {
                return Err(ConfigError::Invalid(format!("{name} must be positive")));
            }
        }
        if self.service_report_max_age_seconds == 0 {
            return Err(ConfigError::Invalid(
                "service_report_max_age_seconds must be positive".to_owned(),
            ));
        }
        if !self.update_channel.trim().is_empty() {
            if self.update_check_interval_seconds == 0 {
                // A zero interval is a hot loop against a release host, which gets the deployment
                // rate-limited rather than updated.
                return Err(ConfigError::Invalid(
                    "update_check_interval_seconds must be positive when update_channel is set"
                        .to_owned(),
                ));
            }
            if !self.update_feed_base_url.starts_with("https://") {
                // The digest in the release is the only integrity check there is; fetching it over
                // plaintext would let whoever is on the path choose both the binary and its digest.
                return Err(ConfigError::Invalid(
                    "update_feed_base_url must be https://".to_owned(),
                ));
            }
        }
        Ok(())
    }

    /// How long a peer's measurement still counts, in milliseconds.
    pub fn service_report_max_age_millis(&self) -> u64 {
        self.service_report_max_age_seconds.saturating_mul(1_000)
    }

    /// The update lane's configuration for this binary.
    pub fn update_config(&self) -> nodera_service::update::UpdateConfig {
        nodera_service::update::UpdateConfig {
            channel: self.update_channel.clone(),
            feed_base_url: self.update_feed_base_url.clone(),
            binary_name: "nodera-tracker".to_owned(),
            check_interval_seconds: self.update_check_interval_seconds,
            drain_grace_seconds: self.drain_grace_seconds,
            release_public_key: self.update_release_public_key.clone(),
        }
    }

    /// The routes this tracker advertises, falling back to its bind address.
    pub fn routes(&self) -> Vec<String> {
        if self.advertised_routes.is_empty() {
            vec![self.bind_addr.to_string()]
        } else {
            self.advertised_routes.clone()
        }
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

    #[test]
    fn every_config_key_has_an_environment_override() {
        // The drift guard. A key that exists in the file and not in the environment is a setting an
        // operator can only reach by bind-mounting a config file into a container — which is the
        // thing this lane exists to remove.
        let config = Config {
            persist_dir: Some(PathBuf::from("/var/lib/nodera")), // `None` serializes to nothing.
            ..Config::default()
        };
        let serialized = toml::Value::try_from(&config).expect("config serializes");
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

        let mut config: Config = toml::from_str("bind_addr = \"127.0.0.1:25600\"\n").unwrap();
        let env = MapEnv::empty()
            .with("NODERA_TRACKER_BIND_ADDR", "0.0.0.0:6969")
            .with(
                "NODERA_TRACKER_ADVERTISED_ROUTES",
                "tcp://a:6969,tcp://b:6969",
            )
            .with("NODERA_TRACKER_UDP_ENABLED", "false");
        let applied = config.apply_env(&env).unwrap();
        config.validate().unwrap();

        assert_eq!(config.bind_addr, "0.0.0.0:6969".parse().unwrap());
        assert_eq!(config.advertised_routes, ["tcp://a:6969", "tcp://b:6969"]);
        assert!(!config.udp_enabled);
        assert_eq!(applied.applied.len(), 3);
    }

    #[test]
    fn an_environment_value_that_breaks_a_bound_is_still_refused() {
        use nodera_service::env::MapEnv;

        // Env-configurability must not become a way around validation.
        let mut config = Config::default();
        config
            .apply_env(&MapEnv::empty().with("NODERA_TRACKER_PEER_TTL_SECONDS", "1"))
            .unwrap();
        assert!(config.validate().is_err());
    }

    #[test]
    fn the_java_peers_tracker_endpoint_variable_does_not_stop_this_service() {
        use nodera_service::env::MapEnv;

        // `NODERA_TRACKER_ENDPOINTS` belongs to the peer. Sharing a shell with one must not be fatal.
        let mut config = Config::default();
        config
            .apply_env(&MapEnv::empty().with("NODERA_TRACKER_ENDPOINTS", "tcp://host:6969"))
            .unwrap();
        assert_eq!(config, Config::default());
    }
}
