//! Length-prefixed stream framing, matching `SocketPeerTransport`.
//!
//! Every frame is `u32 length (big-endian) + length bytes`, capped at [`MAX_FRAME_BYTES`]. The
//! services reuse this so they talk to existing Java transport code without a second framing
//! layer.

use crate::{CodecError, Result};

/// Absolute cap on a single frame — mirrors `SocketPeerTransport.MAX_FRAME_BYTES`.
///
/// A hostile length prefix is rejected against this bound *before* any allocation.
pub const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

/// Prepend the `u32` big-endian length header to `payload`.
///
/// Returns an error when the payload exceeds [`MAX_FRAME_BYTES`].
pub fn frame(payload: &[u8]) -> Result<Vec<u8>> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(CodecError::LengthOverrun {
            claimed: payload.len() as u64,
            remaining: MAX_FRAME_BYTES,
        });
    }
    let mut out = Vec::with_capacity(4 + payload.len());
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    Ok(out)
}

/// Validate a length header and return the body length it announces.
///
/// The caller reads exactly that many bytes next. Split out from [`frame`] so async readers can
/// bound the read before allocating a buffer.
pub fn decode_length(header: [u8; 4]) -> Result<usize> {
    let len = u32::from_be_bytes(header) as usize;
    if len > MAX_FRAME_BYTES {
        return Err(CodecError::LengthOverrun {
            claimed: len as u64,
            remaining: MAX_FRAME_BYTES,
        });
    }
    Ok(len)
}

/// Split one complete frame off the front of `buf`.
///
/// Returns `Ok(None)` when `buf` does not yet hold a whole frame — the caller reads more bytes and
/// retries.
pub fn take_frame(buf: &[u8]) -> Result<Option<(&[u8], usize)>> {
    if buf.len() < 4 {
        return Ok(None);
    }
    let len = decode_length([buf[0], buf[1], buf[2], buf[3]])?;
    if buf.len() < 4 + len {
        return Ok(None);
    }
    Ok(Some((&buf[4..4 + len], 4 + len)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_and_take_are_inverse() {
        let framed = frame(b"hello").unwrap();
        assert_eq!(&framed[..4], &[0, 0, 0, 5]);
        let (body, consumed) = take_frame(&framed).unwrap().unwrap();
        assert_eq!(body, b"hello");
        assert_eq!(consumed, framed.len());
    }

    #[test]
    fn partial_frame_yields_none() {
        let framed = frame(b"hello").unwrap();
        assert!(take_frame(&framed[..6]).unwrap().is_none());
        assert!(take_frame(&framed[..2]).unwrap().is_none());
    }

    #[test]
    fn oversized_length_header_is_rejected() {
        let header = ((MAX_FRAME_BYTES + 1) as u32).to_be_bytes();
        assert!(matches!(
            decode_length(header),
            Err(CodecError::LengthOverrun { .. })
        ));
    }
}
