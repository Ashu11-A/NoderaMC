//! The Microsoft account, and why this build does not have one.
//!
//! # The constraint, stated plainly
//!
//! Starting Minecraft directly — assembling the command line and spawning the JVM — needs an
//! account token, and getting one needs an Azure application id that **Microsoft has approved for
//! the Minecraft launcher API**. That approval is an out-of-band application per project. No such
//! credential exists in this repository, and none can be produced from the source tree.
//!
//! So [`Tier::Direct`](super::Tier::Direct) is built, tested, and **gated**. Everything up to the
//! network call is here and exercised; the credential is read from `NODERA_MSA_CLIENT_ID`, and when
//! it is absent the launcher says so *before* it starts anything, rather than failing at the third
//! redirect of a flow the player has already begun.
//!
//! # Why the gate is a sentence rather than a hidden button
//!
//! A Play button that silently picks a worse route is a Play button whose behaviour the player
//! cannot predict. The ladder is visible: the interface says which tier will run and why, and "this
//! build carries no Microsoft client id" is a true and complete explanation of why the direct route
//! is not the one being taken.
//!
//! # The offline account
//!
//! [`Account::offline`] exists for development and for the end-to-end suite: it fills the
//! `${auth_*}` placeholders with a synthetic identity so the command-line assembly can be exercised
//! without a login. It is **not** a way to play — a LAN-opened vanilla world authenticates joiners
//! against Mojang's session servers, and an offline client is refused by the ordinary Nodera target.
//! It is labelled as such wherever it is offered.

use serde::{Deserialize, Serialize};

/// The environment variable an operator sets to enable the direct route.
pub const CLIENT_ID_VAR: &str = "NODERA_MSA_CLIENT_ID";

/// The identity the game is launched with.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Account {
    pub name: String,
    pub uuid: String,
    pub access_token: String,
    /// `msa` for a real account, `legacy` for the synthetic one — the value the game puts in
    /// `${user_type}`, and the honest signal to anything downstream about which this is.
    pub user_type: String,
    /// Whether this identity will be accepted by an online-mode server.
    pub online: bool,
}

impl Account {
    /// A synthetic identity, for development and the end-to-end suite.
    ///
    /// The UUID is derived from the name so a given developer keeps the same one between runs —
    /// a player whose identity changes every launch appears as a different person to every world
    /// they have ever joined.
    pub fn offline(name: &str) -> Self {
        let name = if name.trim().is_empty() {
            "Player"
        } else {
            name.trim()
        };
        Self {
            name: name.to_owned(),
            uuid: offline_uuid(name),
            access_token: "0".to_owned(),
            user_type: "legacy".to_owned(),
            online: false,
        }
    }
}

/// The offline UUID, in the shape the game expects.
///
/// A stable hash of the name rather than a random value, and version-3-shaped so nothing downstream
/// rejects it for being malformed. This is deliberately **not** the `OfflinePlayer:<name>` MD5 the
/// vanilla server computes — matching that would make an offline identity indistinguishable from a
/// real player's on a server that accepts them, and this identity should never be mistaken for one.
fn offline_uuid(name: &str) -> String {
    let mut hash: u128 = 0xcbf2_9ce4_8422_2325;
    for byte in name.as_bytes() {
        hash ^= *byte as u128;
        hash = hash.wrapping_mul(0x1000_0000_01b3);
    }
    let bytes = hash.to_be_bytes();
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "{}-{}-3{}-8{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[13..16],
        &hex[17..20],
        &hex[20..32]
    )
}

/// What the interface says when the direct route cannot be taken.
///
/// A constant rather than a computed sentence, because it is not an error the player can act on —
/// it is an explanation of which route Play is taking, and it has to name the cause *and* what
/// happens instead.
pub const DIRECT_UNAVAILABLE: &str =
    "this build carries no Microsoft client id, so it cannot sign \
     in to start the game itself — Nodera will use your own launcher instead";

/// Why the direct route is unavailable, or [`None`] when it is available.
///
/// Checked **before** anything is started. A flow that begins and then discovers it has no
/// credential has already cost the player a device-code screen they cannot complete.
pub fn direct_unavailable() -> Option<&'static str> {
    match std::env::var(CLIENT_ID_VAR) {
        Ok(id) if !id.trim().is_empty() => None,
        _ => Some(DIRECT_UNAVAILABLE),
    }
}

/// The client id, when there is one.
pub fn client_id() -> Option<String> {
    std::env::var(CLIENT_ID_VAR)
        .ok()
        .map(|id| id.trim().to_owned())
        .filter(|id| !id.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_sentence_names_the_cause_and_what_happens_instead() {
        // Not an error the player can act on — an explanation of which route Play is taking.
        assert!(DIRECT_UNAVAILABLE.contains("Microsoft client id"));
        assert!(DIRECT_UNAVAILABLE.contains("your own launcher"));
    }

    #[test]
    fn the_credential_is_read_from_the_environment_and_trimmed() {
        // One test, not three: `set_var` is process-wide, and separate tests asserting on it would
        // race each other exactly as `daemon::ownership`'s comment warns.
        std::env::remove_var(CLIENT_ID_VAR);
        assert_eq!(client_id(), None);
        assert_eq!(direct_unavailable(), Some(DIRECT_UNAVAILABLE));

        std::env::set_var(CLIENT_ID_VAR, "  00000000-aaaa-bbbb-cccc-000000000000  ");
        assert_eq!(
            client_id().as_deref(),
            Some("00000000-aaaa-bbbb-cccc-000000000000"),
            "a copied-in id with a stray space still works"
        );
        assert_eq!(direct_unavailable(), None);

        // Whitespace is not a credential.
        std::env::set_var(CLIENT_ID_VAR, "   ");
        assert_eq!(client_id(), None);
        assert!(direct_unavailable().is_some());

        std::env::remove_var(CLIENT_ID_VAR);
    }

    #[test]
    fn an_offline_identity_is_stable_and_never_claims_to_be_online() {
        let first = Account::offline("Ashu");
        let again = Account::offline("Ashu");
        // A player whose UUID changes every launch appears as a different person to every world they
        // have ever joined — their builds, their inventory and their permissions all follow the id.
        assert_eq!(first.uuid, again.uuid);
        assert_ne!(first.uuid, Account::offline("Someone").uuid);

        assert!(
            !first.online,
            "an offline identity must never claim otherwise"
        );
        assert_eq!(first.user_type, "legacy");
        assert_eq!(first.uuid.len(), 36, "{}", first.uuid);
        assert_eq!(first.uuid.matches('-').count(), 4);

        // An empty name still produces a usable identity rather than an empty `${auth_player_name}`,
        // which the game renders as a nameless player.
        assert_eq!(Account::offline("   ").name, "Player");
    }
}
