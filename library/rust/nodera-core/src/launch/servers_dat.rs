//! Putting the tunnel address into Minecraft's Multiplayer list.
//!
//! # What this buys, and what it does not
//!
//! The official launcher has no command-line way to join a server, so the best that can be done for
//! a player who uses it is to have the world already in their Multiplayer list when the game opens:
//! one click instead of copying an address into Direct Connect. That is the whole claim. It is not
//! auto-connect, and the interface must not say it is.
//!
//! # Why the NBT is written by hand
//!
//! `servers.dat` is a single uncompressed NBT compound holding one list of compounds, each with a
//! `name` and an `ip`. Reading and writing that is about a hundred lines; a general NBT library
//! would be a dependency carrying gzip, region files and a tag model this file will never use.
//!
//! # It never destroys a list it could not read
//!
//! A player's saved servers are theirs, and some of them are addresses they cannot get back. So a
//! file that does not parse is left alone and the caller is told — the fallback (show the address)
//! still works, and the alternative is an app that silently replaces a server list with one entry.

use std::path::Path;

const TAG_END: u8 = 0;
const TAG_BYTE: u8 = 1;
const TAG_SHORT: u8 = 2;
const TAG_INT: u8 = 3;
const TAG_LONG: u8 = 4;
const TAG_FLOAT: u8 = 5;
const TAG_DOUBLE: u8 = 6;
const TAG_BYTE_ARRAY: u8 = 7;
const TAG_STRING: u8 = 8;
const TAG_LIST: u8 = 9;
const TAG_COMPOUND: u8 = 10;
const TAG_INT_ARRAY: u8 = 11;
const TAG_LONG_ARRAY: u8 = 12;

/// One saved server, as much of it as this app touches.
///
/// Every other key a server entry can carry — `icon`, `acceptTextures`, `previewsChat` — is
/// preserved verbatim on the entries already in the file. Only the entry this app adds is built from
/// nothing, and it deliberately carries no more than it needs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Server {
    pub name: String,
    pub ip: String,
    /// Everything else in this entry, as raw `(tag, name, payload)` triples, so a rewrite gives a
    /// player back the icon and the chat settings they had.
    other: Vec<(u8, String, Vec<u8>)>,
}

impl Server {
    pub fn new(name: impl Into<String>, ip: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            ip: ip.into(),
            other: Vec::new(),
        }
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], String> {
        if self.at + count > self.bytes.len() {
            return Err("the file ends in the middle of a tag".to_owned());
        }
        let slice = &self.bytes[self.at..self.at + count];
        self.at += count;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, String> {
        let bytes = self.take(2)?;
        Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn i32(&mut self) -> Result<i32, String> {
        let bytes = self.take(4)?;
        Ok(i32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn string(&mut self) -> Result<String, String> {
        let length = self.u16()? as usize;
        let bytes = self.take(length)?;
        String::from_utf8(bytes.to_vec()).map_err(|_| "a name is not valid UTF-8".to_owned())
    }

    /// The payload of one tag, returned verbatim so it can be written back unchanged.
    fn payload(&mut self, tag: u8) -> Result<Vec<u8>, String> {
        let start = self.at;
        match tag {
            TAG_BYTE => { self.take(1)?; }
            TAG_SHORT => { self.take(2)?; }
            TAG_INT | TAG_FLOAT => { self.take(4)?; }
            TAG_LONG | TAG_DOUBLE => { self.take(8)?; }
            TAG_BYTE_ARRAY => {
                let count = self.i32()?.max(0) as usize;
                self.take(count)?;
            }
            TAG_STRING => {
                let count = self.u16()? as usize;
                self.take(count)?;
            }
            TAG_INT_ARRAY => {
                let count = self.i32()?.max(0) as usize;
                self.take(count * 4)?;
            }
            TAG_LONG_ARRAY => {
                let count = self.i32()?.max(0) as usize;
                self.take(count * 8)?;
            }
            TAG_LIST => {
                let inner = self.u8()?;
                let count = self.i32()?.max(0) as usize;
                for _ in 0..count {
                    self.payload(inner)?;
                }
            }
            TAG_COMPOUND => loop {
                let inner = self.u8()?;
                if inner == TAG_END {
                    break;
                }
                self.string()?;
                self.payload(inner)?;
            },
            other => return Err(format!("tag {other} is not one this file should contain")),
        }
        Ok(self.bytes[start..self.at].to_vec())
    }
}

fn put_string(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(bytes);
}

fn put_named_string(out: &mut Vec<u8>, name: &str, value: &str) {
    out.push(TAG_STRING);
    put_string(out, name);
    put_string(out, value);
}

/// Read the saved-server list out of a `servers.dat`.
///
/// An absent file is an empty list — that is what a player who has never added a server has, and it
/// is a perfectly good thing to append to.
pub fn read(path: &Path) -> Result<Vec<Server>, String> {
    let Ok(bytes) = std::fs::read(path) else {
        return Ok(Vec::new());
    };
    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    // Gzip. The vanilla client writes this file uncompressed, but a third-party tool may not, and
    // silently treating a gzip header as NBT would "parse" into nonsense.
    if bytes.len() > 2 && bytes[0] == 0x1f && bytes[1] == 0x8b {
        return Err("that servers.dat is compressed, which this app cannot rewrite".to_owned());
    }

    let mut reader = Reader { bytes: &bytes, at: 0 };
    if reader.u8()? != TAG_COMPOUND {
        return Err("that servers.dat does not start with a compound".to_owned());
    }
    reader.string()?; // the root name, conventionally empty

    let mut servers = Vec::new();
    loop {
        let tag = reader.u8()?;
        if tag == TAG_END {
            break;
        }
        let name = reader.string()?;
        if tag == TAG_LIST && name == "servers" {
            let inner = reader.u8()?;
            let count = reader.i32()?.max(0) as usize;
            if inner != TAG_COMPOUND && count > 0 {
                return Err("the servers list does not hold compounds".to_owned());
            }
            for _ in 0..count {
                let mut server = Server::new("", "");
                loop {
                    let field = reader.u8()?;
                    if field == TAG_END {
                        break;
                    }
                    let key = reader.string()?;
                    let payload = reader.payload(field)?;
                    match (field, key.as_str()) {
                        (TAG_STRING, "name") => {
                            server.name = String::from_utf8_lossy(&payload[2..]).to_string()
                        }
                        (TAG_STRING, "ip") => {
                            server.ip = String::from_utf8_lossy(&payload[2..]).to_string()
                        }
                        // Kept verbatim: the icon and the chat settings are the player's.
                        _ => server.other.push((field, key, payload)),
                    }
                }
                servers.push(server);
            }
        } else {
            reader.payload(tag)?;
        }
    }
    Ok(servers)
}

/// Encode a saved-server list as a `servers.dat` body.
pub fn encode(servers: &[Server]) -> Vec<u8> {
    let mut out = Vec::new();
    out.push(TAG_COMPOUND);
    put_string(&mut out, "");

    out.push(TAG_LIST);
    put_string(&mut out, "servers");
    out.push(TAG_COMPOUND);
    out.extend_from_slice(&(servers.len() as i32).to_be_bytes());
    for server in servers {
        put_named_string(&mut out, "name", &server.name);
        put_named_string(&mut out, "ip", &server.ip);
        for (tag, key, payload) in &server.other {
            out.push(*tag);
            put_string(&mut out, key);
            out.extend_from_slice(payload);
        }
        out.push(TAG_END);
    }

    out.push(TAG_END);
    out
}

/// Put one entry at the top of the list, replacing any earlier entry this app wrote for it.
///
/// Top, because it is what the player pressed Play for ten seconds ago and the Multiplayer list is
/// otherwise ordered by however long ago they added things.
///
/// Replacing rather than appending matters over a session: the tunnel binds a **new loopback port**
/// each time, so re-joining the same world twice would otherwise leave a list of near-identical
/// entries, all but one of them pointing at a port nothing is listening on.
pub fn upsert(path: &Path, entry: Server) -> Result<(), String> {
    let mut servers = read(path)?;
    servers.retain(|held| held.name != entry.name);
    servers.insert(0, entry);

    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("cannot create {}: {e}", parent.display()))?;
    }
    std::fs::write(path, encode(&servers))
        .map_err(|e| format!("cannot write {}: {e}", path.display()))
}

/// The name this app gives its own entries, so it can recognise and replace them.
pub fn entry_name(world: &str) -> String {
    let world = world.trim();
    if world.is_empty() {
        "Nodera".to_owned()
    } else {
        format!("Nodera — {world}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("nodera-servers-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("servers.dat")
    }

    #[test]
    fn a_written_list_reads_back_the_same() {
        let path = temp("roundtrip");
        upsert(&path, Server::new("Nodera — SkyRealm", "127.0.0.1:25601")).unwrap();

        let back = read(&path).unwrap();
        assert_eq!(back.len(), 1);
        assert_eq!(back[0].name, "Nodera — SkyRealm");
        assert_eq!(back[0].ip, "127.0.0.1:25601");
    }

    #[test]
    fn the_players_own_servers_survive_and_keep_their_extra_fields() {
        let path = temp("preserve");
        // A hand-built file with a field this app knows nothing about, which is what a real one has.
        let mut theirs = Server::new("Hypixel", "mc.hypixel.net");
        let mut icon = Vec::new();
        put_string(&mut icon, "PNGDATA");
        theirs.other.push((TAG_STRING, "icon".to_owned(), icon));
        std::fs::write(&path, encode(&[theirs])).unwrap();

        upsert(&path, Server::new("Nodera — SkyRealm", "127.0.0.1:25601")).unwrap();

        let back = read(&path).unwrap();
        assert_eq!(back.len(), 2);
        // Top, because it is what the player pressed Play for ten seconds ago.
        assert_eq!(back[0].name, "Nodera — SkyRealm");
        assert_eq!(back[1].name, "Hypixel");
        assert_eq!(
            back[1].other.len(),
            1,
            "a server's icon is the player's and must survive a rewrite"
        );
    }

    #[test]
    fn re_joining_the_same_world_replaces_its_entry_rather_than_stacking_them() {
        let path = temp("upsert");
        upsert(&path, Server::new("Nodera — SkyRealm", "127.0.0.1:25601")).unwrap();
        // The tunnel binds a new loopback port each time, so an append would leave a list of
        // near-identical entries, all but one pointing at a port nothing is listening on.
        upsert(&path, Server::new("Nodera — SkyRealm", "127.0.0.1:25777")).unwrap();

        let back = read(&path).unwrap();
        assert_eq!(back.len(), 1);
        assert_eq!(back[0].ip, "127.0.0.1:25777");
    }

    #[test]
    fn a_missing_file_is_an_empty_list_rather_than_an_error() {
        let path = temp("absent");
        assert!(read(&path).unwrap().is_empty());
        upsert(&path, Server::new("Nodera", "127.0.0.1:1")).unwrap();
        assert_eq!(read(&path).unwrap().len(), 1);
    }

    #[test]
    fn a_file_this_app_cannot_read_is_left_alone() {
        let path = temp("hostile");
        std::fs::write(&path, b"\x1f\x8b\x08nonsense").unwrap();
        let before = std::fs::read(&path).unwrap();

        // Some of a player's saved servers are addresses they cannot get back. Refusing costs them
        // one click; overwriting costs them the list.
        assert!(read(&path).is_err());
        assert!(upsert(&path, Server::new("Nodera", "127.0.0.1:1")).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), before, "the file must be untouched");
    }

    #[test]
    fn a_truncated_file_is_refused_rather_than_half_read() {
        let path = temp("truncated");
        let whole = encode(&[Server::new("A", "a.example")]);
        std::fs::write(&path, &whole[..whole.len() - 4]).unwrap();
        assert!(read(&path).is_err());
    }

    #[test]
    fn the_entry_name_is_recognisable_and_never_empty() {
        assert_eq!(entry_name("SkyRealm"), "Nodera — SkyRealm");
        // A world with no name still needs a stable key, or `upsert` could not replace its entry.
        assert_eq!(entry_name("   "), "Nodera");
    }
}
