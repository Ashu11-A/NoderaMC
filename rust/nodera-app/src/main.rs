// The desktop entry point.
//
// Everything is in `lib.rs`, because Tauri's Android build loads this crate as a library and calls
// `run()` through JNI rather than executing a `main`. Keeping one copy of the application means the
// two platforms cannot drift.
#![cfg_attr(all(not(debug_assertions), target_os = "windows"), windows_subsystem = "windows")]

fn main() {
    nodera_app_lib::run();
}
