//! Operator configuration (`nodera-telemetry.toml`).
//!
//! Two kinds of setting live here and they are not interchangeable. The **bounds** exist because
//! the service is unauthenticated and internet-facing, exactly like the tracker's. The **privacy
//! settings** — the pseudonymisation secret, its rotation period, the geolocation table — decide
//! what the stored data *is*, and one of them (`subject_secret`) has no safe default at all, so
//! the service refuses to start until an operator sets it.

use nodera_service::env::{EnvOverlay, EnvSource};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::PathBuf;

/// Prefix for the environment form of every key below.
pub const ENV_PREFIX: &str = "NODERA_TELEMETRY_";

/// The placeholder shipped in the example configuration. Refused explicitly so that copying the
/// example and forgetting to edit it fails loudly at boot rather than quietly producing subjects
/// every other deployment can also compute.
pub const PLACEHOLDER_SECRET: &str = "change-me";

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
    /// HMAC key behind [`crate::subject`]. **No default that works** — see [`PLACEHOLDER_SECRET`].
    pub subject_secret: String,
    /// How many days a pseudonymous subject survives before it rotates.
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
}

impl Default for Config {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:25620".parse().expect("valid default bind addr"),
            spool_dir: PathBuf::from("telemetry-spool"),
            spool_max_bytes: 64 * 1024 * 1024,
            spool_max_seconds: 300,
            subject_secret: String::new(),
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
        }
    }
}

/// Why a configuration was refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ConfigError {
    #[error(
        "subject_secret is unset: telemetry cannot be pseudonymised without one. \
         Generate 32+ random characters and keep them out of the warehouse."
    )]
    MissingSecret,
    #[error("subject_secret is still the example placeholder {PLACEHOLDER_SECRET:?}")]
    PlaceholderSecret,
    #[error("subject_secret is shorter than 16 characters")]
    WeakSecret,
    #[error("{0} must be greater than zero")]
    MustBePositive(&'static str),
    /// An environment variable was unusable, or named nothing this service has.
    #[error("invalid environment configuration: {0}")]
    Env(#[from] nodera_service::env::EnvError),
}

/// Environment variable that overrides [`Config::subject_secret`].
///
/// A secret in a config file is a secret in a bind mount, in a backup, and eventually in a
/// screenshot. The container deployment passes it this way so the file on disk stays publishable.
///
/// This is now one key of the general scheme rather than a special case, and the name is unchanged
/// because it was already `ENV_PREFIX` + the TOML key.
pub const SECRET_ENV: &str = "NODERA_TELEMETRY_SUBJECT_SECRET";

impl Config {
    /// Apply `NODERA_TELEMETRY_*` overrides.
    ///
    /// **This widens a deliberately narrow rule, so the reasoning is worth restating.** The earlier
    /// version overrode only [`SECRET_ENV`], on the grounds that everything else is an operator
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
    /// values, because one of them is this service's pseudonymisation secret.
    pub fn apply_env(
        &mut self,
        env: &impl EnvSource,
    ) -> Result<nodera_service::env::Applied, ConfigError> {
        let mut overlay = EnvOverlay::new(ENV_PREFIX, env);
        overlay.set("bind_addr", &mut self.bind_addr);
        overlay.set("spool_dir", &mut self.spool_dir);
        overlay.set("spool_max_bytes", &mut self.spool_max_bytes);
        overlay.set("spool_max_seconds", &mut self.spool_max_seconds);
        // `set_non_empty`, not `set`: an empty value here has no meaning, and a compose file
        // referencing an unset variable passes exactly that. Blanking the secret refuses the start.
        overlay.set_non_empty("subject_secret", &mut self.subject_secret);
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
        if self.subject_secret.is_empty() {
            return Err(ConfigError::MissingSecret);
        }
        if self.subject_secret == PLACEHOLDER_SECRET {
            return Err(ConfigError::PlaceholderSecret);
        }
        if self.subject_secret.len() < 16 {
            return Err(ConfigError::WeakSecret);
        }
        if self.max_frame_bytes == 0 {
            return Err(ConfigError::MustBePositive("max_frame_bytes"));
        }
        if self.max_events_per_batch == 0 {
            return Err(ConfigError::MustBePositive("max_events_per_batch"));
        }
        if self.max_attrs_per_event == 0 {
            return Err(ConfigError::MustBePositive("max_attrs_per_event"));
        }
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

    fn valid() -> Config {
        Config {
            subject_secret: "0123456789abcdef0123".to_owned(),
            ..Config::default()
        }
    }

    #[test]
    fn the_default_configuration_refuses_to_start() {
        assert_eq!(
            Config::default().validate().unwrap_err(),
            ConfigError::MissingSecret
        );
    }

    #[test]
    fn the_example_placeholder_is_refused_by_name() {
        let config = Config {
            subject_secret: PLACEHOLDER_SECRET.to_owned(),
            ..Config::default()
        };
        assert_eq!(
            config.validate().unwrap_err(),
            ConfigError::PlaceholderSecret
        );
    }

    #[test]
    fn a_short_secret_is_refused() {
        let config = Config {
            subject_secret: "short".to_owned(),
            ..Config::default()
        };
        assert_eq!(config.validate().unwrap_err(), ConfigError::WeakSecret);
    }

    #[test]
    fn a_realistic_configuration_validates() {
        assert!(valid().validate().is_ok());
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
            subject_secret = "0123456789abcdef0123"
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
        let bounds = valid().bounds();
        assert_eq!(bounds.max_clock_skew_millis, 300_000);
        assert_eq!(bounds.max_event_age_millis, 7 * 24 * 3_600_000);
    }

    #[test]
    fn the_secret_can_come_from_the_environment_so_it_need_not_sit_in_a_file() {
        use nodera_service::env::MapEnv;

        let mut config = Config::default();
        config
            .apply_env(&MapEnv::empty().with(SECRET_ENV, "an-environment-provided-secret"))
            .unwrap();
        assert_eq!(config.subject_secret, "an-environment-provided-secret");
        assert!(config.validate().is_ok());

        // An empty value must not silently blank a secret the file did provide: a compose file
        // referencing an unset variable passes the empty string, and that must not be an outage.
        let mut kept = valid();
        let before = kept.subject_secret.clone();
        kept.apply_env(&MapEnv::empty().with(SECRET_ENV, "   "))
            .unwrap();
        assert_eq!(kept.subject_secret, before);
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
        let mut config = valid();
        assert!(config
            .apply_env(&MapEnv::empty().with("NODERA_TELEMETRY_SPOOL_MAX_BYTE", "1"))
            .is_err());
    }

    #[test]
    fn a_zero_bound_that_would_disable_validation_is_refused() {
        let config = Config {
            max_events_per_batch: 0,
            ..valid()
        };
        assert_eq!(
            config.validate().unwrap_err(),
            ConfigError::MustBePositive("max_events_per_batch")
        );
    }
}
