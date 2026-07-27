//! The worlds this tracker has been told to forget, and the proof for each.
//!
//! # Why a tracker keeps a deletion instead of just performing one
//!
//! Removing a world from the registry is not enough. Peers that were offline when their owner
//! deleted a world keep announcing it, and a tracker with no memory would re-list it from the very
//! first of those announces — so a deletion would survive only until the next peer woke up.
//!
//! Keeping the record turns that around. An announce for a deleted world is answered with the
//! tombstone itself, so the tracker stops being a place where a deleted world can be re-listed and
//! becomes the thing that tells the stragglers. It is not an instruction the peer has to trust: it
//! is the owner's signed record, which the peer verifies for itself exactly as this tracker did.
//!
//! # The window
//!
//! 120 days. Long enough to cover any believable absence — a player away for a season, a seedbox
//! off for a summer — and bounded so the cache cannot grow forever. **Honest bound:** a peer that
//! reappears after the window with the world's bytes still on disk will find nobody left to
//! contradict it. The window is a judgement, not a proof.

use nodera_codec::tombstone::{WorldDeletionGossip, WorldTombstone};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// How long a deletion is remembered, in milliseconds — the same window the Java peers keep.
pub const RETENTION_MILLIS: u64 = 120 * 24 * 60 * 60 * 1_000;

/// One remembered deletion.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Entry {
    /// The encoded `WorldDeletionGossip` frame to hand to anyone still announcing the world.
    notice: Vec<u8>,
    /// When the owner issued the deletion, epoch millis.
    issued_at_millis: u64,
}

/// The tracker's deleted-world cache.
#[derive(Debug)]
pub struct DeletedWorlds {
    entries: HashMap<Vec<u8>, Entry>,
    retention_millis: u64,
    /// Where the cache is written, when the operator configured one.
    ///
    /// `None` means memory-only, and the retention window then really means "until this process
    /// restarts". Reported rather than papered over — an operator who wants the window to mean
    /// what it says configures `persist_dir`.
    dir: Option<PathBuf>,
}

impl DeletedWorlds {
    /// Build a cache, restoring anything already on disk.
    pub fn new(dir: Option<PathBuf>) -> Self {
        let mut cache = Self {
            entries: HashMap::new(),
            retention_millis: RETENTION_MILLIS,
            dir,
        };
        cache.restore();
        cache
    }

    /// How many deletions are remembered.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether nothing is remembered.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Whether this world has been deleted at its owner's verified request.
    pub fn is_deleted(&self, world_id: &[u8]) -> bool {
        self.entries.contains_key(world_id)
    }

    /// The frame to send to a peer still announcing a deleted world.
    pub fn notice_for(&self, world_id: &[u8]) -> Option<&[u8]> {
        self.entries.get(world_id).map(|e| e.notice.as_slice())
    }

    /// Record a deletion this tracker has **already verified**.
    ///
    /// # Panics
    /// Never. An unverified tombstone is refused rather than stored, so nothing this cache hands
    /// back can be something the tracker failed to check.
    pub fn remember(&mut self, tombstone: &WorldTombstone) -> bool {
        if !tombstone.verify() {
            return false;
        }
        let notice = WorldDeletionGossip {
            world_id: tombstone.world_id.clone(),
            encoded_tombstone: tombstone.encoded().to_vec(),
        }
        .encode();
        let issued_at_millis = tombstone.issued_at_epoch.max(0) as u64;
        self.persist(&tombstone.world_id_hex(), &notice);
        self.entries.insert(
            tombstone.world_id.clone(),
            Entry {
                notice,
                issued_at_millis,
            },
        );
        true
    }

    /// Drop deletions older than the retention window.
    ///
    /// # Returns
    /// How many were forgotten.
    pub fn prune(&mut self, now_millis: u64) -> usize {
        let floor = now_millis.saturating_sub(self.retention_millis);
        let expired: Vec<Vec<u8>> = self
            .entries
            .iter()
            .filter(|(_, e)| e.issued_at_millis < floor)
            .map(|(id, _)| id.clone())
            .collect();
        for id in &expired {
            self.entries.remove(id);
            self.remove_file(&hex(id));
        }
        expired.len()
    }

    fn restore(&mut self) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        let Ok(listing) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in listing.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("tombstone") {
                continue;
            }
            let Ok(bytes) = std::fs::read(&path) else {
                continue;
            };
            // Re-verified on the way in: a file on our own disk gets no more trust than a frame off
            // the wire, because an edited one would make this tracker refuse a world nobody deleted.
            let Ok(gossip) = WorldDeletionGossip::decode(&bytes) else {
                continue;
            };
            if let Some(tombstone) = gossip.verified() {
                self.entries.insert(
                    tombstone.world_id.clone(),
                    Entry {
                        notice: bytes,
                        issued_at_millis: tombstone.issued_at_epoch.max(0) as u64,
                    },
                );
            }
        }
    }

    fn persist(&self, world_id_hex: &str, notice: &[u8]) {
        let Some(path) = self.file_for(world_id_hex) else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        // Temp-then-rename: a half-written notice would be dropped at restart, silently un-deleting
        // a world.
        let temp = path.with_extension("tombstone.tmp");
        if std::fs::write(&temp, notice).is_ok() {
            let _ = std::fs::rename(&temp, &path);
        }
    }

    fn remove_file(&self, world_id_hex: &str) {
        if let Some(path) = self.file_for(world_id_hex) {
            let _ = std::fs::remove_file(path);
        }
    }

    /// The file for a world, or `None` when the id is not hex.
    ///
    /// The id arrives from the network and becomes a path component, so hex-only is the whole
    /// check: it makes `..`, separators and absolute paths unrepresentable rather than filtered.
    fn file_for(&self, world_id_hex: &str) -> Option<PathBuf> {
        let dir = self.dir.as_ref()?;
        if world_id_hex.is_empty() || !world_id_hex.bytes().all(|b| b.is_ascii_hexdigit()) {
            return None;
        }
        Some(Path::new(dir).join(format!("{world_id_hex}.tombstone")))
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn golden() -> Vec<u8> {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .and_then(Path::parent)
            .expect("repo root")
            .join("fixtures/wire/world-deletion-gossip.bin");
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
    }

    fn tombstone() -> WorldTombstone {
        WorldDeletionGossip::decode(&golden())
            .expect("decode")
            .verified()
            .expect("verify")
    }

    #[test]
    fn a_verified_deletion_is_remembered_and_answers_later_announces() {
        let mut cache = DeletedWorlds::new(None);
        let tombstone = tombstone();

        assert!(cache.remember(&tombstone));

        assert!(cache.is_deleted(&tombstone.world_id));
        // The answer is the proof, not an assertion by this tracker: the peer re-verifies it.
        let notice = cache.notice_for(&tombstone.world_id).expect("a notice");
        assert!(
            WorldDeletionGossip::decode(notice)
                .expect("decode")
                .verified()
                .is_some()
        );
    }

    #[test]
    fn an_unverifiable_deletion_is_not_remembered() {
        let mut cache = DeletedWorlds::new(None);
        let mut forged = tombstone();
        forged.owner_signature = vec![0u8; 64];

        assert!(!cache.remember(&forged));
        assert!(cache.is_empty());
    }

    #[test]
    fn deletions_expire_after_the_window_and_not_before() {
        let mut cache = DeletedWorlds::new(None);
        let tombstone = tombstone();
        cache.remember(&tombstone);
        let issued = tombstone.issued_at_epoch as u64;

        assert_eq!(cache.prune(issued + RETENTION_MILLIS), 0, "still inside");
        assert!(cache.is_deleted(&tombstone.world_id));
        assert_eq!(cache.prune(issued + RETENTION_MILLIS + 1), 1);
        assert!(!cache.is_deleted(&tombstone.world_id));
    }

    #[test]
    fn a_persisted_cache_survives_a_restart() {
        let dir = std::env::temp_dir().join(format!("nodera-deleted-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let tombstone = tombstone();
        {
            let mut cache = DeletedWorlds::new(Some(dir.clone()));
            cache.remember(&tombstone);
        }

        // What a restart is: a second cache over the same directory.
        let restarted = DeletedWorlds::new(Some(dir.clone()));

        assert!(restarted.is_deleted(&tombstone.world_id));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_edited_file_on_disk_is_ignored() {
        let dir = std::env::temp_dir().join(format!("nodera-deleted-bad-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let tombstone = tombstone();
        {
            let mut cache = DeletedWorlds::new(Some(dir.clone()));
            cache.remember(&tombstone);
        }
        let file = dir.join(format!("{}.tombstone", tombstone.world_id_hex()));
        let mut bytes = std::fs::read(&file).expect("read");
        let last = bytes.len() - 1;
        bytes[last] ^= 0x01;
        std::fs::write(&file, &bytes).expect("write");

        assert!(DeletedWorlds::new(Some(dir.clone())).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
