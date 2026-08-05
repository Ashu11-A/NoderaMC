//! The parts of Android that only Java can answer.
//!
//! Kept in one place, and behind functions that degrade to "not applicable" everywhere else, so the
//! rest of the app never branches on the platform.

pub mod battery;
/// The door the native Android front end comes through.
pub mod bridge;
pub mod network;
pub mod worker;

/// The generic half of the JNI bridge, for callers that are not about batteries.
///
/// `with_context` means "attach to the VM and talk to the framework", not power management. It
/// lives in [`battery`] because that was the first thing here that needed it; re-exporting it under
/// this neutral path keeps callers about links from naming batteries.
#[cfg(target_os = "android")]
pub(crate) use battery::platform::with_context;
