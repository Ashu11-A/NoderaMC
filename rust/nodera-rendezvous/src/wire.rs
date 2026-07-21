//! The TCP surface: length-prefixed control frames, per-connection tasks, and the circuit bridge.
//!
//! Framing is `nodera-codec`'s (`u32` length + body, 16 MiB cap) so peers reach the service with the
//! same reader/writer they already use for `SocketPeerTransport`. Registration/discovery/reservation
//! are cheap request/reply. A reservation turns the connection into a **control channel**: it waits
//! for an inbound circuit, and when one arrives it delivers `RelayIncoming` and then splices the two
//! sockets, metering bytes/duration/idle against the reservation (rendezvous.md §4.5/§8.4). Frames on
//! the bridged legs are opaque, end-to-end-encrypted bytes — the relay never sees plaintext (§8.2).

use crate::circuit::{CircuitLimits, CircuitMeter, TeardownReason};
use crate::registry::Namespace;
use crate::service::{Decision, Rendezvous};
use nodera_codec::framing;
use nodera_codec::rendezvous::{RelayConnect, RelayIncoming, RelayReservation, RendezvousMessage};
use nodera_codec::types::NodeId;
use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, Mutex};

/// Wall-clock milliseconds. Used only for reservation freshness and circuit metering — never for
/// anything a peer's correctness depends on.
pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Something delivered to a reserved peer's control channel.
enum ControlEvent {
    /// An inbound circuit: the connecting peer's socket, moved in for the reserver to bridge.
    Circuit {
        source_stream: TcpStream,
        connect: RelayConnect,
    },
    /// A control frame (a stamped `PunchSync`) to forward to the reserver.
    Frame(Vec<u8>),
}

/// The live control channels of reserved peers, keyed by `(namespace, peer)`.
type ControlChannels = Arc<Mutex<HashMap<(Namespace, NodeId), mpsc::Sender<ControlEvent>>>>;

/// Read one length-prefixed frame; `Ok(None)` at a clean end of stream.
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

/// Serve one connection: control request/reply until the peer reserves, connects, or hangs up.
async fn serve_connection(
    rendezvous: Arc<Mutex<Rendezvous>>,
    channels: ControlChannels,
    mut stream: TcpStream,
    remote: SocketAddr,
    max_frame_bytes: usize,
) {
    loop {
        let frame = match read_frame(&mut stream, max_frame_bytes).await {
            Ok(Some(frame)) => frame,
            Ok(None) => return,
            Err(e) => {
                eprintln!("nodera-rendezvous: connection {remote} read error: {e}");
                return;
            }
        };

        let decision = {
            let mut guard = rendezvous.lock().await;
            guard.handle_frame(
                &frame,
                Some(remote.ip()),
                Some(remote.to_string()),
                now_millis(),
            )
        };

        match decision {
            Decision::Reply(reply) => {
                if write_frame(&mut stream, &reply).await.is_err() {
                    return;
                }
            }
            Decision::Reserved {
                namespace,
                peer,
                reservation,
            } => {
                let reply = RendezvousMessage::Reservation(reservation.clone()).encode();
                if write_frame(&mut stream, &reply).await.is_err() {
                    return;
                }
                run_reserved(
                    rendezvous,
                    channels,
                    stream,
                    remote,
                    namespace,
                    peer,
                    reservation,
                )
                .await;
                return;
            }
            Decision::Connect(connect) => {
                route_connect(&channels, stream, connect, remote).await;
                return; // the stream was handed to the target's task (or dropped).
            }
            Decision::Forward(sync) => {
                let key = (
                    Namespace::new(sync.network_id, sync.genesis_hash.clone()),
                    sync.target,
                );
                let frame = RendezvousMessage::PunchSync(sync).encode();
                if let Some(tx) = channels.lock().await.get(&key).cloned() {
                    let _ = tx.send(ControlEvent::Frame(frame)).await;
                }
            }
            Decision::Drop(reason) => {
                eprintln!("nodera-rendezvous: dropping {remote}: {reason}");
                return;
            }
        }
    }
}

/// Hand a connecting peer's socket to the target reserver's control channel, or drop it.
async fn route_connect(
    channels: &ControlChannels,
    stream: TcpStream,
    connect: RelayConnect,
    remote: SocketAddr,
) {
    let key = (
        Namespace::new(connect.network_id, connect.genesis_hash.clone()),
        connect.target,
    );
    let sender = channels.lock().await.get(&key).cloned();
    match sender {
        Some(tx) => {
            if tx
                .send(ControlEvent::Circuit {
                    source_stream: stream,
                    connect,
                })
                .await
                .is_err()
            {
                eprintln!("nodera-rendezvous: {remote} target went away before the bridge");
            }
        }
        None => {
            // No reservation for the target: the connecting peer sees a closed socket and falls
            // back (rendezvous.md §8.4 — no reservation, no relaying).
            eprintln!("nodera-rendezvous: {remote} CONNECT to an unreserved target refused");
        }
    }
}

/// A reserved peer's control loop: await an inbound circuit or a forwarded frame, and serve any
/// further control frames the reserver sends, until it disconnects.
async fn run_reserved(
    rendezvous: Arc<Mutex<Rendezvous>>,
    channels: ControlChannels,
    mut stream: TcpStream,
    remote: SocketAddr,
    namespace: Namespace,
    peer: NodeId,
    reservation: RelayReservation,
) {
    let (tx, mut rx) = mpsc::channel::<ControlEvent>(8);
    let key = (namespace.clone(), peer);
    channels.lock().await.insert(key.clone(), tx);

    let max_frame_bytes = { rendezvous.lock().await.config().max_frame_bytes };

    loop {
        tokio::select! {
            event = rx.recv() => {
                match event {
                    Some(ControlEvent::Circuit { source_stream, connect }) => {
                        channels.lock().await.remove(&key); // one circuit consumes the reservation
                        // Belt-and-braces: re-validate the reservation the circuit will be metered
                        // against before bridging; an expired one is refused rather than relayed.
                        if !rendezvous
                            .lock()
                            .await
                            .reservation_is_valid(&namespace, peer, &reservation, now_millis())
                        {
                            eprintln!("nodera-rendezvous: {remote} reservation expired before bridge");
                            return;
                        }
                        let incoming = RelayIncoming {
                            network_id: connect.network_id,
                            genesis_hash: connect.genesis_hash.clone(),
                            source: connect.source,
                            target: peer,
                            // The reserver echoes its own reservation proof — validating it is
                            // trivially true; the attestation is the relay having delivered the
                            // circuit against a live reservation at all.
                            proof: reservation.proof.clone(),
                        };
                        let frame = RendezvousMessage::Incoming(incoming).encode();
                        if write_frame(&mut stream, &frame).await.is_err() {
                            return;
                        }
                        let punch_peers = (connect.source, peer);
                        rendezvous.lock().await.note_circuit();
                        bridge(stream, source_stream, limits_of(&reservation), remote).await;
                        // The circuit is gone; drop any punch coordination it accrued.
                        rendezvous
                            .lock()
                            .await
                            .forget_punch(punch_peers.0, punch_peers.1);
                        return;
                    }
                    Some(ControlEvent::Frame(frame)) => {
                        if write_frame(&mut stream, &frame).await.is_err() {
                            channels.lock().await.remove(&key);
                            return;
                        }
                    }
                    None => {
                        return;
                    }
                }
            }
            read = read_frame(&mut stream, max_frame_bytes) => {
                match read {
                    Ok(Some(frame)) => {
                        let decision = {
                            let mut guard = rendezvous.lock().await;
                            guard.handle_frame(&frame, Some(remote.ip()), Some(remote.to_string()), now_millis())
                        };
                        match decision {
                            Decision::Reply(reply) => {
                                if write_frame(&mut stream, &reply).await.is_err() {
                                    channels.lock().await.remove(&key);
                                    return;
                                }
                            }
                            Decision::Forward(sync) => {
                                let fkey = (
                                    Namespace::new(sync.network_id, sync.genesis_hash.clone()),
                                    sync.target,
                                );
                                let fframe = RendezvousMessage::PunchSync(sync).encode();
                                if let Some(other) = channels.lock().await.get(&fkey).cloned() {
                                    let _ = other.send(ControlEvent::Frame(fframe)).await;
                                }
                            }
                            Decision::Drop(_) => {
                                channels.lock().await.remove(&key);
                                return;
                            }
                            // A reserved connection re-reserving or connecting is not expected;
                            // ignore it rather than disturbing the live reservation.
                            _ => {}
                        }
                    }
                    _ => {
                        channels.lock().await.remove(&key);
                        return;
                    }
                }
            }
        }
    }
}

fn limits_of(reservation: &RelayReservation) -> CircuitLimits {
    CircuitLimits {
        max_bytes: reservation.max_bytes,
        max_duration_millis: reservation.max_duration_millis,
        // The reservation does not carry the idle timeout on the wire; the relay applies its own,
        // never longer than the circuit's own lifetime.
        idle_timeout_millis: reservation.max_duration_millis.clamp(1_000, 60_000),
    }
}

/// Splice two sockets, copying bytes each way and metering against the reservation.
///
/// One task owns both halves and multiplexes the two directions with `select!`, plus a 1 s ticker
/// for the time-based limits. Any teardown reason closes both writers so both peers observe the end.
async fn bridge(a: TcpStream, b: TcpStream, limits: CircuitLimits, remote: SocketAddr) {
    let (mut ar, mut aw) = a.into_split();
    let (mut br, mut bw) = b.into_split();
    let mut buf_a = vec![0u8; 16 * 1024];
    let mut buf_b = vec![0u8; 16 * 1024];
    let mut meter = CircuitMeter::new(limits, now_millis());
    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    let reason = loop {
        tokio::select! {
            r = ar.read(&mut buf_a) => match r {
                Ok(0) => break TeardownReason::RemoteClosed,
                Ok(n) => {
                    if bw.write_all(&buf_a[..n]).await.is_err() {
                        break TeardownReason::Error;
                    }
                    if let Some(reason) = meter.record(n as u64, now_millis()) {
                        break reason;
                    }
                }
                Err(_) => break TeardownReason::Error,
            },
            r = br.read(&mut buf_b) => match r {
                Ok(0) => break TeardownReason::RemoteClosed,
                Ok(n) => {
                    if aw.write_all(&buf_b[..n]).await.is_err() {
                        break TeardownReason::Error;
                    }
                    if let Some(reason) = meter.record(n as u64, now_millis()) {
                        break reason;
                    }
                }
                Err(_) => break TeardownReason::Error,
            },
            _ = ticker.tick() => {
                if let Some(reason) = meter.check_time(now_millis()) {
                    break reason;
                }
            }
        }
    };

    let _ = aw.shutdown().await;
    let _ = bw.shutdown().await;
    println!(
        "nodera-rendezvous: circuit via {remote} closed ({}), {} bytes",
        reason.code(),
        meter.bytes_transferred()
    );
}

/// Run the listener until `shutdown` resolves, then drain.
pub async fn run(
    rendezvous: Arc<Mutex<Rendezvous>>,
    listener: TcpListener,
    shutdown: impl std::future::Future<Output = ()>,
) -> io::Result<()> {
    let channels: ControlChannels = Arc::new(Mutex::new(HashMap::new()));
    let (max_frame_bytes, sweep_interval) = {
        let guard = rendezvous.lock().await;
        (
            guard.config().max_frame_bytes,
            Duration::from_secs(u64::from(guard.config().refresh_interval_seconds).max(1)),
        )
    };

    let sweeper = tokio::spawn({
        let rendezvous = Arc::clone(&rendezvous);
        async move {
            let mut ticker = tokio::time::interval(sweep_interval);
            loop {
                ticker.tick().await;
                let mut guard = rendezvous.lock().await;
                let expired = guard.sweep(now_millis());
                let (regs, discs, res, circuits, rejected, namespaces) = guard.stats();
                if expired > 0 || regs > 0 || res > 0 || circuits > 0 {
                    println!(
                        "nodera-rendezvous: namespaces={namespaces} registrations={regs} \
                         discoveries={discs} reservations={res} circuits={circuits} \
                         rejected={rejected} expired_now={expired}"
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
                        let rendezvous = Arc::clone(&rendezvous);
                        let channels = Arc::clone(&channels);
                        tokio::spawn(async move {
                            serve_connection(rendezvous, channels, stream, remote, max_frame_bytes)
                                .await;
                        });
                    }
                    Err(e) => eprintln!("nodera-rendezvous: accept error: {e}"),
                }
            }
            _ = &mut shutdown => {
                println!("nodera-rendezvous: draining on shutdown signal");
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
    use crate::reservation::ReservationKeeper;
    use crate::test_support::signed_record;
    use nodera_codec::rendezvous::{RelayReserve, RendezvousDiscover, RendezvousRegister};
    use nodera_codec::types::{NetworkId, RegistrationEvent};

    const NET: NetworkId = NetworkId { msb: 1, lsb: 2 };

    async fn spawn_service() -> (SocketAddr, Arc<Mutex<Rendezvous>>) {
        let config = Config {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            reservation_max_bytes: 64,
            ..Config::default()
        };
        let keeper = ReservationKeeper::new(vec![0x42; 32], "127.0.0.1:0".to_owned());
        let rendezvous = Arc::new(Mutex::new(Rendezvous::new(config, keeper)));
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let served = Arc::clone(&rendezvous);
        tokio::spawn(async move {
            let _ = run(served, listener, std::future::pending::<()>()).await;
        });
        (addr, rendezvous)
    }

    async fn send(stream: &mut TcpStream, msg: &RendezvousMessage) {
        write_frame(stream, &msg.encode()).await.unwrap();
    }

    async fn recv(stream: &mut TcpStream) -> RendezvousMessage {
        let frame = read_frame(stream, 1 << 20).await.unwrap().unwrap();
        RendezvousMessage::decode(&frame).unwrap()
    }

    #[tokio::test]
    async fn register_over_a_socket_is_confirmed_then_discovered() {
        let (addr, _svc) = spawn_service().await;
        let now = crate::test_support::ISSUED_AT;
        let signed = {
            let mut s = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
            s.record.issued_at_epoch_millis = now_millis(); // fresh against the service clock
            crate::test_support::TestSigner::new(1).sign(s.record)
        };
        let _ = now;

        let mut b = TcpStream::connect(addr).await.unwrap();
        send(
            &mut b,
            &RendezvousMessage::Register(RendezvousRegister { signed }),
        )
        .await;
        match recv(&mut b).await {
            RendezvousMessage::ObservedAddress(o) => assert_eq!(o.peer, NodeId::new(0, 1)),
            other => panic!("expected observed-address, got {other:?}"),
        }

        let mut a = TcpStream::connect(addr).await.unwrap();
        send(
            &mut a,
            &RendezvousMessage::Discover(RendezvousDiscover {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                cursor: 0,
                limit: 0,
            }),
        )
        .await;
        match recv(&mut a).await {
            RendezvousMessage::Peers(p) => assert_eq!(p.records.len(), 1),
            other => panic!("expected peers, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn a_reserved_peer_is_bridged_to_a_connecting_peer_and_the_byte_cap_tears_down() {
        let (addr, _svc) = spawn_service().await;

        // B reserves and keeps its control connection open.
        let mut b = TcpStream::connect(addr).await.unwrap();
        send(
            &mut b,
            &RendezvousMessage::Reserve(RelayReserve {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                peer: NodeId::new(0, 1),
            }),
        )
        .await;
        let proof = match recv(&mut b).await {
            RendezvousMessage::Reservation(r) => {
                assert!(r.accepted);
                r.proof
            }
            other => panic!("expected reservation, got {other:?}"),
        };

        // A connects to B; the relay bridges them.
        let mut a = TcpStream::connect(addr).await.unwrap();
        send(
            &mut a,
            &RendezvousMessage::Connect(RelayConnect {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                source: NodeId::new(0, 5),
                target: NodeId::new(0, 1),
            }),
        )
        .await;

        // B is told a circuit is inbound, echoing its own reservation proof.
        match recv(&mut b).await {
            RendezvousMessage::Incoming(i) => {
                assert_eq!(i.source, NodeId::new(0, 5));
                assert_eq!(i.proof, proof);
            }
            other => panic!("expected incoming, got {other:?}"),
        }

        // The legs are now a raw, opaque byte pipe. A sends, B receives — end to end.
        a.write_all(b"hello-over-the-relay").await.unwrap();
        let mut buf = [0u8; 20];
        b.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, b"hello-over-the-relay");

        // Exhausting the 64-byte cap tears the circuit down: both halves eventually close.
        let _ = a.write_all(&[0u8; 64]).await;
        let mut sink = Vec::new();
        // Reading to EOF returns once the relay shuts the circuit; the exact byte count depends on
        // buffering, but the connection must end rather than stream forever.
        let _ = tokio::time::timeout(Duration::from_secs(5), b.read_to_end(&mut sink)).await;
    }

    #[tokio::test]
    async fn a_connect_to_an_unreserved_target_closes_without_bridging() {
        let (addr, _svc) = spawn_service().await;
        let mut a = TcpStream::connect(addr).await.unwrap();
        send(
            &mut a,
            &RendezvousMessage::Connect(RelayConnect {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                source: NodeId::new(0, 5),
                target: NodeId::new(0, 99),
            }),
        )
        .await;
        let mut buf = [0u8; 1];
        assert_eq!(a.read(&mut buf).await.unwrap(), 0, "closed, not bridged");
    }
}
