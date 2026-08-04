//! Read-only mirror of the two frozen tag registries.
//!
//! * [`type_tags`] mirrors `core/crypto/TypeTags.java` — the tags nested `Encodable` values carry.
//! * [`message_tags`] re-exports the generated [`crate::kinds::message_tags`], rendered from
//!   `protocol/wire/WireRegistry.java` — the outer message frame kinds.
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
    /// `GenesisRecertification` — founding-peer-set multi-party genesis endorsement (L-20).
    pub const GENESIS_RECERTIFICATION: u16 = 104;
    /// `ContainerEntry` — one container's canonical slot contents in the root (Task 16 / L-10).
    pub const CONTAINER_ENTRY: u16 = 105;
    /// `MovePlayerAction` — a signed, committee-validated player step (Task 16 / L-12).
    pub const MOVE_PLAYER_ACTION: u16 = 106;
    /// Persisted set of `WorldPermissionGrant`s for one world (issue #36 F5 persistence).
    pub const WORLD_PERMISSION_SET: u16 = 107;
    /// `CommandAction` — one command from the deterministic command subset (Task 16 / L-14).
    pub const COMMAND_ACTION: u16 = 108;

    /// `ScheduledTickEntry` — Task 13 hashed scheduled-tick queue entry (reserved 36, live 2026-07-23).
    pub const SCHEDULED_TICK_ENTRY: u16 = 36;
    /// `BlockEventEntry` — Task 13 hashed piston two-phase event entry (reserved 37, live 2026-07-23).
    pub const BLOCK_EVENT_ENTRY: u16 = 37;

    /// `WorldOwnership` — a world's public key bound to the peer that created it, doubly signed.
    pub const WORLD_OWNERSHIP: u16 = 109;
    /// `PersistedWorldKey` — a world signing key's on-disk form. Secret; never on the wire.
    pub const WORLD_KEY_SECRET: u16 = 110;
    /// `WorldAdminProof` — a challenge signed by a world's private key.
    pub const WORLD_ADMIN_PROOF: u16 = 111;
    /// `WorldRegistry` — the worlds a peer shares or supports, as persisted by the worker.
    pub const WORLD_REGISTRY: u16 = 112;
    /// `WorldShareLink` — an invitation to one world: its id, where to look, who administers it.
    pub const WORLD_SHARE_LINK: u16 = 113;
    /// `WorldTombstone` — the owner's signed request that the network forget a world, carrying its
    /// own ownership claim so any receiver can verify it with no prior knowledge of the world.
    pub const WORLD_TOMBSTONE: u16 = 114;

    // --- service directory (tracker 5 / rendezvous 5 / network 13) ---
    /// `ServiceRecord` — a rendezvous's or tracker's own signed self-description.
    pub const SERVICE_RECORD: u16 = 115;
    /// `ServiceScore` — a tracker's aggregate opinion of one service; components, not a verdict.
    pub const SERVICE_SCORE: u16 = 116;
    /// `ServiceObservation` — one peer's probe counters and RTT percentiles for one service.
    pub const SERVICE_OBSERVATION: u16 = 117;
    /// `ServiceDirectoryEntry` — one directory row: signed record + the answering tracker's score.
    pub const SERVICE_DIRECTORY_ENTRY: u16 = 118;

    /// `HaloEndorsement` — a committee member's signature over the halo slice its region
    /// published, so a neighbour requires a quorum of the source committee before executing
    /// against it (engine L-2; Java engine only).
    pub const HALO_ENDORSEMENT: u16 = 119;

    /// `WorldRevival` — the owner putting back a world they deleted, carrying the same ownership
    /// evidence as the tombstone it supersedes. Ranked against that tombstone by issue time, so
    /// neither record can be replayed to undo the other.
    pub const WORLD_REVIVAL: u16 = 120;

    /// Highest tag assigned on the Java side; new tags start at `NEXT + 1`.
    pub const NEXT: u16 = 120;
}

/// Message frame kinds — re-exported from the generated schema.
///
/// This module used to be a hand-copied subset of the Java registry, which is how it could hold 24
/// of the 72 kinds and still look complete: nothing in the file said what was missing. It is now
/// [`crate::kinds::message_tags`], rendered from `WireRegistry.java`, so the two registries agree
/// by construction rather than by anyone remembering.
pub use crate::kinds::message_tags;

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
    message_tags::SERVICE_ANNOUNCE,
    message_tags::SERVICE_ANNOUNCE_ACK,
    message_tags::SERVICE_DIRECTORY_QUERY,
    message_tags::SERVICE_DIRECTORY_RESPONSE,
    message_tags::SERVICE_SCORE_REPORT,
    message_tags::SERVICE_DRAIN_NOTICE,
];
