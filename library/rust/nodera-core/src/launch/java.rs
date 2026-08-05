//! Finding a Java runtime that can actually run this version.
//!
//! # Why the version is probed rather than assumed
//!
//! Minecraft 1.21.1 declares `javaVersion.majorVersion: 21`. A machine with Java 17 on `PATH` —
//! which is most machines that have ever installed anything JVM-shaped — will start, spend ten
//! seconds on a black window, and die with `UnsupportedClassVersionError: … has been compiled by a
//! more recent version of the Java Runtime`. That message is accurate and useless: it names a class
//! file, not the runtime the player has to install.
//!
//! So every candidate is run once with `-version` and its major version read. One 40 ms subprocess
//! buys an error that says "this needs Java 21; the one found is 17, at /usr/bin/java".

use std::path::{Path, PathBuf};

/// A runtime this machine has, and what it is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Runtime {
    pub path: PathBuf,
    pub major: u32,
    /// Where it was found, for the log: `profile`, `bundled`, `JAVA_HOME`, `PATH`.
    pub source: &'static str,
}

/// The executable name, which is the one thing Windows spells differently.
///
/// `javaw` rather than `java` there, deliberately: `java.exe` is a console subsystem binary, so
/// launching the game from a windowed app opens an empty terminal beside it that the player then has
/// to leave open for the game to keep running.
pub fn executable() -> &'static str {
    if cfg!(target_os = "windows") {
        "javaw.exe"
    } else {
        "java"
    }
}

/// The console executable — used for `-version`, because `javaw` writes nowhere.
fn probe_executable() -> &'static str {
    if cfg!(target_os = "windows") {
        "java.exe"
    } else {
        "java"
    }
}

/// Ask a runtime what it is. `None` when it cannot be run at all.
///
/// Both streams are read: every JDK since 1.0 has written `-version` to **stderr**, and several
/// recent ones write to stdout instead. A probe that read only one of them would report every
/// runtime on the other kind of machine as unusable.
pub fn probe(java: &Path) -> Option<u32> {
    let probe_path = java.with_file_name(probe_executable());
    let candidate = if probe_path.is_file() {
        probe_path
    } else {
        java.to_path_buf()
    };
    let output = std::process::Command::new(&candidate)
        .arg("-version")
        .output()
        .ok()?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stderr),
        String::from_utf8_lossy(&output.stdout)
    );
    parse_major(&text)
}

/// The major version out of a `java -version` banner.
///
/// Two spellings, because both are still in the wild:
///   `openjdk version "21.0.4"`  → 21
///   `java version "1.8.0_402"`  → 8
pub fn parse_major(banner: &str) -> Option<u32> {
    let quoted = banner.split('"').nth(1)?;
    let mut parts = quoted.split(['.', '_', '-']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        // The pre-9 scheme: the major version is the *second* component.
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// Every runtime worth trying, best first.
///
/// The order is a claim about which one the player actually plays with:
///
/// 1. the profile's own `javaDir` — they chose it, in their launcher, for this profile;
/// 2. the runtime the official launcher downloaded under `<root>/runtime/<component>` — it is the
///    one matched to this version and it is present precisely because they launched this version;
/// 3. `JAVA_HOME`;
/// 4. `PATH`.
///
/// Nothing here is filtered by version — that is [`select`]'s job, so the error can say what was
/// found as well as what was needed.
pub fn candidates(
    root: &Path,
    profile_java: Option<&Path>,
    component: &str,
) -> Vec<(PathBuf, &'static str)> {
    let mut out: Vec<(PathBuf, &'static str)> = Vec::new();

    if let Some(chosen) = profile_java {
        out.push((normalise(chosen), "profile"));
    }

    // `runtime/<component>/<os-arch>/<component>/bin/java`. The middle directory is named for the
    // platform and there is exactly one, so it is read rather than reconstructed — the names Mojang
    // uses there ("linux", "mac-os-arm64", "windows-x64") are not derivable from Rust's target
    // triple without a table that would go stale.
    if !component.is_empty() {
        let base = root.join("runtime").join(component);
        if let Ok(entries) = std::fs::read_dir(&base) {
            for entry in entries.flatten() {
                let candidate = entry.path().join(component).join("bin").join(executable());
                if candidate.is_file() {
                    out.push((candidate, "bundled"));
                }
            }
        }
    }

    if let Ok(home) = std::env::var("JAVA_HOME") {
        if !home.trim().is_empty() {
            out.push((
                PathBuf::from(home).join("bin").join(executable()),
                "JAVA_HOME",
            ));
        }
    }

    out.push((PathBuf::from(executable()), "PATH"));
    out.retain(|(path, source)| *source == "PATH" || path.is_file());
    out
}

/// Turn whatever the profile recorded into an executable path.
///
/// Launchers write this field three different ways — the executable itself, the `bin` directory, or
/// the JDK home — and all three are common enough that picking one and being wrong means the app
/// refuses to launch on a correctly configured machine.
fn normalise(recorded: &Path) -> PathBuf {
    if recorded.is_file() {
        return recorded.to_path_buf();
    }
    let direct = recorded.join(executable());
    if direct.is_file() {
        return direct;
    }
    recorded.join("bin").join(executable())
}

/// Pick a runtime that satisfies `required`, or say what is wrong in one sentence.
pub fn select(
    root: &Path,
    profile_java: Option<&Path>,
    component: &str,
    required: u32,
) -> Result<Runtime, String> {
    let candidates = candidates(root, profile_java, component);
    if candidates.is_empty() {
        return Err(format!(
            "this version needs Java {required}, and no Java runtime was found on this machine"
        ));
    }

    let mut best: Option<Runtime> = None;
    for (path, source) in candidates {
        let Some(major) = probe(&path) else { continue };
        let runtime = Runtime {
            path,
            major,
            source,
        };
        if runtime.major >= required {
            return Ok(runtime);
        }
        // Remember the closest miss, so the refusal can name a real runtime rather than a shrug.
        if best
            .as_ref()
            .map(|held| runtime.major > held.major)
            .unwrap_or(true)
        {
            best = Some(runtime);
        }
    }

    match best {
        Some(found) => Err(format!(
            "this version needs Java {required}; the newest one found is Java {} at {}",
            found.major,
            found.path.display()
        )),
        None => Err(format!(
            "this version needs Java {required}, and nothing that answered `-version` was usable"
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn both_version_spellings_are_read() {
        assert_eq!(
            parse_major("openjdk version \"21.0.4\" 2024-07-16"),
            Some(21)
        );
        assert_eq!(parse_major("openjdk version \"17.0.11\""), Some(17));
        // The pre-9 scheme puts the major version second. A parser that took the first component
        // would read Java 8 as Java 1 and reject every machine that has it.
        assert_eq!(parse_major("java version \"1.8.0_402\""), Some(8));
        assert_eq!(parse_major("openjdk version \"24-ea\""), Some(24));
        assert_eq!(parse_major("no version here"), None);
    }

    #[test]
    fn a_machine_with_only_an_old_runtime_is_told_which_one_it_has() {
        let root = std::env::temp_dir().join(format!("nodera-java-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();

        // Nothing on this synthetic root, and `PATH`'s java is whatever the test machine has — so
        // assert the *shape* of the refusal rather than a specific version, which would make this
        // test's result depend on the machine running it.
        let refused = select(&root, None, "java-runtime-delta", 999);
        let error = refused.unwrap_err();
        assert!(error.contains("needs Java 999"), "{error}");

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn a_profile_javadir_is_accepted_as_an_executable_a_bin_dir_or_a_home() {
        let root = std::env::temp_dir().join(format!("nodera-javadir-{}", std::process::id()));
        let home = root.join("jdk");
        std::fs::create_dir_all(home.join("bin")).unwrap();
        let exe = home.join("bin").join(executable());
        std::fs::write(&exe, b"#!/bin/sh\n").unwrap();

        // All three spellings appear in real `launcher_profiles.json` files, and picking one would
        // mean refusing to launch on a correctly configured machine.
        assert_eq!(normalise(&exe), exe);
        assert_eq!(normalise(&home.join("bin")), exe);
        assert_eq!(normalise(&home), exe);

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn the_windowed_launcher_never_opens_a_console() {
        // `java.exe` is a console-subsystem binary: launching the game with it from a windowed app
        // opens an empty terminal the player must leave open for the game to keep running.
        if cfg!(target_os = "windows") {
            assert_eq!(executable(), "javaw.exe");
            assert_eq!(
                probe_executable(),
                "java.exe",
                "but `javaw` writes nowhere, so probe with `java`"
            );
        } else {
            assert_eq!(executable(), "java");
        }
    }
}
