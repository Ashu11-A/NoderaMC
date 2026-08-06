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

use nodera_codec::tombstone::{WorldDeletionGossip, WorldRevival, WorldTombstone};
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

    /// Whether nothing is remembered. Paired with [`Self::len`]; the guard path itself asks
    /// [`Self::notice_for`], so this and [`Self::is_deleted`] exist for assertions.
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Whether this world has been deleted at its owner's verified request.
    #[cfg(test)]
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

    /// Forget a deletion because its owner has signed a later restore.
    ///
    /// The tracker holds deletions longer than anybody — 120 days, so that peers which were offline
    /// still hear about them. That is exactly why it has to accept the undo: without it the owner
    /// re-sharing their own world would be refused by every directory on the network for four
    /// months, with the world's bytes sitting on the peers the whole time.
    ///
    /// The record is verified here rather than by the caller's say-so, and the restore has to be
    /// **newer** than the deletion it undoes — a revival captured before the delete cannot be
    /// replayed to reverse it.
    ///
    /// # Returns
    /// Whether a remembered deletion was actually dropped.
    pub fn forget(&mut self, revival: &WorldRevival) -> bool {
        if !revival.verify() {
            return false;
        }
        let Some(entry) = self.entries.get(&revival.world_id) else {
            return false;
        };
        if (revival.issued_at_epoch.max(0) as u64) <= entry.issued_at_millis {
            return false;
        }
        self.entries.remove(&revival.world_id);
        self.remove_file(&revival.world_id_hex());
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

    /// Write the tombstone to disk, printing the report when it cannot be written.
    fn persist(&self, world_id_hex: &str, notice: &[u8]) {
        if let Err(report) = self.try_persist(world_id_hex, notice) {
            eprintln!("nodera-tracker: {report}");
        }
    }

    /// Write the tombstone to disk, returning what the operator needs to read when it fails.
    ///
    /// Every failure here is loud, and that is the whole point of the function. The tombstone is
    /// the only durable record that an owner deleted their world: if it stays in memory, the next
    /// restart **lists the deleted world again**, and the operator's only clue used to be that it
    /// came back. A full disk, a read-only mount, and a cross-device `rename` (`EXDEV`, which
    /// happens when the persist directory is a bind mount and the temp file lands beside it) all
    /// produced exactly that silence.
    ///
    /// The report is **returned** rather than only printed so that the three failure branches can
    /// be driven from a test. `eprintln!` writes to the process's stderr, which a unit test cannot
    /// read, and a guarded branch nothing asserts on is one revert away from being `let _ =` again
    /// — which is exactly the state this function was found in. [`Self::persist`] is the one caller
    /// and does the printing.
    ///
    /// # Returns
    /// `Ok(())` when the notice is on disk, or when this cache is memory-only and never promised to
    /// put it there. `Err` carries the operator-facing sentence, naming the path, the cause and the
    /// consequence.
    fn try_persist(&self, world_id_hex: &str, notice: &[u8]) -> Result<(), String> {
        if self.dir.is_none() {
            // Memory-only is a configuration and says so on `dir`; it is not a failed write.
            return Ok(());
        }
        let Some(path) = self.file_for(world_id_hex) else {
            return Err(format!(
                "deletion for {world_id_hex} not persisted: the world id is not hex, so it has no \
                 file name — it will be forgotten at restart"
            ));
        };
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                format!(
                    "cannot create {} for the deletion of {world_id_hex}: {e} — the deletion will \
                     be forgotten at restart and the world listed again",
                    parent.display()
                )
            })?;
        }
        // Temp-then-rename: a half-written notice would be dropped at restart, silently un-deleting
        // a world.
        let temp = path.with_extension("tombstone.tmp");
        std::fs::write(&temp, notice).map_err(|e| {
            format!(
                "cannot write {} for the deletion of {world_id_hex}: {e} — the deletion will be \
                 forgotten at restart and the world listed again",
                temp.display()
            )
        })?;
        std::fs::rename(&temp, &path).map_err(|e| {
            // The temp file goes, or every failed write leaves a `.tombstone.tmp` behind for the
            // operator to wonder about.
            let _ = std::fs::remove_file(&temp);
            format!(
                "cannot move {} into place for the deletion of {world_id_hex}: {e} — the deletion \
                 will be forgotten at restart and the world listed again",
                temp.display()
            )
        })
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
        let path = nodera_codec::repo::wire_fixtures().join("world-deletion-gossip.bin");
        let frame =
            std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        // A consensus kind: unwrap the opaque payload the tolerant plane carries it in.
        nodera_codec::wire::consensus_payload(&frame)
            .expect("the golden frame carries an opaque consensus payload")
            .to_vec()
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
        assert!(WorldDeletionGossip::decode(notice)
            .expect("decode")
            .verified()
            .is_some());
    }

    fn revival() -> WorldRevival {
        let path = nodera_codec::repo::wire_fixtures().join("world-revival-gossip.bin");
        let frame =
            std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        let payload = nodera_codec::wire::consensus_payload(&frame)
            .expect("the golden frame carries an opaque consensus payload");
        nodera_codec::tombstone::WorldRevivalGossip::decode(payload)
            .expect("decode")
            .verified()
            .expect("verify")
    }

    /// The whole point of the record: the owner can undo their own deletion, and this tracker stops
    /// answering the world's announces with a tombstone. Without it a re-shared world is refused by
    /// every directory for 120 days while its bytes sit on the peers.
    #[test]
    fn the_owners_later_restore_clears_the_deletion() {
        let mut cache = DeletedWorlds::new(None);
        let tombstone = tombstone();
        cache.remember(&tombstone);

        assert!(cache.forget(&revival()));

        assert!(!cache.is_deleted(&tombstone.world_id));
        assert!(cache.notice_for(&tombstone.world_id).is_none());
    }

    #[test]
    fn a_forged_or_stale_restore_leaves_the_deletion_standing() {
        let mut cache = DeletedWorlds::new(None);
        let tombstone = tombstone();
        cache.remember(&tombstone);

        let mut forged = revival();
        forged.owner_signature = vec![0u8; 64];
        assert!(!cache.forget(&forged), "an unsigned restore is not an undo");

        // A replay of a restore issued before the deletion: verifies perfectly, and must still lose.
        let mut stale = revival();
        stale.issued_at_epoch = tombstone.issued_at_epoch - 1;
        assert!(!cache.forget(&stale));

        assert!(cache.is_deleted(&tombstone.world_id));
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
