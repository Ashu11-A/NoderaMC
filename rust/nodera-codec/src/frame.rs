//! The `NDR2` outer frame.
//!
//! ```text
//! magic:u32 'NDR2' | epoch:u16 | kind:u16 | flags:u16 | correlationId:u64 | len:u32 | body
//! ```
//!
//! The previous frame started straight in on a `u16` tag, so a peer from a different generation did
//! not fail — it *misparsed*, and reported whatever the misparse produced. A magic number and an
//! explicit epoch turn that into one readable error at the first byte.
//!
//! The body length is what makes "an unknown kind is skipped and answered" implementable: a service
//! can route, refuse and reply for kinds it does not implement, because it can always find where the
//! message ends.
//!
//! Byte-for-byte counterpart of Java's `dev.nodera.protocol.wire.NoderaFrame`.

use crate::{CodecError, Result};

/// `'N' 'D' 'R' '2'` — the first four bytes of every frame.
pub const MAGIC: u32 = 0x4E44_5232;

/// The wire generation this build speaks.
pub const WIRE_EPOCH: u16 = crate::kinds::WIRE_EPOCH;

/// Bytes before the body: magic(4) + epoch(2) + kind(2) + flags(2) + correlation(8) + len(4).
pub const HEADER_BYTES: usize = 22;

/// Frame flag bits.
pub mod flags {
    /// No flags.
    pub const NONE: u16 = 0x0000;
    /// This frame asks a question and expects a matching `RESPONSE`.
    pub const REQUEST: u16 = 0x0001;
    /// This frame answers a `REQUEST`; its correlation id must match a pending one.
    pub const RESPONSE: u16 = 0x0002;
    /// Unsolicited; correlation id is 0.
    pub const EVENT: u16 = 0x0004;
    /// The sender will not read an answer.
    pub const NO_REPLY_EXPECTED: u16 = 0x0008;
}

/// A parsed frame header plus its body.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NoderaFrame {
    /// The wire generation.
    pub epoch: u16,
    /// The message kind ([`crate::kinds`]).
    pub kind: u16,
    /// Request / response / event bits; see [`flags`].
    pub flags: u16,
    /// 0 for events; echoed by a response onto the request that asked for it.
    pub correlation_id: u64,
    /// The encoded body.
    pub body: Vec<u8>,
}

impl NoderaFrame {
    /// An unsolicited event carrying `body`.
    pub fn event(kind: u16, body: Vec<u8>) -> Self {
        Self {
            epoch: WIRE_EPOCH,
            kind,
            flags: flags::EVENT,
            correlation_id: 0,
            body,
        }
    }

    /// A response to `correlation_id`.
    pub fn response(kind: u16, correlation_id: u64, body: Vec<u8>) -> Self {
        Self {
            epoch: WIRE_EPOCH,
            kind,
            flags: flags::RESPONSE,
            correlation_id,
            body,
        }
    }

    /// A request carrying `correlation_id`.
    pub fn request(kind: u16, correlation_id: u64, body: Vec<u8>) -> Self {
        Self {
            epoch: WIRE_EPOCH,
            kind,
            flags: flags::REQUEST,
            correlation_id,
            body,
        }
    }

    /// Encode the frame.
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_BYTES + self.body.len());
        out.extend_from_slice(&MAGIC.to_be_bytes());
        out.extend_from_slice(&self.epoch.to_be_bytes());
        out.extend_from_slice(&self.kind.to_be_bytes());
        out.extend_from_slice(&self.flags.to_be_bytes());
        out.extend_from_slice(&self.correlation_id.to_be_bytes());
        out.extend_from_slice(&(self.body.len() as u32).to_be_bytes());
        out.extend_from_slice(&self.body);
        out
    }

    /// Decode a frame.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] when the magic, the epoch, or the declared length does not hold.
    /// The magic is checked first so a frame from the previous wire generation — or from something
    /// that is not Nodera at all — produces one clear message rather than a cascade of nonsense.
    pub fn decode(raw: &[u8]) -> Result<Self> {
        // The magic is checked before the length: a short frame from the previous generation must
        // be diagnosed as "not a Nodera frame" rather than as "truncated", because the first is
        // actionable and the second sends whoever is reading the log looking for a network fault.
        if raw.len() < 4 {
            return Err(CodecError::UnexpectedEof {
                needed: HEADER_BYTES,
                remaining: raw.len(),
            });
        }
        let magic = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]);
        if magic != MAGIC {
            return Err(CodecError::Malformed(format!(
                "not a Nodera frame: expected magic NDR2 (0x{MAGIC:08x}), got 0x{magic:08x}. \
                 A peer speaking the pre-NDR2 wire looks exactly like this."
            )));
        }
        if raw.len() < HEADER_BYTES {
            return Err(CodecError::UnexpectedEof {
                needed: HEADER_BYTES,
                remaining: raw.len(),
            });
        }
        let epoch = u16::from_be_bytes([raw[4], raw[5]]);
        if epoch != WIRE_EPOCH {
            return Err(CodecError::Malformed(format!(
                "wire epoch {epoch} is not this build's epoch {WIRE_EPOCH}; the frame grammar \
                 itself differs, so nothing below this point can be trusted"
            )));
        }
        let kind = u16::from_be_bytes([raw[6], raw[7]]);
        let flags = u16::from_be_bytes([raw[8], raw[9]]);
        let mut corr = [0u8; 8];
        corr.copy_from_slice(&raw[10..18]);
        let correlation_id = u64::from_be_bytes(corr);
        let declared = u32::from_be_bytes([raw[18], raw[19], raw[20], raw[21]]) as usize;
        let available = raw.len() - HEADER_BYTES;
        if declared != available {
            return Err(CodecError::Malformed(format!(
                "frame declares a {declared}-byte body but {available} byte(s) follow the header"
            )));
        }
        Ok(Self {
            epoch,
            kind,
            flags,
            correlation_id,
            body: raw[HEADER_BYTES..].to_vec(),
        })
    }

    /// Read only the kind, without validating or copying the body.
    pub fn peek_kind(raw: &[u8]) -> Result<u16> {
        if raw.len() < HEADER_BYTES {
            return Err(CodecError::UnexpectedEof {
                needed: HEADER_BYTES,
                remaining: raw.len(),
            });
        }
        if u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) != MAGIC {
            return Err(CodecError::Malformed("not a Nodera frame: bad magic".into()));
        }
        Ok(u16::from_be_bytes([raw[6], raw[7]]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        let frame = NoderaFrame::request(27, 0x0123_4567_89AB_CDEF, b"body".to_vec());
        let decoded = NoderaFrame::decode(&frame.encode()).unwrap();
        assert_eq!(decoded, frame);
        assert_eq!(NoderaFrame::peek_kind(&frame.encode()).unwrap(), 27);
    }

    #[test]
    fn a_pre_ndr2_frame_fails_at_the_magic() {
        // The old frame opened on `u16 tag; u16 version` — here, tag 27 version 1.
        let legacy = [0u8, 27, 0, 1, 0, 0, 0, 0];
        let err = NoderaFrame::decode(&legacy).unwrap_err();
        assert!(format!("{err}").contains("NDR2"), "{err}");
    }

    #[test]
    fn a_foreign_epoch_is_refused_before_the_body_is_trusted() {
        let mut raw = NoderaFrame::event(27, b"x".to_vec()).encode();
        raw[5] = WIRE_EPOCH as u8 + 1;
        let err = NoderaFrame::decode(&raw).unwrap_err();
        assert!(format!("{err}").contains("epoch"), "{err}");
    }

    #[test]
    fn a_lying_length_is_refused() {
        let mut raw = NoderaFrame::event(27, b"body".to_vec()).encode();
        raw[21] = 99;
        assert!(NoderaFrame::decode(&raw).is_err());
    }

    #[test]
    fn an_unknown_kind_is_still_a_well_formed_frame() {
        // The property D4 depends on: the receiver can find the end of a message it cannot read,
        // so it can answer instead of hanging up.
        let frame = NoderaFrame::event(60_000, b"payload from a later release".to_vec());
        let decoded = NoderaFrame::decode(&frame.encode()).unwrap();
        assert_eq!(decoded.kind, 60_000);
        assert!(crate::kinds::find(decoded.kind).is_none());
    }
}
