//! The task that makes a service a participant rather than just a listener.
//!
//! One loop owns four things that used to be nobody's job:
//!
//! * **Announce.** Sign a fresh [`ServiceRecord`] and send it to every configured tracker on an
//!   interval the trackers themselves pace, so peers can discover this service instead of being
//!   configured with it.
//! * **Remember the siblings.** Every ack carries the other services of this kind. Holding the merged
//!   list means a drain can name replacements immediately, without a discovery round trip at the one
//!   moment the service is least able to make one.
//! * **Drain.** On a shutdown signal or an update: refuse new work, tell the peers already committed
//!   (directly, down their own control channels) and the trackers (so future queries see it), let
//!   in-flight work finish inside a grace period, then stop.
//! * **Update.** Notice that the published binary is not the one running, verify the replacement,
//!   drain, swap, re-exec.
//!
//! The order inside a drain is the whole design and it is enforced here rather than trusted to each
//! service's `main`: **refuse, announce, notify, wait, exit**. A service that stopped answering before
//! it told anyone produced exactly the failure the peer side handled worst — a vanished reservation
//! with no replacement and no reason.

use std::future::Future;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use nodera_codec::service::{
    ServiceDirectoryEntry, ServiceDrainNotice, ServiceKind, ServiceLifecycle, ServiceRecord,
};
use nodera_codec::types::NetworkId;

use crate::directory::{self, AnnounceOutcome};
use crate::drain::DrainState;
use crate::identity::{RecordSnapshot, ServiceIdentity};
use crate::update::{self, Fetcher, UpdateConfig};

/// The capacity numbers a service reports about itself, sampled at announce time.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CapacitySnapshot {
    /// Registrations or announced peers held now.
    pub active_sessions: u32,
    /// The operator's ceiling; 0 = unstated.
    pub max_sessions: u32,
    /// Relay circuits bridged now.
    pub active_circuits: u32,
    /// The ceiling; 0 = unstated.
    pub max_circuits: u32,
    /// Requests refused in the last window.
    pub rejected_last_window: u32,
}

/// What a concrete service must provide for the shared lifecycle to drive it.
pub trait ServiceHost: Send + Sync + 'static {
    /// Rendezvous or tracker.
    fn kind(&self) -> ServiceKind;

    /// Current capacity numbers.
    fn capacity(&self) -> CapacitySnapshot;

    /// Push a signed drain notice to every peer holding a control channel.
    ///
    /// Returns how many peers were reached — logged, because "drained cleanly, told nobody" and
    /// "drained cleanly, told forty peers" are different events and only one of them is fine.
    ///
    /// A tracker has no long-lived peer channels and returns 0; that is correct, not a stub.
    fn notify_peers(&self, notice: &ServiceDrainNotice) -> usize;
}

/// Everything the lifecycle loop needs that is not the host itself.
#[derive(Debug, Clone)]
pub struct LifecycleConfig {
    /// Trackers to announce to.
    pub tracker_endpoints: Vec<String>,
    /// Routes to advertise.
    pub routes: Vec<String>,
    /// The network served.
    pub network_id: NetworkId,
    /// The running binary's product version.
    pub version: String,
    /// How long a signed record stays valid without a refresh.
    pub record_ttl_seconds: u64,
    /// How often to re-announce. Clamped to at least 5 s so a misconfiguration cannot become a flood.
    pub announce_interval_seconds: u64,
    /// The update lane's configuration.
    pub update: UpdateConfig,
}

impl LifecycleConfig {
    fn announce_interval(&self) -> Duration {
        Duration::from_secs(self.announce_interval_seconds.max(5))
    }

    fn record_ttl_millis(&self) -> u64 {
        self.record_ttl_seconds.max(30).saturating_mul(1_000)
    }
}

/// Why the lifecycle loop finished.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// The process was asked to stop; the drain completed (or its grace period expired).
    Stopped,
    /// A verified update is installed and the caller should re-exec into it.
    Updated,
}

/// The shared state a service's request path can read: am I draining, and where should peers go?
#[derive(Debug)]
pub struct Lifecycle {
    drain: Arc<DrainState>,
    siblings: Mutex<Vec<ServiceDirectoryEntry>>,
}

impl Lifecycle {
    /// A fresh, serving lifecycle.
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            drain: DrainState::new(),
            siblings: Mutex::new(Vec::new()),
        })
    }

    /// The drain state, shared with the accept path.
    pub fn drain(&self) -> &Arc<DrainState> {
        &self.drain
    }

    /// The last merged sibling directory.
    pub fn siblings(&self) -> Vec<ServiceDirectoryEntry> {
        self.siblings
            .lock()
            .map(|held| held.clone())
            .unwrap_or_default()
    }

    fn set_siblings(&self, entries: Vec<ServiceDirectoryEntry>) {
        if let Ok(mut held) = self.siblings.lock() {
            *held = entries;
        }
    }
}

impl Default for Lifecycle {
    fn default() -> Self {
        Self {
            drain: DrainState::new(),
            siblings: Mutex::new(Vec::new()),
        }
    }
}

/// Wall clock in epoch milliseconds.
pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or_default()
}

/// Run the lifecycle until the process is asked to stop or an update is ready to install.
///
/// On [`Outcome::Updated`] the replacement is already verified, staged, and installed at the
/// executable's path — the caller re-execs. Splitting the re-exec out keeps this function testable and
/// keeps the one irreversible step in `main`, where it is visible.
pub async fn run<H, F>(
    host: Arc<H>,
    lifecycle: Arc<Lifecycle>,
    identity: Arc<ServiceIdentity>,
    config: LifecycleConfig,
    fetcher: Arc<dyn Fetcher>,
    shutdown: F,
) -> Outcome
where
    H: ServiceHost,
    F: Future<Output = ()> + Send,
{
    let mut announce_tick = tokio::time::interval(config.announce_interval());
    announce_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut update_tick = tokio::time::interval(Duration::from_secs(
        config.update.check_interval_seconds.max(60),
    ));
    update_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    // The first tick of a tokio interval fires immediately; for the update lane that would mean a
    // release fetch during startup, so it is consumed here.
    update_tick.tick().await;

    let shutdown = std::pin::pin!(shutdown);
    let mut shutdown = shutdown;

    loop {
        tokio::select! {
            _ = announce_tick.tick() => {
                announce_once(&*host, &lifecycle, &identity, &config).await;
            }
            _ = update_tick.tick(), if config.update.enabled() => {
                if let Some(staged) = staged_update(&config, &fetcher).await {
                    println!(
                        "nodera-service: update {} verified; draining before install",
                        &staged.digest_hex[..12]
                    );
                    drain_now(
                        &*host,
                        &lifecycle,
                        &identity,
                        &config,
                        ServiceDrainNotice::REASON_UPDATE,
                    )
                    .await;
                    match update::install(&staged) {
                        Ok(()) => return Outcome::Updated,
                        Err(e) => {
                            // Drained for nothing. Say so plainly: the operator now has a service
                            // that refuses work and needs a restart, and silence here would make
                            // that look like a hang.
                            eprintln!(
                                "nodera-service: update staged but could not be installed ({e}); \
                                 the service is drained and must be restarted manually"
                            );
                            return Outcome::Stopped;
                        }
                    }
                }
            }
            _ = &mut shutdown => {
                drain_now(
                    &*host,
                    &lifecycle,
                    &identity,
                    &config,
                    ServiceDrainNotice::REASON_SHUTDOWN,
                )
                .await;
                return Outcome::Stopped;
            }
        }
    }
}

/// Announce once, and remember the siblings the trackers reported.
async fn announce_once<H: ServiceHost>(
    host: &H,
    lifecycle: &Lifecycle,
    identity: &ServiceIdentity,
    config: &LifecycleConfig,
) -> Vec<AnnounceOutcome> {
    if config.tracker_endpoints.is_empty() {
        return Vec::new();
    }
    let (record, signature) = sign_now(host, lifecycle, identity, config);
    let outcomes = directory::announce_to_all(&config.tracker_endpoints, &record, &signature).await;
    let merged = directory::merge_directories(&outcomes);
    if !merged.is_empty() {
        lifecycle.set_siblings(merged);
    }
    outcomes
}

/// Sign a record describing the service as it is right now.
fn sign_now<H: ServiceHost>(
    host: &H,
    lifecycle: &Lifecycle,
    identity: &ServiceIdentity,
    config: &LifecycleConfig,
) -> (ServiceRecord, Vec<u8>) {
    let capacity = host.capacity();
    identity.sign_record(RecordSnapshot {
        kind: host.kind(),
        lifecycle: lifecycle.drain().lifecycle(),
        network_id: config.network_id,
        routes: config.routes.clone(),
        version: config.version.clone(),
        active_sessions: capacity.active_sessions,
        max_sessions: capacity.max_sessions,
        active_circuits: capacity.active_circuits,
        max_circuits: capacity.max_circuits,
        rejected_last_window: capacity.rejected_last_window,
        now_millis: now_millis(),
        ttl_millis: config.record_ttl_millis(),
        drain_deadline_epoch_millis: lifecycle.drain().deadline_epoch_millis(),
    })
}

/// Perform a full drain: refuse, announce, notify, wait.
///
/// Public so a service's own `main` can drain on an operator request without going through the
/// update lane, and so the sequence exists in exactly one place.
pub async fn drain_now<H: ServiceHost>(
    host: &H,
    lifecycle: &Lifecycle,
    identity: &ServiceIdentity,
    config: &LifecycleConfig,
    reason: &str,
) {
    let grace = Duration::from_secs(config.update.drain_grace_seconds.max(1));
    let deadline = now_millis() + grace.as_millis() as u64;
    // 1. Refuse new work first. A reservation granted after this point is a promise already broken.
    if !lifecycle.drain().begin(deadline, reason) {
        return;
    }

    // 2. Sign the draining record once and use the same bytes everywhere, so a peer that verifies the
    //    notice has verified exactly what the trackers were told.
    let (record, signature) = sign_now(host, lifecycle, identity, config);
    let replacements = lifecycle.siblings();
    let notice = ServiceDrainNotice {
        record: record.clone(),
        signature: signature.clone(),
        replacements: replacements.clone(),
        reason: reason.to_owned(),
    };

    // 3. Tell the peers already committed, directly. They are the ones losing an inbound path.
    let notified = host.notify_peers(&notice);
    // And the trackers, so a peer that asks after this moment is not sent here.
    let acked = directory::announce_to_all(&config.tracker_endpoints, &record, &signature)
        .await
        .len();
    println!(
        "nodera-service: draining ({reason}); told {notified} peer(s) and {acked} tracker(s), \
         offering {} replacement(s)",
        replacements.len()
    );

    // 4. Let in-flight work finish. A relay circuit carrying a world transfer completes; it does not
    //    get cut because an update happened to be available.
    let cut = lifecycle.drain().wait_for_quiet(grace).await;
    if cut == 0 {
        println!("nodera-service: drained with nothing in flight");
    } else {
        println!("nodera-service: grace period expired with {cut} unit(s) of work still in flight");
    }

    // 5. Only now say Stopped, so the record does not disappear from discovery while circuits were
    //    still using it.
    let (stopped, stopped_signature) = identity.sign_record(RecordSnapshot {
        kind: host.kind(),
        lifecycle: ServiceLifecycle::Stopped,
        network_id: config.network_id,
        routes: config.routes.clone(),
        version: config.version.clone(),
        active_sessions: 0,
        max_sessions: 0,
        active_circuits: 0,
        max_circuits: 0,
        rejected_last_window: 0,
        now_millis: now_millis(),
        ttl_millis: config.record_ttl_millis(),
        drain_deadline_epoch_millis: 0,
    });
    directory::announce_to_all(&config.tracker_endpoints, &stopped, &stopped_signature).await;
}

/// Check for and stage an update, off the runtime's worker threads.
async fn staged_update(
    config: &LifecycleConfig,
    fetcher: &Arc<dyn Fetcher>,
) -> Option<update::StagedUpdate> {
    let update_config = config.update.clone();
    let fetcher = Arc::clone(fetcher);
    let staged = tokio::task::spawn_blocking(move || {
        let exe = std::env::current_exe().ok()?;
        if !update::is_installable(&exe) {
            // Checked before anything is drained: cutting every circuit for an update that then
            // cannot be written would be strictly worse than not updating.
            eprintln!(
                "nodera-service: {} is not replaceable; skipping the update check",
                exe.display()
            );
            return None;
        }
        let digest = match update::check(&update_config, fetcher.as_ref(), &exe) {
            Ok(Some(digest)) => digest,
            Ok(None) => return None,
            Err(e) => {
                eprintln!("nodera-service: update check failed: {e}");
                return None;
            }
        };
        match update::stage(&update_config, fetcher.as_ref(), &exe, &digest) {
            Ok(staged) => Some(staged),
            Err(e) => {
                eprintln!("nodera-service: update download refused: {e}");
                None
            }
        }
    })
    .await
    .ok()??;
    Some(staged)
}

#[cfg(test)]
mod tests {
    use super::*;
    use nodera_codec::types::NodeId;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct FakeHost {
        notified: AtomicUsize,
        last_reason: Mutex<String>,
        last_replacements: AtomicUsize,
        last_lifecycle: Mutex<Option<ServiceLifecycle>>,
    }

    impl FakeHost {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                notified: AtomicUsize::new(0),
                last_reason: Mutex::new(String::new()),
                last_replacements: AtomicUsize::new(0),
                last_lifecycle: Mutex::new(None),
            })
        }
    }

    impl ServiceHost for FakeHost {
        fn kind(&self) -> ServiceKind {
            ServiceKind::Rendezvous
        }

        fn capacity(&self) -> CapacitySnapshot {
            CapacitySnapshot {
                active_sessions: 7,
                max_sessions: 100,
                active_circuits: 2,
                max_circuits: 8,
                rejected_last_window: 1,
            }
        }

        fn notify_peers(&self, notice: &ServiceDrainNotice) -> usize {
            self.notified.fetch_add(1, Ordering::SeqCst);
            *self.last_reason.lock().unwrap() = notice.reason.clone();
            self.last_replacements
                .store(notice.replacements.len(), Ordering::SeqCst);
            *self.last_lifecycle.lock().unwrap() = Some(notice.record.lifecycle);
            3
        }
    }

    fn config() -> LifecycleConfig {
        LifecycleConfig {
            tracker_endpoints: Vec::new(),
            routes: vec!["rdv.example:25601".to_owned()],
            network_id: NetworkId::new(1, 2),
            version: "0.1.0".to_owned(),
            record_ttl_seconds: 300,
            announce_interval_seconds: 120,
            update: UpdateConfig {
                channel: String::new(),
                feed_base_url: update::DEFAULT_FEED_BASE_URL.to_owned(),
                asset_name: "nodera-rendezvous".to_owned(),
                check_interval_seconds: 3_600,
                drain_grace_seconds: 1,
            },
        }
    }

    fn identity() -> Arc<ServiceIdentity> {
        Arc::new(ServiceIdentity::from_parts(NodeId::new(4, 5), [7u8; 32]))
    }

    #[tokio::test]
    async fn a_drain_refuses_work_before_it_tells_anybody() {
        // The ordering that matters: by the time a peer is told, the service is already closed to new
        // reservations, so nobody can be handed a slot it knows it will drop.
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        assert!(lifecycle.drain().accepts_new_work());

        drain_now(&*host, &lifecycle, &identity(), &config(), "operator").await;

        assert!(!lifecycle.drain().accepts_new_work());
        assert_eq!(host.notified.load(Ordering::SeqCst), 1);
        assert_eq!(*host.last_reason.lock().unwrap(), "operator");
        assert_eq!(
            *host.last_lifecycle.lock().unwrap(),
            Some(ServiceLifecycle::Draining),
            "the notice a peer verifies must say Draining, not Serving"
        );
    }

    #[tokio::test]
    async fn the_drain_notice_carries_the_replacements_the_trackers_reported() {
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        lifecycle.set_siblings(vec![sibling(1), sibling(2)]);
        drain_now(&*host, &lifecycle, &identity(), &config(), "update").await;
        assert_eq!(
            host.last_replacements.load(Ordering::SeqCst),
            2,
            "a peer losing its relay should be told where to go, not left to rediscover"
        );
    }

    #[tokio::test]
    async fn draining_twice_notifies_once() {
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        drain_now(&*host, &lifecycle, &identity(), &config(), "operator").await;
        drain_now(&*host, &lifecycle, &identity(), &config(), "shutdown").await;
        assert_eq!(host.notified.load(Ordering::SeqCst), 1);
        assert_eq!(*host.last_reason.lock().unwrap(), "operator");
    }

    #[tokio::test]
    async fn in_flight_work_delays_the_drain_and_the_grace_period_bounds_it() {
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        let _stuck = lifecycle.drain().enter();
        let started = std::time::Instant::now();
        drain_now(&*host, &lifecycle, &identity(), &config(), "update").await;
        // The 1 s grace in the test config bounds it; without the wait this would return instantly.
        assert!(
            started.elapsed() >= Duration::from_millis(900),
            "the drain did not wait for in-flight work: {:?}",
            started.elapsed()
        );
    }

    #[tokio::test]
    async fn the_signed_record_reports_the_hosts_own_capacity() {
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        let (record, signature) = sign_now(&*host, &lifecycle, &identity(), &config());
        assert_eq!(record.active_sessions, 7);
        assert_eq!(record.max_circuits, 8);
        assert_eq!(record.rejected_last_window, 1);
        nodera_codec::sig::verify(&record.public_key, &record.signed_bytes(), &signature).unwrap();
    }

    #[tokio::test]
    async fn a_shutdown_signal_drains_and_returns_stopped() {
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        let (tx, rx) = tokio::sync::oneshot::channel::<()>();
        let task = tokio::spawn({
            let host = Arc::clone(&host);
            let lifecycle = Arc::clone(&lifecycle);
            async move {
                run(
                    host,
                    lifecycle,
                    identity(),
                    config(),
                    Arc::new(update::CurlFetcher::default()),
                    async move {
                        let _ = rx.await;
                    },
                )
                .await
            }
        });
        tx.send(()).unwrap();
        assert_eq!(task.await.unwrap(), Outcome::Stopped);
        assert_eq!(host.notified.load(Ordering::SeqCst), 1);
        assert_eq!(*host.last_reason.lock().unwrap(), "shutdown");
    }

    #[tokio::test]
    async fn announcing_with_no_trackers_configured_is_a_no_op_not_an_error() {
        // A single-tracker deployment leaves this empty, and a service that failed to start because
        // it had nowhere to announce would have made discovery load-bearing for its own liveness.
        let host = FakeHost::new();
        let lifecycle = Lifecycle::new();
        let outcomes = announce_once(&*host, &lifecycle, &identity(), &config()).await;
        assert!(outcomes.is_empty());
        assert!(lifecycle.siblings().is_empty());
    }

    fn sibling(id: u64) -> ServiceDirectoryEntry {
        ServiceDirectoryEntry {
            record: ServiceRecord {
                service: NodeId::new(id, id),
                public_key: vec![0x42; 44],
                kind: ServiceKind::Rendezvous,
                lifecycle: ServiceLifecycle::Serving,
                network_id: NetworkId::new(1, 2),
                routes: vec![format!("rdv{id}.example:25601")],
                version: "0.1.0".to_owned(),
                active_sessions: 0,
                max_sessions: 0,
                active_circuits: 0,
                max_circuits: 0,
                rejected_last_window: 0,
                issued_at_epoch_millis: 1,
                expires_at_epoch_millis: 2,
                drain_deadline_epoch_millis: 0,
            },
            signature: vec![0x77; 64],
            score: nodera_codec::service::ServiceScore::default(),
        }
    }
}
