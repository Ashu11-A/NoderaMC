//! Read-only mirror of the two frozen tag registries.
//!
//! * [`type_tags`] mirrors `core/crypto/TypeTags.java` — the tags nested `Encodable` values carry.
//! * [`message_tags`] mirrors `protocol/codec/MessageCodec.java` — the outer message frame tags.
//!
//! Both registries are **append-only**: assigning a number is permanent and renumbering is a
//! network-breaking change. `tests/tag_mirror.rs` parses the Java sources and fails the build if
//! either side gains a tag the other does not have, so the two implementations cannot drift.

/// Nested-value tags (`dev.nodera.core.crypto.TypeTags`).
pub mod type_tags {
    // --- identity ---
    /// `NodeId`.
    pub const NODE_ID: u16 = 1;
    /// `NodeCapabilities`.
    pub const NODE_CAPABILITIES: u16 = 2;
    /// `PeerRole`.
    pub const PEER_ROLE: u16 = 3;

    // --- tracker / discovery (Task 20) ---
    /// `WorldHealth`.
    pub const WORLD_HEALTH: u16 = 73;

    /// Highest tag assigned on the Java side; new tags start at `NEXT + 1`.
    pub const NEXT: u16 = 89;
}

/// Message frame tags (`dev.nodera.protocol.codec.MessageCodec`).
pub mod message_tags {
    /// `TrackerQuery` (Task 20).
    pub const TRACKER_QUERY: u16 = 27;
    /// `TrackerResponse` (Task 20).
    pub const TRACKER_RESPONSE: u16 = 28;
    /// `InventoryAdvertisement` (Task 20).
    pub const INVENTORY_ADVERTISEMENT: u16 = 29;
    /// `TrackerAnnounce` (Task 28).
    pub const TRACKER_ANNOUNCE: u16 = 33;
    /// `TrackerAnnounceAck` (Task 28).
    pub const TRACKER_ANNOUNCE_ACK: u16 = 34;

    /// Highest tag assigned on the Java side; new tags start at `NEXT_TAG + 1`.
    pub const NEXT_TAG: u16 = 34;
}

/// The message tags this crate can decode today.
///
/// Deliberately a subset of the Java registry: the services only ever handle discovery traffic
/// (Task 0 §4 rule 7 — no game, consensus, or storage logic on the Rust side). The mirror test
/// asserts these numbers agree with Java, not that the sets are equal.
pub const SUPPORTED_MESSAGE_TAGS: &[u16] = &[
    message_tags::TRACKER_QUERY,
    message_tags::TRACKER_RESPONSE,
    message_tags::INVENTORY_ADVERTISEMENT,
    message_tags::TRACKER_ANNOUNCE,
    message_tags::TRACKER_ANNOUNCE_ACK,
];
