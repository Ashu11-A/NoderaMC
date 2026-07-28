//! Canonical TLV — the infrastructure plane's body encoding.
//!
//! ```text
//! body  = field*                        (ascending fieldId, each id at most once)
//! field = fieldId:u16 | wireType:u8 | len:u32 | value
//! ```
//!
//! The per-field length is the whole point. A positional body gives a reader no way to find the end
//! of a field it does not know, so it cannot find the start of the next one — which meant, before
//! this, that there was no such thing as a compatible change to a Nodera message. With a length, an
//! unknown field is skipped and everything after it still decodes, so a service built today keeps
//! answering a peer built tomorrow.
//!
//! Tolerant is not lax. Ids must strictly ascend and may not repeat, fixed-width types must carry
//! their exact length, a boolean must be 0 or 1, and a string must be well-formed UTF-8. Each of
//! those was a real defect on the old wire: accepting anything else gives one value several
//! spellings, which is how two implementations end up hashing the same message differently.
//!
//! Byte-for-byte counterpart of Java's `dev.nodera.protocol.wire.TlvWriter` / `TlvReader`.

use crate::{CodecError, Result};
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};

/// The physical shape of one field's value.
///
/// Codes are assigned explicitly and permanently; they are never a discriminant's position.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WireType {
    /// Unsigned 8-bit; length exactly 1.
    U8,
    /// Unsigned 16-bit, big-endian; length exactly 2.
    U16,
    /// Unsigned 32-bit, big-endian; length exactly 4.
    U32,
    /// Unsigned 64-bit, big-endian; length exactly 8.
    U64,
    /// Exactly 0 or 1 — one value, one spelling.
    Bool,
    /// Raw bytes.
    Bytes,
    /// UTF-8 text, validated strictly.
    Str,
    /// A nested TLV body, same grammar recursively.
    Nested,
    /// `count:u32` then `count` × `len:u32 | elementBytes`.
    List,
}

impl WireType {
    /// The permanent wire code.
    pub fn code(self) -> u8 {
        match self {
            WireType::U8 => 1,
            WireType::U16 => 2,
            WireType::U32 => 3,
            WireType::U64 => 4,
            WireType::Bool => 5,
            WireType::Bytes => 6,
            WireType::Str => 7,
            WireType::Nested => 8,
            WireType::List => 9,
        }
    }

    /// The exact value length this type requires, or `None` when it is variable.
    pub fn fixed_length(self) -> Option<usize> {
        match self {
            WireType::U8 | WireType::Bool => Some(1),
            WireType::U16 => Some(2),
            WireType::U32 => Some(4),
            WireType::U64 => Some(8),
            _ => None,
        }
    }

    /// Resolve a wire code.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] for a code no type carries — a field whose type cannot be
    /// identified cannot be skipped safely.
    pub fn from_code(code: u8) -> Result<Self> {
        Ok(match code {
            1 => WireType::U8,
            2 => WireType::U16,
            3 => WireType::U32,
            4 => WireType::U64,
            5 => WireType::Bool,
            6 => WireType::Bytes,
            7 => WireType::Str,
            8 => WireType::Nested,
            9 => WireType::List,
            other => {
                return Err(CodecError::Malformed(format!(
                    "unknown TLV wire type code {other}"
                )))
            }
        })
    }
}

// ---------------------------------------------------------------------- writer

/// Builds a canonical TLV body.
///
/// Field ids must be written in strictly ascending order; the builder refuses anything else,
/// because a body with two field orders would be two byte strings meaning one value.
#[derive(Debug, Default)]
pub struct TlvWriter {
    buf: Vec<u8>,
    last_id: i32,
}

impl TlvWriter {
    /// A new, empty body.
    pub fn new() -> Self {
        Self {
            buf: Vec::with_capacity(64),
            last_id: -1,
        }
    }

    fn field(&mut self, id: u16, ty: WireType, value: &[u8]) -> &mut Self {
        debug_assert!(
            i32::from(id) > self.last_id,
            "TLV field ids must strictly ascend; wrote {} then {id}",
            self.last_id
        );
        self.last_id = i32::from(id);
        self.buf.extend_from_slice(&id.to_be_bytes());
        self.buf.push(ty.code());
        self.buf
            .extend_from_slice(&(value.len() as u32).to_be_bytes());
        self.buf.extend_from_slice(value);
        self
    }

    /// Write an unsigned 8-bit field.
    pub fn u8(&mut self, id: u16, v: u8) -> &mut Self {
        self.field(id, WireType::U8, &[v])
    }

    /// Write an unsigned 16-bit field.
    pub fn u16(&mut self, id: u16, v: u16) -> &mut Self {
        self.field(id, WireType::U16, &v.to_be_bytes())
    }

    /// Write an unsigned 32-bit field.
    pub fn u32(&mut self, id: u16, v: u32) -> &mut Self {
        self.field(id, WireType::U32, &v.to_be_bytes())
    }

    /// Write an unsigned 64-bit field.
    pub fn u64(&mut self, id: u16, v: u64) -> &mut Self {
        self.field(id, WireType::U64, &v.to_be_bytes())
    }

    /// Write a boolean as exactly 0 or 1.
    pub fn bool(&mut self, id: u16, v: bool) -> &mut Self {
        self.field(id, WireType::Bool, &[u8::from(v)])
    }

    /// Write raw bytes.
    pub fn bytes(&mut self, id: u16, v: &[u8]) -> &mut Self {
        self.field(id, WireType::Bytes, v)
    }

    /// Write UTF-8 text.
    pub fn str(&mut self, id: u16, v: &str) -> &mut Self {
        self.field(id, WireType::Str, v.as_bytes())
    }

    /// Write a UUID as two big-endian `u64` halves in one 16-byte field.
    pub fn uuid(&mut self, id: u16, msb: u64, lsb: u64) -> &mut Self {
        let mut raw = [0u8; 16];
        raw[..8].copy_from_slice(&msb.to_be_bytes());
        raw[8..].copy_from_slice(&lsb.to_be_bytes());
        self.field(id, WireType::Bytes, &raw)
    }

    /// Write a strictly ascending list of `u32`s, packed four bytes each.
    ///
    /// Piece-index lists run to thousands of entries, and the general list encoding spends eleven
    /// bytes on each one to buy an extensibility a `u32` will never need.
    pub fn u32_array(&mut self, id: u16, values: &[u32]) -> &mut Self {
        let mut raw = Vec::with_capacity(values.len() * 4);
        for v in values {
            raw.extend_from_slice(&v.to_be_bytes());
        }
        self.field(id, WireType::Bytes, &raw)
    }

    /// Write a nested TLV body.
    pub fn nested(&mut self, id: u16, body: impl FnOnce(&mut TlvWriter)) -> &mut Self {
        let mut inner = TlvWriter::new();
        body(&mut inner);
        let raw = inner.finish();
        self.field(id, WireType::Nested, &raw)
    }

    /// Write a list: `count:u32` then each element as `len:u32 | elementBytes`.
    pub fn list<T>(
        &mut self,
        id: u16,
        items: &[T],
        mut element: impl FnMut(&mut TlvWriter, &T),
    ) -> &mut Self {
        let mut body = Vec::with_capacity(16);
        body.extend_from_slice(&(items.len() as u32).to_be_bytes());
        for item in items {
            let mut inner = TlvWriter::new();
            element(&mut inner, item);
            let raw = inner.finish();
            body.extend_from_slice(&(raw.len() as u32).to_be_bytes());
            body.extend_from_slice(&raw);
        }
        self.field(id, WireType::List, &body)
    }

    /// Re-emit a field this build did not recognise, byte for byte.
    ///
    /// A peer in the middle of a version spread must not silently strip the fields the peers on
    /// either side of it depend on.
    pub fn raw(&mut self, field: &TlvField) -> &mut Self {
        self.field(field.id, field.ty, &field.value)
    }

    /// The encoded body.
    pub fn finish(self) -> Vec<u8> {
        self.buf
    }

    /// The encoded body, without consuming the builder.
    pub fn to_vec(&self) -> Vec<u8> {
        self.buf.clone()
    }
}

// ---------------------------------------------------------------------- reader

/// One decoded field, kept raw so an unknown one can be re-emitted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlvField {
    /// The field id, as it appeared on the wire.
    pub id: u16,
    /// The declared wire type.
    pub ty: WireType,
    /// The raw value bytes, without the header.
    pub value: Vec<u8>,
}

/// The difference between the field set a frame arrived with and the one this build writes.
///
/// Forward compatibility runs in two directions and both have to survive a re-encode: a newer peer
/// sends a field this build cannot read (kept in `preserved`), and an older peer omits one this
/// build does write (recorded in `absent`, so re-emitting it does not put words in that peer's
/// mouth). Together they say "reproduce the field set you were given".
///
/// Mirrors Java's `dev.nodera.protocol.wire.TlvOverlay`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TlvOverlay {
    /// Fields no accessor read, plus any field this build could only approximate.
    pub preserved: Vec<TlvField>,
    /// Ids this build writes that the received frame did not carry.
    pub absent: BTreeSet<u16>,
}

impl TlvOverlay {
    /// Whether the frame's field set is exactly what this build writes.
    pub fn is_empty(&self) -> bool {
        self.preserved.is_empty() && self.absent.is_empty()
    }

    /// Apply the overlay to an encoded body, yielding the body as the sender spelled it.
    ///
    /// # Errors
    /// Propagates any grammar error from re-parsing `encoded`.
    pub fn apply_to(&self, encoded: &[u8]) -> Result<Vec<u8>> {
        if self.is_empty() {
            return Ok(encoded.to_vec());
        }
        let mut all: BTreeMap<u16, TlvField> = TlvBody::parse(encoded)?
            .fields
            .into_iter()
            .map(|f| (f.id, f))
            .collect();
        for id in &self.absent {
            all.remove(id);
        }
        // Preserved fields win: they are the bytes that actually arrived, and the only reason a
        // field is preserved is that this build could not fully reconstruct it.
        for f in &self.preserved {
            all.insert(f.id, f.clone());
        }
        let mut w = TlvWriter::new();
        for f in all.values() {
            w.raw(f);
        }
        Ok(w.finish())
    }
}

/// A parsed TLV body.
///
/// Fields are read by id; one that is absent yields the caller's documented default, and one this
/// build does not know is kept in [`TlvBody::fields`] rather than discarded.
#[derive(Debug, Clone, Default)]
pub struct TlvBody {
    /// Every field, ascending.
    pub fields: Vec<TlvField>,
    consumed: RefCell<BTreeSet<u16>>,
    verbatim: RefCell<BTreeMap<u16, TlvField>>,
    absent: RefCell<BTreeSet<u16>>,
}

/// Borrow one field's value straight out of `body`, without copying.
///
/// Exists so a decoder can hand a verifier the **received** bytes a signature covers instead of a
/// re-encoding of the value it just decoded. Verifying a re-encoding checks this implementation
/// against itself, and quietly accepts a record whose canonical form differs from what the peer
/// actually signed.
///
/// # Errors
/// Propagates any grammar error from the walk.
pub fn field_slice<'a>(body: &'a [u8], id: u16) -> Result<Option<&'a [u8]>> {
    let mut pos = 0usize;
    while pos < body.len() {
        if body.len() - pos < 7 {
            return Err(CodecError::UnexpectedEof {
                needed: 7,
                remaining: body.len() - pos,
            });
        }
        let field_id = u16::from_be_bytes([body[pos], body[pos + 1]]);
        let len = u32::from_be_bytes([body[pos + 3], body[pos + 4], body[pos + 5], body[pos + 6]])
            as usize;
        pos += 7;
        if len > body.len() - pos {
            return Err(CodecError::LengthOverrun {
                claimed: len as u64,
                remaining: body.len() - pos,
            });
        }
        if field_id == id {
            return Ok(Some(&body[pos..pos + len]));
        }
        pos += len;
    }
    Ok(None)
}

impl TlvBody {
    /// Parse a body.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] when the grammar, the ordering, or a fixed-width length is wrong.
    pub fn parse(body: &[u8]) -> Result<Self> {
        let mut fields = Vec::new();
        let mut pos = 0usize;
        let mut last_id: i32 = -1;
        while pos < body.len() {
            if body.len() - pos < 7 {
                return Err(CodecError::UnexpectedEof {
                    needed: 7,
                    remaining: body.len() - pos,
                });
            }
            let id = u16::from_be_bytes([body[pos], body[pos + 1]]);
            let ty = WireType::from_code(body[pos + 2])?;
            let len =
                u32::from_be_bytes([body[pos + 3], body[pos + 4], body[pos + 5], body[pos + 6]])
                    as usize;
            pos += 7;
            if len > body.len() - pos {
                return Err(CodecError::LengthOverrun {
                    claimed: len as u64,
                    remaining: body.len() - pos,
                });
            }
            if i32::from(id) <= last_id {
                return Err(CodecError::Malformed(format!(
                    "TLV field ids must strictly ascend; saw {last_id} then {id}"
                )));
            }
            last_id = i32::from(id);
            if let Some(fixed) = ty.fixed_length() {
                if len != fixed {
                    return Err(CodecError::Malformed(format!(
                        "TLV field {id} is {ty:?} so its value must be {fixed} byte(s), got {len}"
                    )));
                }
            }
            fields.push(TlvField {
                id,
                ty,
                value: body[pos..pos + len].to_vec(),
            });
            pos += len;
        }
        Ok(Self {
            fields,
            ..Default::default()
        })
    }

    /// Declare that this build read a field *lossily*, so it must be re-emitted verbatim.
    ///
    /// For values whose interpretation can drop something — an enum code from a later release, a
    /// role this build has no name for. The decoded value is a usable approximation and the bytes
    /// are the truth, so the bytes are what gets forwarded.
    pub fn mark_verbatim(&self, id: u16) {
        if let Some(f) = self.fields.iter().find(|f| f.id == id) {
            self.verbatim.borrow_mut().insert(id, f.clone());
        }
    }

    /// The fields no accessor asked for, plus any this build could only approximate.
    pub fn unconsumed(&self) -> Vec<TlvField> {
        let consumed = self.consumed.borrow();
        let mut out: BTreeMap<u16, TlvField> = self.verbatim.borrow().clone();
        for f in &self.fields {
            if !consumed.contains(&f.id) {
                out.insert(f.id, f.clone());
            }
        }
        out.into_values().collect()
    }

    /// How the received field set differed from the one this build writes.
    pub fn overlay(&self) -> TlvOverlay {
        TlvOverlay {
            preserved: self.unconsumed(),
            absent: self.absent.borrow().clone(),
        }
    }

    fn typed(&self, id: u16, expected: WireType) -> Result<Option<&TlvField>> {
        // Reads are recorded so the overlay can say exactly how the received field set differed
        // from the one this build writes — in both directions.
        self.consumed.borrow_mut().insert(id);
        match self.fields.iter().find(|f| f.id == id) {
            None => {
                // An older peer that does not write this field yet.
                self.absent.borrow_mut().insert(id);
                Ok(None)
            }
            Some(f) if f.ty == expected => Ok(Some(f)),
            Some(f) => Err(CodecError::Malformed(format!(
                "TLV field {id} is {:?} but was read as {expected:?}",
                f.ty
            ))),
        }
    }

    /// Whether the body carries this field id.
    pub fn has(&self, id: u16) -> bool {
        self.fields.iter().any(|f| f.id == id)
    }

    /// Read a `u8`, or `fallback` when absent.
    pub fn u8(&self, id: u16, fallback: u8) -> Result<u8> {
        Ok(self.typed(id, WireType::U8)?.map_or(fallback, |f| f.value[0]))
    }

    /// Read a `u16`, or `fallback` when absent.
    pub fn u16(&self, id: u16, fallback: u16) -> Result<u16> {
        Ok(self
            .typed(id, WireType::U16)?
            .map_or(fallback, |f| u16::from_be_bytes([f.value[0], f.value[1]])))
    }

    /// Read a `u32`, or `fallback` when absent.
    pub fn u32(&self, id: u16, fallback: u32) -> Result<u32> {
        Ok(self.typed(id, WireType::U32)?.map_or(fallback, |f| {
            u32::from_be_bytes([f.value[0], f.value[1], f.value[2], f.value[3]])
        }))
    }

    /// Read a `u64`, or `fallback` when absent.
    pub fn u64(&self, id: u16, fallback: u64) -> Result<u64> {
        Ok(self.typed(id, WireType::U64)?.map_or(fallback, |f| {
            let mut raw = [0u8; 8];
            raw.copy_from_slice(&f.value);
            u64::from_be_bytes(raw)
        }))
    }

    /// Read a boolean, or `fallback` when absent.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] if the byte is neither 0 nor 1. "Any nonzero byte" gave `true` 255
    /// encodings, and the conformance fuzz found both implementations doing it.
    pub fn bool(&self, id: u16, fallback: bool) -> Result<bool> {
        match self.typed(id, WireType::Bool)? {
            None => Ok(fallback),
            Some(f) => match f.value[0] {
                0 => Ok(false),
                1 => Ok(true),
                other => Err(CodecError::Malformed(format!(
                    "TLV field {id}: a boolean must be 0 or 1, got {other}"
                ))),
            },
        }
    }

    /// Read raw bytes, or an empty slice when absent.
    pub fn bytes(&self, id: u16) -> Result<Vec<u8>> {
        Ok(self
            .typed(id, WireType::Bytes)?
            .map_or_else(Vec::new, |f| f.value.clone()))
    }

    /// Read UTF-8 text, or `""` when absent.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] if the bytes are not well-formed UTF-8. Replacement-decoding them
    /// would re-encode as different bytes — a live divergence when only one side did it.
    pub fn str(&self, id: u16) -> Result<String> {
        match self.typed(id, WireType::Str)? {
            None => Ok(String::new()),
            Some(f) => String::from_utf8(f.value.clone()).map_err(|_| {
                CodecError::Malformed(format!("TLV field {id} is not well-formed UTF-8"))
            }),
        }
    }

    /// Read a 16-byte UUID field as `(msb, lsb)`, or zeros when absent.
    pub fn uuid(&self, id: u16) -> Result<(u64, u64)> {
        match self.typed(id, WireType::Bytes)? {
            None => Ok((0, 0)),
            Some(f) if f.value.len() == 16 => {
                let mut msb = [0u8; 8];
                let mut lsb = [0u8; 8];
                msb.copy_from_slice(&f.value[..8]);
                lsb.copy_from_slice(&f.value[8..]);
                Ok((u64::from_be_bytes(msb), u64::from_be_bytes(lsb)))
            }
            Some(f) => Err(CodecError::Malformed(format!(
                "TLV field {id}: a UUID is 16 bytes, got {}",
                f.value.len()
            ))),
        }
    }

    /// Read a packed strictly-ascending `u32` array, or an empty vector when absent.
    ///
    /// # Errors
    /// [`CodecError::Malformed`] when the length is not a multiple of four or the values are not
    /// strictly ascending. The order is a canonicality rule, not a convenience.
    pub fn u32_array(&self, id: u16) -> Result<Vec<u32>> {
        let Some(f) = self.typed(id, WireType::Bytes)? else {
            return Ok(Vec::new());
        };
        if f.value.len() % 4 != 0 {
            return Err(CodecError::Malformed(format!(
                "TLV field {id}: a u32 array must be a multiple of 4 bytes, got {}",
                f.value.len()
            )));
        }
        let mut out = Vec::with_capacity(f.value.len() / 4);
        let mut previous: i64 = -1;
        for chunk in f.value.chunks_exact(4) {
            let v = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
            if i64::from(v) <= previous {
                return Err(CodecError::Malformed(format!(
                    "TLV field {id}: u32 arrays must be strictly ascending; got {previous} then {v}"
                )));
            }
            previous = i64::from(v);
            out.push(v);
        }
        Ok(out)
    }

    /// Read a nested TLV body, or an empty one when absent.
    ///
    /// The caller reads fields out of the result and then calls [`TlvBody::seal_nested`], which is
    /// what carries an unreadable component up to the enclosing body. Preservation has to work at
    /// every depth or it does not work at all: `NodeCapabilities` and `PeerEntry` are exactly the
    /// structures a newer release grows, and they are always nested inside something else.
    pub fn nested(&self, id: u16) -> Result<TlvBody> {
        match self.typed(id, WireType::Nested)? {
            None => Ok(TlvBody::default()),
            Some(f) => TlvBody::parse(&f.value),
        }
    }

    /// Record that a nested body carried something this build could not read, so the whole
    /// enclosing field is re-emitted as received.
    pub fn seal_nested(&self, id: u16, inner: &TlvBody) {
        if !inner.unconsumed().is_empty() {
            self.mark_verbatim(id);
        }
    }

    /// Read a list, decoding each element from its own body.
    pub fn list<T>(
        &self,
        id: u16,
        mut element: impl FnMut(&TlvBody) -> Result<T>,
    ) -> Result<Vec<T>> {
        let Some(f) = self.typed(id, WireType::List)? else {
            return Ok(Vec::new());
        };
        let body = &f.value;
        if body.len() < 4 {
            return Err(CodecError::Malformed(format!(
                "TLV list {id} is missing its count"
            )));
        }
        let count = u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as usize;
        if count > body.len() {
            return Err(CodecError::LengthOverrun {
                claimed: count as u64,
                remaining: body.len(),
            });
        }
        let mut out = Vec::with_capacity(count);
        let mut pos = 4usize;
        for index in 0..count {
            if body.len() - pos < 4 {
                return Err(CodecError::Malformed(format!(
                    "TLV list {id} is truncated at element {index}"
                )));
            }
            let len =
                u32::from_be_bytes([body[pos], body[pos + 1], body[pos + 2], body[pos + 3]]) as usize;
            pos += 4;
            if len > body.len() - pos {
                return Err(CodecError::LengthOverrun {
                    claimed: len as u64,
                    remaining: body.len() - pos,
                });
            }
            let inner = TlvBody::parse(&body[pos..pos + len])?;
            out.push(element(&inner)?);
            if !inner.unconsumed().is_empty() {
                // One element carrying an unreadable component makes the whole list unrepeatable
                // from the decoded value, so the original field is kept verbatim.
                self.mark_verbatim(id);
            }
            pos += len;
        }
        if pos != body.len() {
            return Err(CodecError::TrailingBytes(body.len() - pos));
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_every_scalar() {
        let mut w = TlvWriter::new();
        w.u8(1, 7)
            .u16(2, 0xBEEF)
            .u32(3, 0xDEAD_BEEF)
            .u64(4, u64::MAX)
            .bool(5, true)
            .bytes(6, b"raw")
            .str(7, "héllo")
            .uuid(8, 1, 2);
        let body = TlvBody::parse(&w.finish()).unwrap();

        assert_eq!(body.u8(1, 0).unwrap(), 7);
        assert_eq!(body.u16(2, 0).unwrap(), 0xBEEF);
        assert_eq!(body.u32(3, 0).unwrap(), 0xDEAD_BEEF);
        assert_eq!(body.u64(4, 0).unwrap(), u64::MAX);
        assert!(body.bool(5, false).unwrap());
        assert_eq!(body.bytes(6).unwrap(), b"raw");
        assert_eq!(body.str(7).unwrap(), "héllo");
        assert_eq!(body.uuid(8).unwrap(), (1, 2));
    }

    #[test]
    fn an_absent_field_takes_the_callers_default() {
        let body = TlvBody::parse(&TlvWriter::new().finish()).unwrap();
        assert_eq!(body.u32(1, 42).unwrap(), 42);
        assert_eq!(body.str(2).unwrap(), "");
        assert!(body.bytes(3).unwrap().is_empty());
    }

    #[test]
    fn an_unknown_field_is_skipped_and_kept() {
        // The property the whole plane exists for: a newer peer appends a field, and this build
        // reads everything around it and can hand the field back untouched.
        let mut w = TlvWriter::new();
        w.u32(1, 5).bytes(900, b"from the future").str(1000, "also");
        let body = TlvBody::parse(&w.finish()).unwrap();

        assert_eq!(body.u32(1, 0).unwrap(), 5);
        assert_eq!(body.fields.len(), 3);
        assert_eq!(body.fields[1].id, 900);
        assert_eq!(body.fields[1].value, b"from the future");
    }

    #[test]
    fn descending_or_duplicate_ids_are_refused() {
        let mut raw = TlvWriter::new();
        raw.u32(5, 1);
        let mut body = raw.finish();
        let mut second = TlvWriter::new();
        second.u32(5, 2);
        body.extend_from_slice(&second.finish());
        assert!(matches!(
            TlvBody::parse(&body),
            Err(CodecError::Malformed(_))
        ));
    }

    #[test]
    fn a_boolean_has_exactly_two_spellings() {
        let mut w = TlvWriter::new();
        w.bool(1, true);
        let mut body = w.finish();
        *body.last_mut().unwrap() = 2;
        assert!(matches!(
            TlvBody::parse(&body).unwrap().bool(1, false),
            Err(CodecError::Malformed(_))
        ));
    }

    #[test]
    fn a_truncated_field_is_refused_rather_than_guessed_at() {
        let mut w = TlvWriter::new();
        w.bytes(1, b"0123456789");
        let body = w.finish();
        assert!(TlvBody::parse(&body[..body.len() - 3]).is_err());
    }

    #[test]
    fn lists_round_trip_with_their_own_element_lengths() {
        let mut w = TlvWriter::new();
        w.list(1, &["a", "bb", "ccc"], |e, s| {
            e.str(1, s);
        });
        let body = TlvBody::parse(&w.finish()).unwrap();
        let items: Vec<String> = body.list(1, |e| e.str(1)).unwrap();
        assert_eq!(items, vec!["a", "bb", "ccc"]);
    }

    #[test]
    fn u32_arrays_must_ascend() {
        let mut w = TlvWriter::new();
        w.u32_array(1, &[3, 1, 2]);
        let body = TlvBody::parse(&w.finish()).unwrap();
        assert!(matches!(body.u32_array(1), Err(CodecError::Malformed(_))));
    }
}
