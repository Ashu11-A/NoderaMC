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

    // --- rendezvous / relay (Task 29) ---
    /// `PeerCandidate` — one reachability candidate (host / reflexive / relay …).
    pub const PEER_CANDIDATE: u16 = 90;
    /// `SignedPeerRecord` — the canonical, Ed25519-signed rendezvous registration body.
    pub const SIGNED_PEER_RECORD: u16 = 91;

    // --- world identity + permissions (Task 33; Java-side records, mirrored for registry parity) ---
    /// `WorldIdentity` — the author-signed per-world record (unique id + author + share state).
    pub const WORLD_IDENTITY: u16 = 92;
    /// `WorldPermissionGrant` — an author/operator-signed role grant for a world.
    pub const WORLD_PERMISSION_GRANT: u16 = 93;
    /// `EntityMutation` — compare-and-set entity-table transition (Java engine only).
    pub const ENTITY_MUTATION: u16 = 94;
    /// `InventoryCredit` — replay-safe one-way player inventory effect (Java engine only).
    pub const INVENTORY_CREDIT: u16 = 95;
    /// `EntityTransferCertificate` — binds two Java region transitions.
    pub const ENTITY_TRANSFER_CERT: u16 = 96;
    /// `EntityTransferPreparedEvent` — Java region-log event.
    pub const ENTITY_TRANSFER_PREPARED_EVENT: u16 = 97;
    /// `EntityTransferCommittedEvent` — Java region-log event.
    pub const ENTITY_TRANSFER_COMMITTED_EVENT: u16 = 98;
    /// `EntityTransferIntent` — Java engine border-handoff effect.
    pub const ENTITY_TRANSFER_INTENT: u16 = 99;
    /// `EntityTransferDescriptor` — jointly approved source/target transition descriptor.
    pub const ENTITY_TRANSFER_DESCRIPTOR: u16 = 100;
    /// `EntityTransferAcceptedEvent` — Java region-log acceptance marker.
    pub const ENTITY_TRANSFER_ACCEPTED_EVENT: u16 = 101;
    /// `EntityTransferRecord` — Java durable transfer stage record.
    pub const ENTITY_TRANSFER_RECORD: u16 = 102;
    /// `CertifiedWorldGenesis` — genesis extracted from an existing world, self-certified (Task 30c).
    pub const CERTIFIED_WORLD_GENESIS: u16 = 103;

    /// Highest tag assigned on the Java side; new tags start at `NEXT + 1`.
    pub const NEXT: u16 = 103;
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

    // --- rendezvous / relay (Task 29) ---
    /// `RendezvousRegister` — a signed self-registration in a `(network, world)` namespace.
    pub const RENDEZVOUS_REGISTER: u16 = 35;
    /// `RendezvousDiscover` — a namespace query with a cursor + page limit.
    pub const RENDEZVOUS_DISCOVER: u16 = 36;
    /// `RendezvousPeers` — a page of signed peer records.
    pub const RENDEZVOUS_PEERS: u16 = 37;
    /// `RelayReserve` — a peer reserves an inbound relay slot.
    pub const RELAY_RESERVE: u16 = 38;
    /// `RelayReservation` — the relay's answer: route, expiry, limits, HMAC proof.
    pub const RELAY_RESERVATION: u16 = 39;
    /// `RelayConnect` — a peer asks to be bridged to a target's reserved slot.
    pub const RELAY_CONNECT: u16 = 40;
    /// `RelayIncoming` — the relay tells the reserver a circuit is inbound.
    pub const RELAY_INCOMING: u16 = 41;
    /// `PunchSync` — relayed observed-address exchange + a synchronized go-signal.
    pub const PUNCH_SYNC: u16 = 42;
    /// `ObservedAddress` — the relay reports a caller's reflexive address (STUN-ish).
    pub const OBSERVED_ADDRESS: u16 = 43;
    /// `TrackerCatalogQuery` — list every listed world (tracker directory / browse).
    pub const TRACKER_CATALOG_QUERY: u16 = 44;
    /// `TrackerCatalogResponse` — the directory listing answer.
    pub const TRACKER_CATALOG_RESPONSE: u16 = 45;
    /// `EntityTransferPrepare` — Java committee transfer prepare.
    pub const ENTITY_TRANSFER_PREPARE: u16 = 46;
    /// `EntityTransferAccept` — Java committee transfer acceptance.
    pub const ENTITY_TRANSFER_ACCEPT: u16 = 47;
    /// `EntityTransferCommit` — Java paired transfer commit.
    pub const ENTITY_TRANSFER_COMMIT: u16 = 48;
    /// `TrackerRoutesQuery` — full claimed dial-route lists of a world's live peers (join flow).
    pub const TRACKER_ROUTES_QUERY: u16 = 49;
    /// `TrackerRoutesResponse` — the per-peer route-list answer.
    pub const TRACKER_ROUTES_RESPONSE: u16 = 50;
    /// `WorldManifestQuery` — Java peer↔peer manifest fetch (world-continuity lane).
    pub const WORLD_MANIFEST_QUERY: u16 = 51;
    /// `WorldManifestAnswer` — the seeder's manifest list (world-continuity lane).
    pub const WORLD_MANIFEST_ANSWER: u16 = 52;
    /// `ActionForward` — Java no-host submission: route an action to its region's primary.
    pub const ACTION_FORWARD: u16 = 53;
    /// `EventSyncQuery` — Java forward event-sync request (Task 9 / L-30).
    pub const EVENT_SYNC_QUERY: u16 = 54;
    /// `EventSyncAnswer` — the serving peer's certified events since the requested id.
    pub const EVENT_SYNC_ANSWER: u16 = 55;

    /// Highest tag assigned on the Java side; new tags start at `NEXT_TAG + 1`.
    pub const NEXT_TAG: u16 = 55;
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
    message_tags::RENDEZVOUS_REGISTER,
    message_tags::RENDEZVOUS_DISCOVER,
    message_tags::RENDEZVOUS_PEERS,
    message_tags::RELAY_RESERVE,
    message_tags::RELAY_RESERVATION,
    message_tags::RELAY_CONNECT,
    message_tags::RELAY_INCOMING,
    message_tags::PUNCH_SYNC,
    message_tags::OBSERVED_ADDRESS,
    message_tags::TRACKER_CATALOG_QUERY,
    message_tags::TRACKER_CATALOG_RESPONSE,
    message_tags::TRACKER_ROUTES_QUERY,
    message_tags::TRACKER_ROUTES_RESPONSE,
];
