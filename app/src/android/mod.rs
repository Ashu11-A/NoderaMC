//! The parts of Android that only Java can answer.
//!
//! Kept in one place, and behind functions that degrade to "not applicable" everywhere else, so the
//! rest of the app never branches on the platform.

pub mod battery;
pub mod network;
pub mod worker;

/// The generic half of the JNI bridge, for callers that are not about batteries.
///
/// `with_context` and `open_intent_action` are "attach to the VM and talk to the framework" and
/// "start an activity" — neither is about power management. They live in [`battery`] because that
/// was the first thing here that needed them, and moving them would rewrite every call in that file
/// for no behavioural gain. Re-exported under this neutral path instead, so a module about opening
/// links does not have to say `battery::` to open one.
#[cfg(target_os = "android")]
pub(crate) use battery::platform::{open_intent_action, with_context};
