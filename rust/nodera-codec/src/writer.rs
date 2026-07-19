//! Canonical writer — the byte-for-byte counterpart of Java's `CanonicalWriter`.
//!
//! Big-endian, fixed-width integers, no varints. This is the ONE place that knows how scalars are
//! serialised on the Rust side; changing a method here changes every hash and signature the
//! services verify.

/// Buffered canonical encoder.
///
/// Not shared between threads; construct one per encoding call (same contract as the Java writer).
#[derive(Debug, Default, Clone)]
pub struct CanonicalWriter {
    buf: Vec<u8>,
}

impl CanonicalWriter {
    /// A writer with the default initial capacity (matches Java's 256-byte default).
    pub fn new() -> Self {
        Self::with_capacity(256)
    }

    /// A writer with an explicit initial capacity.
    pub fn with_capacity(capacity: usize) -> Self {
        Self {
            buf: Vec::with_capacity(capacity),
        }
    }

    /// The frame prefix every top-level `Encodable` starts with: `u16 tag; u16 version`.
    pub fn write_frame_header(&mut self, tag: u16, version: u16) -> &mut Self {
        self.write_u16(tag).write_u16(version)
    }

    /// Writes the low 8 bits, matching Java's `writeU8(int)`.
    pub fn write_u8(&mut self, v: u8) -> &mut Self {
        self.buf.push(v);
        self
    }

    /// Big-endian `u16`.
    pub fn write_u16(&mut self, v: u16) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Big-endian `u32`.
    pub fn write_u32(&mut self, v: u32) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Big-endian `u64`.
    pub fn write_u64(&mut self, v: u64) -> &mut Self {
        self.buf.extend_from_slice(&v.to_be_bytes());
        self
    }

    /// Big-endian `i64` (Java writes signed longs through the same `writeU64`).
    pub fn write_i64(&mut self, v: i64) -> &mut Self {
        self.write_u64(v as u64)
    }

    /// `u8` boolean / optional-presence marker.
    pub fn write_bool(&mut self, v: bool) -> &mut Self {
        self.write_u8(u8::from(v))
    }

    /// `u32` length prefix + raw bytes.
    pub fn write_bytes(&mut self, bytes: &[u8]) -> &mut Self {
        self.write_u32(bytes.len() as u32);
        self.buf.extend_from_slice(bytes);
        self
    }

    /// Raw bytes with no length prefix (the caller already framed them).
    pub fn write_raw(&mut self, bytes: &[u8]) -> &mut Self {
        self.buf.extend_from_slice(bytes);
        self
    }

    /// `u32` UTF-8 byte-length + UTF-8 bytes.
    pub fn write_string(&mut self, s: &str) -> &mut Self {
        self.write_bytes(s.as_bytes())
    }

    /// `u32` element count + each element via `write_element`.
    ///
    /// Set/map semantics: the caller sorts by the documented key *before* calling — canonical
    /// order is a property of the value, never of the encoder.
    pub fn write_list<T, F>(&mut self, items: &[T], mut write_element: F) -> &mut Self
    where
        F: FnMut(&mut Self, &T),
    {
        self.write_u32(items.len() as u32);
        for item in items {
            write_element(self, item);
        }
        self
    }

    /// Bytes written so far.
    pub fn len(&self) -> usize {
        self.buf.len()
    }

    /// Whether nothing has been written yet.
    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }

    /// Borrow the encoded bytes.
    pub fn as_slice(&self) -> &[u8] {
        &self.buf
    }

    /// Take the encoded bytes, consuming the writer.
    pub fn into_vec(self) -> Vec<u8> {
        self.buf
    }

    /// Discard the contents, keeping the allocation.
    pub fn reset(&mut self) {
        self.buf.clear();
    }
}
