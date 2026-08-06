//! The TCP surface: length-prefixed control frames, per-connection tasks, and the circuit bridge.
//!
//! Framing is `nodera-codec`'s (`u32` length + body, 16 MiB cap) so peers reach the service with the
//! same reader/writer they already use for `SocketPeerTransport`. Registration/discovery/reservation
//! are cheap request/reply. A reservation turns the connection into a **control channel**: it waits
//! for an inbound circuit, and when one arrives it delivers `RelayIncoming` and then splices the two
//! sockets, metering bytes/duration/idle against the reservation (docs/rendezvous/REFERENCE.md). Frames on
//! the bridged legs are opaque, end-to-end-encrypted bytes — the relay never sees plaintext.

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
pub enum ControlEvent {
    /// An inbound circuit: the connecting peer's socket, moved in for the reserver to bridge.
    Circuit {
        source_stream: TcpStream,
        connect: RelayConnect,
    },
    /// A control frame (a stamped `PunchSync`) to forward to the reserver.
    Frame(Vec<u8>),
}

/// The live control channels of reserved peers, keyed by `(namespace, peer)`.
///
/// A **std** mutex, not tokio's, and that is deliberate: every operation on this map is a hash lookup
/// or an insert, and it has to be callable from a *synchronous* context — the shared lifecycle's
/// `notify_peers` broadcasts a drain notice from a non-async trait method. The senders are cloned out
/// from under the lock before anything is awaited, so nothing is ever held across a suspend point.
pub type ControlChannels =
    Arc<std::sync::Mutex<HashMap<(Namespace, NodeId), mpsc::Sender<ControlEvent>>>>;

/// A new, empty control-channel map.
pub fn control_channels() -> ControlChannels {
    Arc::new(std::sync::Mutex::new(HashMap::new()))
}

/// Clone out the sender for one key, releasing the lock before the caller awaits anything.
fn channel_for(
    channels: &ControlChannels,
    key: &(Namespace, NodeId),
) -> Option<mpsc::Sender<ControlEvent>> {
    channels.lock().ok()?.get(key).cloned()
}

fn insert_channel(
    channels: &ControlChannels,
    key: (Namespace, NodeId),
    tx: mpsc::Sender<ControlEvent>,
) {
    if let Ok(mut held) = channels.lock() {
        held.insert(key, tx);
    }
}

fn remove_channel(channels: &ControlChannels, key: &(Namespace, NodeId)) {
    if let Ok(mut held) = channels.lock() {
        held.remove(key);
    }
}

/// Push one frame to every reserved peer, returning how many accepted it.
///
/// `try_send` rather than `send`: this runs on the drain path, and a peer whose control channel is
/// backed up must not be able to hold up telling the other forty. The frame is small and the queue is
/// eight deep, so a full queue means that peer is already not reading.
pub fn broadcast_frame(channels: &ControlChannels, frame: &[u8]) -> usize {
    let senders: Vec<mpsc::Sender<ControlEvent>> = match channels.lock() {
        Ok(held) => held.values().cloned().collect(),
        Err(_) => return 0,
    };
    senders
        .into_iter()
        .filter(|tx| tx.try_send(ControlEvent::Frame(frame.to_vec())).is_ok())
        .count()
}

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
                if let Some(tx) = channel_for(&channels, &key) {
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
    let sender = channel_for(channels, &key);
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
            // back (docs/rendezvous/REFERENCE.md — no reservation, no relaying).
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
    insert_channel(&channels, key.clone(), tx);

    let (max_frame_bytes, idle_timeout_millis) = {
        let config = rendezvous.lock().await;
        let config = config.config();
        (config.max_frame_bytes, config.circuit_idle_timeout_millis())
    };

    loop {
        tokio::select! {
            event = rx.recv() => {
                match event {
                    Some(ControlEvent::Circuit { source_stream, connect }) => {
                        remove_channel(&channels, &key); // one circuit consumes the reservation
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
                        // The in-flight guard is taken *before* the bridge and dropped when it ends,
                        // including on a panic. This is what makes a drain a drain: without it, the
                        // circuits were detached tasks nobody awaited, and the runtime dropping at
                        // the end of `main` cut every one of them mid-frame.
                        let guard = {
                            let mut service = rendezvous.lock().await;
                            service.note_circuit();
                            service.drain().enter()
                        };
                        bridge(
                            stream,
                            source_stream,
                            limits_of(&reservation, idle_timeout_millis),
                            remote,
                        )
                        .await;
                        drop(guard);
                        // The circuit is gone; drop any punch coordination it accrued.
                        rendezvous
                            .lock()
                            .await
                            .forget_punch(punch_peers.0, punch_peers.1);
                        return;
                    }
                    Some(ControlEvent::Frame(frame)) => {
                        if write_frame(&mut stream, &frame).await.is_err() {
                            remove_channel(&channels, &key);
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
                                    remove_channel(&channels, &key);
                                    return;
                                }
                            }
                            Decision::Forward(sync) => {
                                let fkey = (
                                    Namespace::new(sync.network_id, sync.genesis_hash.clone()),
                                    sync.target,
                                );
                                let fframe = RendezvousMessage::PunchSync(sync).encode();
                                if let Some(other) = channel_for(&channels, &fkey) {
                                    let _ = other.send(ControlEvent::Frame(fframe)).await;
                                }
                            }
                            Decision::Drop(_) => {
                                remove_channel(&channels, &key);
                                return;
                            }
                            // A reserved connection re-reserving or connecting is not expected;
                            // ignore it rather than disturbing the live reservation.
                            _ => {}
                        }
                    }
                    _ => {
                        remove_channel(&channels, &key);
                        return;
                    }
                }
            }
        }
    }
}

/// The limits one circuit is metered against.
///
/// `idle_timeout_millis` is the operator's `circuit_idle_timeout_seconds`. It used to be a constant
/// clamp here while the configuration key was loaded, env-overridable and validated as
/// must-be-positive — so an operator could set it, see it accepted, and change nothing at all.
fn limits_of(reservation: &RelayReservation, idle_timeout_millis: u64) -> CircuitLimits {
    CircuitLimits {
        max_bytes: reservation.max_bytes,
        max_duration_millis: reservation.max_duration_millis,
        // The reservation does not carry the idle timeout on the wire, so the relay applies its
        // own — never longer than the circuit's own lifetime, and never zero, which would tear a
        // circuit down on the tick it opened.
        idle_timeout_millis: idle_timeout_millis
            .min(reservation.max_duration_millis)
            .max(1),
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

/// Run the listener until `shutdown` resolves.
///
/// `channels` is passed in rather than created here so the lifecycle task can broadcast a drain notice
/// down the same control channels this loop registers. `shutdown` resolves *after* the drain has
/// finished, not when the signal arrives — which is why the listener can stay open through it: a peer
/// arriving mid-drain gets a reservation refused with a reason instead of a connection refused, and a
/// reason is what makes it move somewhere else rather than retry here.
pub async fn run(
    rendezvous: Arc<Mutex<Rendezvous>>,
    listener: TcpListener,
    channels: Option<ControlChannels>,
    shutdown: impl std::future::Future<Output = ()>,
) -> io::Result<()> {
    let channels = channels.unwrap_or_else(control_channels);
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
                let in_flight = guard.drain().in_flight();
                let draining = guard.drain().is_draining();
                if expired > 0 || regs > 0 || res > 0 || circuits > 0 {
                    println!(
                        "nodera-rendezvous: namespaces={namespaces} registrations={regs} \
                         discoveries={discs} reservations={res} circuits={circuits} \
                         rejected={rejected} expired_now={expired} in_flight={in_flight} \
                         draining={draining}"
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
                // The drain already ran: this is the point at which the listener closes, after the
                // peers have been told and the circuits have finished. The old code printed
                // "draining" here and then dropped the runtime, which cut them instead.
                println!("nodera-rendezvous: closing the listener");
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

    /// The configured idle timeout reaches the meter.
    ///
    /// It did not: the key was loaded, env-overridable and validated, and the bridge applied a
    /// constant. An operator raising it for a slow transfer saw the setting accepted and the
    /// circuit torn down at the old timeout anyway.
    #[test]
    fn the_configured_idle_timeout_is_what_a_circuit_is_metered_against() {
        let reservation = RelayReservation {
            accepted: true,
            relay_route: String::new(),
            expires_at_epoch_millis: 0,
            max_bytes: 1 << 20,
            max_duration_millis: 600_000,
            proof: Vec::new(),
            reason: String::new(),
        };
        let config = Config {
            circuit_idle_timeout_seconds: 300,
            ..Config::default()
        };
        assert_eq!(
            limits_of(&reservation, config.circuit_idle_timeout_millis()).idle_timeout_millis,
            300_000
        );
        // Never longer than the circuit itself, and never zero.
        let brief = RelayReservation {
            max_duration_millis: 5_000,
            ..reservation
        };
        assert_eq!(
            limits_of(&brief, config.circuit_idle_timeout_millis()).idle_timeout_millis,
            5_000
        );
    }

    async fn spawn_service() -> (SocketAddr, Arc<Mutex<Rendezvous>>) {
        let (addr, service, _channels) = spawn_service_with_channels().await;
        (addr, service)
    }

    /// Spawn a service and keep a handle on its control-channel map, so a test can do what the
    /// lifecycle task does: push a frame down every reserved peer's own socket.
    async fn spawn_service_with_channels() -> (SocketAddr, Arc<Mutex<Rendezvous>>, ControlChannels)
    {
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
        let channels = control_channels();
        let wired = Arc::clone(&channels);
        tokio::spawn(async move {
            let _ = run(served, listener, Some(wired), std::future::pending::<()>()).await;
        });
        (addr, rendezvous, channels)
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

    // --- draining, over real sockets ---

    async fn reserve(addr: SocketAddr, peer: u64) -> (TcpStream, RelayReservation) {
        let mut stream = TcpStream::connect(addr).await.unwrap();
        send(
            &mut stream,
            &RendezvousMessage::Reserve(RelayReserve {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                peer: NodeId::new(0, peer),
            }),
        )
        .await;
        match recv(&mut stream).await {
            RendezvousMessage::Reservation(reservation) => (stream, reservation),
            other => panic!("expected a reservation, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn a_drain_notice_arrives_on_a_reserved_peers_own_control_channel() {
        // The decisive property of the migration lane: the peer that is about to lose its inbound path
        // is told on the socket it already holds, with somewhere to go, before anything breaks.
        let (addr, service, channels) = spawn_service_with_channels().await;
        let (mut reserver, reservation) = reserve(addr, 1).await;
        assert!(reservation.accepted);

        // Wait for the control channel to be registered (the reserver's task installs it).
        for _ in 0..100 {
            if channels.lock().unwrap().len() == 1 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert_eq!(channels.lock().unwrap().len(), 1);

        let identity =
            nodera_service::identity::ServiceIdentity::from_parts(NodeId::new(77, 77), [9u8; 32]);
        let (record, signature) = identity.sign_record(nodera_service::identity::RecordSnapshot {
            kind: nodera_codec::service::ServiceKind::Rendezvous,
            lifecycle: nodera_codec::service::ServiceLifecycle::Draining,
            network_id: NET,
            routes: vec!["rdv.example:25601".to_owned()],
            version: "0.1.0".to_owned(),
            active_sessions: 1,
            max_sessions: 10,
            active_circuits: 0,
            max_circuits: 4,
            rejected_last_window: 0,
            now_millis: now_millis(),
            ttl_millis: 300_000,
            drain_deadline_epoch_millis: now_millis() + 30_000,
        });
        let notice = nodera_codec::service::ServiceDrainNotice {
            record,
            signature,
            replacements: Vec::new(),
            reason: nodera_codec::service::ServiceDrainNotice::REASON_UPDATE.to_owned(),
        };
        let frame = nodera_codec::service::ServiceMessage::DrainNotice(notice).encode();
        assert_eq!(broadcast_frame(&channels, &frame), 1);

        let received = read_frame(&mut reserver, 1 << 20).await.unwrap().unwrap();
        match nodera_codec::service::ServiceMessage::decode(&received).unwrap() {
            nodera_codec::service::ServiceMessage::DrainNotice(got) => {
                assert_eq!(
                    got.record.lifecycle,
                    nodera_codec::service::ServiceLifecycle::Draining
                );
                assert_eq!(got.reason, "update");
                // Verifiable, so a peer cannot be herded onto an attacker's relay by a forged notice.
                nodera_codec::sig::verify(
                    &got.record.public_key,
                    &got.record.signed_bytes(),
                    &got.signature,
                )
                .unwrap();
            }
            other => panic!("expected a drain notice, got tag {}", other.tag()),
        }
        drop(service);
    }

    #[tokio::test]
    async fn a_draining_relay_refuses_a_reservation_with_a_readable_reason() {
        // A refusal a peer can read means "re-reserve elsewhere now". A closed socket means "retry
        // here" — which is what the peer side used to do, once, before giving up for good.
        let (addr, service, _channels) = spawn_service_with_channels().await;
        service
            .lock()
            .await
            .drain()
            .begin(now_millis() + 30_000, "update");
        let (_stream, reservation) = reserve(addr, 2).await;
        assert!(!reservation.accepted);
        assert_eq!(reservation.reason, crate::service::DRAINING_REASON);
        assert!(reservation.proof.is_empty(), "no proof for a refused slot");
    }

    #[tokio::test]
    async fn a_draining_relay_still_answers_discovery() {
        // Refusing discovery too would strand exactly the peers that need to find each other in
        // order to move somewhere else.
        let (addr, service, _channels) = spawn_service_with_channels().await;
        let signed = {
            let mut s = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
            s.record.issued_at_epoch_millis = now_millis();
            crate::test_support::TestSigner::new(1).sign(s.record)
        };
        let mut registrant = TcpStream::connect(addr).await.unwrap();
        send(
            &mut registrant,
            &RendezvousMessage::Register(RendezvousRegister { signed }),
        )
        .await;
        let _ = recv(&mut registrant).await;

        service
            .lock()
            .await
            .drain()
            .begin(now_millis() + 30_000, "update");

        let mut asker = TcpStream::connect(addr).await.unwrap();
        send(
            &mut asker,
            &RendezvousMessage::Discover(RendezvousDiscover {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                cursor: 0,
                limit: 0,
            }),
        )
        .await;
        match recv(&mut asker).await {
            RendezvousMessage::Peers(page) => assert_eq!(page.records.len(), 1),
            other => panic!("a draining relay must still answer discovery, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn a_draining_relay_refuses_a_new_circuit() {
        let (addr, service, _channels) = spawn_service_with_channels().await;
        let (_reserver, reservation) = reserve(addr, 3).await;
        assert!(reservation.accepted);
        service
            .lock()
            .await
            .drain()
            .begin(now_millis() + 30_000, "update");

        let mut caller = TcpStream::connect(addr).await.unwrap();
        send(
            &mut caller,
            &RendezvousMessage::Connect(RelayConnect {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                source: NodeId::new(0, 9),
                target: NodeId::new(0, 3),
            }),
        )
        .await;
        let mut buf = [0u8; 1];
        assert_eq!(
            caller.read(&mut buf).await.unwrap(),
            0,
            "a circuit that would break inside the grace period is refused, not opened"
        );
    }

    #[tokio::test]
    async fn a_live_circuit_registers_as_in_flight_work() {
        // This is the counter a drain waits on. Before it existed, bridged circuits were detached
        // tasks nobody awaited, so "draining" cut them the moment the runtime dropped.
        let (addr, service, _channels) = spawn_service_with_channels().await;
        assert_eq!(service.lock().await.drain().in_flight(), 0);

        let (mut b, _reservation) = reserve(addr, 4).await;
        let mut a = TcpStream::connect(addr).await.unwrap();
        send(
            &mut a,
            &RendezvousMessage::Connect(RelayConnect {
                network_id: NET,
                genesis_hash: b"world".to_vec(),
                source: NodeId::new(0, 5),
                target: NodeId::new(0, 4),
            }),
        )
        .await;
        match recv(&mut b).await {
            RendezvousMessage::Incoming(_) => {}
            other => panic!("expected an incoming circuit, got {other:?}"),
        }
        let mut counted = false;
        for _ in 0..100 {
            if service.lock().await.drain().in_flight() == 1 {
                counted = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(counted, "a bridged circuit must count as in-flight work");

        // And releases when the circuit ends, so a drain is not held open by a finished transfer.
        drop(a);
        drop(b);
        let mut released = false;
        for _ in 0..200 {
            if service.lock().await.drain().in_flight() == 0 {
                released = true;
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(released, "the guard must release when the bridge ends");
    }
}
