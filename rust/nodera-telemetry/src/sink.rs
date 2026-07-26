//! The write side: newline-delimited JSON files in a spool directory.
//!
//! **Why a file and not a Kafka producer.** The Big Data plane (`docker/telemetry/`) is Vector →
//! Redpanda → ClickHouse, and every one of those is a moving part with its own failure modes. If
//! this service spoke Kafka directly, a broker outage would either block ingest or drop consented
//! reports on the floor, and the crate would carry a heavyweight native dependency into a
//! workspace that deliberately has none. Writing NDJSON to a directory makes the durability
//! story trivial — the file is the buffer — and lets Vector own retries, backpressure, and
//! delivery. The service stays a small, auditable program whose job is *deciding what may be
//! stored*, which is the part that has to be reviewable.
//!
//! One line per event, not per batch: every downstream consumer wants rows, and splitting a batch
//! later would mean re-deriving the envelope fields for each row anyway.

use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::PathBuf;

/// Anything that can take a finished NDJSON line.
pub trait EventSink: Send {
    fn write_line(&mut self, line: &str, now_millis: u64) -> io::Result<()>;
    /// Push buffered lines to the OS. Called once per batch rather than once per line: a batch is
    /// the unit a client retries, so it is also the useful unit of durability.
    fn flush(&mut self) -> io::Result<()>;
}

/// A rotating NDJSON writer.
pub struct NdjsonSink {
    dir: PathBuf,
    max_bytes: u64,
    max_millis: u64,
    current: Option<Current>,
    sequence: u64,
}

struct Current {
    writer: BufWriter<File>,
    bytes: u64,
    opened_millis: u64,
}

impl NdjsonSink {
    /// `max_bytes` and `max_seconds` are both rotation triggers; whichever fires first wins. Time
    /// matters as much as size because a quiet service would otherwise hold its last few hundred
    /// rows in an open file indefinitely, and Vector's read position is per file.
    pub fn new(dir: impl Into<PathBuf>, max_bytes: u64, max_seconds: u64) -> io::Result<Self> {
        let dir = dir.into();
        std::fs::create_dir_all(&dir)?;
        Ok(Self {
            dir,
            max_bytes: max_bytes.max(1),
            max_millis: max_seconds.max(1) * 1_000,
            current: None,
            sequence: 0,
        })
    }

    fn should_rotate(&self, now_millis: u64) -> bool {
        match &self.current {
            None => true,
            Some(current) => {
                current.bytes >= self.max_bytes
                    || now_millis.saturating_sub(current.opened_millis) >= self.max_millis
            }
        }
    }

    fn rotate(&mut self, now_millis: u64) -> io::Result<()> {
        if let Some(mut current) = self.current.take() {
            current.writer.flush()?;
        }
        self.sequence += 1;
        let path = self.dir.join(format!(
            "telemetry-{now_millis:013}-{:06}.ndjson",
            self.sequence
        ));
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        self.current = Some(Current {
            writer: BufWriter::new(file),
            bytes: 0,
            opened_millis: now_millis,
        });
        Ok(())
    }
}

impl EventSink for NdjsonSink {
    fn write_line(&mut self, line: &str, now_millis: u64) -> io::Result<()> {
        if self.should_rotate(now_millis) {
            self.rotate(now_millis)?;
        }
        let current = self.current.as_mut().expect("rotate installed a file");
        current.writer.write_all(line.as_bytes())?;
        current.writer.write_all(b"\n")?;
        current.bytes += line.len() as u64 + 1;
        Ok(())
    }

    fn flush(&mut self) -> io::Result<()> {
        match self.current.as_mut() {
            Some(current) => current.writer.flush(),
            None => Ok(()),
        }
    }
}

/// An in-memory sink, for tests that assert on what *would* be written.
#[cfg(test)]
#[derive(Debug, Default)]
pub struct MemorySink {
    pub lines: Vec<String>,
    pub flushes: usize,
}

#[cfg(test)]
impl EventSink for MemorySink {
    fn write_line(&mut self, line: &str, _now_millis: u64) -> io::Result<()> {
        self.lines.push(line.to_owned());
        Ok(())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.flushes += 1;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::sync::atomic::{AtomicU64, Ordering};

    /// A unique scratch directory, removed on drop. No `tempfile` dependency: the workspace pins
    /// its dependency list deliberately and one directory is not worth a crate.
    struct Scratch(PathBuf);

    impl Scratch {
        fn new(label: &str) -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(0);
            let unique = COUNTER.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "nodera-telemetry-{label}-{}-{unique}",
                std::process::id()
            ));
            let _ = std::fs::remove_dir_all(&path);
            Self(path)
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn files(dir: &Path) -> Vec<PathBuf> {
        let mut paths: Vec<PathBuf> = std::fs::read_dir(dir)
            .unwrap()
            .filter_map(|e| e.ok().map(|e| e.path()))
            .collect();
        paths.sort();
        paths
    }

    #[test]
    fn lines_land_in_a_file_and_survive_a_flush() {
        let scratch = Scratch::new("write");
        let mut sink = NdjsonSink::new(&scratch.0, 1 << 20, 3_600).unwrap();
        sink.write_line("{\"a\":1}", 1_000).unwrap();
        sink.write_line("{\"a\":2}", 1_001).unwrap();
        sink.flush().unwrap();

        let paths = files(&scratch.0);
        assert_eq!(paths.len(), 1);
        let body = std::fs::read_to_string(&paths[0]).unwrap();
        assert_eq!(body, "{\"a\":1}\n{\"a\":2}\n");
    }

    #[test]
    fn the_file_rotates_once_it_passes_the_size_bound() {
        let scratch = Scratch::new("size");
        let mut sink = NdjsonSink::new(&scratch.0, 8, 3_600).unwrap();
        sink.write_line("{\"a\":1}", 1_000).unwrap(); // 8 bytes with the newline — at the bound
        sink.write_line("{\"a\":2}", 1_001).unwrap(); // so this one opens a second file
        sink.flush().unwrap();
        assert_eq!(files(&scratch.0).len(), 2);
    }

    #[test]
    fn the_file_rotates_once_it_passes_the_age_bound() {
        let scratch = Scratch::new("age");
        let mut sink = NdjsonSink::new(&scratch.0, 1 << 30, 60).unwrap();
        sink.write_line("{\"a\":1}", 0).unwrap();
        sink.write_line("{\"a\":2}", 59_999).unwrap();
        assert_eq!(files(&scratch.0).len(), 1);
        sink.write_line("{\"a\":3}", 60_000).unwrap();
        sink.flush().unwrap();
        assert_eq!(files(&scratch.0).len(), 2);
    }

    #[test]
    fn rotated_file_names_sort_in_write_order() {
        let scratch = Scratch::new("order");
        let mut sink = NdjsonSink::new(&scratch.0, 1, 3_600).unwrap();
        for index in 0..3 {
            sink.write_line("{}", 1_000 + index).unwrap();
        }
        sink.flush().unwrap();
        let names: Vec<String> = files(&scratch.0)
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
            .collect();
        let mut sorted = names.clone();
        sorted.sort();
        assert_eq!(names, sorted);
        assert_eq!(names.len(), 3);
    }

    #[test]
    fn a_missing_spool_directory_is_created_rather_than_refused() {
        let scratch = Scratch::new("mkdir");
        let nested = scratch.0.join("a/b/c");
        let mut sink = NdjsonSink::new(&nested, 1 << 20, 3_600).unwrap();
        sink.write_line("{}", 0).unwrap();
        sink.flush().unwrap();
        assert_eq!(files(&nested).len(), 1);
    }

    #[test]
    fn the_memory_sink_records_lines_and_flushes_for_the_service_tests() {
        let mut sink = MemorySink::default();
        sink.write_line("{\"a\":1}", 0).unwrap();
        sink.flush().unwrap();
        assert_eq!(sink.lines, vec!["{\"a\":1}".to_owned()]);
        assert_eq!(sink.flushes, 1);
    }

    #[test]
    fn flushing_before_any_write_is_not_an_error() {
        let scratch = Scratch::new("empty");
        let mut sink = NdjsonSink::new(&scratch.0, 1 << 20, 3_600).unwrap();
        assert!(sink.flush().is_ok());
        assert!(
            files(&scratch.0).is_empty(),
            "no file is opened until a line is written"
        );
    }
}
