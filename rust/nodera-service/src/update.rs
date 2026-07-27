//! Self-update from a GitHub release, gated on a clean drain.
//!
//! ## The shape of the problem
//!
//! Operators run these binaries on hosts they do not check daily. A tracker or rendezvous that never
//! updates is the component most likely to be running a build with a known bug — and the one whose
//! restart hurts most, because a rendezvous restart cuts every circuit it is bridging. So the update
//! lane is not "download and restart"; it is:
//!
//! 1. notice that the published binary is not the one running,
//! 2. download and **verify** the replacement before touching anything,
//! 3. hand the running work off — drain, announce `Draining`, tell connected peers where to go,
//! 4. swap the file and re-exec.
//!
//! Steps 3 and 4 are the caller's; this module owns 1, 2 and the swap itself.
//!
//! ## Why the comparison is a digest, not a version string
//!
//! The project publishes a rolling `latest` prerelease on every push, so the product version in
//! `VERSION` stays `0.1.0` across hundreds of distinct builds. A version comparison would see no
//! change and never update. Comparing the **published SHA-256 of our own asset** against the digest of
//! the file we are executing answers the question that actually matters — *am I running the binary
//! that is published?* — with no version plumbing and no room for an off-by-one in an ordering rule.
//!
//! ## Consent
//!
//! `channel` is empty by default, which means off. Someone who downloaded a tracker agreed to run a
//! tracker; they did not agree to let it replace its own executable. The project's own deployments set
//! it. This is the same consent rule the telemetry lane follows, for the same reason.
//!
//! ## What is deliberately not here
//!
//! The digest comes from the release, so this verifies **integrity, not provenance**: anyone who can
//! publish to the release can publish a digest for what they published. Release signing is tracked as
//! an open limitation rather than pretended away — see `docs/tracker/LIMITATIONS.md` (L-61).

use std::io;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

/// Where the rolling release lives. Overridable so a fork, a mirror, or a test can point elsewhere.
pub const DEFAULT_FEED_BASE_URL: &str =
    "https://github.com/Ashu11-A/NoderaMC/releases/download/latest";

/// The digest manifest published alongside the binaries.
pub const CHECKSUM_ASSET: &str = "SHA256SUMS";

/// How the update lane is configured. Empty `channel` means the whole lane is inert.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateConfig {
    /// Release channel; empty disables updating entirely.
    pub channel: String,
    /// Base URL that `<base>/<asset>` resolves against.
    pub feed_base_url: String,
    /// The asset name for this binary (`nodera-tracker`, `nodera-rendezvous`).
    pub asset_name: String,
    /// How often to check.
    pub check_interval_seconds: u64,
    /// How long in-flight work may hold up the restart.
    pub drain_grace_seconds: u64,
}

impl UpdateConfig {
    /// Whether updating is enabled at all.
    pub fn enabled(&self) -> bool {
        !self.channel.trim().is_empty()
    }

    /// The URL of an asset in the configured release.
    pub fn asset_url(&self, asset: &str) -> String {
        format!("{}/{}", self.feed_base_url.trim_end_matches('/'), asset)
    }
}

/// The largest response the update lane will hold in memory.
///
/// A release binary is single-digit megabytes and the digest manifest is a few hundred bytes. This
/// ceiling exists so a feed that answers with an endless stream — a misconfigured proxy, a hostile
/// mirror — costs a bounded allocation and an error instead of the service's memory.
pub const MAX_RESPONSE_BYTES: u64 = 128 * 1024 * 1024;

/// Fetches bytes over HTTPS.
///
/// A trait, and blocking, for two reasons: this path runs once every few hours and has no business
/// holding an async runtime's attention, and a test needs to drive the whole decision path — digest
/// mismatch, missing manifest, unreachable feed — without a network.
pub trait Fetcher: Send + Sync {
    /// Fetch `url`, or fail.
    fn get(&self, url: &str) -> io::Result<Vec<u8>>;
}

/// The default fetcher: an in-process HTTPS client.
///
/// This replaced a `curl` subprocess (L-82). The original reasoning for `curl` was that the
/// alternative pulls a TLS stack into a service whose whole point is to be small, and that curl is
/// present on every host that could install a release in the first place. The second half turned
/// out to be false in the deployment that matters most: the published container images are
/// `alpine` with one static binary in them, and `curl` is exactly the kind of thing they do not
/// have. Shipping it to satisfy an update path would have added a package, a package manager's
/// worth of attack surface, and a second TLS configuration to keep correct.
///
/// What the in-process client buys beyond removing that dependency: the timeout, the redirect
/// limit and the response ceiling are all values this code sets and a test can assert, rather than
/// flags on a subprocess whose absence, version and proxy configuration are the host's business.
#[derive(Debug, Clone)]
pub struct HttpsFetcher {
    /// Whole-request timeout in seconds, redirects included. 0 means the default.
    pub timeout_seconds: u64,
    /// Largest response accepted.
    pub max_bytes: u64,
}

impl Default for HttpsFetcher {
    fn default() -> Self {
        Self {
            timeout_seconds: 0,
            max_bytes: MAX_RESPONSE_BYTES,
        }
    }
}

impl HttpsFetcher {
    /// Seconds allowed for one whole request.
    fn timeout(&self) -> std::time::Duration {
        std::time::Duration::from_secs(if self.timeout_seconds == 0 {
            60
        } else {
            self.timeout_seconds
        })
    }
}

impl Fetcher for HttpsFetcher {
    fn get(&self, url: &str) -> io::Result<Vec<u8>> {
        // Redirects are followed because a GitHub release asset URL is one: the release page hands
        // out a redirect to object storage. Bounded, though — an unbounded chain is a way to make
        // this request last as long as an attacker likes despite the timeout.
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(self.timeout()))
            .max_redirects(5)
            .build()
            .into();
        let mut response = agent
            .get(url)
            .call()
            .map_err(|e| io::Error::other(format!("fetch failed for {url}: {e}")))?;
        response
            .body_mut()
            .with_config()
            .limit(self.max_bytes)
            .read_to_vec()
            .map_err(|e| io::Error::other(format!("reading {url} failed: {e}")))
    }
}

/// The previous fetcher: `curl -fsSL`.
///
/// Kept, and kept working, for the host where curl is the right answer — one already configured
/// with a proxy, a corporate CA bundle, or a `.curlrc` that the operator maintains and this code
/// has no business duplicating. It is no longer the default; see [`HttpsFetcher`].
#[derive(Debug, Clone)]
pub struct CurlFetcher {
    /// Per-request timeout in seconds.
    pub timeout_seconds: u64,
    /// The program to run. `curl` unless an operator has a reason to name a specific one.
    ///
    /// A field rather than a literal so the missing-program path is reachable from a test: that
    /// error message is the only thing standing between an operator and an update lane that fails
    /// for a reason nobody can guess from the outside.
    pub program: String,
}

impl Default for CurlFetcher {
    fn default() -> Self {
        Self {
            timeout_seconds: 0,
            program: "curl".to_owned(),
        }
    }
}

impl Fetcher for CurlFetcher {
    fn get(&self, url: &str) -> io::Result<Vec<u8>> {
        let timeout = if self.timeout_seconds == 0 {
            60
        } else {
            self.timeout_seconds
        };
        let output = std::process::Command::new(&self.program)
            .arg("--fail")
            .arg("--silent")
            .arg("--show-error")
            .arg("--location")
            .arg("--max-time")
            .arg(timeout.to_string())
            .arg(url)
            .output()
            .map_err(|e| {
                io::Error::new(
                    e.kind(),
                    format!(
                        "cannot run curl, which the update lane needs ({e}); \
                         install curl or set update_channel = \"\" to disable updating"
                    ),
                )
            })?;
        if !output.status.success() {
            return Err(io::Error::other(format!(
                "curl failed for {url}: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            )));
        }
        Ok(output.stdout)
    }
}

/// A verified replacement, staged next to the running executable but not yet in place.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StagedUpdate {
    /// Where the new binary was written.
    pub staged_path: PathBuf,
    /// The executable it will replace.
    pub target_path: PathBuf,
    /// The published digest, hex — what the caller logs so an operator can confirm the build.
    pub digest_hex: String,
}

/// The SHA-256 of a file, hex-encoded.
pub fn digest_of(path: &Path) -> io::Result<String> {
    let bytes = std::fs::read(path)?;
    Ok(hex(&Sha256::digest(bytes)))
}

/// Find the digest for `asset` in a `SHA256SUMS` body.
///
/// Accepts both the `sha256sum` layouts (`<hex>  <name>` and `<hex> *<name>`) and matches on the
/// file's base name, so a manifest generated with a path prefix still resolves.
pub fn digest_for_asset(manifest: &str, asset: &str) -> Option<String> {
    for line in manifest.lines() {
        let mut parts = line.split_whitespace();
        let hex = parts.next()?;
        let name = parts.next().unwrap_or_default();
        let name = name.trim_start_matches('*');
        let base = name.rsplit('/').next().unwrap_or(name);
        if base == asset && hex.len() == 64 && hex.chars().all(|c| c.is_ascii_hexdigit()) {
            return Some(hex.to_ascii_lowercase());
        }
    }
    None
}

/// Whether a newer build is published, and its digest.
///
/// `Ok(None)` means "already current" — the common case, and deliberately not an error.
pub fn check(
    config: &UpdateConfig,
    fetcher: &dyn Fetcher,
    running_binary: &Path,
) -> io::Result<Option<String>> {
    if !config.enabled() {
        return Ok(None);
    }
    let manifest = fetcher.get(&config.asset_url(CHECKSUM_ASSET))?;
    let manifest = String::from_utf8_lossy(&manifest);
    let published = digest_for_asset(&manifest, &config.asset_name).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            format!("{CHECKSUM_ASSET} lists no digest for {}", config.asset_name),
        )
    })?;
    let running = digest_of(running_binary)?;
    if published == running {
        Ok(None)
    } else {
        Ok(Some(published))
    }
}

/// Download the published asset, verify it against `expected_digest`, and stage it next to the target.
///
/// Nothing is swapped here. A verified file on disk beside the running one is a state an operator can
/// inspect, and it means the risky step (replacing the executable) happens only after the download is
/// known good rather than while bytes are still arriving.
pub fn stage(
    config: &UpdateConfig,
    fetcher: &dyn Fetcher,
    target_path: &Path,
    expected_digest: &str,
) -> io::Result<StagedUpdate> {
    let bytes = fetcher.get(&config.asset_url(&config.asset_name))?;
    let actual = hex(&Sha256::digest(&bytes));
    if actual != expected_digest.to_ascii_lowercase() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "downloaded {} does not match its published digest \
                 (published {expected_digest}, downloaded {actual}) — refusing to install",
                config.asset_name
            ),
        ));
    }
    if bytes.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "downloaded update is empty",
        ));
    }
    let staged_path = staged_path_for(target_path);
    std::fs::write(&staged_path, &bytes)?;
    make_executable(&staged_path)?;
    Ok(StagedUpdate {
        staged_path,
        target_path: target_path.to_path_buf(),
        digest_hex: actual,
    })
}

/// Move a staged update into place.
///
/// A rename, so the replacement is atomic: there is no instant at which the executable path holds a
/// half-written file. On Unix the running process keeps its own open image, which is why the swap is
/// safe to do before the re-exec rather than after.
pub fn install(staged: &StagedUpdate) -> io::Result<()> {
    std::fs::rename(&staged.staged_path, &staged.target_path)
}

/// Replace this process with the binary at `target_path`, preserving its arguments.
///
/// Only returns on failure — success is the process image being replaced. Re-exec rather than
/// spawn-and-exit so the service keeps its pid, its supervisor relationship, and its systemd unit.
pub fn restart_into(target_path: &Path) -> io::Error {
    let args: Vec<String> = std::env::args().skip(1).collect();
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        std::process::Command::new(target_path).args(&args).exec()
    }
    #[cfg(not(unix))]
    {
        match std::process::Command::new(target_path).args(&args).spawn() {
            Ok(_) => std::process::exit(0),
            Err(e) => e,
        }
    }
}

/// Whether the running executable can actually be replaced.
///
/// Checked before draining: cutting every circuit for an update that then cannot be installed —
/// because the binary lives in a root-owned directory, or a read-only image — would be strictly worse
/// than not updating.
pub fn is_installable(target_path: &Path) -> bool {
    let Some(parent) = target_path.parent() else {
        return false;
    };
    let probe = staged_path_for(target_path);
    if std::fs::write(&probe, b"nodera-update-probe").is_err() {
        return false;
    }
    let renamable = parent.exists();
    let _ = std::fs::remove_file(&probe);
    renamable
}

fn staged_path_for(target_path: &Path) -> PathBuf {
    let mut name = target_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "nodera-service".to_owned());
    name.push_str(".update");
    target_path.with_file_name(name)
}

#[cfg(unix)]
fn make_executable(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> io::Result<()> {
    Ok(())
}

fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::Mutex;

    struct FakeFeed {
        bodies: Mutex<HashMap<String, Vec<u8>>>,
    }

    impl FakeFeed {
        fn new() -> Self {
            Self {
                bodies: Mutex::new(HashMap::new()),
            }
        }

        fn serve(&self, asset: &str, body: &[u8]) {
            self.bodies
                .lock()
                .unwrap()
                .insert(asset.to_owned(), body.to_vec());
        }
    }

    impl Fetcher for FakeFeed {
        fn get(&self, url: &str) -> io::Result<Vec<u8>> {
            let asset = url.rsplit('/').next().unwrap_or_default().to_owned();
            self.bodies
                .lock()
                .unwrap()
                .get(&asset)
                .cloned()
                .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("no {asset}")))
        }
    }

    fn config() -> UpdateConfig {
        UpdateConfig {
            channel: "latest".to_owned(),
            feed_base_url: "https://example.invalid/releases/download/latest".to_owned(),
            asset_name: "nodera-rendezvous".to_owned(),
            check_interval_seconds: 3_600,
            drain_grace_seconds: 30,
        }
    }

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "nodera-update-{tag}-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn manifest_for(asset: &str, body: &[u8]) -> String {
        format!("{}  {asset}\n", hex(&Sha256::digest(body)))
    }

    #[test]
    fn an_empty_channel_makes_the_whole_lane_inert() {
        // The consent rule: running a tracker is not agreeing to let it rewrite its own binary.
        let off = UpdateConfig {
            channel: "  ".to_owned(),
            ..config()
        };
        assert!(!off.enabled());
        let feed = FakeFeed::new();
        // No manifest is served, so a lane that checked anyway would error rather than return None.
        assert_eq!(check(&off, &feed, Path::new("/nonexistent")).unwrap(), None);
    }

    #[test]
    fn running_the_published_build_reports_no_update() {
        let dir = temp_dir("current");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve(
            CHECKSUM_ASSET,
            manifest_for("nodera-rendezvous", b"build-A").as_bytes(),
        );
        assert_eq!(check(&config(), &feed, &binary).unwrap(), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_different_published_digest_is_an_available_update() {
        // A rolling `latest` keeps VERSION at 0.1.0 across every build, so the digest is the only
        // comparison that can see a new build at all.
        let dir = temp_dir("newer");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve(
            CHECKSUM_ASSET,
            manifest_for("nodera-rendezvous", b"build-B").as_bytes(),
        );
        let found = check(&config(), &feed, &binary).unwrap().unwrap();
        assert_eq!(found, hex(&Sha256::digest(b"build-B")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_manifest_without_our_asset_is_an_error_not_a_silent_skip() {
        let dir = temp_dir("missing-asset");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve(
            CHECKSUM_ASSET,
            manifest_for("nodera-tracker", b"build-B").as_bytes(),
        );
        assert!(check(&config(), &feed, &binary).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_download_that_does_not_match_its_digest_is_refused_before_anything_is_staged() {
        // The decisive property of the whole lane: a corrupted or substituted download never becomes
        // the executable, and no drain is spent on it.
        let dir = temp_dir("mismatch");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve("nodera-rendezvous", b"something-else-entirely");
        let expected = hex(&Sha256::digest(b"build-B"));
        let err = stage(&config(), &feed, &binary, &expected).unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(!binary.with_file_name("nodera-rendezvous.update").exists());
        assert_eq!(std::fs::read(&binary).unwrap(), b"build-A");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_empty_download_is_refused() {
        let dir = temp_dir("empty");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve("nodera-rendezvous", b"");
        let expected = hex(&Sha256::digest(b""));
        assert!(stage(&config(), &feed, &binary, &expected).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn staging_then_installing_replaces_the_binary_atomically() {
        let dir = temp_dir("install");
        let binary = dir.join("nodera-rendezvous");
        std::fs::write(&binary, b"build-A").unwrap();
        let feed = FakeFeed::new();
        feed.serve("nodera-rendezvous", b"build-B");
        let expected = hex(&Sha256::digest(b"build-B"));

        let staged = stage(&config(), &feed, &binary, &expected).unwrap();
        // Staged, verified, and the running binary is still untouched.
        assert_eq!(std::fs::read(&binary).unwrap(), b"build-A");
        assert_eq!(std::fs::read(&staged.staged_path).unwrap(), b"build-B");

        install(&staged).unwrap();
        assert_eq!(std::fs::read(&binary).unwrap(), b"build-B");
        assert!(!staged.staged_path.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_staged_update_is_executable() {
        let dir = temp_dir("mode");
        let binary = dir.join("nodera-tracker");
        std::fs::write(&binary, b"a").unwrap();
        let feed = FakeFeed::new();
        feed.serve("nodera-tracker", b"b");
        let cfg = UpdateConfig {
            asset_name: "nodera-tracker".to_owned(),
            ..config()
        };
        let staged = stage(&cfg, &feed, &binary, &hex(&Sha256::digest(b"b"))).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&staged.staged_path)
                .unwrap()
                .permissions()
                .mode();
            assert_ne!(
                mode & 0o111,
                0,
                "a staged update that is not executable cannot be run"
            );
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn both_sha256sum_manifest_layouts_and_a_path_prefix_resolve() {
        let two_space = "aa".repeat(32) + "  nodera-tracker\n";
        assert_eq!(
            digest_for_asset(&two_space, "nodera-tracker"),
            Some("aa".repeat(32))
        );
        let binary_star = "bb".repeat(32) + " *nodera-tracker\n";
        assert_eq!(
            digest_for_asset(&binary_star, "nodera-tracker"),
            Some("bb".repeat(32))
        );
        let with_path = "cc".repeat(32) + "  build/nodera-tracker\n";
        assert_eq!(
            digest_for_asset(&with_path, "nodera-tracker"),
            Some("cc".repeat(32))
        );
        let uppercase = "AA".repeat(32) + "  nodera-tracker\n";
        assert_eq!(
            digest_for_asset(&uppercase, "nodera-tracker"),
            Some("aa".repeat(32)),
            "digests compare case-insensitively"
        );
    }

    #[test]
    fn a_malformed_manifest_line_is_ignored_rather_than_trusted() {
        let junk =
            "not-a-digest  nodera-tracker\n".to_owned() + &"dd".repeat(32) + "  nodera-tracker\n";
        assert_eq!(
            digest_for_asset(&junk, "nodera-tracker"),
            Some("dd".repeat(32))
        );
        assert_eq!(digest_for_asset("", "nodera-tracker"), None);
    }

    #[test]
    fn an_unwritable_location_is_reported_before_any_drain_is_spent() {
        assert!(!is_installable(Path::new("/proc/1/nodera-tracker")));
        let dir = temp_dir("writable");
        let binary = dir.join("nodera-tracker");
        std::fs::write(&binary, b"a").unwrap();
        assert!(is_installable(&binary));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn asset_urls_do_not_double_the_separator() {
        let trailing = UpdateConfig {
            feed_base_url: "https://example.invalid/latest/".to_owned(),
            ..config()
        };
        assert_eq!(
            trailing.asset_url("SHA256SUMS"),
            "https://example.invalid/latest/SHA256SUMS"
        );
    }

    #[test]
    fn a_missing_curl_still_says_what_to_do_about_it() {
        // `CurlFetcher` is no longer the default (L-82) but it is still reachable, and the value of
        // it was always this message: an update lane that fails because a program is absent must
        // say so, and say how to turn the lane off, rather than reporting a bare ENOENT.
        let fetcher = CurlFetcher {
            program: "nodera-no-such-program".to_owned(),
            ..CurlFetcher::default()
        };
        let error = fetcher
            .get("https://example.invalid/whatever")
            .expect_err("a program that does not exist cannot fetch");
        let rendered = error.to_string();

        assert!(rendered.contains("cannot run curl"), "{rendered}");
        assert!(rendered.contains("update_channel"), "{rendered}");
    }

    #[test]
    fn the_default_fetcher_caps_what_it_will_hold_in_memory() {
        // A feed that answers with an endless stream must cost a bounded allocation, not the
        // service. The ceiling is the code's, not the transport's.
        assert_eq!(HttpsFetcher::default().max_bytes, MAX_RESPONSE_BYTES);
        assert_eq!(
            HttpsFetcher::default().timeout(),
            std::time::Duration::from_secs(60)
        );
    }

    #[test]
    fn a_response_larger_than_the_ceiling_is_refused_rather_than_allocated() {
        use std::io::{Read, Write};
        use std::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("addr");
        let server = std::thread::spawn(move || {
            let Ok((mut stream, _)) = listener.accept() else {
                return;
            };
            let mut discard = [0u8; 1024];
            let _ = stream.read(&mut discard);
            // Declares more than the ceiling allows, then sends what it can before the client hangs
            // up. `Content-Length` alone is not trusted — the read itself is what is bounded.
            let _ = stream.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n",
            );
            let chunk = vec![b'x'; 4096];
            for _ in 0..256 {
                if stream.write_all(&chunk).is_err() {
                    return;
                }
            }
        });

        let fetcher = HttpsFetcher {
            timeout_seconds: 10,
            max_bytes: 1024,
        };
        let result = fetcher.get(&format!("http://{addr}/big"));
        let _ = server.join();

        assert!(
            result.is_err(),
            "a body past the ceiling must fail, not truncate silently: {:?}",
            result.map(|b| b.len())
        );
    }

    #[test]
    fn the_default_fetcher_reads_a_body_it_is_allowed_to_read() {
        use std::io::{Read, Write};
        use std::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("addr");
        let server = std::thread::spawn(move || {
            let Ok((mut stream, _)) = listener.accept() else {
                return;
            };
            let mut discard = [0u8; 1024];
            let _ = stream.read(&mut discard);
            let _ = stream.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\nhello world",
            );
        });

        let body = HttpsFetcher::default()
            .get(&format!("http://{addr}/small"))
            .expect("a small body is fetched");
        let _ = server.join();

        assert_eq!(body, b"hello world");
    }
}
