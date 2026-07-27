//! One announce round — the unit of work both the peer loop and the status command perform.
//!
//! Shared rather than duplicated because the two used to differ: the loop announced *and* queried,
//! while the command only announced, so "peers seen" read zero or one depending on which of them
//! had written the screen last. A number whose meaning depends on its writer is not a number worth
//! showing.

use crate::peer::identity::PeerIdentity;
use crate::peer::{tracker, PeerStatus};

/// Announce to every tracker, then ask each of them back.
///
/// The second half is what distinguishes "a tracker took the bytes" from "other peers can find
/// this device" — the first is a receipt, the second is the service.
pub fn announce_round(identity: &PeerIdentity, trackers: &[String]) -> PeerStatus {
    use base64::Engine as _;

    let mut status = PeerStatus {
        node_id: identity.node_id().to_owned(),
        public_key: base64::engine::general_purpose::STANDARD.encode(identity.public_key()),
        ..PeerStatus::default()
    };
    for endpoint in trackers {
        status
            .trackers
            .push(tracker::announce(identity, endpoint, Vec::new()));
    }
    status.announced = status.trackers.iter().any(|t| t.accepted);
    if status.announced {
        status.last_announce_ms = now_ms();
        // Only the trackers that accepted. Querying the ones that just failed costs a second full
        // connect timeout each — with two black-holed endpoints this loop spent ~32 s blocking, and
        // it spent it to ask a question we already know the answer to.
        for tracker_status in status.trackers.iter().filter(|t| t.accepted) {
            if let Ok(peers) = tracker::query(&tracker_status.endpoint, &tracker::COMMONS_WORLD) {
                status.known_peers = status.known_peers.max(peers.len() as u64);
            }
        }
    }
    status
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
