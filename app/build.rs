//! Build script for the companion app.
//!
//! Besides Tauri's own codegen, this resolves the project's published service index — the list of
//! trackers and relays the app ships with as its built-in store — and writes it into `OUT_DIR` for
//! `stores.rs` to `include_str!`.
//!
//! The list is not in this tree. It is `index.json` on the orphan `services` branch, so that a list
//! which changes when an operator joins is data rather than a commit against the source tree. This
//! file resolves it in the same four steps, in the same order, as `scripts/services.py` and
//! `:core`'s `generateOfficialServices` task:
//!
//! 1. `git show <branch>:<index>` — the local ref.
//! 2. `git show <remote>/<branch>:<index>` — the fetched remote ref.
//! 3. `curl` on the raw URL — if this host has a network and a curl.
//! 4. the cache under `dir.services` — whatever a previous build resolved.
//!
//! Every success rewrites that cache, so one `git fetch origin services:services` makes every later
//! build offline. If all four fail the build fails, naming the fetch that would fix it: a companion
//! app that silently shipped an empty built-in store would start with nowhere to look and report
//! "no trackers are answering", which reads as a broken application rather than a broken build.
//!
//! Every name here — branch, remote, file, URL, cache directory — is read from `/layout.properties`.
//! Nothing in this file may guess any of them.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The name `stores.rs` includes out of `OUT_DIR`.
const GENERATED_INDEX: &str = "official-index.json";

fn main() {
    let root = repository_root();
    let manifest = LayoutManifest::load(&root);

    let branch = manifest.get("services.branch");
    let remote = manifest.get("services.remote");
    let index_name = manifest.get("services.index");
    let raw_url = manifest.get("services.rawBase") + &index_name;
    let cache = root.join(manifest.get("dir.services")).join(&index_name);

    // Rerun when the branch moves, and when the manifest that names it changes. A tree without the
    // ref — a fresh shallow clone — has no file to watch, so the build falls through to the network
    // and reruns every time, which is correct: there is nothing local to fingerprint.
    println!("cargo:rerun-if-changed={}", root.join("layout.properties").display());
    if let Some(head) = git_ref_path(&root, &branch) {
        println!("cargo:rerun-if-changed={}", head.display());
    }

    let body = git_show(&root, &branch, &index_name)
        .or_else(|| git_show(&root, &format!("{remote}/{branch}"), &index_name))
        .or_else(|| git_fetch(&root, &remote, &branch).then(|| git_show(&root, "FETCH_HEAD", &index_name)).flatten())
        .or_else(|| curl(&raw_url))
        .or_else(|| fs::read_to_string(&cache).ok())
        .unwrap_or_else(|| {
            panic!(
                "cannot resolve {index_name} from the '{branch}' branch: no local ref, no \
                 {remote}/{branch}, no network and no cache at {}. Run:\n    git fetch {remote} \
                 {branch}:{branch}",
                cache.display()
            )
        });

    if let Some(parent) = cache.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::write(&cache, &body);

    let out =
        PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join(GENERATED_INDEX);
    fs::write(&out, &body).unwrap_or_else(|e| panic!("cannot write {}: {e}", out.display()));

    tauri_build::build();
}

/// The repository root: the directory holding both `VERSION` and `settings.gradle.kts`.
///
/// Both markers, and a walk rather than a count of `..` segments — the same rule every other
/// consumer of the layout manifest applies, so that this crate changing depth breaks nothing.
fn repository_root() -> PathBuf {
    let start = std::env::var("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .expect("cargo sets CARGO_MANIFEST_DIR");
    let mut candidate: Option<&Path> = Some(&start);
    while let Some(directory) = candidate {
        if directory.join("VERSION").is_file() && directory.join("settings.gradle.kts").is_file() {
            return directory.to_path_buf();
        }
        candidate = directory.parent();
    }
    panic!(
        "cannot find the repository root (a directory holding both VERSION and \
         settings.gradle.kts) above {}",
        start.display()
    );
}

/// The layout table, parsed the way every other consumer parses it.
struct LayoutManifest(Vec<(String, String)>);

impl LayoutManifest {
    fn load(root: &Path) -> Self {
        let path = root.join("layout.properties");
        let text = fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        // A deliberately small subset of `java.util.Properties`: `key = value`, `#` comments, blank
        // lines. No escapes, no continuations, no interpolation — that restriction is what lets
        // five languages each parse this file in ten lines.
        let mut entries = Vec::new();
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
                continue;
            }
            if let Some((key, value)) = line.split_once('=') {
                entries.push((key.trim().to_owned(), value.trim().to_owned()));
            }
        }
        Self(entries)
    }

    fn get(&self, key: &str) -> String {
        self.0
            .iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.clone())
            .unwrap_or_else(|| panic!("layout.properties must declare {key}"))
    }
}

/// One file out of a git ref, or `None` if the ref or the file is not there.
///
/// `git show` and never a checkout: resolving the branch must not touch the working tree, which is
/// somebody's uncommitted work.
fn git_show(root: &Path, git_ref: &str, name: &str) -> Option<String> {
    let output = Command::new("git")
        .arg("-C")
        .arg(root)
        .arg("show")
        .arg(format!("{git_ref}:{name}"))
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let body = String::from_utf8(output.stdout).ok()?;
    (!body.trim().is_empty()).then_some(body)
}

/// Fetch the branch into `FETCH_HEAD`, shallowly.
///
/// Only reached when the ref is absent, which is what a `git clone --depth 1` and every
/// `actions/checkout` look like. Without this step each of them falls through to the web server, and
/// a build that could have read the branch reads an HTTP response instead. Shallow because one
/// commit of a four-file branch is all anybody wants.
fn git_fetch(root: &Path, remote: &str, branch: &str) -> bool {
    Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["fetch", "--depth", "1", "--quiet", remote, branch])
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

/// The file cargo should watch so a moved branch reruns this script.
fn git_ref_path(root: &Path, branch: &str) -> Option<PathBuf> {
    let git_dir = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["rev-parse", "--git-dir"])
        .output()
        .ok()
        .filter(|out| out.status.success())
        .and_then(|out| String::from_utf8(out.stdout).ok())?;
    let git_dir = root.join(git_dir.trim());
    let head = git_dir.join("refs").join("heads").join(branch);
    head.is_file().then_some(head)
}

/// Fetch the raw URL with `curl`.
///
/// Shelling out rather than taking an HTTP crate as a build dependency: this path runs only on a
/// host that has neither the branch nor a cache, and paying for a TLS stack in every build of the
/// app to serve that case would be the wrong trade. Absent curl simply means this step fails and
/// the cache is tried next.
fn curl(url: &str) -> Option<String> {
    let output = Command::new("curl")
        .args([
            "--fail",
            "--silent",
            "--show-error",
            "--location",
            "--max-time",
            "15",
            url,
        ])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let body = String::from_utf8(output.stdout).ok()?;
    (!body.trim().is_empty()).then_some(body)
}
