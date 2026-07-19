//! Canonical reader — the byte-for-byte counterpart of Java's `CanonicalReader`.
//!
//! Every length and count prefix is bounded against the bytes actually present *before* an
//! allocation happens: a `u32` prefix is attacker-controlled pre-auth, and without the bound a
//! 4-byte header could force a ~4 GiB allocation (the same memory-amplification guard the Java
//! reader carries).

use crate::{CodecError, Result};

/// Cursor over a canonical frame.
///
/// Not shared between threads; construct one per decode call.
#[derive(Debug, Clone)]
pub struct CanonicalReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> CanonicalReader<'a> {
    /// Wrap a frame.
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    /// Bytes not yet consumed.
    pub fn remaining(&self) -> usize {
        self.data.len() - self.pos
    }

    /// Whether the whole frame has been consumed.
    pub fn is_empty(&self) -> bool {
        self.remaining() == 0
    }

    /// Error unless the frame is fully consumed — a valid message followed by extra bytes is
    /// malformed, never silently accepted.
    pub fn expect_end(&self) -> Result<()> {
        match self.remaining() {
            0 => Ok(()),
            n => Err(CodecError::TrailingBytes(n)),
        }
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.remaining() < n {
            return Err(CodecError::UnexpectedEof {
                needed: n,
                remaining: self.remaining(),
            });
        }
        let slice = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(slice)
    }

    /// Read a `u8`.
    pub fn read_u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    /// Read a big-endian `u16`.
    pub fn read_u16(&mut self) -> Result<u16> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    /// Read a big-endian `u32`.
    pub fn read_u32(&mut self) -> Result<u32> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// Read a big-endian `u64`.
    pub fn read_u64(&mut self) -> Result<u64> {
        let b = self.take(8)?;
        Ok(u64::from_be_bytes([
            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
        ]))
    }

    /// Read a big-endian `i64` (Java's signed `writeU64` values).
    pub fn read_i64(&mut self) -> Result<i64> {
        Ok(self.read_u64()? as i64)
    }

    /// Read a `u8` boolean / optional-presence marker.
    pub fn read_bool(&mut self) -> Result<bool> {
        Ok(self.read_u8()? != 0)
    }

    /// Read the `u16 tag; u16 version` frame header of a nested `Encodable` and validate both.
    pub fn read_frame_header(&mut self, expected_tag: u16, expected_version: u16) -> Result<()> {
        let tag = self.read_u16()?;
        if tag != expected_tag {
            return Err(CodecError::UnexpectedTag {
                expected: expected_tag,
                actual: tag,
            });
        }
        let version = self.read_u16()?;
        if version != expected_version {
            return Err(CodecError::UnsupportedVersion { tag, version });
        }
        Ok(())
    }

    /// Read a `u32`-length-prefixed byte string.
    pub fn read_bytes(&mut self) -> Result<&'a [u8]> {
        let len = self.read_u32()? as usize;
        if len > self.remaining() {
            return Err(CodecError::LengthOverrun {
                claimed: len as u64,
                remaining: self.remaining(),
            });
        }
        self.take(len)
    }

    /// Read a `u32`-length-prefixed byte string as an owned vector.
    pub fn read_bytes_vec(&mut self) -> Result<Vec<u8>> {
        Ok(self.read_bytes()?.to_vec())
    }

    /// Read a `u32`-length-prefixed UTF-8 string.
    pub fn read_string(&mut self) -> Result<String> {
        let raw = self.read_bytes()?;
        String::from_utf8(raw.to_vec())
            .map_err(|e| CodecError::Malformed(format!("invalid UTF-8 string: {e}")))
    }

    /// Read a `u32` count followed by that many elements.
    ///
    /// Every encoded element is at least one byte, so a count larger than the remaining frame
    /// cannot be legitimate — bounded before the backing allocation.
    pub fn read_list<T, F>(&mut self, mut read_element: F) -> Result<Vec<T>>
    where
        F: FnMut(&mut Self) -> Result<T>,
    {
        let count = self.read_u32()? as usize;
        if count > self.remaining() {
            return Err(CodecError::LengthOverrun {
                claimed: count as u64,
                remaining: self.remaining(),
            });
        }
        let mut out = Vec::with_capacity(count);
        for _ in 0..count {
            out.push(read_element(self)?);
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::CanonicalWriter;

    #[test]
    fn scalars_round_trip_big_endian() {
        let mut w = CanonicalWriter::new();
        w.write_u8(0xAB)
            .write_u16(0xBEEF)
            .write_u32(0xDEAD_BEEF)
            .write_u64(0x0102_0304_0506_0708)
            .write_bool(true)
            .write_string("nodera")
            .write_bytes(&[1, 2, 3]);

        let encoded = w.into_vec();
        assert_eq!(&encoded[..1], &[0xAB]);
        assert_eq!(&encoded[1..3], &[0xBE, 0xEF]);

        let mut r = CanonicalReader::new(&encoded);
        assert_eq!(r.read_u8().unwrap(), 0xAB);
        assert_eq!(r.read_u16().unwrap(), 0xBEEF);
        assert_eq!(r.read_u32().unwrap(), 0xDEAD_BEEF);
        assert_eq!(r.read_u64().unwrap(), 0x0102_0304_0506_0708);
        assert!(r.read_bool().unwrap());
        assert_eq!(r.read_string().unwrap(), "nodera");
        assert_eq!(r.read_bytes().unwrap(), &[1, 2, 3]);
        r.expect_end().unwrap();
    }

    #[test]
    fn hostile_length_prefix_is_rejected_before_allocating() {
        // u32 length = 0xFFFFFFFF with no payload: must error, never allocate 4 GiB.
        let frame = [0xFF, 0xFF, 0xFF, 0xFF];
        let mut r = CanonicalReader::new(&frame);
        assert!(matches!(
            r.read_bytes(),
            Err(CodecError::LengthOverrun { .. })
        ));
    }

    #[test]
    fn hostile_list_count_is_rejected_before_allocating() {
        let frame = [0xFF, 0xFF, 0xFF, 0xFF];
        let mut r = CanonicalReader::new(&frame);
        let decoded: Result<Vec<u8>> = r.read_list(|rr| rr.read_u8());
        assert!(matches!(decoded, Err(CodecError::LengthOverrun { .. })));
    }

    #[test]
    fn trailing_bytes_are_malformed() {
        let frame = [0u8, 1u8];
        let mut r = CanonicalReader::new(&frame);
        r.read_u8().unwrap();
        assert_eq!(r.expect_end(), Err(CodecError::TrailingBytes(1)));
    }
}
