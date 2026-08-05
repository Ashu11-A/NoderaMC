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
use crate::daemon::{LaunchedWorker, RestartSignal};
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
    /// What the running worker was actually started with. Written by the supervisor, read by
    /// [`Self::worker_ownership`] — it is the only thing that can tell a bind-time setting apart
    /// from a bind-time setting the user has *changed*, which is the difference between a banner
    /// that means something and one that is permanently true.
    pub launched: Arc<LaunchedWorker>,
    pub network: Arc<std::sync::Mutex<android::network::NetworkState>>,
    pub pending_store: Arc<PendingStore>,
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    launch: Arc<crate::launch::LaunchCoordinator>,
    /// Resolved once, at construction. Reading the environment per call would let one process talk
    /// to two workers if something changed it mid-run, which is a bug with no symptom until it is a
    /// very confusing one.
    control_addr: String,
}

impl NoderaCore {
    pub fn new() -> Self {
        let config_status = Arc::new(ConfigStatusHandle::new());
        Self {
            // `restored`, not `new`: the traffic totals are the node's, not this process's, and they
            // read back from disk so neither a worker restart nor closing the app sends them to zero.
            dashboard: Arc::new(DashboardStore::restored()),
            system: Arc::new(SystemHandle::new()),
            logs: Arc::new(LogBuffer::new()),
            settings: Arc::new(SettingsHandle::load()),
            config_status: Arc::clone(&config_status),
            pause: Arc::new(PauseHandle::new()),
            push: Arc::new(PushSignal::new(config_status)),
            restart: Arc::new(RestartSignal::default()),
            launched: Arc::new(LaunchedWorker::default()),
            // Deliberately not probed here: on Android the Activity has not yet bound this process's
            // `Context`, so a read would panic (caught, but noisy) and be discarded ten seconds later
            // anyway. The sampler fills it in.
            network: Arc::new(std::sync::Mutex::new(android::network::pending())),
            pending_store: Arc::new(PendingStore::default()),
            #[cfg(not(any(target_os = "android", target_os = "ios")))]
            launch: Arc::new(crate::launch::LaunchCoordinator::default()),
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

    /// Who owns the worker, and which settings the *running* one is out of date on.
    ///
    /// The pending set is computed here rather than in the command so the answer comes from the same
    /// supervisor record the automatic restart works from — two ways of deciding what is pending is
    /// two ways for the screen to disagree with what the app then does.
    pub fn worker_ownership(&self) -> daemon::WorkerOwnership {
        daemon::ownership(
            self.launched
                .pending_restart_keys(&self.settings.snapshot()),
        )
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
        self.request_config_push();
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
        self.request_config_push();
        Ok(())
    }

    fn update_settings(
        &self,
        change: impl FnOnce(&mut settings::Settings),
    ) -> Result<settings::Settings, String> {
        let next = self.settings.update(change)?;
        #[cfg(target_os = "android")]
        if let Err(reason) = daemon::write_worker_properties(&next) {
            log::warn!("could not refresh Android worker properties: {reason}");
        }
        settings::write_sync_file(&next);
        self.request_config_push();
        Ok(next)
    }

    pub fn set_theme(&self, theme: settings::Theme) -> Result<(), String> {
        self.update_settings(move |settings| settings.appearance.theme = theme)?;
        Ok(())
    }

    pub fn set_network_policy(
        &self,
        policy: settings::NetworkPolicy,
        max_connections: u32,
    ) -> Result<(), String> {
        self.update_settings(move |settings| {
            settings.network.transfer_network = policy;
            settings.network.max_connections = max_connections;
        })?;
        Ok(())
    }

    pub fn set_storage_policy(&self, budget_bytes: u64, sweep_seconds: u64) -> Result<(), String> {
        self.update_settings(move |settings| {
            settings.storage.replication_budget_bytes = budget_bytes;
            settings.storage.replication_sweep_seconds = sweep_seconds;
        })?;
        Ok(())
    }

    pub fn set_power_rules(
        &self,
        only_charging: bool,
        battery_control: bool,
        threshold: u8,
        during_game: bool,
    ) -> Result<(), String> {
        self.update_settings(move |settings| {
            settings.behavior.only_when_charging = only_charging;
            settings.behavior.battery_control = battery_control;
            settings.behavior.battery_threshold_percent = threshold;
            settings.behavior.power_rules_during_game = during_game;
        })?;
        Ok(())
    }

    fn request_config_push(&self) {
        self.push.request();
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
        self.update_storage_dir(worlds_dir, true)
    }

    pub fn set_storage_dir(&self, worlds_dir: String) -> Result<(), String> {
        self.update_storage_dir(worlds_dir, false)
    }

    fn update_storage_dir(&self, worlds_dir: String, complete_setup: bool) -> Result<(), String> {
        self.update_settings(move |settings| {
            settings.storage.peer_worlds_dir = worlds_dir;
            if complete_setup {
                settings.setup.completed = true;
            }
        })?;
        Ok(())
    }

    /* -------------------------------------------------------------------------------- stores */

    pub fn tracker_stores(&self) -> Vec<stores::TrackerStore> {
        self.settings.snapshot().network.tracker_stores
    }

    pub fn set_direct_trackers(&self, trackers: Vec<String>) -> Result<(), String> {
        self.update_settings(move |settings| {
            settings.network.default_trackers = trackers;
        })?;
        Ok(())
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

        // One entry per URL. Re-adding a store is how a user refreshes one by hand, so it replaces
        // rather than duplicating — two rows for one URL would double every endpoint it contributes.
        let added = store.clone();
        self.update_settings(move |settings| {
            settings
                .network
                .tracker_stores
                .retain(|held| held.url != added.url);
            settings.network.tracker_stores.push(added);
        })?;
        Ok(store)
    }

    pub fn remove_tracker_store(&self, url: String) -> Result<(), String> {
        if !self
            .settings
            .snapshot()
            .network
            .tracker_stores
            .iter()
            .any(|held| held.url == url)
        {
            return Err(format!("no store here is served from {url}"));
        }
        self.update_settings(move |settings| {
            settings
                .network
                .tracker_stores
                .retain(|held| held.url != url);
        })?;
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
        let fetched = settings.network.tracker_stores;
        let updated = self.update_settings(move |current| {
            for replacement in fetched {
                if let Some(store) = current
                    .network
                    .tracker_stores
                    .iter_mut()
                    .find(|held| held.url == replacement.url)
                {
                    *store = replacement;
                }
            }
        })?;
        Ok(updated.network.tracker_stores)
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

        let settings = self.settings.snapshot();
        let store = settings
            .network
            .tracker_stores
            .iter()
            .find(|held| held.url == target)
            .ok_or_else(|| format!("no store here is served from {target}"))?;
        let updated = match fetched.and_then(|body| stores::parse_index(&body)) {
            Ok(index) => {
                let built_in = store.built_in;
                stores::store_from(&target, index, now_millis(), built_in)
            }
            // Beside the services, never instead of them. See `refresh_tracker_stores`.
            Err(e) => {
                let mut failed = store.clone();
                failed.last_error = e.to_string();
                failed
            }
        };
        let replacement = updated.clone();
        self.update_settings(move |settings| {
            if let Some(store) = settings
                .network
                .tracker_stores
                .iter_mut()
                .find(|held| held.url == replacement.url)
            {
                *store = replacement;
            }
        })?;
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
                let original_stores = document.network.tracker_stores.clone();
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
                let refreshed_stores = document.network.tracker_stores;
                if let Ok(updated) = self.settings.update(move |current| {
                    // A user may add, remove, or manually refresh a store while these network reads
                    // are in flight. Replace only entries still equal to this loop's original view.
                    for (original, replacement) in original_stores.into_iter().zip(refreshed_stores)
                    {
                        if let Some(store) = current
                            .network
                            .tracker_stores
                            .iter_mut()
                            .find(|held| held.url == original.url && **held == original)
                        {
                            *store = replacement;
                        }
                    }
                }) {
                    settings::write_sync_file(&updated);
                    if changed {
                        // A store that gained a tracker should reach a running worker rather than
                        // wait for a restart — `network.default_trackers` is a live key.
                        self.request_config_push();
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
        let session_id = session_id.trim();
        if !api::network::is_sha256_hex(session_id) {
            return api::network::Outcome::failed(
                "session id must be exactly 64 hexadecimal characters",
            );
        }
        api::network::join_session(&self.control_addr, session_id).await
    }

    pub async fn leave_world(&self, session_id: String) -> api::network::Outcome {
        let session_id = session_id.trim();
        if !api::network::is_sha256_hex(session_id) {
            return api::network::Outcome::failed(
                "session id must be exactly 64 hexadecimal characters",
            );
        }
        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        let session = crate::launch::SessionId::parse(session_id).expect("validated above");
        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        self.launch.cancel_for(&session);
        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        let outcome = crate::launch::cleanup_tunnel(&self.control_addr, &session).await;
        #[cfg(any(target_os = "android", target_os = "ios"))]
        let outcome = api::network::leave_session(&self.control_addr, session_id).await;
        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        if self.launch.release_if_closed(&session, &outcome).is_some() && !outcome.ok {
            return api::network::Outcome::ok("");
        }
        #[cfg(not(any(target_os = "android", target_os = "ios")))]
        if !outcome.closed_or_absent() {
            self.launch.cleanup_failed(&session, &outcome.error);
        }
        outcome
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

    fn current_peer_query(&self) -> Result<(String, Vec<String>), String> {
        let dashboard = self.snapshot();
        if !matches!(
            dashboard.link.status,
            api::model::LinkStatus::Live | api::model::LinkStatus::Polling
        ) || dashboard.node.node_id.is_empty()
        {
            return Err("the worker is not currently reporting its peer identity".to_owned());
        }
        let trackers = dashboard
            .discovery
            .trackers
            .iter()
            .map(|tracker| {
                let host = if tracker.host.contains(':') && !tracker.host.starts_with('[') {
                    format!("[{}]", tracker.host)
                } else {
                    tracker.host.clone()
                };
                if tracker.scheme.is_empty() {
                    format!("{host}:{}", tracker.port)
                } else {
                    format!("{}://{host}:{}", tracker.scheme, tracker.port)
                }
            })
            .collect::<Vec<_>>();
        if trackers.is_empty() {
            return Err("the worker is not currently reporting any tracker".to_owned());
        }
        Ok((dashboard.node.node_id, trackers))
    }

    /// The worker's peer identity and its current presence in the commons tracker namespace.
    ///
    /// The worker owns peer networking on every platform. This method only queries for the identity
    /// already reported over `NODERA-STATE`; it never creates or announces an app-owned second peer.
    pub async fn peer_status(&self) -> Result<peer::PeerStatus, String> {
        let (node_id, trackers) = self.current_peer_query()?;
        tokio::task::spawn_blocking(move || {
            let check = peer::tracker::verify_presence(&node_id, &trackers);
            if !check.trackers.iter().any(|tracker| tracker.reachable) {
                return Err(check.error);
            }
            Ok(peer::PeerStatus {
                node_id: node_id.clone(),
                trackers: check.trackers,
                announced: check.found_self,
                known_peers: check
                    .peers
                    .iter()
                    .filter(|peer| peer.as_str() != node_id)
                    .count() as u64,
                ..peer::PeerStatus::default()
            })
        })
        .await
        .map_err(|e| format!("the peer task failed: {e}"))?
    }

    /// The round trip that proves this device is on the network.
    ///
    /// Queries the tracker and looks for the worker's reported identity. The worker owns the
    /// announce; signing another one here would test a different peer and alter the network.
    pub async fn peer_self_test(&self) -> Result<peer::tracker::SelfTest, String> {
        let (node_id, trackers) = self.current_peer_query()?;
        tokio::task::spawn_blocking(move || Ok(peer::tracker::verify_presence(&node_id, &trackers)))
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
    ///
    /// # The runtime is a parameter, not a `tokio::spawn`
    ///
    /// This is called from each shell's startup, and neither of those is inside a runtime: Tauri's
    /// `setup` hook runs on the main thread before the async runtime is entered, and the Android
    /// bridge owns a runtime it has only just built. A bare `tokio::spawn` there does not fail to
    /// compile — it panics at run time with *"there is no reactor running"*, which is a window that
    /// never opens and a stack trace pointing at this file rather than at the caller that forgot.
    ///
    /// Taking the handle makes the requirement part of the signature, so it cannot be forgotten.
    pub fn start_shared_loops(
        self: &Arc<Self>,
        runtime: &tokio::runtime::Handle,
        sink: Arc<dyn crate::api::link::Sink>,
        events: Arc<dyn crate::api::events::EventSink>,
    ) {
        // The link. The authoritative liveness signal and the only thing that writes the dashboard:
        // the worker PUSHES state as it changes, so the screen is current because the node said so
        // rather than because the app guessed when to ask. Its offline→online edge is what
        // re-pushes configuration to a worker that came back without it.
        let store = Arc::clone(&self.dashboard);
        let reconnect = Arc::new(tokio::sync::Notify::new());
        let reconnect_push = Arc::clone(&self.push);
        let reconnect_signal = Arc::clone(&reconnect);
        runtime.spawn(async move {
            loop {
                reconnect_signal.notified().await;
                reconnect_push.request();
            }
        });
        let addr = self.control_addr.clone();
        runtime.spawn(async move { crate::api::link::pump(addr, store, sink, reconnect).await });

        // The event stream beside it. Two connections on purpose: the link carries what is TRUE of
        // the node and this carries what HAPPENED to it. A prompt built on the first would only
        // fire when the app happened to be connected at the moment the player acted.
        let addr = self.control_addr.clone();
        runtime.spawn(async move { crate::api::events::pump(addr, events).await });

        // Keep the tracker stores fresh, and the worker's synchronisation file with them. Started
        // before the pusher so a first-run install has written the file — the only channel to an
        // Android worker — by the time the worker looks for it.
        let stores = Arc::clone(self);
        runtime.spawn(async move { stores.sync_stores_forever().await });

        // Coalesce configuration pushes: one per settle window, however many saves arrive.
        let pusher = Arc::new(config::ConfigPusher {
            control_addr: self.control_addr.clone(),
            settings: Arc::clone(&self.settings),
            pause: Arc::clone(&self.pause),
            status: Arc::clone(&self.config_status),
        });
        let push = Arc::clone(&self.push);
        runtime.spawn(async move { config::debounce_loop(pusher, push).await });

        // Connection rules, on every platform. Desktop reports the subject as unsupported and the
        // loop is inert there; on a phone this is what stops the node spending somebody's data
        // allowance on strangers' worlds.
        let (settings, pause, push, cache) = (
            Arc::clone(&self.settings),
            Arc::clone(&self.pause),
            Arc::clone(&self.push),
            Arc::clone(&self.network),
        );
        runtime
            .spawn(async move { crate::power::sample_network(settings, pause, push, cache).await });

        // A telemetry answer given while the worker was still starting is delivered here, not lost.
        // First run is precisely when the node is least likely to be listening.
        let settings = Arc::clone(&self.settings);
        let addr = self.control_addr.clone();
        runtime.spawn(async move { telemetry::reconcile_loop(addr, settings).await });
    }

    /* ----------------------------------------------------------------------------------- play */

    /// What this machine could start, and which route each would take.
    ///
    /// Read before the button is pressed, so the interface can name the instance and say whether the
    /// player will land in the world or in the Multiplayer menu. A launcher that only reveals its
    /// choice by doing it is one nobody can predict.
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub async fn launch_targets(&self) -> Vec<crate::launch::discover::LaunchTarget> {
        let mut targets = tokio::task::spawn_blocking(crate::launch::discover::targets)
            .await
            .unwrap_or_default();
        // Discovery describes how an install can be started. Expose how this build would actually
        // start it, so a client-id-only build never advertises Direct while planning a fallback.
        for target in &mut targets {
            target.tier = crate::launch::plan::choose(target).tier;
        }
        targets
    }

    /// Last launch transition, retained by backend across frontend remounts.
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub fn launch_state(&self) -> crate::launch::LaunchState {
        self.launch.current()
    }

    /// Best-effort tunnel cleanup before desktop runtime exits.
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub async fn shutdown_launch(&self) {
        let Some(session) = self.launch.owned_session() else {
            return;
        };
        self.launch.cancel_for(&session);
        match tokio::time::timeout(
            std::time::Duration::from_secs(8),
            crate::launch::cleanup_tunnel(&self.control_addr, &session),
        )
        .await
        {
            Ok(outcome) if outcome.closed_or_absent() => {
                self.launch.release_if_closed(&session, &outcome);
            }
            Ok(outcome) => log::warn!("launch: shutdown tunnel cleanup failed: {}", outcome.error),
            Err(_) => log::warn!("launch: shutdown tunnel cleanup exceeded eight seconds"),
        }
    }

    /// Reserve launcher and start one attempt. Reservation happens before returning so two command
    /// invocations cannot both report success and race inside detached tasks.
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    pub fn start_play(
        self: Arc<Self>,
        world_id: String,
        world_name: String,
        preferred: Option<String>,
        sink: Arc<dyn crate::launch::LaunchSink>,
    ) -> Result<(), String> {
        let world = crate::launch::WorldId::parse(world_id)?;
        let (launch_id, state) = self.launch.begin(&world)?;
        sink.publish(state);
        tokio::spawn(async move {
            self.play(launch_id, world, world_name, preferred, sink)
                .await;
        });
        Ok(())
    }

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    fn publish_launch(
        &self,
        launch_id: crate::launch::LaunchId,
        state: crate::launch::LaunchState,
        sink: &dyn crate::launch::LaunchSink,
        finished: bool,
    ) -> bool {
        let accepted = if finished {
            self.launch.finish(launch_id, state.clone())
        } else {
            self.launch.update(launch_id, state.clone())
        };
        if accepted {
            sink.publish(state);
        }
        accepted
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
    /// 4. **Close the tunnel when an owned game exits, and when anything after step 2 fails.** A
    ///    delegated launcher does not expose game lifetime, so its tunnel remains until explicit
    ///    leave rather than pretending launcher-process exit means Minecraft exited.
    ///
    /// Every transition is published, because a Play button that shows a spinner and no word is the
    /// same failure as a dashboard reporting `0` for "we never asked".
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    async fn play(
        self: Arc<Self>,
        launch_id: crate::launch::LaunchId,
        world_id: crate::launch::WorldId,
        world_name: String,
        preferred: Option<String>,
        sink: Arc<dyn crate::launch::LaunchSink>,
    ) {
        use crate::launch::{
            delegated_observation, plan as planner, DelegatedObservation, Phase, Remedy, SessionId,
            Tier, TunnelLease,
        };

        let mut state = self.launch.current();

        let targets = self.launch_targets().await;
        if !self.launch.is_active(launch_id) {
            return;
        }
        let chosen = match planner::plan(&targets, preferred.as_deref()) {
            Ok(chosen) => chosen,
            Err(no) => {
                self.publish_launch(
                    launch_id,
                    state.failed(no.reason, no.remedy),
                    sink.as_ref(),
                    true,
                );
                return;
            }
        };
        state.tier = Some(chosen.tier);
        state.target_id = Some(chosen.target.id.clone());
        state.profile = Some(chosen.target.name.clone());
        state.install_path = Some(chosen.target.game_dir.clone());

        state = state.at(Phase::Joining);
        if !self.publish_launch(launch_id, state.clone(), sink.as_ref(), false) {
            return;
        }
        // Current worker directory contract identifies a live session by its world's genesis hash.
        // Cross that alias explicitly; never pass a WorldId to a session API by accident.
        let session_id = SessionId::for_world(&world_id);
        // Acquired before the await: cancellation after CONNECT is sent but before its reply still
        // owns enough information to issue DISCONNECT.
        let tunnel = TunnelLease::new(
            self.control_addr.clone(),
            session_id.clone(),
            launch_id,
            Arc::clone(&self.launch),
        );
        let joined = api::network::join_session(&self.control_addr, session_id.as_str()).await;
        if !self.launch.is_active(launch_id) {
            // Explicit leave owns cleanup and has already obsoleted this task.
            tunnel.dismiss();
            return;
        }
        if !joined.ok {
            // EOF, timeout, or an error reply does not prove CONNECT failed before opening a port.
            state.session_id = Some(session_id.as_str().to_owned());
            let mut reason = if joined.error.is_empty() {
                "the worker could not open a tunnel to that world".to_owned()
            } else {
                joined.error.clone()
            };
            let cleanup = tunnel.close().await;
            let closed = cleanup.closed_or_absent();
            if closed {
                state.session_id = None;
            } else {
                reason.push_str(&format!(
                    "; its tunnel could not be closed: {}",
                    cleanup.error
                ));
            }
            self.publish_launch(
                launch_id,
                state.failed(reason, Remedy::Retry),
                sink.as_ref(),
                closed,
            );
            return;
        }
        state.session_id = Some(session_id.as_str().to_owned());
        state.address = Some(joined.value.clone());

        state = state.at(Phase::Preparing);
        if !self.publish_launch(launch_id, state.clone(), sink.as_ref(), false) {
            tunnel.dismiss();
            return;
        }
        let preparation = planner::prepare(
            &chosen,
            &joined.value,
            &world_name,
            crate::launch::auth::account().as_ref(),
        );
        if !self.launch.is_active(launch_id) {
            tunnel.dismiss();
            return;
        }
        let prepared = match preparation {
            Ok(prepared) => prepared,
            Err(no) => {
                let fallback = no.remedy == Remedy::CopyAddress;
                if fallback {
                    // Address is now the usable result. Backend retains tunnel until leave_world.
                    tunnel.preserve();
                } else {
                    let cleanup = tunnel.close().await;
                    if cleanup.closed_or_absent() {
                        state.session_id = None;
                    } else {
                        state.reason = format!(
                            "{}; its tunnel could not be closed: {}",
                            no.reason, cleanup.error
                        );
                    }
                }
                let reason = if state.reason.is_empty() {
                    no.reason
                } else {
                    state.reason.clone()
                };
                let finished = fallback || state.session_id.is_none();
                self.publish_launch(
                    launch_id,
                    state.failed(reason, no.remedy),
                    sink.as_ref(),
                    !fallback && finished,
                );
                return;
            }
        };
        if !self.launch.is_active(launch_id) {
            tunnel.dismiss();
            return;
        }
        state.java = prepared.java.as_ref().map(|p| p.display().to_string());

        state = state.at(Phase::Spawning);
        if !self.publish_launch(launch_id, state.clone(), sink.as_ref(), false) {
            tunnel.dismiss();
            return;
        }
        let command = prepared.command.clone();
        // Process creation is short and synchronous. Putting it in spawn_blocking creates an
        // uncancellable gap where the task can be aborted while the OS still starts the game.
        let spawned = self.launch.if_active(launch_id, || {
            std::process::Command::new(&command.program)
                .args(&command.arguments)
                .current_dir(&command.working_dir)
                .spawn()
        });
        let mut child = match spawned {
            None => {
                tunnel.dismiss();
                return;
            }
            Some(Ok(child)) => child,
            Some(Err(error)) => {
                let cleanup = tunnel.close().await;
                let closed = cleanup.closed_or_absent();
                if closed {
                    state.session_id = None;
                }
                let mut reason = format!(
                    "{} could not be started: {error}",
                    command.program.display()
                );
                if !closed {
                    reason.push_str(&format!(
                        "; its tunnel could not be closed: {}",
                        cleanup.error
                    ));
                }
                self.publish_launch(
                    launch_id,
                    state.failed(reason, Remedy::PickInstall),
                    sink.as_ref(),
                    closed,
                );
                return;
            }
        };

        if !self.launch.is_active(launch_id) {
            let _ = child.kill();
            let _ = child.wait();
            tunnel.dismiss();
            return;
        }

        state.pid = Some(child.id());
        log::info!(
            "launch: {} started for {} on {}",
            chosen.target.name,
            world_id.as_str(),
            joined.value
        );

        if chosen.tier != Tier::Direct {
            let grace = tokio::time::Instant::now() + std::time::Duration::from_millis(750);
            let mut handed_off = false;
            loop {
                if !self.launch.is_active(launch_id) {
                    if !handed_off {
                        let _ = child.kill();
                    }
                    tokio::task::spawn_blocking(move || {
                        let _ = child.wait();
                    });
                    tunnel.dismiss();
                    return;
                }

                let exit = match child.try_wait() {
                    Ok(Some(status)) => Some((status.success(), status.code())),
                    Ok(None) => None,
                    Err(error) => {
                        let cleanup = tunnel.close().await;
                        let closed = cleanup.closed_or_absent();
                        if closed {
                            state.session_id = None;
                        }
                        self.publish_launch(
                            launch_id,
                            state.failed(
                                format!("launcher process could not be observed: {error}"),
                                Remedy::Retry,
                            ),
                            sink.as_ref(),
                            closed,
                        );
                        return;
                    }
                };
                if handed_off && exit.is_some() {
                    // After handoff, launcher lifetime says nothing about Minecraft lifetime.
                    state.pid = None;
                    state.exit_code = exit.and_then(|(_, code)| code);
                    if self.publish_launch(launch_id, state, sink.as_ref(), false) {
                        tunnel.preserve();
                    }
                    return;
                }
                match delegated_observation(exit, tokio::time::Instant::now() >= grace) {
                    DelegatedObservation::Pending => {}
                    DelegatedObservation::Handoff if !handed_off => {
                        handed_off = true;
                        state.handoff = true;
                        state = state.at(Phase::Running);
                        if !self.publish_launch(launch_id, state.clone(), sink.as_ref(), false) {
                            tunnel.dismiss();
                            return;
                        }
                    }
                    DelegatedObservation::Handoff => {}
                    DelegatedObservation::Accepted => {
                        state.handoff = true;
                        state.pid = None;
                        state.exit_code = exit.and_then(|(_, code)| code);
                        state = state.at(Phase::Running);
                        if self.publish_launch(launch_id, state, sink.as_ref(), false) {
                            tunnel.preserve();
                        } else {
                            tunnel.dismiss();
                        }
                        return;
                    }
                    DelegatedObservation::Failed => {
                        state.exit_code = exit.and_then(|(_, code)| code);
                        let code = state
                            .exit_code
                            .map(|code| format!(" with code {code}"))
                            .unwrap_or_default();
                        let cleanup = tunnel.close().await;
                        let closed = cleanup.closed_or_absent();
                        if closed {
                            state.session_id = None;
                        }
                        self.publish_launch(
                            launch_id,
                            state.failed(
                                format!("launcher exited before handoff{code}"),
                                Remedy::Retry,
                            ),
                            sink.as_ref(),
                            closed,
                        );
                        return;
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            }
        }

        // Direct route owns the actual game JVM, so its child lifetime is authoritative.
        state = state.at(Phase::Running);
        if !self.publish_launch(launch_id, state.clone(), sink.as_ref(), false) {
            let _ = child.kill();
            let _ = child.wait();
            tunnel.dismiss();
            return;
        }
        let exited = tokio::task::spawn_blocking(move || child.wait()).await;
        state.exit_code = match exited {
            Ok(Ok(status)) => status.code(),
            _ => None,
        };
        if !self.launch.is_active(launch_id) {
            tunnel.dismiss();
            return;
        }
        let cleanup = tunnel.close().await;
        if cleanup.closed_or_absent() {
            state.session_id = None;
            self.publish_launch(launch_id, state.at(Phase::Exited), sink.as_ref(), true);
        } else {
            self.publish_launch(
                launch_id,
                state.failed(
                    format!(
                        "Minecraft exited, but its tunnel could not be closed: {}",
                        cleanup.error
                    ),
                    Remedy::Retry,
                ),
                sink.as_ref(),
                false,
            );
        }
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

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    #[derive(Default)]
    struct LaunchStates(std::sync::Mutex<Vec<crate::launch::LaunchState>>);

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    impl crate::launch::LaunchSink for LaunchStates {
        fn publish(&self, state: crate::launch::LaunchState) {
            self.0.lock().unwrap().push(state);
        }
    }

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

    #[tokio::test]
    async fn join_and_leave_reject_non_sha256_ids_before_control_io() {
        let core = NoderaCore::new();
        let joined = core.join_world("aabb".to_owned()).await;
        let left = core.leave_world("aabb".to_owned()).await;

        assert!(!joined.ok);
        assert!(joined.error.contains("exactly 64"), "{}", joined.error);
        assert!(!left.ok);
        assert!(left.error.contains("exactly 64"), "{}", left.error);
    }

    #[test]
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    fn obsolete_attempt_cannot_publish_stale_event() {
        const WORLD: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        let core = NoderaCore::new();
        let world = crate::launch::WorldId::parse(WORLD).unwrap();
        let session = crate::launch::SessionId::for_world(&world);
        let (id, state) = core.launch.begin(&world).unwrap();
        core.launch.cancel_for(&session).unwrap();
        let sink = LaunchStates::default();

        assert!(!core.publish_launch(id, state.at(crate::launch::Phase::Running), &sink, false));
        assert!(sink.0.lock().unwrap().is_empty());
    }
}
