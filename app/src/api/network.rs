//! The verbs that make an unmodified Minecraft playable on this network.
//!
//! Four things, all of them round trips to the worker rather than pushed state:
//!
//! * **LAN decisions** — the answer to "you opened a world; shall I put it on the network?".
//! * **The directory** — what other people are offering right now.
//! * **Join / leave** — opening a local port tunnelled to somebody else's world, and closing it.
//! * **Share links** — the invitation you paste into a chat window.
//!
//! None of these belong on the dashboard stream. A decision is a command; a directory is a query
//! against services that may be slow or down, and pushing it would mean every node re-fetching a
//! catalogue several times a second for a screen nobody has open.
//!
//! # The one thing to be careful about
//!
//! Joining returns a **loopback address** for the player to type into Minecraft. It is not a world
//! download, and nothing here transfers a save: the tunnel carries the game's own connection. Say
//! that in the UI, because "join" in every other client in this genre means "fetch the content".

use serde::{Deserialize, Serialize};

use crate::control::{request, PROTOCOL_VERSION};

/// One world the trackers say is joinable.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct DirectoryEntry {
    /// The id to hand back to [`join_session`].
    #[serde(default)]
    pub session_id: String,
    #[serde(default)]
    pub name: String,
    /// Live announcing **peers** for this world, as the tracker counts them.
    ///
    /// Named for what it is. It used to be `players`, and it was rendered under a "Players" column
    /// — but a tracker only ever sees peers announcing a swarm: three always-on seeders of a world
    /// nobody plays produced "3 players", and two players on one machine produced one. Who is
    /// actually in a world is known only to a node with a game there, and reaches this app through
    /// the world's own row, not through the directory.
    #[serde(default, alias = "players")]
    pub peers: u64,
    /// Content pieces held network-wide. Zero for a LAN session, which has no content at all.
    #[serde(default)]
    pub pieces: u64,
    #[serde(default)]
    pub health: String,
    /// Whether this node is the one hosting it — shown, not hidden: two machines is a real case.
    #[serde(default)]
    pub mine: bool,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct Directory {
    #[serde(default)]
    worlds: Vec<DirectoryEntry>,
}

/// The outcome of a command that either worked or has a reason it did not.
///
/// A `Result<(), String>` would be tidier in Rust and worse in the UI: every one of these is a
/// button a person pressed, and "it did not work" without the worker's own words sends them to a
/// log file. The reason is part of the answer.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Outcome {
    pub ok: bool,
    pub error: String,
    /// Free-form payload: the loopback address for a join, the URI for a share link.
    pub value: String,
}

impl Outcome {
    fn ok(value: impl Into<String>) -> Self {
        Self {
            ok: true,
            error: String::new(),
            value: value.into(),
        }
    }

    fn failed(error: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: error.into(),
            value: String::new(),
        }
    }
}

/// Answer the "shall I share this LAN world?" question.
///
/// `action` is `SHARE`, `DECLINE` or `STOP`. Three verbs rather than a boolean because declining is
/// not the same as never being asked: a declined world stays listed so it can be shared later, which
/// is the only way "not now" can mean anything.
pub async fn lan_action(control_addr: &str, action: &str, port: u16) -> Outcome {
    let verb = action.to_ascii_uppercase();
    match request(
        control_addr,
        format!("NODERA-LAN {PROTOCOL_VERSION} {verb} {port}"),
    )
    .await
    {
        Ok(_) => Outcome::ok(""),
        Err(reason) => Outcome::failed(reason),
    }
}

/// What is joinable right now, as the trackers report it.
///
/// An unreachable tracker yields an empty list rather than an error: the browser's honest state is
/// "nothing found", and the reason a tracker is unreachable is already on the dashboard, where
/// endpoint health belongs.
pub async fn directory(control_addr: &str, limit: u32) -> Vec<DirectoryEntry> {
    let line = match request(
        control_addr,
        format!("NODERA-DIRECTORY {PROTOCOL_VERSION} {limit}"),
    )
    .await
    {
        Ok(line) => line,
        Err(_) => return Vec::new(),
    };
    serde_json::from_str::<Directory>(&line)
        .map(|d| d.worlds)
        .unwrap_or_default()
}

/// Join a session: returns the `127.0.0.1:<port>` to type into Minecraft's Direct Connect.
pub async fn join_session(control_addr: &str, session_id: &str) -> Outcome {
    match request(
        control_addr,
        format!("NODERA-CONNECT {PROTOCOL_VERSION} {session_id}"),
    )
    .await
    {
        Ok(reply) => {
            let address = reply.strip_prefix("NODERA-OK").unwrap_or_default().trim();
            if address.is_empty() {
                Outcome::failed("the worker opened no port")
            } else {
                Outcome::ok(address)
            }
        }
        Err(reason) => Outcome::failed(reason),
    }
}

/// Close a local door opened by [`join_session`].
pub async fn leave_session(control_addr: &str, session_id: &str) -> Outcome {
    match request(
        control_addr,
        format!("NODERA-DISCONNECT {PROTOCOL_VERSION} {session_id}"),
    )
    .await
    {
        Ok(_) => Outcome::ok(""),
        Err(reason) => Outcome::failed(reason),
    }
}

/// Mint the invitation for a world: a `nodera:?xt=…` URI carrying trackers, rendezvous and the
/// world's public key — and no content, which is what makes it safe to paste.
pub async fn share_link(control_addr: &str, world_id: &str) -> Outcome {
    match request(
        control_addr,
        format!("NODERA-SHARELINK {PROTOCOL_VERSION} {world_id}"),
    )
    .await
    {
        Ok(reply) => {
            let uri = reply.strip_prefix("NODERA-OK").unwrap_or_default().trim();
            if uri.is_empty() {
                Outcome::failed("the worker returned no link")
            } else {
                Outcome::ok(uri)
            }
        }
        Err(reason) => Outcome::failed(reason),
    }
}

/// Save an invitation as a `.nodera` file.
///
/// The file holds the URI text. A binary form exists on the Java side for signing later, but a file
/// somebody can open and read is worth more here: an invitation is a thing people forward, and one
/// they can inspect is one they can trust.
pub fn write_share_file(path: &std::path::Path, uri: &str) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("could not create {}: {e}", parent.display()))?;
    }
    std::fs::write(path, format!("{uri}\n"))
        .map_err(|e| format!("could not write {}: {e}", path.display()))
}

/// The world id inside an invitation, without needing the worker to parse it.
///
/// Deliberately duplicated from the Java parser rather than round-tripped through the worker: this
/// runs when a user drops a file on the window, and a link the app cannot even read should be
/// refused there and then rather than after a socket round trip.
pub fn world_id_of(uri: &str) -> Option<String> {
    let query = uri.split_once('?')?.1;
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=')?;
        if key.eq_ignore_ascii_case("xt") {
            let decoded = percent_decode(value);
            let id = decoded.strip_prefix("urn:nodera:")?;
            let hex = id.trim();
            if !hex.is_empty() && hex.chars().all(|c| c.is_ascii_hexdigit()) {
                return Some(hex.to_ascii_lowercase());
            }
            return None;
        }
    }
    None
}

/// Minimal percent-decoding — enough for the one field above, and no dependency for it.
fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'%' if index + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[index + 1..index + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(byte) => {
                        out.push(byte);
                        index += 3;
                    }
                    Err(_) => {
                        out.push(b'%');
                        index += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                index += 1;
            }
            byte => {
                out.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_world_id_is_read_out_of_a_pasted_link() {
        let uri = "nodera:?xt=urn%3Anodera%3Aaabbccdd&dn=My+World&tr=tcp%3A%2F%2Fx%3A1";
        assert_eq!(world_id_of(uri), Some("aabbccdd".to_owned()));
    }

    #[test]
    fn the_magnet_spelling_reads_the_same() {
        let uri = "magnet:?xt=urn%3Anodera%3AAABBCC";
        assert_eq!(world_id_of(uri), Some("aabbcc".to_owned()));
    }

    #[test]
    fn anything_that_is_not_an_invitation_is_refused_before_a_round_trip() {
        // A link the app cannot read should fail on the drop, not after a socket exchange.
        assert_eq!(world_id_of("https://example.com"), None);
        assert_eq!(world_id_of("nodera:?dn=no+id"), None);
        assert_eq!(world_id_of("magnet:?xt=urn%3Abtih%3Aaabb"), None);
        assert_eq!(world_id_of("nodera:?xt=urn%3Anodera%3Anothex"), None);
    }

    /// The written file must still be readable by whoever receives it — one invitation URI, on its
    /// own line, and nothing else. The reader half used to live here too; it was reachable only
    /// from a command with no caller and was deleted (A-UX-5), so what the writer emits is now
    /// asserted directly rather than through its own reader.
    #[test]
    fn a_saved_invitation_is_the_uri_on_its_own_line() {
        let dir = std::env::temp_dir().join(format!("nodera-share-{}", std::process::id()));
        let file = dir.join("world.nodera");
        let uri = "nodera:?xt=urn%3Anodera%3Aaabb&dn=Test";

        write_share_file(&file, uri).expect("write");
        let text = std::fs::read_to_string(&file).expect("read back");
        assert_eq!(text, format!("{uri}\n"));
        assert_eq!(world_id_of(text.trim()), Some("aabb".to_owned()));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_outcome_carries_the_workers_own_words() {
        let failed = Outcome::failed("nobody reachable is hosting that world right now");
        assert!(!failed.ok);
        // The reason is the answer. A bare false sends people to a log file.
        assert!(failed.error.contains("nobody reachable"));
        assert!(Outcome::ok("127.0.0.1:41234").ok);
    }
}
