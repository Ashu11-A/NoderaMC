//! Configuring a service from its environment, with the same strictness the TOML loader has.
//!
//! Both services were file-configured only, which is right for a host an operator administers and
//! wrong for a container: an image that needs a config file baked in or bind-mounted is an image
//! nobody can run from a single `docker run` line. So the environment becomes a third layer, and the
//! precedence is the obvious one — **defaults, then the file, then the environment** — because the
//! environment is the layer an orchestrator controls and the file is the layer a human wrote.
//!
//! ## Unknown variables are refused, exactly like unknown TOML keys
//!
//! The TOML loaders use `deny_unknown_fields`, and the reason is written down in
//! `nodera-tracker/src/config.rs`: *a typo'd key must not leave the operator believing a limit is in
//! force.* An environment variable is more prone to that failure, not less — there is no file to
//! re-read, no schema, and a mistyped `NODERA_TRACKER_MAX_PEER_PER_WORLD` looks exactly as
//! authoritative in a compose file as the correct spelling. So [`EnvOverlay::finish`] fails on any
//! `NODERA_<SERVICE>_*` variable that no field claimed.
//!
//! That strictness has one deliberate exception, [`EnvOverlay::ignore`]: a handful of names in these
//! prefixes already belong to *other* components (`NODERA_TRACKER_ENDPOINTS` is read by the Java
//! peer, not by the tracker service). A shell that legitimately holds both must not be unable to
//! start either, so those names are declared as belonging elsewhere rather than silently tolerated.
//!
//! ## Values are never echoed
//!
//! Every error here names the variable and the type expected, never the value. One of these
//! variables is a telemetry subject secret, and an error message is the one place a secret escapes
//! into a log that outlives the process.

use std::collections::{BTreeMap, BTreeSet};
use std::net::SocketAddr;
use std::path::PathBuf;

/// Where environment values are read from.
///
/// A trait rather than direct `std::env` calls because process environment is global mutable state:
/// tests that set it race each other, and a test that races is a test nobody trusts.
pub trait EnvSource {
    /// The value of one variable.
    fn get(&self, key: &str) -> Option<String>;

    /// Every variable name beginning with `prefix`.
    ///
    /// Needed for the unknown-variable check: refusing a typo means knowing what was actually set,
    /// not only what was asked for.
    fn keys_with_prefix(&self, prefix: &str) -> Vec<String>;
}

/// The real process environment.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemEnv;

impl EnvSource for SystemEnv {
    fn get(&self, key: &str) -> Option<String> {
        std::env::var(key).ok()
    }

    fn keys_with_prefix(&self, prefix: &str) -> Vec<String> {
        std::env::vars()
            .map(|(key, _)| key)
            .filter(|key| key.starts_with(prefix))
            .collect()
    }
}

/// A fixed environment, for tests and for rendering an operator reference.
#[derive(Debug, Clone, Default)]
pub struct MapEnv {
    vars: BTreeMap<String, String>,
}

impl MapEnv {
    /// An environment holding nothing.
    pub fn empty() -> Self {
        Self::default()
    }

    /// Add one variable.
    #[must_use]
    pub fn with(mut self, key: &str, value: &str) -> Self {
        self.vars.insert(key.to_owned(), value.to_owned());
        self
    }
}

impl EnvSource for MapEnv {
    fn get(&self, key: &str) -> Option<String> {
        self.vars.get(key).cloned()
    }

    fn keys_with_prefix(&self, prefix: &str) -> Vec<String> {
        self.vars
            .keys()
            .filter(|key| key.starts_with(prefix))
            .cloned()
            .collect()
    }
}

/// Why an environment-configured service refused to start.
#[derive(Debug, Clone, thiserror::Error, PartialEq, Eq)]
pub enum EnvError {
    /// A variable was set to something that is not the type the field holds.
    ///
    /// The offending value is deliberately absent: one of these variables is a secret.
    #[error("{key} is not a valid {expected}")]
    Invalid {
        /// The full variable name.
        key: String,
        /// What the field needed.
        expected: &'static str,
    },
    /// Variables in this service's prefix that no field claims.
    #[error("unrecognised environment variables: {}", .keys.join(", "))]
    Unknown {
        /// The full variable names, sorted.
        keys: Vec<String>,
    },
}

/// What an overlay did, for the startup log and for the drift tests.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Applied {
    /// Every variable this service understands, whether or not it was set. Sorted.
    pub declared: Vec<String>,
    /// The variables that were set and took effect. Sorted. **Names only** — see the module docs.
    pub applied: Vec<String>,
}

/// Applies `NODERA_<SERVICE>_*` variables onto an already-loaded configuration.
///
/// Built once per start, driven by one `set` call per configuration field, and finished exactly
/// once. The first failure is retained and reported by [`finish`](Self::finish) rather than
/// panicking mid-way, so an operator with three broken variables learns about the first one and not
/// about a partially-applied configuration.
pub struct EnvOverlay<'a> {
    prefix: &'a str,
    present: BTreeMap<String, String>,
    declared: BTreeSet<String>,
    applied: BTreeSet<String>,
    failure: Option<EnvError>,
}

impl<'a> EnvOverlay<'a> {
    /// Snapshot the environment for one service prefix.
    ///
    /// `prefix` includes its trailing underscore (`"NODERA_TRACKER_"`).
    pub fn new(prefix: &'a str, env: &impl EnvSource) -> Self {
        let present = env
            .keys_with_prefix(prefix)
            .into_iter()
            .filter_map(|key| env.get(&key).map(|value| (key, value)))
            .collect();
        Self {
            prefix,
            present,
            declared: BTreeSet::new(),
            applied: BTreeSet::new(),
            failure: None,
        }
    }

    /// Apply one field, named by its **TOML key**.
    ///
    /// The variable is that key uppercased under the prefix, with no other transformation, so the
    /// file and the environment can never name the same setting two different ways.
    pub fn set<T: FromEnv>(&mut self, toml_key: &str, slot: &mut T) {
        let key = self.declare(toml_key);
        let Some(raw) = self.present.get(&key).cloned() else {
            return;
        };
        match T::parse(raw.trim()) {
            Some(value) => {
                *slot = value;
                self.applied.insert(key);
            }
            None => self.fail(EnvError::Invalid {
                key,
                expected: T::EXPECTED,
            }),
        }
    }

    /// Apply a field for which the empty string is **not** a meaningful value, so an empty variable
    /// means "unset" and leaves the loaded value alone.
    ///
    /// Reserve this for settings where blanking would be an outage rather than an instruction. The
    /// motivating case: a compose file that references an unset variable passes the empty string,
    /// and reading that as "clear the pseudonymisation secret" turns a typo in someone's `.env`
    /// into a service that will not start. Settings where empty genuinely means *off*
    /// (`update_channel`, `telemetry_endpoint`) must keep using [`set`](Self::set).
    pub fn set_non_empty(&mut self, toml_key: &str, slot: &mut String) {
        let key = self.declare(toml_key);
        let Some(raw) = self.present.get(&key).cloned() else {
            return;
        };
        if raw.trim().is_empty() {
            return;
        }
        *slot = raw;
        self.applied.insert(key);
    }

    /// Apply a comma-separated list field.
    ///
    /// Empty entries are dropped, so a trailing comma and a stray space are typos the operator does
    /// not have to care about — unlike a misspelled variable name, neither can hide a setting.
    pub fn set_list(&mut self, toml_key: &str, slot: &mut Vec<String>) {
        let key = self.declare(toml_key);
        let Some(raw) = self.present.get(&key).cloned() else {
            return;
        };
        *slot = raw
            .split(',')
            .map(str::trim)
            .filter(|entry| !entry.is_empty())
            .map(ToOwned::to_owned)
            .collect();
        self.applied.insert(key);
    }

    /// Apply an optional path field, where the empty string means "unset".
    pub fn set_optional_path(&mut self, toml_key: &str, slot: &mut Option<PathBuf>) {
        let key = self.declare(toml_key);
        let Some(raw) = self.present.get(&key).cloned() else {
            return;
        };
        let trimmed = raw.trim();
        *slot = if trimmed.is_empty() {
            None
        } else {
            Some(PathBuf::from(trimmed))
        };
        self.applied.insert(key);
    }

    /// Declare that a variable in this prefix belongs to a different component.
    ///
    /// Takes the **full** variable name, because these are exactly the names that do not follow the
    /// prefix-plus-TOML-key rule. Pass the reason as a comment at the call site: an entry here is a
    /// naming collision somebody has to understand later.
    pub fn ignore(&mut self, full_key: &str) {
        self.present.remove(full_key);
    }

    /// Finish, failing on the first bad value or on any unclaimed variable.
    pub fn finish(self) -> Result<Applied, EnvError> {
        if let Some(failure) = self.failure {
            return Err(failure);
        }
        let unknown: Vec<String> = self
            .present
            .keys()
            .filter(|key| !self.declared.contains(*key))
            .cloned()
            .collect();
        if !unknown.is_empty() {
            return Err(EnvError::Unknown { keys: unknown });
        }
        Ok(Applied {
            declared: self.declared.into_iter().collect(),
            applied: self.applied.into_iter().collect(),
        })
    }

    fn declare(&mut self, toml_key: &str) -> String {
        let key = format!("{}{}", self.prefix, toml_key.to_uppercase());
        self.declared.insert(key.clone());
        key
    }

    fn fail(&mut self, error: EnvError) {
        if self.failure.is_none() {
            self.failure = Some(error);
        }
    }
}

/// A configuration field that can be read from one environment string.
pub trait FromEnv: Sized {
    /// What to call this type in an error message.
    const EXPECTED: &'static str;

    /// Parse, or `None` if the value is not this type.
    fn parse(raw: &str) -> Option<Self>;
}

macro_rules! from_env_via_fromstr {
    ($type:ty, $expected:literal) => {
        impl FromEnv for $type {
            const EXPECTED: &'static str = $expected;

            fn parse(raw: &str) -> Option<Self> {
                raw.parse().ok()
            }
        }
    };
}

from_env_via_fromstr!(SocketAddr, "socket address (host:port)");
from_env_via_fromstr!(u32, "whole number");
from_env_via_fromstr!(u64, "whole number");
from_env_via_fromstr!(usize, "whole number");

impl FromEnv for String {
    const EXPECTED: &'static str = "string";

    fn parse(raw: &str) -> Option<Self> {
        Some(raw.to_owned())
    }
}

impl FromEnv for PathBuf {
    const EXPECTED: &'static str = "path";

    fn parse(raw: &str) -> Option<Self> {
        Some(PathBuf::from(raw))
    }
}

impl FromEnv for bool {
    const EXPECTED: &'static str = "boolean (true/false, 1/0)";

    fn parse(raw: &str) -> Option<Self> {
        // `1`/`0` are accepted alongside the words because that is what a compose file written by
        // someone used to other images will contain. Nothing looser: `on`, `yes` and `y` differ
        // between ecosystems, and a bound that silently reads as `false` is the failure this whole
        // module exists to prevent.
        match raw.to_ascii_lowercase().as_str() {
            "true" | "1" => Some(true),
            "false" | "0" => Some(false),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn overlay_of(env: &MapEnv) -> EnvOverlay<'static> {
        EnvOverlay::new("NODERA_TEST_", env)
    }

    #[test]
    fn a_variable_overrides_the_value_the_file_produced() {
        let env = MapEnv::empty().with("NODERA_TEST_BIND_ADDR", "0.0.0.0:6969");
        let mut overlay = overlay_of(&env);
        let mut bind: SocketAddr = "127.0.0.1:25600".parse().unwrap();
        overlay.set("bind_addr", &mut bind);
        let applied = overlay.finish().unwrap();

        assert_eq!(bind, "0.0.0.0:6969".parse::<SocketAddr>().unwrap());
        assert_eq!(applied.applied, vec!["NODERA_TEST_BIND_ADDR".to_owned()]);
    }

    #[test]
    fn an_unset_variable_leaves_the_file_value_alone() {
        let env = MapEnv::empty();
        let mut overlay = overlay_of(&env);
        let mut bind: SocketAddr = "127.0.0.1:25600".parse().unwrap();
        overlay.set("bind_addr", &mut bind);
        let applied = overlay.finish().unwrap();

        assert_eq!(bind, "127.0.0.1:25600".parse::<SocketAddr>().unwrap());
        assert!(applied.applied.is_empty());
        // Declared regardless of whether it was set: this is the operator reference.
        assert_eq!(applied.declared, vec!["NODERA_TEST_BIND_ADDR".to_owned()]);
    }

    #[test]
    fn a_misspelled_variable_is_refused_rather_than_silently_ignored() {
        // The whole point. `MAX_PEER` instead of `MAX_PEERS` must not start a service that looks
        // configured and is not.
        let env = MapEnv::empty().with("NODERA_TEST_MAX_PEER", "10");
        let mut overlay = overlay_of(&env);
        let mut peers: usize = 5;
        overlay.set("max_peers", &mut peers);

        assert_eq!(
            overlay.finish(),
            Err(EnvError::Unknown {
                keys: vec!["NODERA_TEST_MAX_PEER".to_owned()]
            })
        );
        assert_eq!(peers, 5);
    }

    #[test]
    fn a_variable_belonging_to_another_component_is_not_an_error() {
        let env = MapEnv::empty().with("NODERA_TEST_ENDPOINTS", "host:1");
        let mut overlay = overlay_of(&env);
        overlay.ignore("NODERA_TEST_ENDPOINTS");
        let applied = overlay.finish().unwrap();

        assert!(applied.applied.is_empty());
        // And it is not advertised as ours either.
        assert!(applied.declared.is_empty());
    }

    #[test]
    fn a_bad_value_names_the_variable_and_never_the_value() {
        let env = MapEnv::empty().with("NODERA_TEST_SUBJECT_SECRET_PORT", "not-a-port");
        let mut overlay = overlay_of(&env);
        let mut port: u32 = 1;
        overlay.set("subject_secret_port", &mut port);
        let error = overlay.finish().unwrap_err();

        let rendered = error.to_string();
        assert!(
            rendered.contains("NODERA_TEST_SUBJECT_SECRET_PORT"),
            "{rendered}"
        );
        assert!(
            !rendered.contains("not-a-port"),
            "the value leaked: {rendered}"
        );
    }

    #[test]
    fn the_first_bad_value_is_reported_not_the_last() {
        let env = MapEnv::empty()
            .with("NODERA_TEST_ALPHA", "x")
            .with("NODERA_TEST_BETA", "y");
        let mut overlay = overlay_of(&env);
        let (mut alpha, mut beta) = (1u32, 2u32);
        overlay.set("alpha", &mut alpha);
        overlay.set("beta", &mut beta);

        assert_eq!(
            overlay.finish(),
            Err(EnvError::Invalid {
                key: "NODERA_TEST_ALPHA".to_owned(),
                expected: <u32 as FromEnv>::EXPECTED,
            })
        );
    }

    #[test]
    fn a_list_splits_on_commas_and_tolerates_spacing() {
        let env = MapEnv::empty().with("NODERA_TEST_ROUTES", " a:1 , b:2 ,, ");
        let mut overlay = overlay_of(&env);
        let mut routes = vec!["old".to_owned()];
        overlay.set_list("routes", &mut routes);
        overlay.finish().unwrap();

        assert_eq!(routes, vec!["a:1".to_owned(), "b:2".to_owned()]);
    }

    #[test]
    fn an_empty_list_variable_clears_the_file_value() {
        // Setting a variable to nothing is an instruction, not an omission: an image that inherits
        // a config file needs some way to say "none of those".
        let env = MapEnv::empty().with("NODERA_TEST_ROUTES", "");
        let mut overlay = overlay_of(&env);
        let mut routes = vec!["old".to_owned()];
        overlay.set_list("routes", &mut routes);
        overlay.finish().unwrap();

        assert!(routes.is_empty());
    }

    #[test]
    fn an_empty_optional_path_means_unset() {
        let env = MapEnv::empty().with("NODERA_TEST_PERSIST_DIR", "");
        let mut overlay = overlay_of(&env);
        let mut dir = Some(PathBuf::from("/var/lib/nodera"));
        overlay.set_optional_path("persist_dir", &mut dir);
        overlay.finish().unwrap();

        assert_eq!(dir, None);
    }

    #[test]
    fn booleans_accept_words_and_digits_and_refuse_everything_else() {
        assert_eq!(bool::parse("true"), Some(true));
        assert_eq!(bool::parse("TRUE"), Some(true));
        assert_eq!(bool::parse("1"), Some(true));
        assert_eq!(bool::parse("false"), Some(false));
        assert_eq!(bool::parse("0"), Some(false));
        // Looser spellings differ between ecosystems; reading one as `false` would disable a bound.
        assert_eq!(bool::parse("yes"), None);
        assert_eq!(bool::parse(""), None);
    }

    #[test]
    fn a_string_variable_may_legitimately_be_empty() {
        // `update_channel = ""` and `telemetry_endpoint = ""` both mean "off", and an operator must
        // be able to say that from the environment.
        let env = MapEnv::empty().with("NODERA_TEST_UPDATE_CHANNEL", "");
        let mut overlay = overlay_of(&env);
        let mut channel = "latest".to_owned();
        overlay.set("update_channel", &mut channel);
        overlay.finish().unwrap();

        assert!(channel.is_empty());
    }
}
