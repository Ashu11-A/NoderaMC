//! Talking to a tracker: announce this device, then ask who else is there.
//!
//! This is the whole of the mobile peer's networking, and it is the same exchange the Java peer
//! makes — same frames, same signature over the same canonical bytes, same tracker code verifying
//! it. Nothing here is a special case for phones; a tracker cannot tell which kind of peer it is
//! talking to, and that is the point.
//!
//! # The self-test is the acceptance criterion
//!
//! [`self_test`] performs the round trip that proves a device is genuinely on the network: sign and
//! send an announce, get the tracker's ack, then query the tracker back and find **this device's own
//! entry** in the reply. That last step is what makes it a test rather than a ping — a tracker can
//! accept an announce and file it somewhere useless, and the only way to know it did not is to ask
//! for it back and recognise what comes.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use nodera_codec::messages::{AnnounceEvent, DiscoveryMessage, TrackerAnnounce, TrackerQuery};
use nodera_codec::types::{NodeCapabilities, PeerRole};
use serde::{Deserialize, Serialize};

use super::identity::PeerIdentity;
use super::TrackerStatus;

/// How long a tracker exchange may take before it is treated as unreachable.
///
/// A phone's network is frequently a slow one, so this is generous compared with the loopback
/// budgets elsewhere in this app — but bounded, because a UI waiting on a socket with no deadline
/// is a UI that hangs on a train.
const TIMEOUT: Duration = Duration::from_secs(8);

/// The world a device with no world of its own announces itself under.
///
/// A peer must announce *for* a swarm; a phone that hosts nothing still wants to be findable and
/// still helps the peer set. This is a fixed, obviously-synthetic id rather than a random one so
/// every mobile peer lands in the same set and can find the others.
pub const COMMONS_WORLD: [u8; 32] = *b"nodera:mobile-commons:v1\0\0\0\0\0\0\0\0";

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Every address a tracker endpoint resolves to, in the order the resolver returned them.
///
/// **All of them, not the first.** A hostname whose first record is an AAAA on a v4-only network
/// used to be permanently unreachable even though its A record worked, and the user was told the
/// tracker was down. [`connect`] tries each in turn.
///
/// `udp://` is not accepted. It used to be stripped here and then dialled over TCP anyway, so a
/// UDP tracker produced a connection failure that named the host rather than the scheme;
/// `Settings::validate` now refuses it at the point it is typed, and this is the second line of
/// that defence.
fn resolve(endpoint: &str) -> Result<Vec<SocketAddr>, String> {
    // Accept `tcp://host:port`, `host:port`, and a bare host on the default port, because all three
    // are what people actually type into a settings box.
    let trimmed = endpoint.trim();
    if let Some((scheme, _)) = trimmed.split_once("://") {
        if scheme != "tcp" {
            return Err(format!(
                "{endpoint}: this build speaks tcp only, not {scheme}"
            ));
        }
    }
    let bare = trimmed.trim_start_matches("tcp://");
    let with_port = if bare.contains(':') {
        bare.to_owned()
    } else {
        format!("{bare}:25600")
    };
    let addresses: Vec<SocketAddr> = with_port
        .to_socket_addrs()
        .map_err(|e| format!("{endpoint}: {e}"))?
        .collect();
    if addresses.is_empty() {
        return Err(format!("{endpoint}: resolved to nothing"));
    }
    Ok(addresses)
}

/// Connect to the first address that answers.
///
/// The last failure is reported, because with a single address it is *the* failure and with several
/// it is the most recently tried — either way it names something real, which "could not connect" on
/// its own does not.
fn connect(addresses: &[SocketAddr]) -> Result<TcpStream, String> {
    let mut last = "no addresses".to_owned();
    for address in addresses {
        match TcpStream::connect_timeout(address, TIMEOUT) {
            Ok(stream) => return Ok(stream),
            Err(e) => last = format!("{address}: {e}"),
        }
    }
    Err(format!("connect: {last}"))
}

/// One request/reply exchange with a tracker.
fn exchange(
    addresses: &[SocketAddr],
    message: &DiscoveryMessage,
) -> Result<DiscoveryMessage, String> {
    let mut stream = connect(addresses)?;
    stream.set_read_timeout(Some(TIMEOUT)).ok();
    stream.set_write_timeout(Some(TIMEOUT)).ok();

    let frame = nodera_codec::framing::frame(&message.encode())
        .map_err(|e| format!("could not frame the request: {e}"))?;
    stream.write_all(&frame).map_err(|e| format!("send: {e}"))?;
    stream.flush().map_err(|e| format!("send: {e}"))?;

    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .map_err(|e| format!("no reply: {e}"))?;
    let length = nodera_codec::framing::decode_length(header)
        .map_err(|e| format!("bad reply length: {e}"))?;
    let mut body = vec![0u8; length];
    stream
        .read_exact(&mut body)
        .map_err(|e| format!("truncated reply: {e}"))?;
    DiscoveryMessage::decode(&body).map_err(|e| format!("unreadable reply: {e}"))
}

/// Build a signed announce for this device.
fn announce_for(identity: &PeerIdentity, world: &[u8], routes: Vec<String>) -> TrackerAnnounce {
    let mut announce = TrackerAnnounce {
        genesis_hash: world.to_vec(),
        peer: identity.node_id_value(),
        public_key: identity.public_key(),
        event: AnnounceEvent::Heartbeat,
        routes,
        // A phone is a relay-and-bootstrap peer: it helps others find each other. It deliberately
        // does not claim FULL_ARCHIVE or REGION_VALIDATOR, which would offer work it cannot do.
        capabilities: NodeCapabilities {
            logical_cores: 0,
            memory_bytes: 0,
            latency_ms: 0,
            reliability_bits: 0,
            max_primary_regions: 0,
            max_validator_regions: 0,
            accepts_worker: false,
            roles: vec![PeerRole::Bootstrap],
        },
        holdings: Vec::new(),
        world_name: String::new(),
        retention_deadline_epoch_millis: 0,
        reliability_bps: 10_000,
        announce_epoch_millis: now_ms(),
        signature: Vec::new(),
    };
    announce.signature = identity.sign(&announce.signed_portion());
    announce
}

/// Announce this device to one tracker and report how it went.
pub fn announce(identity: &PeerIdentity, endpoint: &str, routes: Vec<String>) -> TrackerStatus {
    let mut status = TrackerStatus {
        endpoint: endpoint.to_owned(),
        ..TrackerStatus::default()
    };
    let addresses = match resolve(endpoint) {
        Ok(addresses) => addresses,
        Err(reason) => {
            status.error = reason;
            return status;
        }
    };
    let started = Instant::now();
    let message = DiscoveryMessage::TrackerAnnounce(announce_for(identity, &COMMONS_WORLD, routes));
    match exchange(&addresses, &message) {
        Ok(DiscoveryMessage::TrackerAnnounceAck(ack)) => {
            status.reachable = true;
            status.accepted = ack.accepted;
            status.next_announce_seconds = ack.next_announce_after_seconds;
            status.latency_ms = Some(started.elapsed().as_millis() as u64);
            // The tracker's own rejection code, verbatim. A peer that was refused needs to know
            // which rule it broke, and inventing a friendlier message would lose that.
            status.error = if ack.accepted {
                String::new()
            } else {
                ack.reason
            };
        }
        Ok(other) => {
            status.reachable = true;
            status.error = format!("the tracker answered with {other:?} instead of an ack");
        }
        Err(reason) => status.error = reason,
    }
    status
}

/// Ask a tracker who it knows for a world.
pub fn query(endpoint: &str, world: &[u8]) -> Result<Vec<String>, String> {
    let addresses = resolve(endpoint)?;
    let message = DiscoveryMessage::TrackerQuery(TrackerQuery {
        genesis_hash: world.to_vec(),
    });
    match exchange(&addresses, &message)? {
        DiscoveryMessage::TrackerResponse(response) => Ok(response
            .peers
            .into_iter()
            .map(|peer| peer.node_id.to_uuid_string())
            .collect()),
        other => Err(format!(
            "the tracker answered with {other:?} instead of peers"
        )),
    }
}

/// The result of the round trip that proves this device is on the network.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct SelfTest {
    /// Whether every step passed.
    pub passed: bool,
    /// Each tracker's outcome for the announce.
    pub trackers: Vec<TrackerStatus>,
    /// Peers the trackers reported back, this device included.
    pub peers: Vec<String>,
    /// Whether this device found **itself** in what the tracker returned.
    pub found_self: bool,
    /// What failed, when something did.
    pub error: String,
}

/// Announce, then ask for it back.
///
/// The second half is the part that matters. An accepted announce only says the tracker took the
/// bytes; finding this device's own node id in the query reply says it filed them where a peer
/// looking for company would find them — which is the whole service a tracker provides.
pub fn self_test(identity: &PeerIdentity, endpoints: &[String], routes: Vec<String>) -> SelfTest {
    let mut result = SelfTest::default();
    if endpoints.is_empty() {
        result.error = "no trackers are configured".to_owned();
        return result;
    }
    for endpoint in endpoints {
        result
            .trackers
            .push(announce(identity, endpoint, routes.clone()));
    }
    let accepted: Vec<&TrackerStatus> = result.trackers.iter().filter(|t| t.accepted).collect();
    if accepted.is_empty() {
        result.error = result
            .trackers
            .iter()
            .map(|t| {
                format!(
                    "{}: {}",
                    t.endpoint,
                    if t.error.is_empty() {
                        "refused"
                    } else {
                        &t.error
                    }
                )
            })
            .collect::<Vec<_>>()
            .join("; ");
        return result;
    }
    // Ask **every** tracker that accepted, not just the first.
    //
    // A tracker can accept an announce and file it where no query will reach — and if that one
    // happens to sort first, querying only it reports "you are not on the network" to a device that
    // is on the network through the other. The test passes if any accepting tracker can hand this
    // device back.
    let endpoints: Vec<String> = accepted.iter().map(|t| t.endpoint.clone()).collect();
    let mut failures = Vec::new();
    for endpoint in &endpoints {
        match query(endpoint, &COMMONS_WORLD) {
            Ok(peers) => {
                if peers.iter().any(|id| id == identity.node_id()) {
                    result.found_self = true;
                }
                for peer in peers {
                    if !result.peers.contains(&peer) {
                        result.peers.push(peer);
                    }
                }
            }
            Err(reason) => failures.push(format!("{endpoint}: {reason}")),
        }
    }
    result.passed = result.found_self;
    if !result.passed {
        result.error = if failures.is_empty() {
            "the tracker accepted the announce but did not return this device".to_owned()
        } else {
            failures.join("; ")
        };
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity() -> PeerIdentity {
        PeerIdentity::from_seed([5u8; 32])
    }

    #[test]
    fn endpoints_are_accepted_in_the_forms_people_type() {
        assert!(resolve("127.0.0.1:25600").is_ok());
        assert!(resolve("tcp://127.0.0.1:25600").is_ok());
        // A bare host means the default tracker port rather than a parse error.
        assert_eq!(resolve("127.0.0.1").unwrap()[0].port(), 25600);
        assert!(resolve("").is_err());
    }

    /// `udp://` was stripped here and then dialled over TCP, so the failure named the host instead
    /// of the scheme and the user had no way to see what was actually wrong.
    #[test]
    fn a_udp_endpoint_says_the_scheme_is_the_problem_instead_of_being_dialled_over_tcp() {
        let error = resolve("udp://127.0.0.1:25600").unwrap_err();
        assert!(error.contains("tcp only"), "{error}");
    }

    /// A hostname with several records must not be written off because the first one is unusable.
    #[test]
    fn every_resolved_address_is_offered_not_just_the_first() {
        // `localhost` is the one name that reliably has more than one record on developer machines
        // (127.0.0.1 and ::1); where it does not, this still asserts the shape.
        let addresses = resolve("localhost:25600").unwrap();
        assert!(!addresses.is_empty());
        assert!(addresses.iter().all(|a| a.port() == 25600));
    }

    #[test]
    fn an_announce_is_signed_over_what_the_tracker_verifies() {
        let identity = identity();
        let announce = announce_for(&identity, &COMMONS_WORLD, vec!["10.0.0.5:25620".into()]);

        // The exact check the tracker performs. If this failed, every announce from a phone would
        // be rejected and the only symptom would be a device that never appears.
        assert!(nodera_codec::sig::verify(
            &announce.public_key,
            &announce.signed_portion(),
            &announce.signature
        )
        .is_ok());
    }

    #[test]
    fn a_phone_claims_only_what_it_can_do() {
        let announce = announce_for(&identity(), &COMMONS_WORLD, vec![]);
        // Claiming FULL_ARCHIVE would offer storage this device does not have, and the tracker's
        // seeder floor would count it as one — making a world look healthier than it is.
        assert_eq!(announce.capabilities.roles, vec![PeerRole::Bootstrap]);
        assert!(announce.holdings.is_empty());
    }

    #[test]
    fn a_self_test_with_no_trackers_says_so_rather_than_passing() {
        let result = self_test(&identity(), &[], vec![]);
        assert!(!result.passed);
        assert!(result.error.contains("no trackers"));
    }

    /// Where `cargo build -p nodera-tracker` leaves the binary.
    fn tracker_binary() -> Option<std::path::PathBuf> {
        let root = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .parent()?
            .to_path_buf();
        ["release", "debug"]
            .iter()
            .map(|profile| root.join("target").join(profile).join("nodera-tracker"))
            .find(|candidate| candidate.is_file())
    }

    /// **The acceptance test for a device with no JVM.**
    ///
    /// Runs the real tracker binary and drives exactly what the phone's "Run self-test" button
    /// drives: sign an announce, send it, read the ack, then query the tracker back and look for
    /// this device's own entry. The second half is what makes it a test rather than a ping — a
    /// tracker can accept an announce and file it where no query will reach, and recognising
    /// yourself in the peer set is the only way to know it did not.
    ///
    /// Skipped loudly when the binary is absent: a test that passes because it did nothing is
    /// worse than one that is not there.
    #[test]
    fn a_device_announces_to_a_real_tracker_and_finds_itself_in_the_reply() {
        let Some(binary) = tracker_binary() else {
            eprintln!("SKIPPED: build it first — (cd rust && cargo build -p nodera-tracker)");
            return;
        };
        let port = 26_500 + (std::process::id() % 400) as u16;
        let endpoint = format!("127.0.0.1:{port}");
        let mut child = match std::process::Command::new(&binary)
            .arg("--bind")
            .arg(format!("127.0.0.1:{port}"))
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
        {
            Ok(child) => child,
            Err(e) => panic!("the tracker binary exists but would not start: {e}"),
        };
        // Wait for the listener rather than sleeping a fixed amount, which is either flaky or slow.
        let deadline = Instant::now() + Duration::from_secs(10);
        let mut up = false;
        while Instant::now() < deadline {
            if std::net::TcpStream::connect(&endpoint).is_ok() {
                up = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        assert!(up, "the tracker did not bind {endpoint}");

        // A fixed seed: this asserts on the identity coming back, and a random one would make a
        // failure impossible to reproduce.
        let identity = PeerIdentity::from_seed([42u8; 32]);
        let result = self_test(
            &identity,
            &[endpoint.clone()],
            vec!["10.0.0.101:25620".to_owned()],
        );
        let _ = child.kill();
        let _ = child.wait();

        assert!(
            result.trackers[0].reachable,
            "the tracker did not answer: {}",
            result.trackers[0].error
        );
        assert!(
            result.trackers[0].accepted,
            "the tracker refused this peer's announce: {}",
            result.trackers[0].error
        );
        // A zero here would mean the tracker never told this device when to come back, and a phone
        // would either hammer it or go silent.
        assert!(result.trackers[0].next_announce_seconds > 0);
        assert!(
            result.found_self,
            "announce accepted, but the query did not return this device — peers: {:?} err: {}",
            result.peers, result.error
        );
        assert!(result.passed, "{}", result.error);
    }

    #[test]
    fn an_unreachable_tracker_is_reported_with_its_address() {
        // Port 1 on loopback: reserved, nothing listens.
        let result = self_test(&identity(), &["127.0.0.1:1".to_owned()], vec![]);
        assert!(!result.passed);
        assert!(result.error.contains("127.0.0.1:1"), "{}", result.error);
        assert!(!result.trackers[0].reachable);
    }
}
