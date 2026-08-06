//! The socket surfaces: a TCP listener (length-prefixed frames, one task per connection) and an
//! optional UDP listener (one datagram per request), plus a periodic sweep.
//!
//! Framing on TCP is `nodera-codec`'s (`u32` length + body, 16 MiB protocol cap) so peers reach the
//! tracker with the same reader/writer they already use for `SocketPeerTransport`. On UDP the
//! datagram *is* the frame — no length prefix, because the datagram boundary already carries it
//! (`docs/tracker/REFERENCE.md`, "Surfaces"). No HTTP: one frozen encoding for the whole network is what
//! makes the cross-language conformance tests meaningful.
//!
//! Both surfaces decode into the same `Tracker::handle_frame`, so a world announced over one is
//! queryable over the other; they differ only in bounds, because a forgeable UDP source address
//! makes an unbounded answer an amplification weapon.

use crate::service::{Handled, Tracker};
pub use nodera_service::frame::now_millis;
use nodera_service::frame::{read_frame, udp_reply_permitted, write_frame};
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::Mutex;

/// Serve one connection until the peer closes it or sends something unserviceable.
///
/// A connection is cheap and stateless: peers may announce, query, or both, and a
/// misbehaving connection is dropped without touching any other.
pub async fn serve_connection(
    tracker: Arc<Mutex<Tracker>>,
    mut stream: TcpStream,
    remote: SocketAddr,
    max_frame_bytes: usize,
) {
    loop {
        let frame = match read_frame(&mut stream, max_frame_bytes).await {
            Ok(Some(frame)) => frame,
            Ok(None) => return,
            Err(e) => {
                eprintln!("nodera-tracker: connection {remote} read error: {e}");
                return;
            }
        };

        let handled = {
            let mut guard = tracker.lock().await;
            guard.handle_frame(
                &frame,
                Some(remote.ip()),
                Some(remote.to_string()),
                now_millis(),
            )
        };

        match handled {
            Handled::Reply(reply) => {
                if let Err(e) = write_frame(&mut stream, &reply).await {
                    eprintln!("nodera-tracker: connection {remote} write error: {e}");
                    return;
                }
            }
            Handled::Unsupported(reason) => {
                // Log and hang up: there is no reply that helps a peer speaking a protocol this
                // service does not serve, and answering would make the tracker a reflector.
                eprintln!("nodera-tracker: dropping {remote}: {reason}");
                return;
            }
        }
    }
}

/// Serve the UDP surface until the task is dropped: one datagram in, at most one datagram out.
///
/// Three bounds separate this from the TCP path, all of them because a UDP source address is
/// forgeable:
///
/// * requests larger than `udp_max_request_bytes` are dropped unread past the buffer;
/// * replies larger than `udp_max_reply_bytes` are **not sent** (a truncated canonical frame is
///   undecodable, so silence is the honest answer — the peer retries over TCP);
/// * replies more than `udp_max_amplification`× the request are not sent, so the service cannot be
///   pointed at a victim as a reflector (`docs/tracker/REFERENCE.md`, "Surfaces").
///
/// Unsupported frames are dropped silently rather than answered, for the same reason: an error
/// reply to a spoofed source is still traffic an attacker chose to aim.
pub async fn serve_udp(tracker: Arc<Mutex<Tracker>>, socket: UdpSocket) {
    let (max_request, max_reply, max_amplification) = {
        let guard = tracker.lock().await;
        let config = guard.config();
        (
            config.udp_max_request_bytes,
            config.udp_max_reply_bytes,
            config.udp_max_amplification,
        )
    };
    // One buffer for the life of the loop; a datagram longer than it is truncated by the OS, and
    // a truncated canonical frame fails to decode — which is the drop we want anyway.
    let mut buffer = vec![0u8; max_request];
    loop {
        let (len, remote) = match socket.recv_from(&mut buffer).await {
            Ok(received) => received,
            Err(e) => {
                eprintln!("nodera-tracker: udp receive error: {e}");
                continue;
            }
        };
        if len > max_request {
            continue;
        }
        let handled = {
            let mut guard = tracker.lock().await;
            guard.handle_frame(
                &buffer[..len],
                Some(remote.ip()),
                Some(remote.to_string()),
                now_millis(),
            )
        };
        let Handled::Reply(reply) = handled else {
            continue;
        };
        if !udp_reply_permitted(len, reply.len(), max_reply, max_amplification) {
            // The answer exists but is too large to hand to an unverified source address.
            continue;
        }
        if let Err(e) = socket.send_to(&reply, remote).await {
            eprintln!("nodera-tracker: udp reply to {remote} failed: {e}");
        }
    }
}

/// Run the listener until `shutdown` resolves, then drain.
///
/// During drain the tracker stops accepting new connections but in-flight ones finish: a peer
/// mid-query gets its answer instead of a reset.
pub async fn run(
    tracker: Arc<Mutex<Tracker>>,
    listener: TcpListener,
    shutdown: impl std::future::Future<Output = ()>,
) -> io::Result<()> {
    let max_frame_bytes = tracker.lock().await.config().max_frame_bytes;
    let sweep_interval = {
        let guard = tracker.lock().await;
        std::time::Duration::from_secs(u64::from(guard.config().announce_interval_seconds).max(1))
    };

    let sweeper = tokio::spawn({
        let tracker = Arc::clone(&tracker);
        async move {
            let mut ticker = tokio::time::interval(sweep_interval);
            loop {
                ticker.tick().await;
                let mut guard = tracker.lock().await;
                let expired = guard.sweep(now_millis());
                let (accepted, rejected, queries, worlds) = guard.stats();
                let deleted = guard.deleted_world_count();
                let (services, draining_rdv, draining_trk) = guard.service_counts();
                let (reports_ok, reports_bad) = guard.report_counts();
                if expired > 0 || accepted > 0 || queries > 0 {
                    println!(
                        "nodera-tracker: worlds={worlds} announces_accepted={accepted} \
                         announces_rejected={rejected} queries={queries} expired_now={expired} \
                         deleted_worlds={deleted} services={services} \
                         draining={draining_rdv}rdv/{draining_trk}trk \
                         reports={reports_ok}/{reports_bad}"
                    );
                }
            }
        }
    });

    nodera_service::serve::accept_loop("nodera-tracker", listener, shutdown, |stream, remote| {
        let tracker = Arc::clone(&tracker);
        tokio::spawn(async move {
            serve_connection(tracker, stream, remote, max_frame_bytes).await;
        });
    })
    .await;
    println!("nodera-tracker: draining on shutdown signal");
    sweeper.abort();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    async fn spawn_test_tracker() -> (SocketAddr, Arc<Mutex<Tracker>>) {
        let config = Config {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            ..Config::default()
        };
        let tracker = Arc::new(Mutex::new(Tracker::new(config)));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let served = Arc::clone(&tracker);
        tokio::spawn(async move {
            let _ = run(served, listener, std::future::pending::<()>()).await;
        });
        (addr, tracker)
    }

    #[tokio::test]
    async fn a_query_over_a_real_socket_is_answered() {
        let (addr, _tracker) = spawn_test_tracker().await;
        let mut stream = TcpStream::connect(addr).await.unwrap();
        let query = DiscoveryMessage::TrackerQuery(TrackerQuery {
            genesis_hash: vec![0x11; 32],
        })
        .encode();
        write_frame(&mut stream, &query).await.unwrap();

        let reply = read_frame(&mut stream, 1 << 20).await.unwrap().unwrap();
        match DiscoveryMessage::decode(&reply).unwrap() {
            DiscoveryMessage::TrackerResponse(r) => assert_eq!(r.genesis_hash, vec![0x11; 32]),
            other => panic!("unexpected reply {other:?}"),
        }
    }

    #[tokio::test]
    async fn one_connection_can_send_several_frames() {
        let (addr, _tracker) = spawn_test_tracker().await;
        let mut stream = TcpStream::connect(addr).await.unwrap();
        for byte in [0x11u8, 0x22, 0x33] {
            let query = DiscoveryMessage::TrackerQuery(TrackerQuery {
                genesis_hash: vec![byte; 32],
            })
            .encode();
            write_frame(&mut stream, &query).await.unwrap();
            let reply = read_frame(&mut stream, 1 << 20).await.unwrap().unwrap();
            match DiscoveryMessage::decode(&reply).unwrap() {
                DiscoveryMessage::TrackerResponse(r) => {
                    assert_eq!(r.genesis_hash, vec![byte; 32]);
                }
                other => panic!("unexpected reply {other:?}"),
            }
        }
    }

    #[tokio::test]
    async fn a_hostile_length_header_is_refused_without_allocating() {
        let (addr, _tracker) = spawn_test_tracker().await;
        let mut stream = TcpStream::connect(addr).await.unwrap();
        // 4 GiB - 1 announced, nothing sent: the service must hang up, not allocate.
        stream.write_all(&[0xFF, 0xFF, 0xFF, 0xFF]).await.unwrap();
        let mut buf = [0u8; 1];
        // The connection is dropped; a read returns EOF rather than an answer.
        assert_eq!(stream.read(&mut buf).await.unwrap(), 0);
    }

    /// Spawn only the UDP surface, returning its bound address.
    async fn spawn_udp_tracker(config: Config) -> SocketAddr {
        let tracker = Arc::new(Mutex::new(Tracker::new(config)));
        let socket = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = socket.local_addr().unwrap();
        tokio::spawn(async move { serve_udp(tracker, socket).await });
        addr
    }

    /// One datagram out, one datagram back — no length prefix, the datagram is the frame.
    #[tokio::test]
    async fn a_query_over_udp_is_answered_in_one_datagram() {
        let addr = spawn_udp_tracker(Config::default()).await;
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let query = DiscoveryMessage::TrackerQuery(TrackerQuery {
            genesis_hash: vec![0x44; 32],
        })
        .encode();
        client.send_to(&query, addr).await.unwrap();

        let mut buf = vec![0u8; 64 * 1024];
        let (len, from) = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            client.recv_from(&mut buf),
        )
        .await
        .expect("udp reply timed out")
        .unwrap();
        assert_eq!(from, addr);
        match DiscoveryMessage::decode(&buf[..len]).unwrap() {
            DiscoveryMessage::TrackerResponse(r) => assert_eq!(r.genesis_hash, vec![0x44; 32]),
            other => panic!("unexpected reply {other:?}"),
        }
    }

    /// The anti-reflection bound: an answer that would amplify beyond the ratio is not sent at
    /// all. Silence is correct — the peer retries on TCP, where the source address is proven.
    #[tokio::test]
    async fn udp_refuses_to_amplify_beyond_the_configured_ratio() {
        let addr = spawn_udp_tracker(Config {
            udp_max_amplification: 1,
            ..Config::default()
        })
        .await;
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let query = DiscoveryMessage::TrackerQuery(TrackerQuery {
            genesis_hash: vec![0x55; 32],
        })
        .encode();
        client.send_to(&query, addr).await.unwrap();

        let mut buf = vec![0u8; 64 * 1024];
        let outcome = tokio::time::timeout(
            std::time::Duration::from_millis(400),
            client.recv_from(&mut buf),
        )
        .await;
        assert!(
            outcome.is_err(),
            "an over-amplifying answer must be dropped, not sent"
        );
    }

    /// A datagram that is not a discovery frame is dropped silently: answering an unverified
    /// source with an error is still traffic an attacker aimed.
    #[tokio::test]
    async fn udp_drops_undecodable_datagrams_without_replying() {
        let addr = spawn_udp_tracker(Config::default()).await;
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client
            .send_to(b"not a canonical frame", addr)
            .await
            .unwrap();

        let mut buf = vec![0u8; 1024];
        let outcome = tokio::time::timeout(
            std::time::Duration::from_millis(400),
            client.recv_from(&mut buf),
        )
        .await;
        assert!(outcome.is_err(), "garbage must not draw a reply");
    }

    /// Announce over UDP, then query over UDP: both surfaces share one registry.
    #[tokio::test]
    async fn udp_and_tcp_share_the_same_registry() {
        let config = Config {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            ..Config::default()
        };
        let tracker = Arc::new(Mutex::new(Tracker::new(config)));

        let udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let udp_addr = udp.local_addr().unwrap();
        let tcp = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let tcp_addr = tcp.local_addr().unwrap();
        tokio::spawn({
            let tracker = Arc::clone(&tracker);
            async move { serve_udp(tracker, udp).await }
        });
        tokio::spawn({
            let tracker = Arc::clone(&tracker);
            async move {
                let _ = run(tracker, tcp, std::future::pending::<()>()).await;
            }
        });

        // Ask over UDP and over TCP for the same world; both must decode and answer.
        let client = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let query = DiscoveryMessage::TrackerQuery(TrackerQuery {
            genesis_hash: vec![0x66; 32],
        })
        .encode();
        client.send_to(&query, udp_addr).await.unwrap();
        let mut buf = vec![0u8; 64 * 1024];
        let (len, _) = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            client.recv_from(&mut buf),
        )
        .await
        .expect("udp reply timed out")
        .unwrap();
        let udp_reply = DiscoveryMessage::decode(&buf[..len]).unwrap();

        let mut stream = TcpStream::connect(tcp_addr).await.unwrap();
        write_frame(&mut stream, &query).await.unwrap();
        let framed = read_frame(&mut stream, 1 << 20).await.unwrap().unwrap();
        let tcp_reply = DiscoveryMessage::decode(&framed).unwrap();

        match (udp_reply, tcp_reply) {
            (
                DiscoveryMessage::TrackerResponse(over_udp),
                DiscoveryMessage::TrackerResponse(over_tcp),
            ) => assert_eq!(over_udp.genesis_hash, over_tcp.genesis_hash),
            other => panic!("unexpected replies {other:?}"),
        }
    }
}
