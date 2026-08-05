//! The Tauri half of the seams `nodera-core` leaves open.
//!
//! Three things the core deliberately does not know how to do, because each has a second answer on
//! Android that has nothing to do with a webview: publish a dashboard snapshot, publish a worker
//! event, and open a web page. The core owns the behaviour around them — the reconnect loop, the
//! sequence cursor, the edge detection, the URL check — and calls out through a trait for the one
//! step that is genuinely per-shell.

use std::sync::atomic::{AtomicBool, Ordering};

use nodera_core::api::events::{EventSink, WorkerEvent, WORKER_EVENT};
use nodera_core::api::link::{DiscoveryEdge, Sink, DASHBOARD_EVENT, DISCOVERY_EVENT};
use nodera_core::api::model::Dashboard;
use nodera_core::browser::LinkOpener;
use tauri::{AppHandle, Emitter};

/// The desktop's dashboard sink: the Tauri events every page listens on.
pub struct TauriSink {
    app: AppHandle,
    /// Edge detection for [`DISCOVERY_EVENT`], owned by the core so both shells agree what a change
    /// in reachability is.
    discovery: DiscoveryEdge,
    /// Whether the last emit failed, so the transition is logged once rather than on every push.
    emit_failing: AtomicBool,
}

impl TauriSink {
    pub fn new(app: AppHandle) -> Self {
        Self {
            app,
            discovery: DiscoveryEdge::default(),
            emit_failing: AtomicBool::new(false),
        }
    }

    /// Emit, and report the *transition* between working and not.
    ///
    /// This was `let _ = emit(...)`, justified by "no window is listening is normal". That is true
    /// on a desktop minimised to the tray and false everywhere else — and it meant a link that was
    /// receiving state perfectly while **nothing reached the screen** looked, from the logs, exactly
    /// like a link that was working. Discarding the one signal that distinguishes them is how a
    /// dashboard sits on `—` with a healthy node behind it.
    fn emit<P: serde::Serialize + Clone>(&self, event: &str, payload: P) {
        match self.app.emit(event, payload) {
            Ok(()) => {
                if self.emit_failing.swap(false, Ordering::Relaxed) {
                    log::info!("ui: events are reaching the interface again");
                }
            }
            Err(e) => {
                if !self.emit_failing.swap(true, Ordering::Relaxed) {
                    log::warn!("ui: {event} could not be delivered to the interface: {e}");
                }
            }
        }
    }
}

impl Sink for TauriSink {
    fn publish(&self, dashboard: Dashboard) {
        if let Some(change) = self.discovery.take(&dashboard) {
            self.emit(DISCOVERY_EVENT, change);
        }
        self.emit(DASHBOARD_EVENT, dashboard);
    }
}

/// The desktop's event sink.
pub struct TauriEventSink(pub AppHandle);

impl EventSink for TauriEventSink {
    fn publish(&self, event: WorkerEvent) {
        let _ = self.0.emit(WORKER_EVENT, event);
    }
}

/// The launch lane's sink: the fifth and last event this app emits.
///
/// Desktop only, because the lane is. There is no Java Minecraft client on Android, so a Play button
/// there would be a button that cannot work.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub struct TauriLaunchSink(pub AppHandle);

#[cfg(not(any(target_os = "android", target_os = "ios")))]
impl nodera_core::launch::LaunchSink for TauriLaunchSink {
    fn publish(&self, state: nodera_core::launch::LaunchState) {
        let _ = self.0.emit(LAUNCH_EVENT, state);
    }
}

/// The event a Play button lives on.
#[cfg(not(any(target_os = "android", target_os = "ios")))]
pub const LAUNCH_EVENT: &str = "nodera://launch";

/// The desktop link ladder: the system browser, then a window of our own.
///
/// Only the desktop needs a shell-side opener. Android's whole ladder lives in Kotlin — deliberately,
/// because a second JNI call made while an exception is pending aborts the process — so the core
/// drives it directly there.
pub struct DesktopOpener(pub AppHandle);

impl LinkOpener for DesktopOpener {
    /// The second rung replaces the clipboard, which was never an answer to "open this link". A
    /// desktop with no registered http handler — a bare container, a stripped kiosk image, a Linux
    /// box with no `xdg-open` — is exactly where a user is least able to work around a dead button,
    /// and this application is a webview: it can always show the page itself.
    fn open_checked(&self, url: &str) -> Result<&'static str, String> {
        match tauri_plugin_opener::open_url(url, None::<&str>) {
            Ok(()) => Ok("browser"),
            Err(reason) => {
                log::warn!("no system browser ({reason}); opening the page in a window");
                self.window(url).map(|()| "webview")
            }
        }
    }
}

impl DesktopOpener {
    /// A plain Tauri window pointed at the page.
    fn window(&self, url: &str) -> Result<(), String> {
        use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

        let parsed = url
            .parse()
            .map_err(|e| format!("that address is not one a window can open: {e}"))?;
        // One reusable label. A second click on a link should raise the window that is already
        // showing a page rather than stack another one behind it — and an unbounded number of
        // windows named after URLs is a leak with a user interface.
        const LABEL: &str = "external-page";
        if let Some(existing) = self.0.get_webview_window(LABEL) {
            let _ = existing.navigate(parsed);
            let _ = existing.set_focus();
            return Ok(());
        }
        WebviewWindowBuilder::new(&self.0, LABEL, WebviewUrl::External(parsed))
            .title("Nodera — external page")
            .inner_size(1000.0, 760.0)
            .build()
            .map(|_| ())
            .map_err(|e| format!("could not open a window for that page: {e}"))
    }
}

#[cfg(test)]
mod tests {
    use nodera_core::browser::check_url;

    /// Does this machine's first rung actually work?
    ///
    /// Ignored by default because it opens a real browser window, which is not something a test
    /// suite should do to whoever runs it. Run it by hand when a user reports that links do
    /// nothing — it separates "the opener is broken here" from "the app never called it".
    ///
    ///     cargo test --manifest-path app/Cargo.toml -- --ignored opens_a_real_browser
    #[test]
    #[ignore = "opens a real browser window"]
    fn opens_a_real_browser() {
        tauri_plugin_opener::open_url("https://noderamc.org", None::<&str>)
            .expect("the desktop opener should reach a browser on a machine that has one");
    }

    #[test]
    fn every_accepted_url_can_be_parsed_by_the_window_fallback() {
        // The desktop's second rung parses the URL again, and a string the core accepted but
        // `Url::parse` rejects would turn a working link into an error only reachable on a machine
        // with no browser — the one machine that cannot afford it. The check lives here because
        // this is the crate that owns the window.
        for url in [
            "https://noderamc.org/",
            "http://example.org",
            "HTTPS://example.org",
            "https://example.org/a/b?c=d#e",
            "https://user@example.org:8443/path",
        ] {
            let checked = check_url(url).expect(url);
            assert!(
                checked.parse::<tauri::Url>().is_ok(),
                "{url} passed check_url but cannot be parsed"
            );
        }
    }
}
