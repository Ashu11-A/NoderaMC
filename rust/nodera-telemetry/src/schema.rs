//! The event registry — the **allow-list** that decides what may be stored at all.
//!
//! This file is the privacy contract in executable form. Nothing reaches the sink that is not
//! named here, so "what does NoderaMC collect?" has exactly one answer and it is a source file a
//! reader can check. Three rules make that claim hold:
//!
//! 1. **An event name that is not in the registry is rejected**, batch-and-all reasons counted.
//!    Silently accepting an unknown name would make the registry documentation rather than a gate.
//! 2. **An attribute that is not in its event's spec is dropped**, not rejected. The asymmetry is
//!    deliberate: a *newer client* adding an attribute must not lose its whole event stream, but
//!    the extra value must still never be written. Forward compatibility is not a reason to store
//!    something nobody declared.
//! 3. **No free text, ever.** Every value is an integer, a bool, a member of a declared enum, a
//!    fixed-length lowercase hex fingerprint, or a bounded version string. A field that could
//!    carry a world name, a player name, a chat line, or a file path cannot exist by construction
//!    — a `String` value with no declared domain is not representable here.
//!
//! Buckets, not measurements. Sizes, durations, and rates arrive pre-bucketed by the emitter and
//! are re-bounded here: a raw millisecond duration or a byte-exact world size is a fingerprint, a
//! bucket index is a statistic.

/// What a single attribute value is allowed to be.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ValueKind {
    /// A whole number inside `[min, max]`. Out-of-range values are dropped, not clamped — a
    /// clamped value looks like a real measurement and would quietly bias every aggregate.
    Int {
        min: i64,
        max: i64,
    },
    Bool,
    /// One of a closed set of lowercase identifiers.
    Enum(&'static [&'static str]),
    /// Exactly `len` lowercase hex characters — stack/divergence fingerprints, never a message.
    Hex {
        len: usize,
    },
    /// A bounded `digits.dots.dashes` version string (e.g. `0.1.0`, `21.1.238`).
    Version,
}

/// One declared attribute of one event.
#[derive(Debug, Clone, Copy)]
pub struct AttrSpec {
    pub key: &'static str,
    pub kind: ValueKind,
}

const fn int(key: &'static str, min: i64, max: i64) -> AttrSpec {
    AttrSpec {
        key,
        kind: ValueKind::Int { min, max },
    }
}

const fn boolean(key: &'static str) -> AttrSpec {
    AttrSpec {
        key,
        kind: ValueKind::Bool,
    }
}

const fn enumerated(key: &'static str, values: &'static [&'static str]) -> AttrSpec {
    AttrSpec {
        key,
        kind: ValueKind::Enum(values),
    }
}

const fn hex(key: &'static str, len: usize) -> AttrSpec {
    AttrSpec {
        key,
        kind: ValueKind::Hex { len },
    }
}

const fn version(key: &'static str) -> AttrSpec {
    AttrSpec {
        key,
        kind: ValueKind::Version,
    }
}

/// Which kind of node an event may come from.
///
/// Enforced against the batch's declared source so a peer cannot inject service-level rows (or
/// vice versa) into a dashboard an operator reads as ground truth.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// A player's node: the mod, the worker, or both.
    Peer,
    Tracker,
    Rendezvous,
    /// A Paper/Folia NoderaEndpoint (docs/server).
    Endpoint,
    /// Emitted by every source.
    Any,
}

impl Source {
    /// Parse the batch-level `src` field.
    pub fn parse(raw: &str) -> Option<Source> {
        match raw {
            "peer" => Some(Source::Peer),
            "tracker" => Some(Source::Tracker),
            "rendezvous" => Some(Source::Rendezvous),
            "endpoint" => Some(Source::Endpoint),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Source::Peer => "peer",
            Source::Tracker => "tracker",
            Source::Rendezvous => "rendezvous",
            Source::Endpoint => "endpoint",
            Source::Any => "any",
        }
    }

    /// Whether an event declared for `self` may arrive in a batch from `batch`.
    fn admits(self, batch: Source) -> bool {
        self == Source::Any || self == batch
    }
}

/// One declared event.
#[derive(Debug, Clone, Copy)]
pub struct EventSpec {
    pub name: &'static str,
    pub source: Source,
    pub attrs: &'static [AttrSpec],
}

const OS: &[&str] = &["linux", "macos", "windows", "other"];
const ARCH: &[&str] = &["x86_64", "aarch64", "other"];
const PATH_KIND: &[&str] = &["direct", "punched", "relayed", "unknown"];
const JOIN_FAILURE: &[&str] = &[
    "none",
    "unreachable",
    "password",
    "permission",
    "timeout",
    "handshake",
    "world_gone",
    "other",
];
const CERTIFICATION: &[&str] = &["certified", "pending", "solo"];
const DIVERGENCE_PHASE: &[&str] = &["shadow", "coordinator", "committee", "fallback"];
const FEATURE: &[&str] = &[
    "share_gui",
    "multiplayer_gui",
    "piece_map",
    "selftest",
    "command_nodera",
    "command_noderac",
    "settings_change",
    "grant",
    "rekey",
    "hud_toggle",
    "companion_dashboard",
];
const ERROR_KIND: &[&str] = &[
    "engine",
    "transport",
    "storage",
    "control",
    "mod_lifecycle",
    "worker",
    "app",
    "other",
];
const NAT_PAIR: &[&str] = &["easy_easy", "easy_hard", "hard_hard", "unknown"];
const SHARE_ORIGIN: &[&str] = &["new_world", "existing_world", "rehost"];
const PLATFORM: &[&str] = &["paper", "folia", "other"];

/// Every event NoderaMC is allowed to collect.
///
/// Grouped by the question each group answers: *who runs this* (environment), *does it work*
/// (functionality/health), *do people use it* (usage), *what does it cost* (traffic/storage).
pub const REGISTRY: &[EventSpec] = &[
    // ---- environment / lifecycle -------------------------------------------------------------
    EventSpec {
        name: "service.start",
        source: Source::Any,
        attrs: &[
            version("version"),
            enumerated("os", OS),
            enumerated("arch", ARCH),
            int("cpu_cores_bucket", 1, 256),
            int("ram_gb_bucket", 1, 1024),
        ],
    },
    EventSpec {
        name: "service.stop",
        source: Source::Any,
        attrs: &[int("uptime_hours_bucket", 0, 8760), boolean("clean")],
    },
    EventSpec {
        name: "session.start",
        source: Source::Peer,
        attrs: &[
            version("mod_version"),
            version("mc_version"),
            version("loader_version"),
            int("java_major", 8, 64),
            boolean("companion_present"),
            boolean("headless"),
        ],
    },
    EventSpec {
        name: "session.end",
        source: Source::Peer,
        attrs: &[int("minutes_bucket", 0, 10_000), boolean("clean")],
    },
    // ---- functionality: the validated lane ---------------------------------------------------
    EventSpec {
        name: "region.ownership",
        source: Source::Peer,
        attrs: &[
            int("regions_owned", 0, 4096),
            int("committee_size", 0, 64),
            enumerated("certification", CERTIFICATION),
        ],
    },
    EventSpec {
        name: "engine.tick",
        source: Source::Peer,
        attrs: &[
            int("tps_bucket", 0, 20),
            int("lag_events", 0, 100_000),
            int("regions", 0, 4096),
            int("window_seconds", 1, 86_400),
        ],
    },
    EventSpec {
        name: "engine.divergence",
        source: Source::Peer,
        attrs: &[
            enumerated("phase", DIVERGENCE_PHASE),
            int("rules_version", 0, 1024),
            hex("fingerprint", 16),
            int("count", 1, 100_000),
        ],
    },
    EventSpec {
        name: "engine.interference",
        source: Source::Peer,
        attrs: &[
            int("revocations", 0, 100_000),
            int("window_seconds", 1, 86_400),
        ],
    },
    // ---- functionality: reachability + the host lane ------------------------------------------
    EventSpec {
        name: "world.share",
        source: Source::Peer,
        attrs: &[
            boolean("password"),
            int("size_mb_bucket", 0, 1_048_576),
            enumerated("origin", SHARE_ORIGIN),
        ],
    },
    EventSpec {
        name: "world.join",
        source: Source::Peer,
        attrs: &[
            boolean("ok"),
            enumerated("path", PATH_KIND),
            enumerated("failure", JOIN_FAILURE),
            int("seconds_bucket", 0, 3_600),
        ],
    },
    EventSpec {
        name: "world.rehost",
        source: Source::Peer,
        attrs: &[boolean("recovered"), int("seconds_bucket", 0, 3_600)],
    },
    EventSpec {
        name: "net.handshake",
        source: Source::Peer,
        attrs: &[
            boolean("ok"),
            enumerated("path", PATH_KIND),
            enumerated("failure", JOIN_FAILURE),
            int("count", 1, 1_000_000),
        ],
    },
    // ---- traffic + storage cost ---------------------------------------------------------------
    EventSpec {
        name: "net.traffic",
        source: Source::Peer,
        attrs: &[
            int("up_mb_bucket", 0, 1_048_576),
            int("down_mb_bucket", 0, 1_048_576),
            int("peers", 0, 4096),
            int("relayed_peers", 0, 4096),
            int("window_seconds", 1, 86_400),
        ],
    },
    EventSpec {
        name: "storage.archive",
        source: Source::Peer,
        attrs: &[
            int("total_mb_bucket", 0, 16_777_216),
            int("pieces_held_percent", 0, 100),
            int("errors", 0, 100_000),
        ],
    },
    // ---- usage --------------------------------------------------------------------------------
    EventSpec {
        name: "feature.use",
        source: Source::Peer,
        attrs: &[enumerated("feature", FEATURE), int("count", 1, 100_000)],
    },
    EventSpec {
        name: "consent.change",
        source: Source::Any,
        attrs: &[boolean("granted")],
    },
    EventSpec {
        name: "error.report",
        source: Source::Any,
        attrs: &[
            enumerated("kind", ERROR_KIND),
            hex("fingerprint", 16),
            boolean("fatal"),
            int("count", 1, 100_000),
        ],
    },
    // ---- the services -------------------------------------------------------------------------
    EventSpec {
        name: "tracker.window",
        source: Source::Tracker,
        attrs: &[
            int("announces", 0, 100_000_000),
            int("announces_rejected", 0, 100_000_000),
            int("queries", 0, 100_000_000),
            int("worlds", 0, 10_000_000),
            int("peers", 0, 100_000_000),
            int("quota_rejections", 0, 100_000_000),
            int("udp_percent", 0, 100),
            int("window_seconds", 1, 86_400),
        ],
    },
    EventSpec {
        name: "tracker.world_health",
        source: Source::Tracker,
        attrs: &[
            int("healthy", 0, 10_000_000),
            int("degraded", 0, 10_000_000),
            int("dead", 0, 10_000_000),
        ],
    },
    EventSpec {
        name: "service.latency",
        source: Source::Any,
        attrs: &[
            int("p50_micros", 0, 60_000_000),
            int("p95_micros", 0, 60_000_000),
            int("p99_micros", 0, 60_000_000),
            int("samples", 1, 100_000_000),
        ],
    },
    EventSpec {
        name: "rendezvous.window",
        source: Source::Rendezvous,
        attrs: &[
            int("registrations", 0, 100_000_000),
            int("discoveries", 0, 100_000_000),
            int("reservations", 0, 100_000_000),
            int("circuits", 0, 100_000_000),
            int("rejected", 0, 100_000_000),
            int("namespaces", 0, 10_000_000),
            int("window_seconds", 1, 86_400),
        ],
    },
    EventSpec {
        name: "rendezvous.punch",
        source: Source::Rendezvous,
        attrs: &[
            int("attempts", 0, 100_000_000),
            int("successes", 0, 100_000_000),
            enumerated("nat_pair", NAT_PAIR),
        ],
    },
    EventSpec {
        name: "rendezvous.relay",
        source: Source::Rendezvous,
        attrs: &[
            int("circuits", 0, 100_000_000),
            int("relayed_mb_bucket", 0, 16_777_216),
            int("median_seconds", 0, 86_400),
            int("denied", 0, 100_000_000),
        ],
    },
    EventSpec {
        name: "endpoint.window",
        source: Source::Endpoint,
        attrs: &[
            enumerated("platform", PLATFORM),
            int("tenants", 0, 10_000),
            int("node_players", 0, 10_000),
            int("tps_bucket", 0, 20),
            int("plugins", 0, 1_000),
            int("window_seconds", 1, 86_400),
        ],
    },
];

/// Look an event name up in the registry.
pub fn spec(name: &str) -> Option<&'static EventSpec> {
    REGISTRY.iter().find(|spec| spec.name == name)
}

/// Look an event up and check it is allowed from this batch's source.
pub fn spec_for(name: &str, source: Source) -> Result<&'static EventSpec, SpecError> {
    match spec(name) {
        None => Err(SpecError::UnknownEvent),
        Some(spec) if !spec.source.admits(source) => Err(SpecError::WrongSource),
        Some(spec) => Ok(spec),
    }
}

/// Why a name did not resolve to a usable spec.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpecError {
    UnknownEvent,
    WrongSource,
}

impl EventSpec {
    /// The declared kind of one attribute, or `None` when the attribute is undeclared.
    pub fn kind_of(&self, key: &str) -> Option<ValueKind> {
        self.attrs
            .iter()
            .find(|attr| attr.key == key)
            .map(|attr| attr.kind)
    }
}

/// The version this registry was built from — the repository `VERSION` stamped by `build.rs`.
///
/// It rides every copy of the schema, including the one an offline client falls back to, so a
/// stale disclosure is **visibly** stale rather than merely old (app L-78).
pub const REGISTRY_VERSION: &str = env!("NODERA_VERSION");

/// The registry, as JSON — the machine-readable half of the privacy notice.
///
/// Lives in the library rather than in the binary because two places consume it and they must not
/// drift: the ingest service answers a probe with it, and the companion app embeds it as the
/// fallback for the case where the service cannot be reached at all.
///
/// `source` distinguishes the two: `service` for a live answer, `bundled` for the embedded copy.
/// A screen that cannot tell them apart would present a possibly-stale list as current.
pub fn schema_json(source: &str) -> String {
    use serde_json::{Map, Value};
    let mut events = Vec::new();
    for spec in REGISTRY {
        let mut attrs = Map::new();
        for attr in spec.attrs {
            attrs.insert(attr.key.to_owned(), Value::from(kind_name(attr.kind)));
        }
        let mut event = Map::new();
        event.insert("name".into(), Value::from(spec.name));
        event.insert("source".into(), Value::from(spec.source.as_str()));
        event.insert("attrs".into(), Value::Object(attrs));
        events.push(Value::Object(event));
    }
    let mut root = Map::new();
    root.insert("row_schema".into(), Value::from(crate::service::ROW_SCHEMA));
    root.insert(
        "batch_version".into(),
        Value::from(crate::event::BATCH_VERSION),
    );
    root.insert("registry_version".into(), Value::from(REGISTRY_VERSION));
    root.insert("disclosure_source".into(), Value::from(source));
    root.insert("events".into(), Value::Array(events));
    Value::Object(root).to_string()
}

/// Test seam: the binary's schema tests assert the rendering of each kind.
pub fn kind_name_for_test(kind: ValueKind) -> String {
    kind_name(kind)
}

fn kind_name(kind: ValueKind) -> String {
    match kind {
        ValueKind::Int { min, max } => format!("int[{min}..{max}]"),
        ValueKind::Bool => "bool".to_owned(),
        ValueKind::Enum(values) => format!("enum({})", values.join("|")),
        ValueKind::Hex { len } => format!("hex[{len}]"),
        ValueKind::Version => "version".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn every_event_name_is_unique() {
        let mut seen = HashSet::new();
        for spec in REGISTRY {
            assert!(seen.insert(spec.name), "duplicate event name {}", spec.name);
        }
    }

    #[test]
    fn every_attribute_key_is_unique_within_its_event() {
        for spec in REGISTRY {
            let mut seen = HashSet::new();
            for attr in spec.attrs {
                assert!(
                    seen.insert(attr.key),
                    "duplicate attribute {} on {}",
                    attr.key,
                    spec.name
                );
            }
        }
    }

    /// The privacy claim in one assertion: no declared value can be an unconstrained string.
    #[test]
    fn no_attribute_accepts_free_text() {
        for spec in REGISTRY {
            for attr in spec.attrs {
                match attr.kind {
                    ValueKind::Enum(values) => assert!(
                        !values.is_empty(),
                        "{}.{} declares an empty enum, which would accept nothing or everything",
                        spec.name,
                        attr.key
                    ),
                    ValueKind::Hex { len } => assert!(
                        len > 0 && len <= 64,
                        "{}.{} hex length is out of bounds",
                        spec.name,
                        attr.key
                    ),
                    ValueKind::Int { min, max } => assert!(min <= max),
                    ValueKind::Bool | ValueKind::Version => {}
                }
            }
        }
    }

    #[test]
    fn enum_members_are_lowercase_identifiers() {
        for spec in REGISTRY {
            for attr in spec.attrs {
                if let ValueKind::Enum(values) = attr.kind {
                    for value in values {
                        assert!(
                            value
                                .chars()
                                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_'),
                            "{}.{} enum member {value:?} is not a lowercase identifier",
                            spec.name,
                            attr.key
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn a_service_event_is_refused_from_a_peer_batch() {
        assert_eq!(
            spec_for("tracker.window", Source::Peer).err(),
            Some(SpecError::WrongSource)
        );
        assert!(spec_for("tracker.window", Source::Tracker).is_ok());
    }

    #[test]
    fn an_any_source_event_is_admitted_everywhere() {
        for source in [
            Source::Peer,
            Source::Tracker,
            Source::Rendezvous,
            Source::Endpoint,
        ] {
            assert!(spec_for("service.start", source).is_ok());
        }
    }

    #[test]
    fn an_unregistered_name_never_resolves() {
        assert_eq!(
            spec_for("world.name", Source::Peer).err(),
            Some(SpecError::UnknownEvent)
        );
    }

    #[test]
    fn sources_round_trip_through_their_wire_names() {
        for source in [
            Source::Peer,
            Source::Tracker,
            Source::Rendezvous,
            Source::Endpoint,
        ] {
            assert_eq!(Source::parse(source.as_str()), Some(source));
        }
        assert_eq!(Source::parse("admin"), None);
    }
}
