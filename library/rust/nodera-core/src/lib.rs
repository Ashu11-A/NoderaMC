//! Everything the companion app *is*, minus the shell it is shown through.
//!
//! # The rule
//!
//! **Nothing in this crate may depend on `tauri`.** Two front ends read it — the desktop webview and
//! the native Android activity — and anything that reaches for an `AppHandle` here is a thing only
//! one of them can use.
//!
//! Where the shell genuinely has to be involved, the seam is a trait the shell implements:
//!
//! * [`api::link::Sink`] and [`api::events::EventSink`] — where a snapshot and an event go. The
//!   desktop emits a Tauri event; Android calls back into Kotlin. Both pumps predate the split and
//!   were already written against these traits, because a link's behaviour on a malformed payload or
//!   a silent socket is the part worth testing and none of it should need a window to exist.
//! * [`browser::LinkOpener`] — how a validated web address is handed to the platform. The *checking*
//!   is here, because refusing `file:///etc/passwd` is not a per-platform decision.
//!
//! # What lives here
//!
//! * [`control`] — the request/response client for the worker's control socket, and the one place a
//!   request line is validated before it is sent.
//! * [`api`] — the view model, the revisioned store, the two streams, and the round trips that are
//!   commands rather than state.
//! * [`settings`], [`config`], [`stores`], [`telemetry`] — the persisted document, the push to the
//!   worker, the tracker-store index, and the consent lane.
//! * [`daemon`], [`logs`], [`system`], [`power`] — supervising the Java worker on the desktop, and
//!   the machine facts the interface reports.
//! * [`peer`] — the app's own Rust peer, which is what runs on a phone where there is no JVM.
//! * [`android`] — the parts of Android only Java can answer, behind functions that degrade to "not
//!   applicable" everywhere else.

pub mod android;
pub mod api;
pub mod browser;
pub mod config;
pub mod control;
pub mod daemon;
pub mod logs;
pub mod metrics;
pub mod peer;
pub mod power;
pub mod settings;
pub mod stores;
pub mod system;
pub mod telemetry;

/// Whether this build runs on a machine that supervises a worker process of its own.
///
/// # Why this is spelled out rather than `cfg!(desktop)`
///
/// `desktop` and `mobile` are not Rust cfgs. They are emitted by `tauri_build`, so they exist only
/// inside the shell crate — and outside it `cfg!(desktop)` is not an error, it is **`false`**, with
/// nothing louder than an `unexpected_cfgs` warning to say so. Moving `daemon::ownership` here
/// carried two of them, and both would have quietly told every desktop user that their worker could
/// not be restarted.
///
/// So the platform question is asked in the only vocabulary that means the same thing in every
/// crate. It is the same predicate `Cargo.toml` already uses to select `starship-battery`.
pub const SUPERVISES_A_WORKER: bool = cfg!(not(any(target_os = "android", target_os = "ios")));

/// Where saved invitations go. Overridable so a test — or a user with an opinion — can move it.
///
/// Here rather than in a shell because two things that are not the shell read it: the About screen
/// reports the folder, and the desktop supervisor resolves paths against it. A copy in each front
/// end is a copy that can disagree about where a user's files are.
pub fn share_dir() -> std::path::PathBuf {
    std::env::var("NODERA_SHARE_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            std::path::PathBuf::from(
                std::env::var("HOME")
                    .or_else(|_| std::env::var("USERPROFILE"))
                    .unwrap_or_else(|_| ".".to_owned()),
            )
            .join("Nodera")
        })
}
