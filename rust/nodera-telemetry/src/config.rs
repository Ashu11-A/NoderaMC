//! Operator configuration (`nodera-telemetry.toml`).
//!
//! Two kinds of setting live here and they are not interchangeable. The **bounds** exist because
//! the service is unauthenticated and internet-facing, exactly like the tracker's. The **privacy
//! settings** — the pseudonymisation rotation period and the geolocation table — decide what the
//! stored data *is*. There is deliberately **no pseudonymisation secret** here: since L-72 retired,
//! the per-period key is minted from the OS CSPRNG and held only in process memory
//! ([`crate::subject`]), so the configuration carries no key material at all and an operator with
//! the full config cannot reproduce a previous period's subjects.

use nodera_service::env::{EnvOverlay, EnvSource};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;

/// Prefix for the environment form of every key below.
pub const ENV_PREFIX: &str = "NODERA_TELEMETRY_";

/// `Serialize` exists so a test can enumerate the keys of a serialized default and fail if any of
/// them has no environment override.
#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, default)]
pub struct Config {
    /// Address to listen on.
    pub bind_addr: SocketAddr,
    /// Where NDJSON batches are spooled for the collector (Vector) to pick up.
    pub spool_dir: PathBuf,
    /// Rotate the spool file once it passes this size.
    pub spool_max_bytes: u64,
    /// …or this age, whichever comes first.
    pub spool_max_seconds: u64,
    /// How many days a pseudonymous subject survives before it rotates. The key behind it is
    /// memory-only — see [`crate::subject::Pseudonymiser`].
    pub subject_rotation_days: u64,
    /// Optional `cidr,country,asn` table for coarse geolocation. Absent means every row is
    /// recorded as `ZZ`/`0`, which is honest and is the default.
    pub geo_table_path: Option<PathBuf>,
    /// Largest accepted frame.
    pub max_frame_bytes: usize,
    pub max_events_per_batch: usize,
    pub max_attrs_per_event: usize,
    /// How far in the past an event timestamp may sit.
    pub max_event_age_seconds: u64,
    /// How far in the future one may sit before it is read as a wrong clock.
    pub max_clock_skew_seconds: u64,
    /// Quota window; `0` on either limit disables that limit.
    pub quota_window_seconds: u64,
    pub per_ip_batch_quota: u32,
    pub per_ip_event_quota: u32,
    /// How often the operator counter line is printed.
    pub report_interval_seconds: u64,
    /// **The deployment declares itself public** (telemetry L-73).
    ///
    /// The listener speaks plaintext and always will: TLS is terminated by the `edge` proxy the
    /// compose stack ships. That arrangement is only worth anything if nothing *else* can reach the
    /// plaintext port, and until now nothing checked. Setting this to `true` says "this service is
    /// published to the internet", and from that moment a connection that did not come through the
    /// declared TLS front is refused rather than served — see [`Config::trusted_proxy_cidrs`].
    ///
    /// The default is `false`, which is the private/single-host case and behaves exactly as before.
    pub public_endpoint: bool,
    /// The address ranges the TLS terminator dials from, as `a.b.c.d/len`.
    ///
    /// Required once [`Config::public_endpoint`] is set, because "public, and everyone may connect
    /// in the clear" is the very condition being closed. Loopback is always admitted regardless, so
    /// a container healthcheck does not need an entry.
    pub trusted_proxy_cidrs: Vec<String>,
}

/// Whether a connection may be served, given what the deployment declared about itself.
///
/// Split out of [`Config`] so the decision is a value that can be tested directly, rather than a
/// condition buried in the accept loop where the only way to observe it is to run a server.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Admission {
    public: bool,
    trusted: Vec<(u128, u32, bool)>,
}

impl Admission {
    /// The policy that admits everything — a private deployment, and the historical behaviour.
    pub fn open() -> Self {
        Self {
            public: false,
            trusted: Vec::new(),
        }
    }

    /// Build from a validated configuration.
    pub fn from_config(config: &Config) -> Result<Self, ConfigError> {
        let mut trusted = Vec::new();
        for raw in &config.trusted_proxy_cidrs {
            trusted.push(parse_cidr(raw).ok_or(ConfigError::BadProxyCidr(raw.clone()))?);
        }
        Ok(Self {
            public: config.public_endpoint,
            trusted,
        })
    }

    /// May this source address be served?
    ///
    /// A private deployment admits everything. A public one admits loopback (healthchecks) and the
    /// declared TLS front, and nothing else: a direct plaintext client on the public endpoint is
    /// refused at accept, before a byte of its frame is read.
    pub fn admits(&self, ip: std::net::IpAddr) -> bool {
        if !self.public {
            return true;
        }
        if ip.is_loopback() {
            return true;
        }
        let (value, v4) = normalise(ip);
        self.trusted.iter().any(|&(net, prefix, net_v4)| {
            net_v4 == v4 && (prefix == 0 || value >> (128 - prefix) == net >> (128 - prefix))
        })
    }
}

/// `a.b.c.d/len` or an IPv6 equivalent → (network as u128, prefix, is-v4).
fn parse_cidr(raw: &str) -> Option<(u128, u32, bool)> {
    let (addr, prefix) = raw.trim().split_once('/')?;
    let ip: std::net::IpAddr = addr.trim().parse().ok()?;
    let prefix: u32 = prefix.trim().parse().ok()?;
    let (value, v4) = normalise(ip);
    let width = if v4 { 32 } else { 128 };
    if prefix > width {
        return None;
    }
    // Shift into the 128-bit space the comparison uses.
    let prefix = prefix + if v4 { 96 } else { 0 };
    Some((value, prefix, v4))
}

/// An address as a `u128` plus its family, with IPv4-mapped IPv6 folded onto IPv4 — the same
/// normalisation [`crate::geo`] uses, so one address never matches under two families.
fn normalise(ip: std::net::IpAddr) -> (u128, bool) {
    match ip {
        std::net::IpAddr::V4(v4) => (u32::from(v4) as u128, true),
        std::net::IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => (u32::from(v4) as u128, true),
            None => (u128::from(v6), false),
        },
    }
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25620".parse().expect("valid default bind addr"),
            spool_dir: PathBuf::from("telemetry-spool"),
            spool_max_bytes: 64 * 1024 * 1024,
            spool_max_seconds: 300,
            subject_rotation_days: 1,
            geo_table_path: None,
            max_frame_bytes: 1024 * 1024,
            max_events_per_batch: 500,
            max_attrs_per_event: 32,
            max_event_age_seconds: 7 * 24 * 3_600,
            max_clock_skew_seconds: 300,
            quota_window_seconds: 60,
            per_ip_batch_quota: 30,
            per_ip_event_quota: 5_000,
            report_interval_seconds: 60,
            public_endpoint: false,
            trusted_proxy_cidrs: Vec::new(),
        }
    }
}

/// Why a configuration was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ConfigError {
    #[error("{0} must be greater than zero")]
    MustBePositive(&'static str),
    #[error(
        "public_endpoint is set but trusted_proxy_cidrs is empty: this service speaks plaintext, \
         so declaring it public without naming the TLS terminator that fronts it would publish an \
         unencrypted listener to the internet"
    )]
    PublicWithoutTlsFront,
    #[error("trusted_proxy_cidrs entry {0:?} is not an address/prefix")]
    BadProxyCidr(String),
    /// An environment variable was unusable, or named nothing this service has.
    #[error("invalid environment configuration: {0}")]
    Env(#[from] nodera_service::env::EnvError),
}

impl Config {
    /// Apply `NODERA_TELEMETRY_*` overrides.
    ///
    /// **This widens a deliberately narrow rule, so the reasoning is worth restating.** The earlier
    /// version overrode only a single key, on the grounds that everything else is an operator
    /// policy decision belonging in a file someone can review rather than in a process environment
    /// nobody can reconstruct afterwards. That argument holds for a host an operator administers
    /// and fails for a container: an image whose only configuration path is a file is an image that
    /// cannot be run without one, and "review the file" is not available to someone composing a
    /// deployment out of published images.
    ///
    /// What actually preserved the reviewability was never the *file*; it was that the values are
    /// recorded somewhere durable. A compose file or a unit file is exactly as reviewable as a TOML
    /// file, and is the artefact those operators already keep in version control. So the rule
    /// becomes: every key is overridable, unknown variables are refused rather than ignored
    /// (`nodera_service::env`), and the startup log names which variables took effect — never their
    /// values, because one of them could be an operator secret for an adjacent service.
    pub fn apply_env(
        &mut self,
        env: &impl EnvSource,
    ) -> Result<nodera_service::env::Applied, ConfigError> {
        let mut overlay = EnvOverlay::new(ENV_PREFIX, env);
        overlay.set("bind_addr", &mut self.bind_addr);
        overlay.set("spool_dir", &mut self.spool_dir);
        overlay.set("spool_max_bytes", &mut self.spool_max_bytes);
        overlay.set("spool_max_seconds", &mut self.spool_max_seconds);
        overlay.set("subject_rotation_days", &mut self.subject_rotation_days);
        overlay.set_optional_path("geo_table_path", &mut self.geo_table_path);
        overlay.set("max_frame_bytes", &mut self.max_frame_bytes);
        overlay.set("max_events_per_batch", &mut self.max_events_per_batch);
        overlay.set("max_attrs_per_event", &mut self.max_attrs_per_event);
        overlay.set("max_event_age_seconds", &mut self.max_event_age_seconds);
        overlay.set("max_clock_skew_seconds", &mut self.max_clock_skew_seconds);
        overlay.set("quota_window_seconds", &mut self.quota_window_seconds);
        overlay.set("per_ip_batch_quota", &mut self.per_ip_batch_quota);
        overlay.set("per_ip_event_quota", &mut self.per_ip_event_quota);
        overlay.set("report_interval_seconds", &mut self.report_interval_seconds);
        overlay.set("public_endpoint", &mut self.public_endpoint);
        overlay.set_list("trusted_proxy_cidrs", &mut self.trusted_proxy_cidrs);
        overlay.finish().map_err(ConfigError::Env)
    }

    /// Every environment variable this service understands, sorted. For the operator reference.
    pub fn env_reference() -> Vec<String> {
        Config::default()
            .apply_env(&nodera_service::env::MapEnv::empty())
            .expect("an empty environment applies cleanly")
            .declared
    }

    pub fn load(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
        let text = std::fs::read_to_string(path)?;
        Ok(toml::from_str(&text)?)
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.max_frame_bytes == 0 {
            return Err(ConfigError::MustBePositive("max_frame_bytes"));
        }
        if self.max_events_per_batch == 0 {
            return Err(ConfigError::MustBePositive("max_events_per_batch"));
        }
        if self.max_attrs_per_event == 0 {
            return Err(ConfigError::MustBePositive("max_attrs_per_event"));
        }
        // L-73: a public declaration is only meaningful if it is enforceable, and it is only
        // enforceable if the operator says which front-end may reach the plaintext port.
        if self.public_endpoint && self.trusted_proxy_cidrs.is_empty() {
            return Err(ConfigError::PublicWithoutTlsFront);
        }
        Admission::from_config(self)?;
        Ok(())
    }

    /// The validation bounds, in the units [`crate::event`] works in.
    pub fn bounds(&self) -> crate::event::Bounds {
        crate::event::Bounds {
            max_events_per_batch: self.max_events_per_batch,
            max_attrs_per_event: self.max_attrs_per_event,
            max_event_age_millis: self.max_event_age_seconds.saturating_mul(1_000),
            max_clock_skew_millis: self.max_clock_skew_seconds.saturating_mul(1_000),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A configuration that validates, as the base for `..valid()` struct updates.
    ///
    /// Since the pseudonymisation secret left the schema (L-72), the default *is* a valid
    /// configuration — `the_default_configuration_validates` below is what keeps that true.
    fn valid() -> Config {
        Config::default()
    }

    #[test]
    fn the_default_configuration_validates() {
        // The pseudonymisation key is minted in memory from the OS CSPRNG (see `subject.rs`), so a
        // default configuration with no secret set is a valid one. The old "refuses to start
        // without a subject_secret" check is gone with L-72: there is no persistent secret to set.
        assert!(Config::default().validate().is_ok());
    }

    #[test]
    fn an_unknown_key_is_a_parse_error_rather_than_a_silently_ignored_setting() {
        let err = toml::from_str::<Config>("bind_add = \"0.0.0.0:1\"\n").unwrap_err();
        assert!(err.to_string().contains("bind_add"));
    }

    #[test]
    fn a_partial_file_keeps_the_defaults_for_everything_else() {
        let config: Config = toml::from_str(
            r#"
            bind_addr = "127.0.0.1:25620"
            subject_rotation_days = 7
            "#,
        )
        .unwrap();
        assert_eq!(config.subject_rotation_days, 7);
        assert_eq!(config.per_ip_batch_quota, 30);
        assert_eq!(config.spool_max_seconds, 300);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn bounds_are_converted_to_milliseconds() {
        let bounds = Config::default().bounds();
        assert_eq!(bounds.max_clock_skew_millis, 300_000);
        assert_eq!(bounds.max_event_age_millis, 7 * 24 * 3_600_000);
    }

    #[test]
    fn every_config_key_has_an_environment_override() {
        let config = Config {
            geo_table_path: Some(PathBuf::from("/etc/nodera/geo.csv")), // `None` serializes away.
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
    fn a_misspelled_telemetry_variable_refuses_the_start() {
        use nodera_service::env::MapEnv;

        // `SPOOL_MAX_BYTE` looks as authoritative in a compose file as the real name.
        let mut config = Config::default();
        assert!(config
            .apply_env(&MapEnv::empty().with("NODERA_TELEMETRY_SPOOL_MAX_BYTE", "1"))
            .is_err());
    }

    /// L-73: declaring the endpoint public without naming a TLS front is refused at boot.
    ///
    /// The failure this closes is not exotic — it is copying the example config onto a public host
    /// and getting an unencrypted listener that serves anyone who finds the port.
    #[test]
    fn declaring_the_endpoint_public_without_a_tls_front_refuses_the_start() {
        let config = Config {
            public_endpoint: true,
            ..valid()
        };
        assert_eq!(
            config.validate().unwrap_err(),
            ConfigError::PublicWithoutTlsFront
        );
    }

    #[test]
    fn an_unparseable_trusted_range_is_refused_rather_than_ignored() {
        let config = Config {
            public_endpoint: true,
            trusted_proxy_cidrs: vec!["203.0.113.0".to_owned()], // no prefix
            ..valid()
        };
        assert_eq!(
            config.validate().unwrap_err(),
            ConfigError::BadProxyCidr("203.0.113.0".to_owned())
        );
    }

    #[test]
    fn a_public_endpoint_admits_only_the_tls_front_and_loopback() {
        let config = Config {
            public_endpoint: true,
            trusted_proxy_cidrs: vec!["203.0.113.0/24".to_owned(), "2001:db8::/32".to_owned()],
            ..valid()
        };
        config.validate().unwrap();
        let admission = Admission::from_config(&config).unwrap();

        assert!(admission.admits("203.0.113.7".parse().unwrap()), "the front");
        assert!(admission.admits("127.0.0.1".parse().unwrap()), "healthcheck");
        assert!(admission.admits("2001:db8::5".parse().unwrap()), "v6 front");
        assert!(
            !admission.admits("198.51.100.9".parse().unwrap()),
            "a direct plaintext client must not be served on a public endpoint"
        );
        assert!(
            !admission.admits("203.0.114.7".parse().unwrap()),
            "one range off is still off"
        );
        // A v4 address must not match a v6 range that happens to share a numeric prefix.
        assert!(!admission.admits("2001:dba::5".parse().unwrap()));
    }

    #[test]
    fn a_private_deployment_is_unchanged_and_admits_everything() {
        let admission = Admission::from_config(&valid()).unwrap();
        assert!(admission.admits("198.51.100.9".parse().unwrap()));
        assert_eq!(admission, Admission::open());
    }

    #[test]
    fn a_zero_bound_that_would_disable_validation_is_refused() {
        let config = Config {
            max_events_per_batch: 0,
            ..Config::default()
        };
        assert_eq!(
            config.validate().unwrap_err(),
            ConfigError::MustBePositive("max_events_per_batch")
        );
    }

    #[test]
    fn a_subject_secret_in_the_file_is_refused_as_unknown_rather_than_silently_honoured() {
        // L-72 removed `subject_secret` from the schema. A deployment that still carries one (a
        // stale compose mount, an unreleased operator note) hits `deny_unknown_fields` here and is
        // told so, rather than silently collecting a value the service no longer reads. That is the
        // honest failure: the privacy model changed, and a quiet no-op would hide it.
        let err = toml::from_str::<Config>(
            r#"
            bind_addr = "127.0.0.1:25620"
            subject_secret = "stale-deployment-secret"
            "#,
        )
        .unwrap_err();
        assert!(
            err.to_string().contains("subject_secret"),
            "the stale key must be named in the refusal: {err}"
        );
    }
}
