//! The TCP surface: length-prefixed frames, one task per connection, a periodic sweep.
//!
//! Framing is `nodera-codec`'s (`u32` length + body, 16 MiB protocol cap) so peers reach the
//! tracker with the same reader/writer they already use for `SocketPeerTransport`. No HTTP: one
//! frozen encoding for the whole network is what makes the cross-language conformance tests
//! meaningful.

use crate::service::{Handled, Tracker};
use nodera_codec::framing;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

/// Wall-clock milliseconds. Used for announce freshness and the retention countdown only — never
/// for anything a peer's correctness depends on.
pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Read one length-prefixed frame.
///
/// Returns `Ok(None)` at a clean end of stream. The length is validated against the protocol cap
/// *before* the body buffer is allocated.
pub async fn read_frame(
    stream: &mut TcpStream,
    max_frame_bytes: usize,
) -> io::Result<Option<Vec<u8>>> {
    let mut header = [0u8; 4];
    match stream.read_exact(&mut header).await {
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = framing::decode_length(header)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    if len > max_frame_bytes {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("frame of {len} bytes exceeds the configured limit {max_frame_bytes}"),
        ));
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await?;
    Ok(Some(body))
}

/// Write one length-prefixed frame.
pub async fn write_frame(stream: &mut TcpStream, payload: &[u8]) -> io::Result<()> {
    let framed = framing::frame(payload)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    stream.write_all(&framed).await
}

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
                if expired > 0 || accepted > 0 || queries > 0 {
                    println!(
                        "nodera-tracker: worlds={worlds} announces_accepted={accepted} \
                         announces_rejected={rejected} queries={queries} expired_now={expired}"
                    );
                }
            }
        }
    });

    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, remote)) => {
                        let tracker = Arc::clone(&tracker);
                        tokio::spawn(async move {
                            serve_connection(tracker, stream, remote, max_frame_bytes).await;
                        });
                    }
                    Err(e) => eprintln!("nodera-tracker: accept error: {e}"),
                }
            }
            _ = &mut shutdown => {
                println!("nodera-tracker: draining on shutdown signal");
                break;
            }
        }
    }
    sweeper.abort();
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};

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
}
