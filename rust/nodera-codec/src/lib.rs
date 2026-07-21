//! `nodera-codec` — byte-exact Rust port of Nodera's frozen canonical encoding.
//!
//! The canonical encoding is a consensus contract (Task 0 §4, Plan §3.7): the same bytes are used
//! on the wire, for hashing, and for signing. A second implementation is only acceptable when
//! conformance is proven mechanically, so this crate is verified two ways:
//!
//! * the tag registries ([`tags`]) are asserted against the Java sources in CI
//!   (`tests/tag_mirror.rs`), so appending a tag on one side without the other fails the build;
//! * every message round-trips byte-exactly against the golden files the Java side emits into
//!   `fixtures/` (`tests/fixtures.rs`).
//!
//! Scope discipline (Task 0 §4 rule 7): this crate — and the services built on it — carry no game,
//! consensus, or storage logic. They decode, verify signatures, and forward.

pub mod framing;
pub mod messages;
pub mod reader;
pub mod rendezvous;
pub mod sig;
pub mod tags;
pub mod types;
pub mod writer;

pub use reader::CanonicalReader;
pub use writer::CanonicalWriter;

/// Every canonical frame starts with `u16 typeTag; u16 ENCODING_VERSION`.
///
/// Mirrors `Encodable.ENCODING_VERSION` / `MessageCodec.ENCODING_VERSION` on the Java side.
pub const ENCODING_VERSION: u16 = 1;

/// Version emitted for tag 23 (`SessionKeepAlive`); its decoder also accepts legacy version 1.
pub const SESSION_KEEP_ALIVE_ENCODING_VERSION: u16 = 2;

/// Anything that can go wrong decoding canonical bytes.
///
/// Decoding is always fallible and never panics: these bytes arrive from the network pre-auth, so
/// a hostile frame must produce an error, not an abort or an oversized allocation.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CodecError {
    /// Input ended before the decoder had read everything the frame promised.
    #[error("unexpected end of canonical input: needed {needed} byte(s), {remaining} remaining")]
    UnexpectedEof {
        /// Bytes the decoder needed.
        needed: usize,
        /// Bytes actually left in the frame.
        remaining: usize,
    },
    /// A `u32` length/count prefix claimed more than the frame can hold (allocation guard —
    /// mirrors the same check in Java's `CanonicalReader`).
    #[error("canonical length prefix {claimed} exceeds remaining {remaining} byte(s)")]
    LengthOverrun {
        /// The length the frame claimed.
        claimed: u64,
        /// Bytes actually left in the frame.
        remaining: usize,
    },
    /// A nested `Encodable` did not start with the tag the decoder expected.
    #[error("expected type tag {expected}, got {actual}")]
    UnexpectedTag {
        /// Tag the decoder required.
        expected: u16,
        /// Tag actually present.
        actual: u16,
    },
    /// The frame's encoding version is not one this build understands.
    #[error("unsupported encoding version {version} for tag {tag}")]
    UnsupportedVersion {
        /// Tag being decoded.
        tag: u16,
        /// Version found on the wire.
        version: u16,
    },
    /// The message type tag is not in the registry.
    #[error("unknown message type tag {0}")]
    UnknownTag(u16),
    /// A value was structurally well-formed but semantically invalid (bad enum ordinal, invalid
    /// UTF-8, out-of-range field).
    #[error("malformed canonical value: {0}")]
    Malformed(String),
    /// The frame decoded successfully but bytes were left over. Two distinct byte strings must
    /// never decode to the same message — that would break sign-over-canonical-bytes.
    #[error("frame has {0} unconsumed trailing byte(s)")]
    TrailingBytes(usize),
}

/// Result alias for decode paths.
pub type Result<T> = core::result::Result<T, CodecError>;
