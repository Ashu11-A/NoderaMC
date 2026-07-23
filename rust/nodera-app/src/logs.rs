//! The worker's system-log feed for the dashboard.
//!
//! One bounded ring buffer, two producers:
//! * **Supervise mode** — [`crate::daemon`] pipes the child worker's stdout/stderr straight in.
//! * **Attach mode** — a tail task follows the externally-written log file
//!   (`NODERA_WORKER_LOG`, default `run/logs/nodera-worker.log` — the `scripts/dev.sh` path).
//!
//! The React UI polls `get_worker_logs` on a cadence; the buffer keeps the last
//! [`MAX_LINES`] lines so a chatty worker cannot grow the app's memory.

use std::collections::VecDeque;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// Ring capacity — enough scrollback for diagnosis, bounded for memory.
pub const MAX_LINES: usize = 500;

/// Bounded, thread-safe log ring.
#[derive(Default)]
pub struct LogBuffer {
    inner: Mutex<VecDeque<String>>,
}

impl LogBuffer {
    pub fn new() -> Self {
        Self::default()
    }

    /// Append one line, evicting the oldest beyond [`MAX_LINES`].
    pub fn push(&self, line: String) {
        let mut buf = self.inner.lock().unwrap();
        if buf.len() == MAX_LINES {
            buf.pop_front();
        }
        buf.push_back(line);
    }

    /// The current scrollback, oldest first.
    pub fn snapshot(&self) -> Vec<String> {
        self.inner.lock().unwrap().iter().cloned().collect()
    }
}

/// The attach-mode log file: `NODERA_WORKER_LOG` else the dev-script default.
fn attach_log_path() -> PathBuf {
    std::env::var("NODERA_WORKER_LOG")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("run/logs/nodera-worker.log"))
}

/// Attach mode: follow the worker's log file (start from its current end, like `tail -f`;
/// a truncated/rotated file restarts from the top). Runs until the app exits.
pub async fn tail_attach_log(buffer: Arc<LogBuffer>) {
    let path = attach_log_path();
    let mut offset: Option<u64> = None; // None = seek to end on first successful open
    let mut partial = String::new();
    let mut tick = tokio::time::interval(Duration::from_millis(1000));
    loop {
        tick.tick().await;
        let Ok(mut file) = std::fs::File::open(&path) else {
            continue;
        };
        let len = file.metadata().map(|m| m.len()).unwrap_or(0);
        let from = match offset {
            None => len, // first sight: only new lines from here on
            Some(prev) if prev > len => 0, // rotation/truncation: start over
            Some(prev) => prev,
        };
        if file.seek(SeekFrom::Start(from)).is_err() {
            continue;
        }
        let mut chunk = String::new();
        if file.read_to_string(&mut chunk).is_err() {
            offset = Some(from);
            continue;
        }
        offset = Some(from + chunk.len() as u64);
        partial.push_str(&chunk);
        while let Some(nl) = partial.find('\n') {
            let line: String = partial.drain(..=nl).collect();
            let line = line.trim_end_matches('\n').trim_end_matches('\r');
            if !line.is_empty() {
                buffer.push(line.to_string());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_keeps_only_the_newest_max_lines() {
        let buf = LogBuffer::new();
        for i in 0..(MAX_LINES + 10) {
            buf.push(format!("line-{i}"));
        }
        let snap = buf.snapshot();
        assert_eq!(snap.len(), MAX_LINES);
        assert_eq!(snap.first().unwrap(), "line-10");
        assert_eq!(snap.last().unwrap(), &format!("line-{}", MAX_LINES + 9));
    }

    #[test]
    fn snapshot_is_oldest_first_and_stable() {
        let buf = LogBuffer::new();
        buf.push("a".into());
        buf.push("b".into());
        assert_eq!(buf.snapshot(), vec!["a".to_string(), "b".to_string()]);
        assert_eq!(buf.snapshot().len(), 2); // snapshot does not drain
    }
}
