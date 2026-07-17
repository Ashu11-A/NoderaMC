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
    public static final int INTERACT_BLOCK_ACTION = 27;  // Task 13 (reserved now)
    public static final int ATTACK_ENTITY_ACTION = 28;   // Task 15 (reserved now)
    // Discriminator for the sealed GameAction hierarchy is implicit: each action carries its tag.

    // --- state ---
    public static final int SNAPSHOT_VERSION     = 30;
    public static final int STATE_ROOT           = 31;
    public static final int BLOCK_MUTATION       = 32;
    public static final int CHUNK_COLUMN_STATE   = 33;
    public static final int REGION_SNAPSHOT      = 34;
    public static final int REGION_DELTA         = 35;
    public static final int SCHEDULED_TICK_ENTRY = 36;   // Task 13 (reserved now)
    public static final int BLOCK_EVENT_ENTRY    = 37;   // Task 13 (reserved now)

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

    /** Highest assigned tag; new tags start at {@code NEXT + 1}. Update when appending. */
    public static final int NEXT = 55;
}
