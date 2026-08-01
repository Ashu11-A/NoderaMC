//! Build script for the companion app's desktop shell.
//!
//! Tauri's codegen, and nothing else. Resolving the project's published service index — the other
//! job this file used to do — moved to `nodera-core`, which is where the code that includes it
//! lives and which builds with no webview toolchain present.

fn main() {
    tauri_build::build();
}
