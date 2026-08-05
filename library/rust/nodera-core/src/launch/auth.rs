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
//! network call is here and exercised. A client id alone is not an account credential, so this build
//! keeps the route unavailable until an authenticated [`Account`] can actually be supplied.
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
    "this build has no signed-in Microsoft account, so it cannot start the game itself — \
     Nodera will use your own launcher instead";

/// Why the direct route is unavailable, or [`None`] when it is available.
///
/// Checked **before** anything is started. A flow that begins and then discovers it has no
/// credential has already cost the player a device-code screen they cannot complete.
pub fn direct_unavailable() -> Option<&'static str> {
    account().is_none().then_some(DIRECT_UNAVAILABLE)
}

/// Authenticated account available to a direct launch.
///
/// No token acquisition or persisted account store exists yet. Returning `None` is intentional:
/// an Azure client id permits starting an OAuth flow, but cannot authenticate a game session.
pub fn account() -> Option<Account> {
    None
}

/// Whether an account has everything an online-mode launch needs.
pub fn is_usable(account: &Account) -> bool {
    account.online
        && !account.name.trim().is_empty()
        && !account.uuid.trim().is_empty()
        && !account.access_token.trim().is_empty()
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
        assert!(DIRECT_UNAVAILABLE.contains("signed-in Microsoft account"));
        assert!(DIRECT_UNAVAILABLE.contains("your own launcher"));
    }

    #[test]
    fn a_client_id_alone_never_claims_direct_authentication_is_usable() {
        std::env::remove_var(CLIENT_ID_VAR);
        assert_eq!(client_id(), None);
        assert_eq!(direct_unavailable(), Some(DIRECT_UNAVAILABLE));

        std::env::set_var(CLIENT_ID_VAR, "  00000000-aaaa-bbbb-cccc-000000000000  ");
        assert_eq!(
            client_id().as_deref(),
            Some("00000000-aaaa-bbbb-cccc-000000000000"),
            "configuration still reads a copied-in id with a stray space"
        );
        assert_eq!(direct_unavailable(), Some(DIRECT_UNAVAILABLE));
        assert_eq!(account(), None);

        // Whitespace is not a credential.
        std::env::set_var(CLIENT_ID_VAR, "   ");
        assert_eq!(client_id(), None);
        assert!(direct_unavailable().is_some());

        std::env::remove_var(CLIENT_ID_VAR);
    }

    #[test]
    fn only_a_complete_online_account_is_usable() {
        let mut account = Account {
            name: "Player".to_owned(),
            uuid: "00000000-0000-0000-0000-000000000001".to_owned(),
            access_token: "token".to_owned(),
            user_type: "msa".to_owned(),
            online: true,
        };
        assert!(is_usable(&account));
        account.online = false;
        assert!(!is_usable(&account));
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
        // Shape only, and the failure message carries the shape rather than the value. An account
        // identifier printed in the clear is an account identifier in a log file, and a test's
        // assertion message is a log line like any other — CodeQL's `rust/cleartext-logging` is
        // right about that even when the account is synthetic, because the same habit applied to
        // `Account::access_token` would leak a real credential.
        let shape: String = first
            .uuid
            .chars()
            .map(|c| if c == '-' { '-' } else { 'x' })
            .collect();
        assert_eq!(shape, "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx", "{shape}");

        // An empty name still produces a usable identity rather than an empty `${auth_player_name}`,
        // which the game renders as a nameless player.
        assert_eq!(Account::offline("   ").name, "Player");
    }
}
