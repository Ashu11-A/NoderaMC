//! Machine + worker resource sampling for the dashboard (RAM / CPU tiles).
//!
//! A background task refreshes [`sysinfo`] on a 2 s cadence and publishes a [`SystemStats`]
//! snapshot: whole-machine CPU/memory plus the supervised `nodera-headless` worker's own
//! CPU share and resident memory (found by command line, so attach mode works too — the app
//! does not need to be the process's parent).

use std::sync::Mutex;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use sysinfo::{ProcessRefreshKind, RefreshKind, System};

/// Resource snapshot pushed to the React frontend alongside the worker metrics.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct SystemStats {
    /// Whole-machine CPU utilisation, 0–100 (average across cores).
    pub machine_cpu_percent: f32,
    pub mem_used_bytes: u64,
    pub mem_total_bytes: u64,
    /// The worker process, when found (attach mode included).
    pub worker_found: bool,
    pub worker_pid: u32,
    /// Worker CPU utilisation, 0–100·cores (sysinfo convention: 100 = one full core).
    pub worker_cpu_percent: f32,
    /// Worker resident set size, bytes.
    pub worker_rss_bytes: u64,
}

/// Thread-safe holder, same shape as [`crate::metrics::MetricsHandle`].
#[derive(Default)]
pub struct SystemHandle {
    inner: Mutex<SystemStats>,
}

impl SystemHandle {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn snapshot(&self) -> SystemStats {
        self.inner.lock().unwrap().clone()
    }

    fn set(&self, stats: SystemStats) {
        *self.inner.lock().unwrap() = stats;
    }
}

/// True when this process looks like the nodera-headless worker (launcher script or its JVM).
fn is_worker(process: &sysinfo::Process) -> bool {
    process
        .cmd()
        .iter()
        .any(|arg| arg.contains("nodera-headless") || arg.contains("HeadlessPeerMain"))
}

/// Run the sampler until the app exits. Two refreshes per sample because CPU deltas need a
/// baseline (sysinfo measures usage between consecutive refreshes).
pub async fn sample(handle: std::sync::Arc<SystemHandle>) {
    let refresh = RefreshKind::new()
        .with_cpu(sysinfo::CpuRefreshKind::new().with_cpu_usage())
        .with_memory(sysinfo::MemoryRefreshKind::everything())
        .with_processes(ProcessRefreshKind::new().with_cpu().with_memory().with_cmd(
            sysinfo::UpdateKind::OnlyIfNotSet,
        ));
    let mut sys = System::new_with_specifics(refresh);
    let mut tick = tokio::time::interval(Duration::from_secs(2));
    loop {
        tick.tick().await;
        sys.refresh_specifics(refresh);
        let worker = sys.processes().values().find(|p| is_worker(p));
        handle.set(SystemStats {
            machine_cpu_percent: sys.global_cpu_info().cpu_usage(),
            mem_used_bytes: sys.used_memory(),
            mem_total_bytes: sys.total_memory(),
            worker_found: worker.is_some(),
            worker_pid: worker.map(|p| p.pid().as_u32()).unwrap_or(0),
            worker_cpu_percent: worker.map(|p| p.cpu_usage()).unwrap_or(0.0),
            worker_rss_bytes: worker.map(|p| p.memory()).unwrap_or(0),
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_round_trips_snapshot() {
        let handle = SystemHandle::new();
        assert_eq!(handle.snapshot().mem_total_bytes, 0);
        handle.set(SystemStats {
            machine_cpu_percent: 12.5,
            mem_used_bytes: 10,
            mem_total_bytes: 20,
            worker_found: true,
            worker_pid: 42,
            worker_cpu_percent: 50.0,
            worker_rss_bytes: 1024,
        });
        let got = handle.snapshot();
        assert!(got.worker_found);
        assert_eq!(got.worker_pid, 42);
        assert_eq!(got.mem_total_bytes, 20);
    }

    #[test]
    fn stats_serialize_to_snake_case_json() {
        let json = serde_json::to_string(&SystemStats::default()).unwrap();
        assert!(json.contains("machine_cpu_percent"));
        assert!(json.contains("worker_rss_bytes"));
    }
}
