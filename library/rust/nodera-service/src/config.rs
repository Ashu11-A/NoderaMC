//! Loading, overlaying and validating a service's operator configuration.
//!
//! Each service owns its own `Config` **struct** — the fields are genuinely different — but the
//! machinery around it was not: three identical `ConfigError` enums, three identical `load`
//! functions, three identical `env_reference` helpers, and three copies of the six steps every
//! `serve` opens with (read the file, overlay the environment, print what it applied, take the
//! flag, validate, bind).
//!
//! ## The asymmetry this deliberately keeps
//!
//! Service configs are `#[serde(deny_unknown_fields, default)]`: an operator's typo in a TOML file
//! must be **loud**, because a mistyped key leaves them believing a limit is in force when nothing
//! is enforcing it. The companion app's `settings.json` does the opposite and accepts unknown keys,
//! because a user's settings file must never be reset by a document a newer build wrote. Both are
//! right, for different readers. Nothing here should be tempted to make them the same.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use tokio::net::TcpListener;

use crate::env::{Applied, EnvSource, SystemEnv};

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
    Env(#[from] crate::env::EnvError),
}

/// Read and deserialize a TOML configuration file.
///
/// Validation is the caller's, because only the service knows what its bounds mean.
pub fn load_toml<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T, ConfigError> {
    let text = std::fs::read_to_string(path).map_err(|source| ConfigError::Io {
        path: path.display().to_string(),
        source,
    })?;
    toml::from_str(&text).map_err(|source| ConfigError::Parse {
        path: path.display().to_string(),
        source,
    })
}

/// What the shared startup path needs of a service's configuration.
///
/// Implemented by [`crate::service_config!`] rather than by hand: every method is either an inherent
/// one each `Config` already has or a one-line field write, so the impl is a shim and writing it out
/// three times would be the duplication this trait exists to remove.
pub trait ServiceConfig: Sized + Default {
    /// Load and validate a file.
    fn load(path: &Path) -> Result<Self, Box<dyn std::error::Error>>;

    /// Overlay this service's `NODERA_<SERVICE>_*` variables. Runs **before** validation, so an
    /// environment that fixes an out-of-range file value is allowed to.
    fn apply_env(&mut self, env: &dyn EnvSource) -> Result<Applied, Box<dyn std::error::Error>>;

    /// Reject values that would disable a bound rather than tune it.
    fn validate(&self) -> Result<(), Box<dyn std::error::Error>>;

    /// Replace the address to listen on (the `--bind` flag).
    ///
    /// Write-only on purpose. [`configure`] is the only generic consumer of this trait and it never
    /// needs to read the address back — each `main` reads its own `bind_addr` **field** at the point
    /// it binds, which for telemetry is thirty lines of unrelated setup later. A matching getter
    /// existed here, was implemented three times by the macro below, and had no caller anywhere.
    fn set_bind_addr(&mut self, addr: SocketAddr);
}

/// Implement [`ServiceConfig`] for a service's `Config` by forwarding to its inherent methods.
#[macro_export]
macro_rules! service_config {
    ($config:ty) => {
        impl $crate::config::ServiceConfig for $config {
            fn load(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
                <$config>::load(path).map_err(Into::into)
            }

            fn apply_env(
                &mut self,
                env: &dyn $crate::env::EnvSource,
            ) -> Result<$crate::env::Applied, Box<dyn std::error::Error>> {
                <$config>::apply_env(self, env).map_err(Into::into)
            }

            fn validate(&self) -> Result<(), Box<dyn std::error::Error>> {
                <$config>::validate(self).map_err(Into::into)
            }

            fn set_bind_addr(&mut self, addr: std::net::SocketAddr) {
                self.bind_addr = addr;
            }
        }
    };
}

/// Defaults, then the file, then the environment, then the flag — then validate.
///
/// Each layer belongs to someone closer to this particular start than the last: the defaults are
/// this build's, the file is a human's, the environment is the orchestrator's, and the flag is
/// whoever typed the command. Only the variable **names** applied from the environment are printed:
/// one of them on the rendezvous side is a key, and a startup log outlives the process that wrote
/// it.
pub fn configure<C: ServiceConfig>(
    name: &str,
    config_path: Option<PathBuf>,
    bind_override: Option<String>,
) -> Result<C, Box<dyn std::error::Error>> {
    let mut config = match config_path {
        Some(path) => C::load(&path)?,
        None => C::default(),
    };
    let from_env = config.apply_env(&SystemEnv)?;
    if !from_env.applied.is_empty() {
        println!(
            "{name}: {} setting(s) from the environment: {}",
            from_env.applied.len(),
            from_env.applied.join(" ")
        );
    }
    if let Some(bind) = bind_override {
        config.set_bind_addr(bind.parse()?);
    }
    config.validate()?;
    Ok(config)
}

/// Bind the listener and announce the address it actually got.
///
/// The line is printed unconditionally: integration tests, and operators binding port `0`, read the
/// real port from it rather than guessing it.
pub async fn bind(name: &str, addr: SocketAddr) -> std::io::Result<(TcpListener, SocketAddr)> {
    let listener = TcpListener::bind(addr).await?;
    let bound = listener.local_addr()?;
    println!("{name}: listening on {bound}");
    Ok((listener, bound))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Default, serde::Deserialize)]
    #[serde(deny_unknown_fields, default)]
    struct Example {
        limit: u32,
    }

    #[test]
    fn a_missing_file_names_the_path_it_could_not_read() {
        let error = load_toml::<Example>(Path::new("/nowhere/nodera-test.toml")).unwrap_err();
        assert!(error.to_string().contains("/nowhere/nodera-test.toml"));
        assert!(matches!(error, ConfigError::Io { .. }));
    }

    /// A typo'd key must not leave the operator believing a limit is in force. This is the property
    /// the app's settings loader deliberately does **not** have — see the module docs.
    #[test]
    fn an_unknown_key_is_refused_rather_than_silently_ignored() {
        let dir = std::env::temp_dir().join(format!("nodera-config-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("service.toml");
        std::fs::write(&path, "limt = 3\n").unwrap();

        let error = load_toml::<Example>(&path).unwrap_err();
        assert!(matches!(error, ConfigError::Parse { .. }));
        assert!(error.to_string().contains("limt"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
