//! The golden corpus, located and read once.
//!
//! `fixtures.rs` and `mutation.rs` are two views of the same `fixtures/wire/*.bin` files — one
//! asserts byte-exact re-encoding, the other asserts the same invariant under deterministic
//! mutation — and each had written out its own directory lookup, its own `.bin` filter and its own
//! sort. Three copies of "where the corpus is" is how a test ends up reading a directory that
//! moved and reporting a pass over zero files, which is exactly the failure
//! `nodera_codec::repo` exists to prevent.
//!
//! Kept deliberately thin: the *routing* of a tag to its codec family stays in each test, because
//! the two files route differently on purpose and folding that together would change what one of
//! them tests.

use std::path::PathBuf;

/// Where the committed golden frames live, resolved through the layout manifest.
pub fn fixtures_dir() -> PathBuf {
    nodera_codec::repo::wire_fixtures()
}

/// Every golden frame, sorted, so a failure names a stable file rather than a directory-order one.
///
/// Panics if the directory cannot be read: an empty corpus is the failure this returns a `Vec` to
/// let the caller assert on, but an unreadable directory is a broken checkout.
pub fn fixture_files() -> Vec<PathBuf> {
    let dir = fixtures_dir();
    let entries = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("cannot read fixtures dir {}: {e}", dir.display()));
    let mut files: Vec<PathBuf> = entries
        .map(|e| e.expect("dir entry").path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "bin"))
        .collect();
    files.sort();
    files
}

/// The wire kind a golden fixture declares.
///
/// Read out of the `NDR2` header rather than the first two bytes: the frame now opens on a magic
/// number, which is the point — a caller that reaches for bytes `0..2` as a tag is reading a frame
/// from the previous generation, and will silently route every fixture to whichever family the
/// routing function falls through to.
pub fn tag_of(golden: &[u8]) -> u16 {
    nodera_codec::frame::NoderaFrame::peek_kind(golden).unwrap_or(u16::MAX)
}

/// The file name a failure should name.
pub fn name_of(path: &std::path::Path) -> String {
    path.file_name().unwrap().to_string_lossy().into_owned()
}
