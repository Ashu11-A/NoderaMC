//! The parts of Android that only Java can answer.
//!
//! Kept in one place, and behind functions that degrade to "not applicable" everywhere else, so the
//! rest of the app never branches on the platform.

pub mod battery;
pub mod network;
