//! Where this node keeps the world data it holds for other people — and letting the user choose.
//!
//! # Why this needs a command rather than a text field
//!
//! On a desktop a path is a path: the user types or picks one and it either exists or does not. On
//! Android almost nothing is writable. An app may write to its own private directory and to the
//! app-specific directories on shared volumes, and everywhere else needs the Storage Access
//! Framework, which hands back a `content://` URI that a Java worker cannot open as a file at all.
//!
//! So this offers the locations that genuinely work on the device it is running on, with the free
//! space on each, and lets the user pick one. Offering a free-text path on a phone would be
//! offering a way to configure a node that then silently fails to store anything.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// What the system folder picker came back with.
///
/// Mirrors the JSON `NoderaStorage.kt` writes. `pending` is the state between opening the picker
/// and the user answering it — a distinct thing from "cancelled", which the UI has to be able to
/// tell apart or it will report a failure the moment the picker opens.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct PickedFolder {
    /// True while the picker is open and no answer has been written yet.
    #[serde(default)]
    pub pending: bool,
    /// The `content://` tree URI, empty when nothing was chosen.
    #[serde(default)]
    pub uri: String,
    /// The folder's display name, as the file manager showed it.
    #[serde(default)]
    pub label: String,
    /// The filesystem path it maps to, empty when Android exposes none.
    #[serde(default)]
    pub path: String,
    /// Whether a real write succeeded there. A grant is not the same as access.
    #[serde(default)]
    pub writable: bool,
    /// Why it cannot be used, in words meant for the person who chose it.
    #[serde(default)]
    pub error: String,
}

/// Read the picker's answer, if it has been written yet.
///
/// Two locations are tried because the two runtimes disagree about what "the app's directory"
/// means: Tauri's `app_data_dir()` is `/data/user/0/<pkg>`, while Android's `Context.filesDir` —
/// the only directory Kotlin can write to without ceremony — is `/data/user/0/<pkg>/files`. The
/// answer was being written to the second and read from the first, so a successful pick looked
/// exactly like a cancelled one.
pub fn picked_folder(data_dir: &Path) -> PickedFolder {
    for candidate in [
        data_dir.join("files/storage-pick.json"),
        data_dir.join("storage-pick.json"),
    ] {
        if let Ok(raw) = std::fs::read_to_string(&candidate) {
            return serde_json::from_str(&raw).unwrap_or_default();
        }
    }
    // No file yet means the picker is still open — or was never opened, which the UI only asks
    // about after opening it.
    PickedFolder {
        pending: true,
        ..PickedFolder::default()
    }
}

/// One place the peer could keep world data.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StorageOption {
    /// Absolute path.
    pub path: String,
    /// Short name for the UI ("App storage", "SD card").
    pub label: String,
    /// One line on what choosing it means.
    pub detail: String,
    /// Free bytes on the volume, or `null` when it could not be measured — never 0, which would
    /// read as "full".
    pub free_bytes: Option<u64>,
    /// Whether a test write in this location actually succeeded.
    pub writable: bool,
}

/// The current storage picture.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StorageInfo {
    /// The directory in force, resolved — never empty, so the UI always has something to show.
    pub current: String,
    /// Whether `current` is the platform default rather than a choice the user made.
    pub is_default: bool,
    /// Everywhere this device can actually write.
    pub options: Vec<StorageOption>,
}

/// Probe the locations this platform offers.
///
/// `configured` is the saved setting (may be empty), `data_dir` the app's own private directory.
pub fn storage_info(configured: &str, data_dir: &Path) -> StorageInfo {
    let mut options = Vec::new();
    let internal = data_dir.join("worlds");
    options.push(describe(
        &internal,
        "App storage",
        "Private to Nodera. Removed when the app is uninstalled.",
    ));

    for (path, label, detail) in external_candidates(data_dir) {
        let option = describe(&path, label, detail);
        // Listed only when it works. A row that says "not writable" is a row the user will tap.
        if option.writable {
            options.push(option);
        }
    }

    let current = if configured.trim().is_empty() {
        internal.display().to_string()
    } else {
        configured.trim().to_owned()
    };

    // A folder the user chose through the system picker is not one of this platform's standard
    // locations, so without this it would vanish from the list the moment they left the screen —
    // the setting was saved, nothing showed it, and the choice looked like it had reset. It is
    // listed first, and re-probed like any other, so "in use" and "still writable" stay honest.
    if !options.iter().any(|o| o.path == current) {
        options.insert(
            0,
            describe_owned(
                Path::new(&current),
                "Chosen folder",
                "Selected with your file manager.",
            ),
        );
    }

    StorageInfo {
        is_default: configured.trim().is_empty(),
        current,
        options,
    }
}

/// [`describe`] for a label that is not a `'static` string.
fn describe_owned(path: &Path, label: &str, detail: &str) -> StorageOption {
    let mut option = describe(path, "", "");
    option.label = label.to_owned();
    option.detail = detail.to_owned();
    option
}

/// Candidate locations beyond the app's private directory.
///
/// On Android these are the app-specific directories on removable and shared volumes: writable
/// without any permission prompt, and real filesystem paths a Java process can open. On a desktop
/// the home directory is offered, which is where a person expects their data to live.
fn external_candidates(_data_dir: &Path) -> Vec<(PathBuf, &'static str, &'static str)> {
    if cfg!(target_os = "android") {
        // Asked of the framework rather than assembled from a guessed path: `getExternalFilesDirs`
        // returns shared storage first and then each removable card, and only the ones currently
        // mounted. A hand-built `/storage/emulated/0/...` path finds shared storage and can never
        // find an SD card.
        crate::android::battery::external_storage_dirs()
            .into_iter()
            .enumerate()
            .map(|(index, dir)| {
                (
                    PathBuf::from(dir).join("worlds"),
                    if index == 0 { "Shared storage" } else { "SD card" },
                    if index == 0 {
                        "Visible over USB. Survives clearing the app's data."
                    } else {
                        "Removable card. Available while it is inserted."
                    },
                )
            })
            .collect()
    } else {
        std::env::var("HOME")
            .ok()
            .map(|home| {
                vec![(
                    PathBuf::from(home).join("Nodera/worlds"),
                    "Home folder",
                    "A normal folder you can browse and back up.",
                )]
            })
            .unwrap_or_default()
    }
}

/// Measure one candidate: can we create it, can we write in it, how much room is left.
///
/// Probed rather than assumed. A directory that exists is not necessarily writable — on Android an
/// app-specific folder on an unmounted SD card exists as a path and fails on the first write, and
/// discovering that when a peer tries to store a world is far too late.
fn describe(path: &Path, label: &str, detail: &str) -> StorageOption {
    let writable = std::fs::create_dir_all(path)
        .and_then(|_| {
            let probe = path.join(".nodera-write-probe");
            std::fs::write(&probe, b"nodera")?;
            std::fs::remove_file(&probe)
        })
        .is_ok();
    StorageOption {
        path: path.display().to_string(),
        label: label.to_owned(),
        detail: detail.to_owned(),
        free_bytes: free_space(path),
        writable,
    }
}

#[cfg(unix)]
fn free_space(path: &Path) -> Option<u64> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    let c_path = CString::new(path.as_os_str().as_bytes()).ok()?;
    // SAFETY: `statvfs` reads into a zeroed struct we own, and the path is a valid NUL-terminated
    // C string for the duration of the call.
    unsafe {
        let mut stat: libc::statvfs = std::mem::zeroed();
        if libc::statvfs(c_path.as_ptr(), &mut stat) != 0 {
            return None;
        }
        Some(stat.f_bavail as u64 * stat.f_frsize as u64)
    }
}

#[cfg(not(unix))]
fn free_space(_path: &Path) -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_default_is_reported_as_a_default_and_still_names_a_path() {
        let dir = std::env::temp_dir().join("nodera-storage-test");

        let info = storage_info("", &dir);

        assert!(info.is_default, "an empty setting is the platform default");
        assert!(
            info.current.contains("worlds"),
            "the UI must always have a concrete path to show, got {}",
            info.current
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_configured_directory_wins_and_is_not_a_default() {
        let dir = std::env::temp_dir().join("nodera-storage-test-2");

        let info = storage_info("/somewhere/chosen", &dir);

        assert_eq!(info.current, "/somewhere/chosen");
        assert!(!info.is_default);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_chosen_folder_stays_in_the_list_and_is_marked_current() {
        let dir = std::env::temp_dir().join("nodera-storage-test-4");
        let chosen = std::env::temp_dir().join("nodera-storage-chosen");
        std::fs::create_dir_all(&chosen).unwrap();

        let info = storage_info(&chosen.display().to_string(), &dir);

        // The regression this pins: a folder picked with the system picker is not one of the
        // platform's own locations, so it used to disappear from the list and the choice looked
        // like it had reset itself.
        assert_eq!(info.current, chosen.display().to_string());
        assert_eq!(info.options.first().map(|o| o.path.clone()), Some(info.current.clone()));
        assert!(info.options[0].writable);
        let _ = std::fs::remove_dir_all(&dir);
        let _ = std::fs::remove_dir_all(&chosen);
    }

    #[test]
    fn a_writable_option_is_proven_by_writing_to_it() {
        let dir = std::env::temp_dir().join("nodera-storage-test-3");

        let info = storage_info("", &dir);
        let app = info.options.first().expect("app storage is always offered");

        // Not "the path exists" — an actual write happened and was cleaned up.
        assert!(app.writable);
        assert!(!std::path::Path::new(&app.path).join(".nodera-write-probe").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
