package dev.nodera.core.crypto;

/**
 * Append-only registry of canonical type tags (Task 2 §4: every top-level {@link Encodable}
 * starts with a {@code u16 typeTag}). These numbers are a frozen wire/hash contract: assigning a
 * tag is permanent; <b>never renumber an existing tag</b> — append only.
 *
 * <p>The registry is owned here (in {@code core}) so every module agrees on the same numbers.
 * When a later task adds an {@code Encodable}, append a new constant with the next free id.
 */
public final class TypeTags {

    private TypeTags() {}

    // --- identity ---
    public static final int NODE_ID              = 1;
    public static final int NODE_CAPABILITIES    = 2;
    public static final int PEER_ROLE            = 3;

    // --- region ---
    public static final int DIMENSION_KEY        = 10;
    public static final int REGION_ID            = 11;
    public static final int REGION_EPOCH         = 12;
    public static final int REGION_BOUNDS        = 13;
    public static final int REGION_REPLICA_ROLE  = 14;
    public static final int REGION_LEASE         = 15;
    public static final int REGION_COMMITTEE     = 16;
    public static final int REGION_PLACEMENT_POL = 17;

    // --- action ---
    public static final int N_BLOCK_POS          = 20;
    public static final int ACTION_ENVELOPE      = 21;
    public static final int ACTION_BATCH         = 22;
    public static final int PLACE_BLOCK_ACTION   = 23;
    public static final int BREAK_BLOCK_ACTION   = 24;
    public static final int DROP_ITEM_ACTION     = 25;   // Task 12 (reserved now)
    public static final int PICKUP_ITEM_ACTION   = 26;   // Task 12 (reserved now)
    public static final int INTERACT_BLOCK_ACTION = 27;  // Task 13 (live since 2026-07-23)
    public static final int ATTACK_ENTITY_ACTION = 28;   // Task 16 (live since 2026-07-24)
    public static final int CONTAINER_ACTION     = 29;   // Task 16 (live since 2026-07-24)
    // Discriminator for the sealed GameAction hierarchy is implicit: each action carries its tag.

    // --- state ---
    public static final int SNAPSHOT_VERSION     = 30;
    public static final int STATE_ROOT           = 31;
    public static final int BLOCK_MUTATION       = 32;
    public static final int CHUNK_COLUMN_STATE   = 33;
    public static final int REGION_SNAPSHOT      = 34;
    public static final int REGION_DELTA         = 35;
    public static final int SCHEDULED_TICK_ENTRY = 36;   // Task 13 (live since 2026-07-23)
    public static final int BLOCK_EVENT_ENTRY    = 37;   // Task 13 (live since 2026-07-23)

    // --- events ---
    public static final int COMMITTED_EVENT_ENV  = 40;
    public static final int BLOCK_CHANGED_EVENT  = 41;

    // --- consensus certificates ---
    public static final int SIGNED_VOTE          = 50;
    public static final int VOTE_DECISION        = 51;
    public static final int QUORUM_CERTIFICATE   = 52;
    public static final int COMMITTEE_CHANGE_CERT = 53;  // Task 9 (reserved now)
    public static final int SERVER_AUTH_CERT     = 54;   // Task 11 (reserved now)
    public static final int GATEWAY_TRANSFER_CERT = 55;  // Task 10 (reserved now)

    // --- coordinator persistence (Task 6) ---
    public static final int RELIABILITY_LEDGER   = 60;
    public static final int COORDINATOR_STATE    = 61;

    // --- torrent distribution data plane (Task 19) ---
    /** One addressable sub-region piece of a content blob. */
    public static final int PIECE                = 70;
    /** The piece-hash list binding a region's blob to its {@code StateRoot}. */
    public static final int PIECE_MANIFEST       = 71;
    /** Per-world KDF parameters carried by an encrypted manifest (Task 23 fills the key path). */
    public static final int WORLD_KEY_MATERIAL   = 72;

    // --- tracker / discovery (Task 20) ---
    /** Per-world health class read by the tracker and the multiplayer UI. */
    public static final int WORLD_HEALTH         = 73;
    /** Persisted node identity (private material) — never a wire type; disk only. */
    public static final int NODE_IDENTITY_SECRET = 74;
    /** A signed invitation blob a friend pastes to join a world. */
    public static final int INVITATION           = 75;
    /** One remembered peer address in the on-disk cached-peer store. */
    public static final int CACHED_PEER          = 76;

    // --- archive replication / repair (Task 21) ---
    /** A repair coordinator assigns a peer to re-replicate specific pieces. */
    public static final int ARCHIVE_REPLICA_ASSIGNMENT = 77;
    /** Acknowledgement that an assigned peer now holds the requested pieces. */
    public static final int ARCHIVE_REPLICA_ACK        = 78;
    /** Per-node multi-factor reliability signals, each in basis points (Task 22). */
    public static final int RELIABILITY_FACTORS        = 79;
    /** An AES-GCM ciphertext piece (nonce + ciphertext + auth tag), Task 23. */
    public static final int ENCRYPTED_PIECE            = 80;

    // --- event-sourced storage persistence (Task 9) ---
    /** A content-addressed blob id: hash + size + compression. */
    public static final int CONTENT_ID                 = 81;
    /** A finalised region checkpoint (version + root + snapshot content + certificate ref). */
    public static final int CHECKPOINT                 = 82;
    /** The world's genesis manifest (seed, rules version, registry fingerprint, genesis root). */
    public static final int GENESIS_MANIFEST           = 83;

    // --- entity lane foundation (Task 12a) ---
    /** A Q32.32 fixed-point 3-vector (entity position/velocity in hashed state). */
    public static final int FIXED_VEC3                 = 84;
    /** A deterministic, region-scoped entity id (StableHash-derived, not a random UUID). */
    public static final int NETWORK_ENTITY_ID          = 85;
    /** The canonical persisted state of one tracked entity (kind/type/pos/vel/age/payload). */
    public static final int PERSISTED_ENTITY_STATE     = 86;
    /** RegionEvent: an entity entered the validated entity table. */
    public static final int ENTITY_CREATED_EVENT       = 87;
    /** RegionEvent: an entity's persisted state changed. */
    public static final int ENTITY_UPDATED_EVENT       = 88;
    /** RegionEvent: an entity left the validated entity table. */
    public static final int ENTITY_REMOVED_EVENT       = 89;

    // --- rendezvous / relay (Task 29) ---
    /** One reachability candidate (host / public / server-reflexive / mapped / relay). */
    public static final int PEER_CANDIDATE             = 90;
    /** The canonical, Ed25519-signed rendezvous registration body. */
    public static final int SIGNED_PEER_RECORD         = 91;

    // --- world identity + permissions (Task 33) ---
    /** {@code WorldIdentity} — the author-signed per-world record (unique id + author + share state). */
    public static final int WORLD_IDENTITY             = 92;
    /** {@code WorldPermissionGrant} — an author/operator-signed role grant for a world. */
    public static final int WORLD_PERMISSION_GRANT     = 93;

    // --- entity state transitions (Task 12a) ---
    /** Compare-and-set mutation of one entity-table row. */
    public static final int ENTITY_MUTATION            = 94;
    /** Replay-safe one-way credit into a player's vanilla inventory. */
    public static final int INVENTORY_CREDIT           = 95;
    /** One certificate binding both halves of an atomic cross-region entity transfer. */
    public static final int ENTITY_TRANSFER_CERT       = 96;
    /** RegionEvent: one side durably prepared an entity transfer. */
    public static final int ENTITY_TRANSFER_PREPARED_EVENT = 97;
    /** RegionEvent: both sides atomically committed an entity transfer. */
    public static final int ENTITY_TRANSFER_COMMITTED_EVENT = 98;
    /** Engine-emitted border crossing to be completed by the atomic transfer coordinator. */
    public static final int ENTITY_TRANSFER_INTENT     = 99;
    /** Jointly approved source/target transition descriptor for one transfer. */
    public static final int ENTITY_TRANSFER_DESCRIPTOR = 100;
    /** RegionEvent: both committees accepted one transfer descriptor. */
    public static final int ENTITY_TRANSFER_ACCEPTED_EVENT = 101;
    /** Durable restart record for the cross-region transfer state machine. */
    public static final int ENTITY_TRANSFER_RECORD = 102;
    /** Genesis manifest extracted from an existing world, self-certified by its host (Task 30c). */
    public static final int CERTIFIED_WORLD_GENESIS = 103;

    /** Task 16 / L-20: the founding peer set's multi-party genesis re-certification. */
    public static final int GENESIS_RECERTIFICATION = 104;

    /** {@code ContainerEntry} — one container's canonical slot contents in the root (Task 16 / L-10). */
    public static final int CONTAINER_ENTRY = 105;

    /** {@code MovePlayerAction} — a signed, committee-validated player step (Task 16 / L-12). */
    public static final int MOVE_PLAYER_ACTION = 106;

    /** Persisted set of {@code WorldPermissionGrant}s for one world (issue #36 F5 persistence). */
    public static final int WORLD_PERMISSION_SET = 107;

    /** One command from the deterministic command subset (Task 16 / L-14). */
    public static final int COMMAND_ACTION = 108;

    /**
     * {@code WorldOwnership} — a world's own public key bound to the peer that created it, signed by
     * both halves. The record the network reads to answer "who administers this world".
     */
    public static final int WORLD_OWNERSHIP = 109;

    /**
     * {@code PersistedWorldKey} — the world signing key's on-disk form. <b>Secret material</b>: it
     * exists only on the creating peer's machine and is never a network message.
     */
    public static final int WORLD_KEY_SECRET = 110;

    /** {@code WorldAdminProof} — a challenge signed by a world's private key (proof of authority). */
    public static final int WORLD_ADMIN_PROOF = 111;

    /** {@code WorldRegistry} — the worlds a peer shares or supports, as persisted by the worker. */
    public static final int WORLD_REGISTRY = 112;

    /**
     * {@code WorldShareLink} — an invitation to one world: its id, where to look for it, and who
     * administers it. The file form of what a magnet link carries as text. No content, no secret.
     */
    public static final int WORLD_SHARE_LINK = 113;

    /**
     * {@code WorldTombstone} — the owner's signed request that the network forget a world, carrying
     * its own ownership claim so any receiver can verify it without prior knowledge.
     */
    public static final int WORLD_TOMBSTONE = 114;

    /**
     * {@code ServiceRecord} — a rendezvous's or tracker's own Ed25519-signed self-description:
     * where to dial it, what version it runs, how loaded it is, and whether it is draining.
     */
    public static final int SERVICE_RECORD = 115;

    /**
     * {@code ServiceScore} — a tracker's aggregate opinion of one service, as components (measured
     * availability, RTT percentiles, free capacity, heartbeat freshness) plus a derived composite a
     * peer recomputes rather than trusts.
     */
    public static final int SERVICE_SCORE = 116;

    /**
     * {@code ServiceObservation} — one peer's probe counters and RTT percentiles for one service
     * over a window. Counters, never a verdict, so a tracker aggregates evidence instead of
     * trusting one peer's judgement.
     */
    public static final int SERVICE_OBSERVATION = 117;

    /**
     * {@code ServiceDirectoryEntry} — one directory row: a signed {@code ServiceRecord} plus the
     * answering tracker's {@code ServiceScore}. The signature travels with the row so a peer
     * verifies the service's identity without trusting the tracker that listed it.
     */
    public static final int SERVICE_DIRECTORY_ENTRY = 118;

    /**
     * {@code HaloEndorsement} — one committee member's signature over the edge slice its region
     * published, so a neighbour can require a quorum of the source committee before executing
     * against it instead of trusting whoever delivered the bytes (engine L-2).
     */
    public static final int HALO_ENDORSEMENT = 119;

    /**
     * {@code WorldRevival} — the owner putting back a world they deleted, carrying the same
     * ownership evidence as the tombstone it supersedes. Without it a deleted world id could never
     * be shared again, because the id is derived from the save and every node remembers the
     * deletion.
     */
    public static final int WORLD_REVIVAL = 120;

    /** Highest assigned tag; new tags start at {@code NEXT + 1}. Update when appending. */
    public static final int NEXT = 120;
}
