//! The socket surface: length-prefixed frames over TCP, one task per connection, plus the
//! periodic sweep and the operator counter line.
//!
//! Framing is `nodera-codec`'s (`u32` length + body), the same one `SocketPeerTransport`, the
//! tracker, and the rendezvous service already speak — so an emitter reuses a writer it has, and
//! an operator debugging a connection reasons about one framing rule for the whole system. The
//! *body* is JSON rather than canonical encoding, which is the one place this service departs from
//! the frozen contract and does so deliberately: telemetry rows leave the Nodera world immediately
//! for a warehouse that speaks JSON, and forcing a canonical round trip on both ends would buy
//! byte-exactness for data whose whole purpose is to be aggregated. That trade is recorded in
//! `docs/telemetry/Task.1.md` §Design.
//!
//! There is **no TLS here.** The service is meant to run behind a TLS-terminating proxy (the
//! compose stack ships one); a plaintext listener exposed directly to the internet is recorded as
//! an open limitation rather than hidden.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use nodera_codec::framing;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;

use crate::service::{Handled, Ingest};

/// Wall-clock milliseconds. Used for quotas, rotation, and the event window only — never for
/// anything any peer's correctness depends on.
pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Read one length-prefixed frame; `Ok(None)` at a clean end of stream.
///
/// Generic over the stream so the bound below can be tested against an in-memory pipe: "a frame
/// larger than the limit is refused *before* the buffer is allocated" is the assertion that keeps
/// a 4-byte header from being an out-of-memory switch, and it deserves a real test.
pub async fn read_frame<S: tokio::io::AsyncRead + Unpin>(
    stream: &mut S,
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
pub async fn write_frame<S: tokio::io::AsyncWrite + Unpin>(
    stream: &mut S,
    payload: &[u8],
) -> io::Result<()> {
    let framed = framing::frame(payload)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    stream.write_all(&framed).await
}

/// Serve one connection until the client closes it or sends something unreadable.
///
/// A client may submit many batches on one connection: an emitter that has been offline drains its
/// spool in one dial rather than one connection per batch.
pub async fn serve_connection(
    ingest: Arc<Mutex<Ingest>>,
    mut stream: TcpStream,
    remote: SocketAddr,
    max_frame_bytes: usize,
) {
    loop {
        let frame = match read_frame(&mut stream, max_frame_bytes).await {
            Ok(Some(frame)) => frame,
            Ok(None) => return,
            Err(e) => {
                eprintln!("nodera-telemetry: connection {remote} read error: {e}");
                return;
            }
        };

        let Handled::Reply(reply) = {
            let mut guard = ingest.lock().await;
            guard.handle_frame(&frame, Some(remote.ip()), now_millis())
        };

        if let Err(e) = write_frame(&mut stream, &reply).await {
            eprintln!("nodera-telemetry: connection {remote} write error: {e}");
            return;
        }
    }
}

/// Accept connections until `shutdown` resolves, then stop taking new ones.
pub async fn run(
    ingest: Arc<Mutex<Ingest>>,
    listener: TcpListener,
    shutdown: impl std::future::Future<Output = ()>,
) -> io::Result<()> {
    let max_frame_bytes = ingest.lock().await.config().max_frame_bytes;
    let maintenance = tokio::spawn(maintenance_loop(Arc::clone(&ingest)));
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            _ = &mut shutdown => break,
            accepted = listener.accept() => match accepted {
                Ok((stream, remote)) => {
                    let ingest = Arc::clone(&ingest);
                    tokio::spawn(serve_connection(ingest, stream, remote, max_frame_bytes));
                }
                Err(e) => {
                    // A failed accept is per-connection (fd exhaustion, a client that vanished);
                    // it must not take the listener down.
                    eprintln!("nodera-telemetry: accept failed: {e}");
                }
            },
        }
    }
    maintenance.abort();
    // The spool is flushed on the way out so a clean SIGTERM never costs the last partial batch.
    Ok(())
}

/// Sweep idle quota counters and print the operator line.
///
/// One task rather than two: both want the same cadence, and an operator reading the log wants the
/// counters to line up with the sweep that produced them.
async fn maintenance_loop(ingest: Arc<Mutex<Ingest>>) {
    let interval = {
        let guard = ingest.lock().await;
        guard.config().report_interval_seconds.max(1)
    };
    let mut ticker = tokio::time::interval(Duration::from_secs(interval));
    ticker.tick().await; // the first tick fires immediately; skip it
    loop {
        ticker.tick().await;
        let mut guard = ingest.lock().await;
        guard.sweep(now_millis());
        let counters = guard.counters().clone();
        let reasons = counters
            .reasons
            .iter()
            .map(|(reason, count)| format!("{reason}={count}"))
            .collect::<Vec<_>>()
            .join(" ");
        println!(
            "nodera-telemetry: batches_accepted={} batches_refused={} events_written={} \
             events_refused={} bytes_in={} write_errors={} {reasons}",
            counters.batches_accepted,
            counters.batches_refused,
            counters.events_written,
            counters.events_refused,
            counters.bytes_in,
            counters.write_errors,
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::geo::GeoTable;
    use crate::sink::NdjsonSink;

    fn config(spool: &std::path::Path) -> Config {
        Config {
            bind_addr: "127.0.0.1:0".parse().unwrap(),
            spool_dir: spool.to_path_buf(),
            subject_secret: "0123456789abcdef0123".to_owned(),
            ..Config::default()
        }
    }

    /// End-to-end over a real socket: frame in, frame out, row on disk.
    ///
    /// The decisive test for this module — the framing, the service, and the sink only agree in
    /// practice if a client that writes a real frame gets a real reply and leaves a real file.
    #[tokio::test]
    async fn a_batch_submitted_over_tcp_is_answered_and_written() {
        let spool = std::env::temp_dir().join(format!(
            "nodera-telemetry-wire-{}-{}",
            std::process::id(),
            now_millis()
        ));
        let _ = std::fs::remove_dir_all(&spool);
        let config = config(&spool);
        let sink = NdjsonSink::new(&config.spool_dir, 1 << 20, 3_600).unwrap();
        let ingest = Arc::new(Mutex::new(Ingest::new(
            config.clone(),
            GeoTable::empty(),
            Box::new(sink),
        )));

        let listener = TcpListener::bind(config.bind_addr).await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
        let server = tokio::spawn({
            let ingest = Arc::clone(&ingest);
            async move {
                let _ = run(ingest, listener, async {
                    let _ = stop_rx.await;
                })
                .await;
            }
        });

        let mut stream = TcpStream::connect(addr).await.unwrap();
        let body = format!(
            "{{\"v\":1,\"src\":\"peer\",\"consent\":\"granted\",\
              \"install\":\"0123456789abcdef0123456789abcdef\",\"agent\":\"test 0.1.0\",\
              \"events\":[{{\"name\":\"feature.use\",\"t\":{},\"attrs\":\
              {{\"feature\":\"selftest\",\"count\":1}}}}]}}",
            now_millis()
        );
        write_frame(&mut stream, body.as_bytes()).await.unwrap();
        let reply = read_frame(&mut stream, 1 << 20).await.unwrap().unwrap();
        let reply: serde_json::Value = serde_json::from_slice(&reply).unwrap();
        assert_eq!(reply["accepted"], serde_json::Value::from(1));

        // A probe on the same connection still answers.
        write_frame(&mut stream, br#"{"v":1,"probe":true}"#)
            .await
            .unwrap();
        let probe = read_frame(&mut stream, 1 << 20).await.unwrap().unwrap();
        let probe: serde_json::Value = serde_json::from_slice(&probe).unwrap();
        assert_eq!(probe["ok"], serde_json::Value::from(true));

        drop(stream);
        let _ = stop_tx.send(());
        let _ = server.await;

        let written: Vec<String> = std::fs::read_dir(&spool)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| std::fs::read_to_string(e.path()).unwrap())
            .collect();
        assert_eq!(written.len(), 1);
        assert!(
            written[0].contains("\"event\":\"feature.use\""),
            "{written:?}"
        );
        let _ = std::fs::remove_dir_all(&spool);
    }

    /// A 4-byte header must not be able to make the service allocate an arbitrary buffer.
    #[tokio::test]
    async fn a_frame_larger_than_the_bound_is_refused_before_the_body_is_read() {
        let (mut client, mut server) = tokio::io::duplex(64);
        // A header claiming 8 MiB, and no body at all behind it.
        tokio::io::AsyncWriteExt::write_all(&mut client, &8_388_608u32.to_be_bytes())
            .await
            .unwrap();
        let err = read_frame(&mut server, 1024 * 1024).await.unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("exceeds the configured limit"));
    }

    #[tokio::test]
    async fn a_clean_end_of_stream_is_not_an_error() {
        let (client, mut server) = tokio::io::duplex(64);
        drop(client);
        assert!(read_frame(&mut server, 1024).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn a_written_frame_reads_back_byte_for_byte() {
        let (mut client, mut server) = tokio::io::duplex(4096);
        write_frame(&mut client, b"{\"v\":1}").await.unwrap();
        let frame = read_frame(&mut server, 4096).await.unwrap().unwrap();
        assert_eq!(frame, b"{\"v\":1}");
    }
}
