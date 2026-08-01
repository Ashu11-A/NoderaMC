//! One handle that *is* the application, for a front end to hold.
//!
//! # Why this exists
//!
//! The commands used to be written against `tauri::State<Arc<Thing>>`, one extractor per handle.
//! That is a perfectly good way to write a Tauri app and a useless one for anybody else: a Jetpack
//! Compose screen reaching in through JNI has no `State`, no `AppHandle`, and no way to name the ten
//! separate `Arc`s the shell happened to `manage`.
//!
//! So the handles live together, in one struct, with the command bodies as methods on it. The
//! desktop shell keeps its `#[tauri::command]` functions — they are the ABI the webview calls — but
//! each is now a delegate that extracts one `State<Arc<NoderaCore>>` and forwards. The Android
//! bridge holds the same struct directly.
//!
//! # What is deliberately *not* here
//!
//! Anything whose answer depends on the shell: the native save dialog, the autostart registration,
//! the app-data directory (Tauri resolves it per platform), and opening a link (a
//! [`crate::browser::LinkOpener`] is passed in). Those stay with the front end that can answer them,
//! and the ones a caller needs are taken as parameters rather than reached for.

use std::collections::HashMap;
use std::sync::Arc;

use crate::api::model::Dashboard;
use crate::api::store::DashboardStore;
use crate::browser::LinkOpener;
use crate::config::{ConfigStatusHandle, PushSignal};
use crate::daemon::RestartSignal;
use crate::logs::LogBuffer;
use crate::power::PauseHandle;
use crate::settings::SettingsHandle;
use crate::system::SystemHandle;
use crate::{android, api, config, daemon, metrics, peer, settings, stores, telemetry};

/// A store URL delivered by deep link, held until the user answers the dialog.
///
/// One slot, not a queue: the second link supersedes the first, because a stack of dialogs is a
/// stack of things to dismiss and the user asked for the most recent one.
#[derive(Default)]
pub struct PendingStore(std::sync::Mutex<Option<String>>);

impl PendingStore {
    /// Record a URL from a link.
    pub fn offer(&self, url: String) {
        if let Ok(mut held) = self.0.lock() {
            *held = Some(url);
        }
    }

    /// Read and clear it.
    pub fn take(&self) -> Option<String> {
        self.0.lock().ok().and_then(|mut held| held.take())
    }
}

/// How often the stores are re-read in the background.
///
/// Six hours. A service list changes when somebody opens a pull request against it, which is not an
/// hourly event; polling harder loads somebody's web server for no benefit, and the manual Refresh
/// button covers the case where a user knows something changed.
pub const STORE_SYNC_INTERVAL: std::time::Duration = std::time::Duration::from_secs(6 * 60 * 60);

/// Wall-clock millis, for "when did this store last answer".
fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Loopback address of the WORKER's control endpoint (owned by `nodera-headless`, probed by both the
/// mod and this app). Keep in sync with the mod's `companion.controlEndpoint` default. Override with
/// `NODERA_CONTROL_PORT`.
pub fn control_addr() -> String {
    let port = std::env::var("NODERA_CONTROL_PORT").unwrap_or_else(|_| "25610".to_string());
    format!("127.0.0.1:{port}")
}

/// The application, minus its window.
pub struct NoderaCore {
    pub dashboard: Arc<DashboardStore>,
    pub system: Arc<SystemHandle>,
    pub logs: Arc<LogBuffer>,
    pub settings: Arc<SettingsHandle>,
    pub config_status: Arc<ConfigStatusHandle>,
    pub pause: Arc<PauseHandle>,
    pub push: Arc<PushSignal>,
    pub restart: Arc<RestartSignal>,
    pub network: Arc<std::sync::Mutex<android::network::NetworkState>>,
    pub pending_store: Arc<PendingStore>,
    /// Resolved once, at construction. Reading the environment per call would let one process talk
    /// to two workers if something changed it mid-run, which is a bug with no symptom until it is a
    /// very confusing one.
    control_addr: String,
}

impl NoderaCore {
    pub fn new() -> Self {
        Self {
            // `restored`, not `new`: the traffic totals are the node's, not this process's, and they
            // read back from disk so neither a worker restart nor closing the app sends them to zero.
            dashboard: Arc::new(DashboardStore::restored()),
            system: Arc::new(SystemHandle::new()),
            logs: Arc::new(LogBuffer::new()),
            settings: Arc::new(SettingsHandle::load()),
            config_status: Arc::new(ConfigStatusHandle::new()),
            pause: Arc::new(PauseHandle::new()),
            push: Arc::new(PushSignal::default()),
            restart: Arc::new(RestartSignal::default()),
            // Deliberately not probed here: on Android the Activity has not yet bound this process's
            // `Context`, so a read would panic (caught, but noisy) and be discarded ten seconds later
            // anyway. The sampler fills it in.
            network: Arc::new(std::sync::Mutex::new(android::network::pending())),
            pending_store: Arc::new(PendingStore::default()),
            control_addr: control_addr(),
        }
    }

    pub fn control_addr(&self) -> &str {
        &self.control_addr
    }

    /* ------------------------------------------------------------------------------ dashboard */

    pub fn snapshot(&self) -> Dashboard {
        api::commands::dashboard(&self.dashboard)
    }

    pub async fn piece_map(&self, world_id: String) -> metrics::PieceMapView {
        api::commands::dashboard_pieces(self.control_addr.clone(), world_id).await
    }

    pub async fn prove_world_admin(&self, world_id: String) -> api::commands::AdminProof {
        api::commands::prove_world_admin(self.control_addr.clone(), world_id).await
    }

    pub async fn delete_world(
        &self,
        world_id: String,
        reason: String,
    ) -> api::commands::DeleteOutcome {
        api::commands::delete_world(self.control_addr.clone(), world_id, reason).await
    }

    /* --------------------------------------------------------------------------------- worker */

    pub fn system_stats(&self) -> crate::system::SystemStats {
        self.system.snapshot()
    }

    pub fn worker_logs(&self) -> Vec<String> {
        self.logs.snapshot()
    }

    pub fn worker_ownership(&self) -> daemon::WorkerOwnership {
        daemon::ownership()
    }

    /// Cycle the worker so env-shaped settings (trackers, port range, archive dir) apply.
    ///
    /// **Refuses in attach mode.** A worker started by `scripts/dev.sh` — or by an operator — is not
    /// this app's process to kill.
    ///
    /// **Refuses on mobile.** The supervisor that consumes the signal is desktop-only, so the
    /// notification would wake nobody; the answer is a sentence telling the user what does work.
    pub fn restart_worker(&self) -> Result<(), String> {
        if let Some(why) = daemon::restart_unavailable() {
            return Err(why.to_owned());
        }
        self.restart.0.notify_one();
        Ok(())
    }

    /// Toggle the manual "pause seeding" flag. Returns the new *effective* pause state, which the
    /// battery rules can also be holding on.
    pub fn toggle_pause(&self) -> bool {
        let paused = self.pause.toggle_manual();
        self.push.request();
        paused
    }

    pub fn pause_reason(&self) -> String {
        self.pause.reason().unwrap_or_default().to_owned()
    }

    /// The whole worker scrollback, newline-terminated — what a bug report should carry.
    ///
    /// Snapshotted here rather than inside a save dialog: the buffer keeps moving while the user
    /// browses, and a file whose contents depend on how long somebody took to choose a folder is not
    /// reproducible.
    pub fn worker_log_text(&self) -> String {
        let mut content = self.logs.snapshot().join("\n");
        content.push('\n');
        content
    }

    /* ------------------------------------------------------------------------------- settings */

    pub fn settings(&self) -> settings::Settings {
        self.settings.snapshot()
    }

    /// Validate + persist settings.
    ///
    /// **Persist first, push second.** The push is a network round-trip to a process that may be
    /// down; making the save depend on it would mean a user loses a setting because their worker
    /// crashed. The push therefore happens asynchronously, debounced, and reports through
    /// [`Self::config_status`] as soft status — never as a save failure.
    ///
    /// Auto-start is *stored* here and applied by the shell, because the OS — not the worker — is
    /// what enforces it, and only the desktop shell has the plugin that can.
    pub fn save_settings(&self, next: settings::Settings) -> Result<(), String> {
        self.settings.save(next)?;
        #[cfg(target_os = "android")]
        if let Err(reason) = daemon::write_worker_properties(&self.settings.snapshot()) {
            log::warn!("could not refresh Android worker properties: {reason}");
        }
        // Kept in step with the document, because on Android this file is the only way the worker
        // ever learns about a tracker (see `stores::sync_file_body`).
        settings::write_sync_file(&self.settings.snapshot());
        self.push.request();
        Ok(())
    }

    pub fn setting_status(&self) -> Vec<settings::SettingStatus> {
        settings::setting_status(&self.config_status.snapshot().report())
    }

    pub fn config_status(&self) -> config::ConfigStatus {
        self.config_status.snapshot()
    }

    /// Why the stored settings could not be read, when they could not. Empty in the normal case.
    pub fn settings_fault(&self) -> String {
        self.settings.fault()
    }

    pub fn setup_state(&self) -> settings::SetupState {
        self.settings.snapshot().setup.clone()
    }

    pub fn complete_setup(&self, worlds_dir: String) -> Result<(), String> {
        let mut next = self.settings.snapshot();
        next.storage.peer_worlds_dir = worlds_dir;
        next.setup.completed = true;
        self.settings.save(next)?;
        self.push.request();
        Ok(())
    }

    /* -------------------------------------------------------------------------------- stores */

    pub fn tracker_stores(&self) -> Vec<stores::TrackerStore> {
        self.settings.snapshot().network.tracker_stores
    }

    pub fn take_pending_tracker_store(&self) -> Option<String> {
        self.pending_store.take()
    }

    /// The endpoints this node will actually dial, and where each one came from.
    ///
    /// Not `network.default_trackers` — that is the box the user types into. The effective list is
    /// that box *plus* whatever the stores contribute, which is what the worker environment and the
    /// synchronisation file have always built. Provenance comes with it, because "where did this
    /// address come from" is the next question anyone has in front of a list they did not write.
    pub fn resolved_services(&self) -> HashMap<String, Vec<stores::ResolvedEndpoint>> {
        let settings = self.settings.snapshot();
        HashMap::from([
            (
                "trackers".to_owned(),
                stores::resolved(
                    &settings.network.default_trackers,
                    &settings.network.tracker_stores,
                    stores::ServiceKind::Tracker,
                ),
            ),
            (
                "rendezvous".to_owned(),
                stores::resolved(
                    &settings.network.rendezvous_endpoints,
                    &settings.network.tracker_stores,
                    stores::ServiceKind::Rendezvous,
                ),
            ),
        ])
    }

    /// Fetch and validate a store index **without** remembering it.
    ///
    /// This is what makes adding a store a decision rather than a leap. Nothing is persisted, so a
    /// preview the user walks away from leaves no trace — and the store is fetched again by
    /// [`Self::add_tracker_store`] if they go ahead, because a preview the app then *stored* would be
    /// a list the user confirmed and a list the app kept, and those are only the same thing until a
    /// publisher edits the file between the two calls.
    pub async fn preview_tracker_store(&self, url: String) -> Result<stores::ServiceIndex, String> {
        let target = url.trim().to_owned();
        stores::check_url(&target).map_err(|e| e.to_string())?;
        let body = tokio::task::spawn_blocking(move || stores::fetch_index(&target))
            .await
            .map_err(|e| format!("the fetch task failed: {e}"))?
            .map_err(|e| e.to_string())?;
        stores::parse_index(&body).map_err(|e| e.to_string())
    }

    /// Fetch a store index, validate it, and remember it. Called only after the user has confirmed.
    pub async fn add_tracker_store(&self, url: String) -> Result<stores::TrackerStore, String> {
        let target = url.trim().to_owned();
        stores::check_url(&target).map_err(|e| e.to_string())?;

        let fetch_url = target.clone();
        let body = tokio::task::spawn_blocking(move || stores::fetch_index(&fetch_url))
            .await
            .map_err(|e| format!("the fetch task failed: {e}"))?
            .map_err(|e| e.to_string())?;
        let index = stores::parse_index(&body).map_err(|e| e.to_string())?;
        let store = stores::store_from(&target, index, now_millis(), false);

        let mut settings = self.settings.snapshot();
        // One entry per URL. Re-adding a store is how a user refreshes one by hand, so it replaces
        // rather than duplicating — two rows for one URL would double every endpoint it contributes.
        settings
            .network
            .tracker_stores
            .retain(|held| held.url != store.url);
        settings.network.tracker_stores.push(store.clone());
        self.settings.save(settings)?;
        settings::write_sync_file(&self.settings.snapshot());
        self.push.request();
        Ok(store)
    }

    pub fn remove_tracker_store(&self, url: String) -> Result<(), String> {
        let mut settings = self.settings.snapshot();
        let before = settings.network.tracker_stores.len();
        settings
            .network
            .tracker_stores
            .retain(|held| held.url != url);
        if settings.network.tracker_stores.len() == before {
            return Err(format!("no store here is served from {url}"));
        }
        self.settings.save(settings)?;
        settings::write_sync_file(&self.settings.snapshot());
        self.push.request();
        Ok(())
    }

    /// Re-read every store.
    ///
    /// A store that fails to refresh keeps the services it last reported and records why. Dropping
    /// them would mean a web server having a bad minute costs the user every tracker they had.
    pub async fn refresh_tracker_stores(&self) -> Result<Vec<stores::TrackerStore>, String> {
        let mut settings = self.settings.snapshot();
        for store in &mut settings.network.tracker_stores {
            let url = store.url.clone();
            let fetched = tokio::task::spawn_blocking(move || stores::fetch_index(&url))
                .await
                .map_err(|e| format!("the fetch task failed: {e}"))?;
            match fetched.and_then(|body| stores::parse_index(&body)) {
                Ok(index) => {
                    let built_in = store.built_in;
                    *store = stores::store_from(&store.url, index, now_millis(), built_in);
                }
                Err(e) => store.last_error = e.to_string(),
            }
        }
        let refreshed = settings.network.tracker_stores.clone();
        self.settings.save(settings)?;
        settings::write_sync_file(&self.settings.snapshot());
        self.push.request();
        Ok(refreshed)
    }

    /// Re-read one store.
    ///
    /// The all-or-nothing refresh is the wrong shape for the case that actually happens: one store in
    /// a list of several is failing, and the user wants to retry *that one*. Refreshing all of them
    /// makes every other row's timestamp lie about when it was last confirmed.
    pub async fn refresh_tracker_store(&self, url: String) -> Result<stores::TrackerStore, String> {
        let target = url.trim().to_owned();
        let fetch_url = target.clone();
        let fetched = tokio::task::spawn_blocking(move || stores::fetch_index(&fetch_url))
            .await
            .map_err(|e| format!("the fetch task failed: {e}"))?;

        let mut settings = self.settings.snapshot();
        let store = settings
            .network
            .tracker_stores
            .iter_mut()
            .find(|held| held.url == target)
            .ok_or_else(|| format!("no store here is served from {target}"))?;
        match fetched.and_then(|body| stores::parse_index(&body)) {
            Ok(index) => {
                let built_in = store.built_in;
                *store = stores::store_from(&target, index, now_millis(), built_in);
            }
            // Beside the services, never instead of them. See `refresh_tracker_stores`.
            Err(e) => store.last_error = e.to_string(),
        }
        let updated = store.clone();
        self.settings.save(settings)?;
        settings::write_sync_file(&self.settings.snapshot());
        self.push.request();
        Ok(updated)
    }

    /// Keep the tracker stores, and the file the worker reads, up to date. Runs for the life of the
    /// app: once at startup, then on [`STORE_SYNC_INTERVAL`].
    ///
    /// A failed sync is not an error — a store keeps the services it last reported and puts the
    /// reason beside them, so an outage costs freshness and never the endpoints this node is using.
    pub async fn sync_stores_forever(self: Arc<Self>) {
        loop {
            let mut document = self.settings.snapshot();
            if document.network.tracker_stores.is_empty() {
                // Nothing subscribed. Still write the file: the user's own typed endpoints belong in
                // it too, and an absent file reads as "never synced".
                settings::write_sync_file(&document);
            } else {
                let mut changed = false;
                for store in &mut document.network.tracker_stores {
                    let url = store.url.clone();
                    let fetched =
                        tokio::task::spawn_blocking(move || stores::fetch_index(&url)).await;
                    match fetched {
                        Ok(Ok(body)) => match stores::parse_index(&body) {
                            Ok(index) => {
                                let built_in = store.built_in;
                                *store =
                                    stores::store_from(&store.url, index, now_millis(), built_in);
                                changed = true;
                            }
                            Err(e) => store.last_error = e.to_string(),
                        },
                        Ok(Err(e)) => store.last_error = e.to_string(),
                        Err(e) => store.last_error = format!("the fetch task failed: {e}"),
                    }
                }
                if self.settings.save(document.clone()).is_ok() {
                    settings::write_sync_file(&document);
                    if changed {
                        // A store that gained a tracker should reach a running worker rather than
                        // wait for a restart — `network.default_trackers` is a live key.
                        self.push.request();
                    }
                }
            }
            tokio::time::sleep(STORE_SYNC_INTERVAL).await;
        }
    }

    /* ------------------------------------------------------------------------------ telemetry */

    /// The node's telemetry consent and emitter status.
    ///
    /// Always read from the worker rather than cached here: the record lives on the node, and the app
    /// is one of several things that may have changed it (the mod's `/nodera telemetry` is another).
    pub async fn telemetry_status(&self) -> telemetry::TelemetryStatus {
        telemetry::status(&self.control_addr, &self.settings).await
    }

    /// Record the person's answer.
    ///
    /// Recorded locally first and handed to the worker second, so a node that is still starting —
    /// which on a fresh install is the normal case — cannot turn a question into a dead end.
    pub async fn set_telemetry_consent(&self, granted: bool) -> telemetry::TelemetryStatus {
        telemetry::set(&self.control_addr, &self.settings, granted).await
    }

    /// What the configured collector says it accepts. A failure falls back to the bundled registry.
    pub async fn collected_schema(&self, endpoint: String) -> Result<String, String> {
        telemetry::collected_schema(&endpoint).await
    }

    /* -------------------------------------------------------------------------------- network */

    /// Answer the "shall I share this LAN world?" question. `action` is `SHARE`, `DECLINE` or `STOP`.
    /// Nothing about a LAN world reaches the network until this says so — detection is not consent.
    pub async fn lan_action(&self, action: String, port: u16) -> api::network::Outcome {
        api::network::lan_action(&self.control_addr, &action, port).await
    }

    pub async fn browse_network(&self, limit: Option<u32>) -> Vec<api::network::DirectoryEntry> {
        api::network::directory(&self.control_addr, limit.unwrap_or(50)).await
    }

    /// Join a session. Returns the `127.0.0.1:<port>` to connect to.
    ///
    /// Nothing is downloaded. The worker opens a tunnel to the host and binds a loopback port; the
    /// player's own Minecraft then connects to it as though the host were on the same LAN.
    pub async fn join_world(&self, session_id: String) -> api::network::Outcome {
        api::network::join_session(&self.control_addr, &session_id).await
    }

    pub async fn leave_world(&self, session_id: String) -> api::network::Outcome {
        api::network::leave_session(&self.control_addr, &session_id).await
    }

    pub async fn world_share_link(&self, world_id: String) -> api::network::Outcome {
        api::network::share_link(&self.control_addr, &world_id).await
    }

    /// Save an invitation as a `.nodera` file and report where it went.
    ///
    /// The app picks the folder rather than opening a file dialog: the point of the button is to
    /// produce something to send in the next ten seconds, and a save dialog for a 200-byte text file
    /// is three clicks of ceremony.
    pub fn save_share_file(&self, name: String, uri: String) -> Result<String, String> {
        let safe: String = name
            .chars()
            .map(|c| {
                if c.is_alphanumeric() || c == '-' || c == '_' {
                    c
                } else {
                    '-'
                }
            })
            .collect();
        let file = crate::share_dir().join(format!(
            "{}.nodera",
            if safe.trim_matches('-').is_empty() {
                "world".to_owned()
            } else {
                safe
            }
        ));
        api::network::write_share_file(&file, &uri)?;
        Ok(file.display().to_string())
    }

    pub fn parse_share_link(&self, uri: String) -> Result<String, String> {
        api::network::world_id_of(&uri).ok_or_else(|| "that is not a Nodera invitation".to_owned())
    }

    /* ----------------------------------------------------------------------------------- peer */

    /// This device's own peer identity and its last tracker exchange.
    ///
    /// Runs the SAME round the peer loop runs — announce, then ask the trackers back. Announcing
    /// only would leave "peers seen" at zero on a screen the loop later fills in, so the number would
    /// depend on which code path last wrote it. One round, one meaning.
    pub async fn peer_status(&self) -> Result<peer::PeerStatus, String> {
        let trackers = self.settings.snapshot().network.default_trackers;
        tokio::task::spawn_blocking(move || {
            let identity = peer::identity::PeerIdentity::load_or_create()?;
            Ok(peer::announce_round(&identity, &trackers))
        })
        .await
        .map_err(|e| format!("the peer task failed: {e}"))?
    }

    /// The round trip that proves this device is on the network.
    ///
    /// Announces, then queries the tracker back and looks for **this device's own entry**. An
    /// accepted announce alone would only say the tracker took the bytes.
    pub async fn peer_self_test(&self) -> Result<peer::tracker::SelfTest, String> {
        let trackers = self.settings.snapshot().network.default_trackers;
        tokio::task::spawn_blocking(move || {
            let identity = peer::identity::PeerIdentity::load_or_create()?;
            Ok(peer::tracker::self_test(&identity, &trackers, Vec::new()))
        })
        .await
        .map_err(|e| format!("the peer task failed: {e}"))?
    }

    /* -------------------------------------------------------------------- platform + about */

    /// Where world data can be kept on this device, and where it is kept now.
    ///
    /// `app_data_dir` is passed in because only the shell can resolve it: Tauri asks the platform,
    /// and the Android activity asks its `Context`. The two answers differ, and this crate guessing
    /// would be the `filesDir` trap that cost a whole feature once already.
    pub fn storage_info(&self, app_data_dir: &std::path::Path) -> api::storage::StorageInfo {
        api::storage::storage_info(
            &self.settings.snapshot().storage.peer_worlds_dir,
            app_data_dir,
        )
    }

    pub fn picked_folder(&self, app_data_dir: &std::path::Path) -> api::storage::PickedFolder {
        api::storage::picked_folder(app_data_dir)
    }

    pub fn network_state(&self) -> android::network::NetworkState {
        self.network.lock().unwrap().clone()
    }

    pub fn open_external(
        &self,
        opener: &dyn LinkOpener,
        url: &str,
    ) -> Result<&'static str, String> {
        crate::browser::open(opener, url)
    }

    /* ------------------------------------------------------------------------ background work */

    /// Start everything that runs for the life of the app and is the same on both shells.
    ///
    /// # Why this is here and not in each front end
    ///
    /// Six loops, and every one of them is about the node rather than about the window: the live
    /// link, the event stream, the store refresh, the configuration pusher, the connection rules,
    /// and the telemetry reconciler. Written out twice — once in a Tauri `setup` hook and once in a
    /// JNI entry point — they would drift, and the drift would be invisible: an Android build that
    /// forgot the store refresh would simply stop learning about new trackers, with nothing on
    /// screen to say so.
    ///
    /// What each shell keeps is what only it has. The desktop adds the tray, the window, the worker
    /// supervisor and the process sampler; Android adds the log tailer for the worker running
    /// inside its own process.
    pub fn start_shared_loops(
        self: &Arc<Self>,
        sink: Arc<dyn crate::api::link::Sink>,
        events: Arc<dyn crate::api::events::EventSink>,
    ) {
        // The link. The authoritative liveness signal and the only thing that writes the dashboard:
        // the worker PUSHES state as it changes, so the screen is current because the node said so
        // rather than because the app guessed when to ask. Its offline→online edge is what
        // re-pushes configuration to a worker that came back without it.
        let store = Arc::clone(&self.dashboard);
        let reconnect = Arc::clone(&self.push.0);
        let addr = self.control_addr.clone();
        tokio::spawn(async move { crate::api::link::pump(addr, store, sink, reconnect).await });

        // The event stream beside it. Two connections on purpose: the link carries what is TRUE of
        // the node and this carries what HAPPENED to it. A prompt built on the first would only
        // fire when the app happened to be connected at the moment the player acted.
        let addr = self.control_addr.clone();
        tokio::spawn(async move { crate::api::events::pump(addr, events).await });

        // Keep the tracker stores fresh, and the worker's synchronisation file with them. Started
        // before the pusher so a first-run install has written the file — the only channel to an
        // Android worker — by the time the worker looks for it.
        let stores = Arc::clone(self);
        tokio::spawn(async move { stores.sync_stores_forever().await });

        // Coalesce configuration pushes: one per settle window, however many saves arrive.
        let pusher = Arc::new(config::ConfigPusher {
            control_addr: self.control_addr.clone(),
            settings: Arc::clone(&self.settings),
            pause: Arc::clone(&self.pause),
            status: Arc::clone(&self.config_status),
        });
        let push = Arc::clone(&self.push);
        tokio::spawn(async move { config::debounce_loop(pusher, push).await });

        // Connection rules, on every platform. Desktop reports the subject as unsupported and the
        // loop is inert there; on a phone this is what stops the node spending somebody's data
        // allowance on strangers' worlds.
        let (settings, pause, push, cache) = (
            Arc::clone(&self.settings),
            Arc::clone(&self.pause),
            Arc::clone(&self.push),
            Arc::clone(&self.network),
        );
        tokio::spawn(
            async move { crate::power::sample_network(settings, pause, push, cache).await },
        );

        // A telemetry answer given while the worker was still starting is delivered here, not lost.
        // First run is precisely when the node is least likely to be listening.
        let settings = Arc::clone(&self.settings);
        let addr = self.control_addr.clone();
        tokio::spawn(async move { telemetry::reconcile_loop(addr, settings).await });
    }

    /* ----------------------------------------------------------------------------------- play */

    /// What this machine could start, and which route each would take.
    ///
    /// Read before the button is pressed, so the interface can name the instance and say whether the
    /// player will land in the world or in the Multiplayer menu. A launcher that only reveals its
    /// choice by doing it is one nobody can predict.
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub async fn launch_targets(&self) -> Vec<crate::launch::discover::LaunchTarget> {
        tokio::task::spawn_blocking(crate::launch::discover::targets)
            .await
            .unwrap_or_default()
    }

    /// Join a world and start the game in it.
    ///
    /// # The order is the design
    ///
    /// 1. **Plan first.** Choosing a route reads the filesystem and nothing else, so the two common
    ///    failures — no installation, and one without the mod — cost the player nothing rather than
    ///    opening a tunnel to somebody's host and then finding there is nothing to start.
    /// 2. **Then the tunnel.** A game that starts and finds nothing listening is a player staring at
    ///    "connection refused" with no idea which half broke.
    /// 3. **Then prepare, then spawn.**
    /// 4. **Close the tunnel when the game exits — and when anything after step 2 fails.** This is
    ///    the leak the old Join screen had: it opened a tunnel and never closed it, so a session
    ///    outlived the game and the node kept a door open to a host the player had finished with.
    ///
    /// Every transition is published, because a Play button that shows a spinner and no word is the
    /// same failure as a dashboard reporting `0` for "we never asked".
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub async fn play(
        self: Arc<Self>,
        world_id: String,
        world_name: String,
        preferred: Option<String>,
        sink: Arc<dyn crate::launch::LaunchSink>,
    ) {
        use crate::launch::{plan as planner, Phase, Remedy};

        let mut state = crate::launch::LaunchState {
            world_id: world_id.clone(),
            ..crate::launch::LaunchState::idle()
        }
        .at(Phase::Resolving);
        sink.publish(state.clone());

        let targets = self.launch_targets().await;
        let chosen = match planner::plan(&targets, preferred.as_deref()) {
            Ok(chosen) => chosen,
            Err(no) => {
                sink.publish(state.failed(no.reason, no.remedy));
                return;
            }
        };
        state.tier = Some(chosen.tier);
        state.profile = Some(chosen.target.name.clone());
        state.install_path = Some(chosen.target.game_dir.clone());

        state = state.at(Phase::Joining);
        sink.publish(state.clone());
        let joined = self.join_world(world_id.clone()).await;
        if !joined.ok {
            let reason = if joined.error.is_empty() {
                "the worker could not open a tunnel to that world".to_owned()
            } else {
                joined.error.clone()
            };
            sink.publish(state.failed(reason, Remedy::Retry));
            return;
        }
        state.session_id = Some(world_id.clone());
        state.address = Some(joined.value.clone());

        state = state.at(Phase::Preparing);
        sink.publish(state.clone());
        let prepared = match planner::prepare(
            &chosen,
            &joined.value,
            &world_name,
            // Only the offline identity uses this, and only the two delegating tiers ever run today
            // — neither of which takes a player name from us, because their launcher owns the
            // account. A real name arrives with a real sign-in, not before.
            "Player",
            crate::launch::auth::client_id().as_deref(),
        ) {
            Ok(prepared) => prepared,
            Err(no) => {
                // The tunnel is already open at this point, and nothing is going to use it.
                self.leave_world(world_id.clone()).await;
                state.session_id = None;
                sink.publish(state.failed(no.reason, no.remedy));
                return;
            }
        };
        state.java = prepared.java.as_ref().map(|p| p.display().to_string());

        state = state.at(Phase::Spawning);
        sink.publish(state.clone());
        let command = prepared.command.clone();
        let spawned = tokio::task::spawn_blocking(move || {
            std::process::Command::new(&command.program)
                .args(&command.arguments)
                .current_dir(&command.working_dir)
                .spawn()
                .map_err(|e| format!("{} could not be started: {e}", command.program.display()))
        })
        .await;

        let child = match spawned {
            Ok(Ok(child)) => child,
            Ok(Err(reason)) => {
                self.leave_world(world_id.clone()).await;
                state.session_id = None;
                sink.publish(state.failed(reason, Remedy::PickInstall));
                return;
            }
            Err(e) => {
                self.leave_world(world_id.clone()).await;
                state.session_id = None;
                sink.publish(state.failed(format!("the launch task failed: {e}"), Remedy::Retry));
                return;
            }
        };

        state.pid = Some(child.id());
        state = state.at(Phase::Running);
        sink.publish(state.clone());
        log::info!(
            "launch: {} started for {world_id} on {}",
            chosen.target.name,
            joined.value
        );

        // Waited on rather than forgotten: the exit is what closes the tunnel, and it is also the
        // only honest way to stop saying "Running".
        let mut child = child;
        let exited = tokio::task::spawn_blocking(move || child.wait()).await;
        state.exit_code = match exited {
            Ok(Ok(status)) => status.code(),
            _ => None,
        };
        self.leave_world(world_id).await;
        state.session_id = None;
        sink.publish(state.at(Phase::Exited));
    }
}

impl Default for NoderaCore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pending_store_holds_one_url_and_gives_it_up_once() {
        let pending = PendingStore::default();
        assert_eq!(pending.take(), None, "nothing offered is nothing to take");

        pending.offer("https://example.org/a.json".to_owned());
        // The second link supersedes the first: a stack of dialogs is a stack of things to dismiss,
        // and the user asked for the most recent one.
        pending.offer("https://example.org/b.json".to_owned());
        assert_eq!(
            pending.take().as_deref(),
            Some("https://example.org/b.json")
        );
        assert_eq!(
            pending.take(),
            None,
            "taking clears it, so a reload cannot re-prompt"
        );
    }

    #[test]
    fn the_control_address_is_resolved_once_rather_than_per_call() {
        let core = NoderaCore::new();
        let first = core.control_addr().to_owned();
        std::env::set_var("NODERA_CONTROL_PORT", "39999");
        // A process that re-read the environment per call could end up talking to two workers, which
        // is a bug with no symptom until it is a very confusing one.
        assert_eq!(core.control_addr(), first);
        std::env::remove_var("NODERA_CONTROL_PORT");
    }

    #[test]
    fn an_invitation_filename_can_never_escape_the_share_folder() {
        let dir = std::env::temp_dir().join(format!("nodera-share-{}", std::process::id()));
        std::env::set_var("NODERA_SHARE_DIR", &dir);
        let core = NoderaCore::new();

        let written = core
            .save_share_file(
                "../../etc/passwd".to_owned(),
                "nodera:?xt=urn:btih:aa".to_owned(),
            )
            .expect("a hostile name is sanitised, not refused");
        let path = std::path::PathBuf::from(&written);
        assert_eq!(
            path.parent(),
            Some(dir.as_path()),
            "every invitation lands in the share folder: {written}"
        );

        // A name with nothing usable left still produces a file rather than a dotfile or an error.
        let fallback = core
            .save_share_file("///".to_owned(), "nodera:?xt=urn:btih:bb".to_owned())
            .expect("an unusable name falls back");
        assert!(fallback.ends_with("world.nodera"), "{fallback}");

        std::env::remove_var("NODERA_SHARE_DIR");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
