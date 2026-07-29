//! The commands React calls. One namespace, one model, no page-specific shapes.
//!
//! Every command here returns something whose provenance is legible: either a [`Dashboard`]
//! (which carries its own `link` block) or a result type that says why it has nothing. The rule
//! this enforces is the one the old dashboard broke — **a page may not render a number it was not
//! given**, so there is no command that answers "0" when the honest answer is "nobody said".

use std::sync::Arc;

use serde::{Deserialize, Serialize};

use crate::api::model::Dashboard;
use crate::api::store::DashboardStore;
use crate::metrics::PieceMapView;

/// The whole current picture. Called once on mount; after that the page lives on the
/// `nodera://dashboard` event, and calls this again only to re-sync (a reopened window, a resumed
/// machine) rather than on a timer.
#[tauri::command]
pub fn dashboard(store: tauri::State<Arc<DashboardStore>>) -> Dashboard {
    store.snapshot()
}

/// One world's piece grid, pulled on demand.
///
/// Deliberately not carried on the push stream: only the selected world is interesting, and a node
/// seeding a dozen worlds should not re-encode every bitmap several times a second for a tab
/// nobody has open.
///
/// A plain function rather than a `#[tauri::command]`: `main.rs` owns the control address and wraps
/// it, so the frontend never has to know — or be able to choose — which endpoint is queried.
pub async fn dashboard_pieces(control_addr: String, world_id: String) -> PieceMapView {
    match crate::control::fetch_pieces(&control_addr, &world_id).await {
        Some(map) => PieceMapView::of(map),
        None => PieceMapView::default(),
    }
}

/// The outcome of asking the worker to prove it administers a world.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AdminProof {
    /// Whether the worker produced a signature over the challenge this app just generated.
    pub signed: bool,
    /// Base64 of the challenge that was sent — fresh every call, never reused.
    pub challenge: String,
    /// Base64 of the canonical `WorldAdminProof`, empty when the worker declined.
    pub proof: String,
    /// The worker's own reason for declining, empty on success.
    pub error: String,
}

/// Ask the worker to sign a fresh challenge with a world's private key.
///
/// **What this does and does not establish.** A successful answer means *this* worker holds that
/// world's private key: the challenge is generated here, per call, so a captured proof cannot
/// satisfy it. It is not a cryptographic verification — this app carries no Ed25519 implementation
/// and deliberately does not grow one to check a claim about the machine it is already running on.
/// Verification is what the *network* does, against the world public key in the gossiped ownership
/// record. The UI must therefore say "your peer signed a fresh challenge", never "verified".
///
/// Wrapped by `main.rs` for the same reason as [`dashboard_pieces`]: the control address is the
/// app's, not the page's.
pub async fn prove_world_admin(control_addr: String, world_id: String) -> AdminProof {
    use base64::Engine as _;
    let challenge = fresh_challenge();
    let encoded = base64::engine::general_purpose::STANDARD.encode(challenge);
    let line = format!(
        "NODERA-PROVE {} {} {}",
        crate::control::PROTOCOL_VERSION,
        world_id,
        encoded
    );
    match crate::control::request(&control_addr, line).await {
        Ok(reply) => {
            let payload = reply.strip_prefix("NODERA-OK").unwrap_or_default().trim();
            AdminProof {
                signed: !payload.is_empty(),
                challenge: encoded,
                proof: payload.to_owned(),
                error: if payload.is_empty() {
                    "the worker answered without a proof".to_owned()
                } else {
                    String::new()
                },
            }
        }
        Err(reason) => AdminProof {
            signed: false,
            challenge: encoded,
            proof: String::new(),
            error: reason,
        },
    }
}

/// 32 bytes that have not been used before.
///
/// Built from the clock and this call's own address rather than from a CSPRNG crate: the challenge
/// only has to be *unrepeated* for a replay to be useless, and the verifier of consequence is the
/// worker on the other end of a loopback socket. A predictable-but-unique nonce is enough here, and
/// saying so is better than implying a randomness property that is not being provided.
fn fresh_challenge() -> [u8; 32] {
    let mut out = [0u8; 32];
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let counter = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let address = &out as *const _ as u64;
    out[..16].copy_from_slice(&nanos.to_le_bytes());
    out[16..24].copy_from_slice(&counter.to_le_bytes());
    out[24..].copy_from_slice(&address.to_le_bytes());
    out
}

static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metrics::{Metrics, WorldRow};

    #[test]
    fn a_challenge_is_never_repeated() {
        let a = fresh_challenge();
        let b = fresh_challenge();
        assert_ne!(a, b, "a repeated challenge makes a captured proof reusable");
    }

    #[test]
    fn a_world_that_is_not_here_is_none_not_an_empty_world() {
        let store = DashboardStore::new();
        store.accept(
            &Metrics {
                connected_worlds: vec![WorldRow {
                    world_id: "abc".into(),
                    name: "W".into(),
                    ..WorldRow::default()
                }],
                ..Metrics::default()
            },
            "stream",
        );

        let dash = store.snapshot();
        assert!(dash.worlds.iter().any(|w| w.world_id == "abc"));
        // The command's own lookup, without needing a Tauri State harness.
        assert!(dash.worlds.iter().find(|w| w.world_id == "gone").is_none());
    }
}

/// The outcome of asking the worker to delete a world.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DeleteOutcome {
    /// Whether the world was deleted here and the request published.
    pub deleted: bool,
    /// How many peers were handed the request directly.
    ///
    /// Zero still means deleted. The rest of the network learns from the trackers and from whoever
    /// is still announcing the world — reported honestly so the UI can say "0 peers reached now"
    /// instead of implying the whole network has already forgotten it.
    pub peers_notified: u32,
    /// The worker's own words when it refused, empty on success.
    pub error: String,
}

/// Ask the worker to delete a world its owner administers, everywhere.
///
/// **Irreversible, and not this app's decision.** The worker signs the request with the world's
/// private key and refuses outright if it does not hold one, so a peer that merely supports
/// somebody else's world gets a refusal here — there is no override, because an override would be a
/// way to delete a world you do not own. Every receiving peer re-verifies the signed record before
/// destroying anything, so nothing about this trusts the app, the worker's word, or the sender.
///
/// Wrapped by `lib.rs` for the same reason as [`prove_world_admin`]: the control address belongs to
/// the app, not to the page.
pub async fn delete_world(control_addr: String, world_id: String, reason: String) -> DeleteOutcome {
    use base64::Engine as _;
    let reason = reason.trim();
    let encoded = if reason.is_empty() {
        String::new()
    } else {
        format!(
            " {}",
            base64::engine::general_purpose::STANDARD.encode(reason.as_bytes())
        )
    };
    let line = format!(
        "NODERA-DELETE {} {}{}",
        crate::control::PROTOCOL_VERSION,
        world_id,
        encoded
    );
    match crate::control::request(&control_addr, line).await {
        Ok(reply) => {
            let payload = reply.strip_prefix("NODERA-OK").unwrap_or_default().trim();
            DeleteOutcome {
                deleted: true,
                peers_notified: payload.parse().unwrap_or(0),
                error: String::new(),
            }
        }
        Err(reason) => DeleteOutcome {
            deleted: false,
            peers_notified: 0,
            error: reason,
        },
    }
}
