//! The socket framing every Nodera service speaks, and the bounds that keep it safe to speak.
//!
//! `nodera-tracker`, `nodera-rendezvous` and `nodera-telemetry` all serve `nodera-codec`'s framing
//! (`u32` length + body) over TCP, and all three had written the reader, the writer and the clock
//! out for themselves. The three copies had already drifted in the way that matters:
//!
//! * only the telemetry copy was **generic over the stream**, so only that one could be tested
//!   against an in-memory pipe — and the bound it tests, *"a frame larger than the limit is refused
//!   before the buffer is allocated"*, is what keeps a 4-byte header from being an out-of-memory
//!   switch. Two of the three services had that property asserted by nothing at all;
//! * the **UDP amplification cap** — a security property, not a tuning knob — lived inline in the
//!   tracker's datagram loop with no home, no name, and nothing else able to reuse it.
//!
//! One copy, one set of tests, three services.

use std::io;
use std::time::{SystemTime, UNIX_EPOCH};

use nodera_codec::framing;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Wall-clock milliseconds.
///
/// For freshness windows, quotas and metering only — never for anything a peer's correctness
/// depends on. The deterministic engine has its own clock and it is not this one.
pub fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Read one length-prefixed frame; `Ok(None)` at a clean end of stream.
///
/// Generic over the stream so the bound below is testable against an in-memory pipe. The announced
/// length is checked against `max_frame_bytes` **before** the body buffer is allocated: without
/// that check a 4-byte header is an out-of-memory switch any unauthenticated socket can throw.
pub async fn read_frame<S: AsyncRead + Unpin>(
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
pub async fn write_frame<S: AsyncWrite + Unpin>(stream: &mut S, payload: &[u8]) -> io::Result<()> {
    let framed = framing::frame(payload)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    stream.write_all(&framed).await
}

/// Whether a UDP reply may be sent at all.
///
/// A UDP source address is forgeable, so an unbounded answer makes a service a reflection amplifier
/// pointed at whoever an attacker names. Two bounds, and a failure of either means **silence**: a
/// truncated canonical frame is undecodable, so not answering is the honest outcome and the peer
/// retries over TCP, where the handshake has already proved the source address.
///
/// * `reply <= max_reply_bytes` — the absolute ceiling on what leaves over UDP;
/// * `reply <= request * max_amplification` — the gain an attacker can buy per spoofed byte.
pub fn udp_reply_permitted(
    request_len: usize,
    reply_len: usize,
    max_reply_bytes: usize,
    max_amplification: usize,
) -> bool {
    reply_len <= max_reply_bytes && reply_len <= request_len.saturating_mul(max_amplification)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The bound that keeps a 4-byte header from being an out-of-memory switch.
    ///
    /// Asserted against an in-memory pipe, which is why this reader is generic: the length is
    /// refused *before* the allocation, so nothing here has to send 4 GiB to prove it.
    #[tokio::test]
    async fn an_oversized_length_is_refused_before_the_body_is_allocated() {
        let (mut client, mut server) = tokio::io::duplex(64);
        // 4 GiB - 1 announced, nothing sent.
        tokio::io::AsyncWriteExt::write_all(&mut client, &[0xFF, 0xFF, 0xFF, 0xFF])
            .await
            .unwrap();
        let error = read_frame(&mut server, 1 << 20).await.unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    }

    /// A frame one byte over the *configured* limit is refused too — the protocol cap is not the
    /// only bound, because a service may be configured well below it.
    #[tokio::test]
    async fn the_configured_limit_is_enforced_and_not_only_the_protocol_cap() {
        let (mut client, mut server) = tokio::io::duplex(4_096);
        write_frame(&mut client, &[7u8; 100]).await.unwrap();
        let error = read_frame(&mut server, 99).await.unwrap_err();
        assert!(error.to_string().contains("exceeds the configured limit"));
    }

    #[tokio::test]
    async fn a_frame_survives_the_round_trip_and_a_closed_stream_is_not_an_error() {
        let (mut client, mut server) = tokio::io::duplex(4_096);
        write_frame(&mut client, b"a canonical frame")
            .await
            .unwrap();
        drop(client);
        assert_eq!(
            read_frame(&mut server, 1 << 20).await.unwrap(),
            Some(b"a canonical frame".to_vec())
        );
        // Clean end of stream, not a failure: a peer that asked and left is normal.
        assert_eq!(read_frame(&mut server, 1 << 20).await.unwrap(), None);
    }

    #[test]
    fn an_answer_that_would_amplify_beyond_the_ratio_is_not_permitted() {
        // 32 bytes in, 4× allowed: 128 out is fine, 129 is a reflector.
        assert!(udp_reply_permitted(32, 128, 64 * 1024, 4));
        assert!(!udp_reply_permitted(32, 129, 64 * 1024, 4));
    }

    #[test]
    fn the_absolute_reply_ceiling_applies_even_when_the_ratio_does_not() {
        // A large request could buy a huge answer on the ratio alone; the ceiling is the backstop.
        assert!(!udp_reply_permitted(1_000_000, 200_000, 32 * 1024, 4));
    }

    #[test]
    fn a_ratio_that_would_overflow_saturates_rather_than_wrapping_into_permission() {
        assert!(udp_reply_permitted(usize::MAX, 1_024, 64 * 1024, 4));
    }
}
