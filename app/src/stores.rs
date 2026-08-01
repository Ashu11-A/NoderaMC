//! Tracker stores — lists of trackers and relays a user chooses to trust.
//!
//! Modelled on how Mihon and Tachiyomi handle extension repositories, and for the same reason: the
//! project cannot be the only source of infrastructure without becoming the authority the whole
//! design avoids having. A store is a URL serving an index in the format published as
//! `index.schema.json` on the orphan `services` branch (`git show services:README.md`); the
//! project's own list is one store among however many a user adds, and can be removed like any
//! other.
//!
//! ## What adding a store does and does not mean
//!
//! It means: "also look here for addresses to try." Nothing more. Every service found through a
//! store still proves its own identity — a tracker's directory rows are Ed25519-signed by the
//! services themselves and verified by the peer before anything is dialled, and no service holds
//! authority over world state. So the blast radius of a hostile store is wasted time: slow answers,
//! hidden peers, relays that will not relay. All three are things peers measure and route around.
//!
//! That is exactly why the index itself is **not signed**. A signature is worth what its key
//! distribution is worth, and there is no trust root here that would make one mean more than "this
//! file came from whoever published this file". What carries the weight is the user's explicit
//! decision, taken with the URL in front of them.
//!
//! ## Why every install needs this
//!
//! A shipped build starts with *no* configured trackers on any platform, deliberately — `127.0.0.1`
//! is the handset on a phone and a tracker the user never installed on a desktop, and a baked LAN
//! address is unreachable everywhere but one network. All of those produce a permanently failing row
//! that teaches the user to ignore red. The built-in store is what a fresh install actually dials: a
//! real, reachable, publicly operated tracker, in a list the user can inspect and delete. Loopback
//! comes back only in [`crate::settings::dev_mode`].

use serde::{Deserialize, Serialize};
use std::time::Duration;

/// The index format version this build understands.
///
/// A reader that does not know the version must refuse the index rather than guess at it: a future
/// index whose fields mean something else is worse than no index.
pub const SCHEMA_VERSION: u32 = 1;

/// Where the project's own list is served from.
///
/// The minified index on the orphan `services` branch. It is spelled here and in
/// `/layout.properties` (`services.rawUrl`), and `official_store_url_matches_the_layout_manifest`
/// holds the two together: a URL assembled independently in four languages is four chances to
/// assemble it differently, and the symptom would be a built-in store that never refreshes.
pub const OFFICIAL_STORE_URL: &str =
    "https://raw.githubusercontent.com/Ashu11-A/NoderaMC/services/index.min.json";

/// The URI scheme a website's "add this store" button uses.
///
/// `nodera://tracker-store?url=<percent-encoded>`, mirroring `mihon://extension-store?url=…`.
pub const DEEP_LINK_HOST: &str = "tracker-store";

/// The biggest index this app will read.
///
/// A service list is a few kilobytes. The ceiling is here so a hostile or broken URL costs a bounded
/// allocation rather than the app's memory.
const MAX_INDEX_BYTES: u64 = 1024 * 1024;

const FETCH_TIMEOUT: Duration = Duration::from_secs(15);

/// What kind of service an entry describes.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServiceKind {
    /// Discovery.
    Tracker,
    /// Reachability behind NAT.
    Rendezvous,
}

/// One service in an index.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServiceEntry {
    /// Discovery or relay.
    pub kind: ServiceKind,
    /// Display name.
    pub name: String,
    /// Routes to try, best first.
    pub endpoints: Vec<String>,
    /// The service's published identity, if the operator stated one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub node_id: Option<String>,
    /// Who runs it — a contact, not an authority.
    #[serde(default)]
    pub operator: String,
    /// Rough location.
    #[serde(default)]
    pub region: String,
    /// Free text from the operator.
    #[serde(default)]
    pub notes: String,
}

/// A parsed index.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServiceIndex {
    /// Format version; must be [`SCHEMA_VERSION`].
    pub schema_version: u32,
    /// What to call this store.
    pub name: String,
    #[serde(default)]
    /// One line about the store.
    pub description: String,
    #[serde(default)]
    /// Where to read more.
    pub homepage: String,
    #[serde(default)]
    /// ISO date the publisher last changed it.
    pub updated: String,
    /// The services.
    #[serde(default)]
    pub services: Vec<ServiceEntry>,
}

/// A store as this app remembers it: where it came from, and what it last said.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrackerStore {
    /// The index URL. This is the store's identity — one entry per URL.
    pub url: String,
    /// The name the index gave itself.
    pub name: String,
    /// The description the index gave itself.
    #[serde(default)]
    pub description: String,
    /// The homepage the index gave itself.
    #[serde(default)]
    pub homepage: String,
    /// The services from the last successful read.
    #[serde(default)]
    pub services: Vec<ServiceEntry>,
    /// When that read happened, epoch millis. 0 for a store that has never been read.
    #[serde(default)]
    pub last_refreshed_epoch_millis: u64,
    /// Why the last refresh failed, or empty.
    ///
    /// Kept **beside** the services rather than replacing them: a store that failed to refresh
    /// today should keep working with what it said yesterday. Losing every tracker because a web
    /// server had a bad minute is a worse outcome than a slightly stale list.
    #[serde(default)]
    pub last_error: String,
    /// Whether this is the store the app shipped with.
    ///
    /// Only used to explain the row in the UI. It is deletable like any other — a built-in the user
    /// cannot remove would make the project the authority this design does not want it to be.
    #[serde(default)]
    pub built_in: bool,
}

/// Why a store could not be added or refreshed.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum StoreError {
    /// The URL is not one this app will fetch.
    #[error("{0}")]
    Url(String),
    /// The fetch failed.
    #[error("could not fetch the store: {0}")]
    Fetch(String),
    /// The body was not an index this build understands.
    #[error("{0}")]
    Index(String),
}

/// Reject a URL before any request is made.
///
/// HTTPS only. A store list served over plaintext is a list anyone on the path can rewrite, and the
/// whole value of a store is that the user decided to trust *that publisher* — which is a decision
/// plaintext quietly hands to somebody else. Android release builds block cleartext anyway; this
/// makes desktop agree rather than differ silently.
pub fn check_url(url: &str) -> Result<(), StoreError> {
    let trimmed = url.trim();
    if trimmed.is_empty() {
        return Err(StoreError::Url("a store needs a URL".to_owned()));
    }
    if !trimmed.starts_with("https://") {
        return Err(StoreError::Url(format!(
            "{trimmed} is not https — a store served over plaintext can be rewritten by anyone \
             on the path, which is exactly the decision you are making by adding it"
        )));
    }
    if trimmed.len() > 2048 {
        return Err(StoreError::Url("that URL is implausibly long".to_owned()));
    }
    Ok(())
}

/// Parse and validate an index body.
///
/// Strict on purpose: an entry this build cannot make sense of is dropped rather than half-read,
/// and an index with no usable entry is an error rather than an empty store the user has to work out
/// for themselves.
pub fn parse_index(body: &str) -> Result<ServiceIndex, StoreError> {
    let index: ServiceIndex = serde_json::from_str(body)
        .map_err(|e| StoreError::Index(format!("that URL did not serve a service index: {e}")))?;

    if index.schema_version != SCHEMA_VERSION {
        return Err(StoreError::Index(format!(
            "that store uses index format {} and this build understands {SCHEMA_VERSION} — \
             update the app rather than trusting a guess at what its fields mean",
            index.schema_version
        )));
    }
    if index.name.trim().is_empty() {
        return Err(StoreError::Index("that store has no name".to_owned()));
    }

    let mut kept = Vec::new();
    for entry in index.services {
        let endpoints: Vec<String> = entry
            .endpoints
            .iter()
            .map(|route| route.trim().to_owned())
            .filter(|route| valid_route(route))
            .collect();
        if endpoints.is_empty() || entry.name.trim().is_empty() {
            continue;
        }
        kept.push(ServiceEntry { endpoints, ..entry });
    }
    if kept.is_empty() {
        return Err(StoreError::Index(
            "that store listed no usable services".to_owned(),
        ));
    }
    Ok(ServiceIndex {
        services: kept,
        ..index
    })
}

/// Whether a route is one this build can dial.
///
/// Deliberately the same rule the settings validator applies to a hand-typed endpoint. A store must
/// not be a way to get a route into the configuration that the settings screen would have refused.
fn valid_route(route: &str) -> bool {
    let address = match route.split_once("://") {
        Some((scheme, rest)) => {
            if scheme != "tcp" && scheme != "udp" {
                return false;
            }
            rest
        }
        None => route,
    };
    match address.rsplit_once(':') {
        Some((host, port)) => {
            !host.is_empty() && port.parse::<u16>().map(|p| p > 0).unwrap_or(false)
        }
        None => false,
    }
}

/// Fetch an index body over HTTPS.
///
/// Blocking, and therefore for a `spawn_blocking` context. The alternative is an async HTTP stack in
/// an app whose every other network path is a hand-framed TCP socket, to serve one request a user
/// makes by hand.
pub fn fetch_index(url: &str) -> Result<String, StoreError> {
    check_url(url)?;
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(FETCH_TIMEOUT))
        .max_redirects(5)
        .build()
        .into();
    let mut response = agent
        .get(url)
        .call()
        .map_err(|e| StoreError::Fetch(e.to_string()))?;
    response
        .body_mut()
        .with_config()
        .limit(MAX_INDEX_BYTES)
        .read_to_string()
        .map_err(|e| StoreError::Fetch(e.to_string()))
}

/// Build a store record from an index that was just read.
pub fn store_from(
    url: &str,
    index: ServiceIndex,
    now_epoch_millis: u64,
    built_in: bool,
) -> TrackerStore {
    TrackerStore {
        url: url.trim().to_owned(),
        name: index.name,
        description: index.description,
        homepage: index.homepage,
        services: index.services,
        last_refreshed_epoch_millis: now_epoch_millis,
        last_error: String::new(),
        built_in,
    }
}

/// The store the app ships with, parsed from the copy compiled into the binary.
///
/// Compiled in rather than fetched on first run, so a fresh install has somewhere to look before it
/// has a working network — and so an install that is never online still shows the user what the
/// default is rather than an empty screen.
pub fn built_in_store() -> Option<TrackerStore> {
    // Resolved from the `services` branch by `build.rs`, not read from this tree — the list is data
    // published on its own branch, and a copy of it sitting in `app/` would be the second table
    // that quietly disagrees with the first.
    const BUNDLED: &str = include_str!(concat!(env!("OUT_DIR"), "/official-index.json"));
    parse_index(BUNDLED)
        .ok()
        .map(|index| store_from(OFFICIAL_STORE_URL, index, 0, true))
}

/// Every endpoint of one kind that the stores contribute, deduplicated, in store order.
///
/// The caller puts the user's own configured endpoints first. That ordering is the whole point: a
/// store must be able to *add* somewhere to look and must never be able to displace an address the
/// user typed. Peers reorder all of it by measurement anyway, so position here is about provenance,
/// not preference.
pub fn endpoints_of(stores: &[TrackerStore], kind: ServiceKind) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for store in stores {
        for service in &store.services {
            if service.kind != kind {
                continue;
            }
            for endpoint in &service.endpoints {
                if !out.iter().any(|held| held == endpoint) {
                    out.push(endpoint.clone());
                }
            }
        }
    }
    out
}

/// One endpoint in the effective list, and where it came from.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResolvedEndpoint {
    /// The route, exactly as it will be handed to the worker.
    pub endpoint: String,
    /// The name of the store that contributed it, or empty when the user typed it.
    ///
    /// Provenance rather than a boolean, because "where did this address come from" is the question
    /// a user actually has in front of a list they did not fully write.
    #[serde(default)]
    pub store: String,
}

/// Merge configured endpoints with what the stores contribute, keeping provenance.
///
/// ## Why this is the primitive and [`merged`] is derived from it
///
/// The app knew the effective list all along — it is what `worker_env`, `WorkerConfig::of` and the
/// synchronisation file are all built from — but nothing ever *showed* it. The settings screen
/// rendered `network.default_trackers`, which is only what the user typed, so an install whose
/// single tracker came from the built-in store read "0 tracker(s)" on one screen while the store
/// screen beside it read "1 tracker · 1 relay". Both were describing the same working node. The
/// store looked ignored because the app never said otherwise.
///
/// So the merge returns provenance now, and the screens render this rather than the raw setting. It
/// is the same walk in the same order — the user's own first, stores after, deduplicated — because
/// [`merged`] is literally this function with the provenance dropped. Two implementations of "what
/// this node will actually dial" is exactly the drift that produced the wrong screen.
pub fn resolved(
    configured: &[String],
    stores: &[TrackerStore],
    kind: ServiceKind,
) -> Vec<ResolvedEndpoint> {
    let mut out: Vec<ResolvedEndpoint> = Vec::new();
    let mut push = |endpoint: String, store: &str| {
        if endpoint.is_empty() || out.iter().any(|held| held.endpoint == endpoint) {
            return;
        }
        out.push(ResolvedEndpoint {
            endpoint,
            store: store.to_owned(),
        });
    };

    // The user's own first. A store must be able to *add* somewhere to look and must never displace
    // an address the user typed.
    for endpoint in configured {
        push(endpoint.trim().to_owned(), "");
    }
    for store in stores {
        for service in &store.services {
            if service.kind != kind {
                continue;
            }
            for endpoint in &service.endpoints {
                push(endpoint.clone(), &store.name);
            }
        }
    }
    out
}

/// Merge configured endpoints with what the stores contribute.
///
/// The plain-string view of [`resolved`], for the three places that hand the list to the worker and
/// have no use for provenance: the environment, `NODERA-CONFIG`, and the synchronisation file.
pub fn merged(configured: &[String], stores: &[TrackerStore], kind: ServiceKind) -> Vec<String> {
    resolved(configured, stores, kind)
        .into_iter()
        .map(|resolved| resolved.endpoint)
        .collect()
}

/// Pull the store URL out of a `nodera://tracker-store?url=…` deep link.
///
/// Returns `None` for anything else, including a `nodera:` URI of another kind — the app already
/// uses `nodera:` for world invitations, and misreading one as the other would add a store from a
/// link that was about something else entirely.
pub fn store_url_from_deep_link(uri: &str) -> Option<String> {
    let rest = uri.trim().strip_prefix("nodera://")?;
    let (host, query) = rest.split_once('?')?;
    if host.trim_end_matches('/') != DEEP_LINK_HOST {
        return None;
    }
    for pair in query.split('&') {
        if let Some(value) = pair.strip_prefix("url=") {
            let decoded = percent_decode(value);
            return (!decoded.is_empty()).then_some(decoded);
        }
    }
    None
}

/// Minimal percent-decoding for the one query parameter this app reads.
///
/// A whole URL-parsing dependency for `%3A` and `%2F` would be the larger risk. Anything that is not
/// a valid escape is left as written, so a malformed link produces a URL the user can see is wrong
/// in the confirmation dialog rather than a silently different one.
fn percent_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).ok();
            if let Some(byte) = hex.and_then(|h| u8::from_str_radix(h, 16).ok()) {
                out.push(byte);
                i += 3;
                continue;
            }
        }
        if bytes[i] == b'+' {
            out.push(b' ');
        } else {
            out.push(bytes[i]);
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// The name of the file the app keeps in sync for the worker to read.
///
/// See [`sync_file_body`] for why this file exists at all.
pub const SYNC_FILE_NAME: &str = "nodera-services.list";

/// Render the synchronisation file: the resolved endpoint list, one route per line.
///
/// ## Why a file, and not just the environment
///
/// On the desktop the app spawns the worker and can hand it environment variables. **On Android it
/// cannot.** The worker there is loaded into the app's own process by `NoderaWorker.start`, and
/// `NoderaWorker.kt` says it plainly: *"Environment variables cannot be set for our own process
/// from Java."* So an Android install had no way at all to tell its worker about a tracker — it fell
/// back to `127.0.0.1:25600`, which on a handset is the handset. Every store a user added on their
/// phone reached the app and stopped there.
///
/// A file both sides agree on is the fix, and it is the same shape Mihon uses: the *subscriptions*
/// are user settings, the *synced content* is a file the app refreshes on a schedule and something
/// else consumes.
///
/// The format is deliberately not JSON. The worker's side of this has to be a few lines of parsing
/// with no dependency and no failure mode more interesting than "skip the line I cannot read" — a
/// half-written or hand-edited file must cost one endpoint, never the worker's ability to start.
pub fn sync_file_body(trackers: &[String], rendezvous: &[String], synced_iso: &str) -> String {
    let mut out = String::new();
    out.push_str("# Nodera synced services. Written by the companion app — do not edit.\n");
    out.push_str("# Change these under Settings -> Tracker stores; edits here are overwritten.\n");
    if !synced_iso.is_empty() {
        out.push_str(&format!("# last synced {synced_iso}\n"));
    }
    for route in trackers {
        out.push_str(&format!("tracker {route}\n"));
    }
    for route in rendezvous {
        out.push_str(&format!("rendezvous {route}\n"));
    }
    out
}

/// Write the synchronisation file next to the app's other state.
///
/// Write-then-rename, because the worker may read it at any moment — including while this runs, on
/// Android, where both live in one process. A half-written list is the one outcome that would be
/// worse than a stale one.
pub fn write_sync_file(dir: &std::path::Path, body: &str) -> std::io::Result<std::path::PathBuf> {
    std::fs::create_dir_all(dir)?;
    let final_path = dir.join(SYNC_FILE_NAME);
    let temp_path = dir.join(format!("{SYNC_FILE_NAME}.tmp"));
    std::fs::write(&temp_path, body)?;
    std::fs::rename(&temp_path, &final_path)?;
    Ok(final_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index_json(extra: &str) -> String {
        format!(
            r#"{{"schema_version":1,"name":"Example","services":[
                {{"kind":"tracker","name":"a","endpoints":["tcp://a.example.org:6969"]}},
                {{"kind":"rendezvous","name":"b","endpoints":["tcp://b.example.org:7500"]}}
              ]{extra}}}"#
        )
    }

    #[test]
    fn the_bundled_official_store_parses() {
        // The file is validated by CI too, but this is the assertion that matters to the app: the
        // list compiled into the binary is one this build can actually read. A fresh install with an
        // unreadable built-in store would show an empty screen and no explanation.
        let store = built_in_store().expect("the bundled official list must parse");
        assert!(!store.services.is_empty());
        assert!(store.built_in);
        assert!(!endpoints_of(std::slice::from_ref(&store), ServiceKind::Tracker).is_empty());
        assert!(!endpoints_of(&[store], ServiceKind::Rendezvous).is_empty());
    }

    #[test]
    fn official_store_url_matches_the_layout_manifest() {
        // Two spellings of one URL: this constant, which a shipped client refreshes from, and
        // `services.rawUrl` in `/layout.properties`, which the three build-time resolvers fetch
        // from. Drift between them would not break a build — it would ship a built-in store that
        // points at a file nobody publishes, and the only symptom is a row that never refreshes.
        let mut manifest = None;
        let mut directory = Some(std::path::Path::new(env!("CARGO_MANIFEST_DIR")));
        while let Some(candidate) = directory {
            let file = candidate.join("layout.properties");
            if file.is_file() && candidate.join("VERSION").is_file() {
                manifest = std::fs::read_to_string(file).ok();
                break;
            }
            directory = candidate.parent();
        }
        let manifest = manifest.expect("the layout manifest must be findable from this crate");
        let declared = manifest
            .lines()
            .filter_map(|line| line.split_once('='))
            .find(|(key, _)| key.trim() == "services.rawUrl")
            .map(|(_, value)| value.trim().to_owned())
            .expect("layout.properties must declare services.rawUrl");
        assert_eq!(declared, OFFICIAL_STORE_URL);
    }

    #[test]
    fn a_plaintext_store_is_refused_before_any_request_is_made() {
        // The decision a user makes when adding a store is "I trust this publisher". Plaintext
        // quietly hands that decision to whoever is on the path.
        let error = check_url("http://example.org/index.json").unwrap_err();
        assert!(error.to_string().contains("not https"), "{error}");
        assert!(check_url("https://example.org/index.json").is_ok());
        assert!(check_url("  ").is_err());
    }

    #[test]
    fn an_index_from_a_future_format_is_refused_rather_than_guessed_at() {
        let body = r#"{"schema_version":2,"name":"Future","services":[]}"#;
        let error = parse_index(body).unwrap_err();
        assert!(error.to_string().contains("update the app"), "{error}");
    }

    #[test]
    fn an_entry_with_a_route_the_settings_screen_would_refuse_is_dropped() {
        // A store must not be a way around the validation a hand-typed endpoint gets.
        let body = r#"{"schema_version":1,"name":"Mixed","services":[
            {"kind":"tracker","name":"bad","endpoints":["http://a.example.org:6969","nonsense"]},
            {"kind":"tracker","name":"good","endpoints":["tcp://b.example.org:6969"]}
        ]}"#;
        let index = parse_index(body).unwrap();
        assert_eq!(index.services.len(), 1);
        assert_eq!(index.services[0].name, "good");
    }

    #[test]
    fn an_index_with_nothing_usable_is_an_error_not_an_empty_store() {
        let body = r#"{"schema_version":1,"name":"Empty","services":[
            {"kind":"tracker","name":"bad","endpoints":["nonsense"]}
        ]}"#;
        assert!(parse_index(body).is_err());
    }

    #[test]
    fn a_store_cannot_displace_an_endpoint_the_user_typed() {
        // Provenance, not preference: stores add places to look, they never reorder the user's own.
        let index = parse_index(&index_json("")).unwrap();
        let store = store_from("https://example.org/i.json", index, 1, false);
        let merged = merged(
            &["tcp://mine.example.org:6969".to_owned()],
            &[store],
            ServiceKind::Tracker,
        );
        assert_eq!(merged[0], "tcp://mine.example.org:6969");
        assert_eq!(merged[1], "tcp://a.example.org:6969");
    }

    #[test]
    fn the_same_endpoint_from_two_stores_appears_once() {
        let index = parse_index(&index_json("")).unwrap();
        let a = store_from("https://a.example.org/i.json", index.clone(), 1, false);
        let b = store_from("https://b.example.org/i.json", index, 1, false);
        assert_eq!(endpoints_of(&[a, b], ServiceKind::Tracker).len(), 1);
    }

    #[test]
    fn a_kind_is_never_mixed_with_the_other() {
        // A relay in the tracker list is an endpoint that will never answer, and the user would see
        // it as a permanently failing row with no explanation.
        let index = parse_index(&index_json("")).unwrap();
        let store = store_from("https://example.org/i.json", index, 1, false);
        assert_eq!(
            endpoints_of(std::slice::from_ref(&store), ServiceKind::Tracker),
            vec!["tcp://a.example.org:6969"]
        );
        assert_eq!(
            endpoints_of(&[store], ServiceKind::Rendezvous),
            vec!["tcp://b.example.org:7500"]
        );
    }

    #[test]
    fn a_deep_link_yields_the_url_it_names() {
        assert_eq!(
            store_url_from_deep_link(
                "nodera://tracker-store?url=https%3A%2F%2Fexample.org%2Findex.json"
            ),
            Some("https://example.org/index.json".to_owned())
        );
    }

    #[test]
    fn an_invitation_link_is_not_read_as_a_store() {
        // `nodera:` already means "join this world" in this app. Reading one as a store would add a
        // store from a link that was about something else entirely.
        assert_eq!(store_url_from_deep_link("nodera://join?world=abc123"), None);
        assert_eq!(store_url_from_deep_link("nodera:world/abc123"), None);
        assert_eq!(store_url_from_deep_link("https://example.org"), None);
        assert_eq!(store_url_from_deep_link("nodera://tracker-store"), None);
    }

    #[test]
    fn a_malformed_escape_survives_into_the_dialog_rather_than_being_invented() {
        // The user is shown this string before anything is fetched. Silently repairing it would
        // show them one URL and use another.
        assert_eq!(
            store_url_from_deep_link("nodera://tracker-store?url=https%3A%2F%2Fa.org%2Fx%ZZ"),
            Some("https://a.org/x%ZZ".to_owned())
        );
    }

    #[test]
    fn the_sync_file_is_readable_by_a_parser_with_no_dependencies() {
        let body = sync_file_body(
            &["tcp://t.example.org:6969".to_owned()],
            &["tcp://r.example.org:7500".to_owned()],
            "2026-07-27T12:00:00Z",
        );
        // Every non-comment line is exactly "<kind> <route>", which is all the worker's reader needs
        // to know. Anything richer here becomes a JSON parser on the other side of the boundary.
        let rows: Vec<&str> = body
            .lines()
            .filter(|line| !line.starts_with('#') && !line.trim().is_empty())
            .collect();
        assert_eq!(
            rows,
            vec![
                "tracker tcp://t.example.org:6969",
                "rendezvous tcp://r.example.org:7500"
            ]
        );
        assert!(body.contains("do not edit"), "{body}");
    }

    #[test]
    fn an_empty_selection_writes_a_file_with_no_routes_rather_than_no_file() {
        // "I synced and there is nothing" and "I have never synced" are different states, and the
        // worker has to be able to tell them apart. An absent file means the latter.
        let body = sync_file_body(&[], &[], "2026-07-27T12:00:00Z");
        assert!(body.contains("last synced"));
        assert!(!body.contains("tracker "));
    }

    #[test]
    fn writing_the_sync_file_is_atomic_and_leaves_no_temporary_behind() {
        let dir = std::env::temp_dir().join(format!("nodera-sync-{}", std::process::id()));
        let body = sync_file_body(&["tcp://t.example.org:6969".to_owned()], &[], "");
        let written = write_sync_file(&dir, &body).expect("written");

        assert_eq!(std::fs::read_to_string(&written).unwrap(), body);
        assert!(
            !dir.join(format!("{SYNC_FILE_NAME}.tmp")).exists(),
            "the temporary file must be renamed, not left beside the real one"
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn the_effective_list_says_where_each_address_came_from() {
        // The bug this closes: the settings screen read `network.default_trackers` and reported
        // "0 tracker(s)" on an install whose tracker came from the built-in store — while the store
        // screen beside it listed that tracker. Nothing was broken except what the app said.
        let index = parse_index(&index_json("")).unwrap();
        let store = store_from("https://example.org/i.json", index, 10, true);
        let typed = vec!["tcp://mine.example.org:6969".to_owned()];

        let trackers = resolved(&typed, std::slice::from_ref(&store), ServiceKind::Tracker);
        assert_eq!(
            trackers,
            vec![
                ResolvedEndpoint {
                    endpoint: "tcp://mine.example.org:6969".to_owned(),
                    store: String::new(),
                },
                ResolvedEndpoint {
                    endpoint: "tcp://a.example.org:6969".to_owned(),
                    store: "Example".to_owned(),
                },
            ],
            "the user's own comes first and carries no store name"
        );
    }

    #[test]
    fn the_effective_list_and_the_worker_list_cannot_disagree() {
        // `merged` is what the worker is handed; `resolved` is what the user is shown. A screen
        // that describes a different list from the one being dialled is the defect this whole pair
        // exists to prevent, so one is defined as the other with a field dropped.
        let index = parse_index(&index_json("")).unwrap();
        let store = store_from("https://example.org/i.json", index, 10, false);
        let typed = vec![
            "tcp://mine.example.org:6969".to_owned(),
            "  ".to_owned(),
            "tcp://a.example.org:6969".to_owned(),
        ];
        for kind in [ServiceKind::Tracker, ServiceKind::Rendezvous] {
            let shown: Vec<String> = resolved(&typed, std::slice::from_ref(&store), kind)
                .into_iter()
                .map(|entry| entry.endpoint)
                .collect();
            assert_eq!(shown, merged(&typed, std::slice::from_ref(&store), kind));
        }
    }

    #[test]
    fn a_failed_refresh_keeps_yesterdays_services() {
        // Losing every tracker because a web server had a bad minute is worse than a stale list.
        let index = parse_index(&index_json("")).unwrap();
        let mut store = store_from("https://example.org/i.json", index, 10, false);
        store.last_error = "connection reset".to_owned();
        assert_eq!(endpoints_of(&[store], ServiceKind::Tracker).len(), 1);
    }
}
