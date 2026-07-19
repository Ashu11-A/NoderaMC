package dev.nodera.core;

/**
 * Nodera shared constants (Task 0 §5). Defined once in {@code core}; surfaced through config in
 * the NeoForge module, read from config in production code, read from these constants in tests.
 *
 * <p>All values are {@code public static final} defaults. Nothing here is a magic number that
 * should be inlined elsewhere.
 */
public final class NoderaConstants {

    private NoderaConstants() {}

    // --- Region geometry (Plan §3.2) ---
    /** 8×8 chunks per region (64 chunks = 128×128 blocks). Static grid, unlike Folia's merge/split. */
    public static final int REGION_SIZE_CHUNKS = 8;
    /** Read-only ring around each region (Plan §3 / "halo"). */
    public static final int HALO_CHUNKS = 1;
    /** Total chunk span of a region plus its halo on one axis (owned + 2·halo). */
    public static final int REGION_SPAN_WITH_HALO = REGION_SIZE_CHUNKS + 2 * HALO_CHUNKS;

    // --- Batch / execution budget (Plan §3.6) ---
    public static final int BATCH_TICKS = 2;
    public static final int BATCH_MAX_MILLIS = 100;
    public static final int CHECKPOINT_INTERVAL_TICKS = 100;

    // --- Leases & heartbeats (Plan §3.4) ---
    public static final int LEASE_LENGTH_TICKS = 200;
    public static final int LEASE_RENEW_TICKS = 40;
    public static final int HEARTBEAT_TICKS = 20;

    // --- Quorum (Plan §3.3) ---
    /** MVP: 1 primary + 2 validators, commit on 2 matching of 3 (Tasks 6–8). */
    public static final String QUORUM_MVP = "2 of 3";
    public static final int QUORUM_MVP_SIZE = 3;
    public static final int QUORUM_MVP_REQUIRED = 2;
    /** Peer era: committee of 4, commit on 3 of 4 (Task 9+). */
    public static final String QUORUM_PEER = "3 of 4";
    public static final int QUORUM_PEER_SIZE = 4;
    public static final int QUORUM_PEER_REQUIRED = 3;

    // --- Crypto (Plan §3.7) ---
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String SIGNATURE_ALGORITHM = "Ed25519";
    public static final String KEYPAIR_ALGORITHM = "Ed25519";
    /** State roots are 32 bytes (SHA-256). */
    public static final int STATE_ROOT_BYTES = 32;
    /** Password KDF salts are generated at 128 bits; decoders reject shorter or oversized salts. */
    public static final int PASSWORD_KDF_SALT_BYTES = 16;
    public static final int PASSWORD_KDF_MAX_SALT_BYTES = 64;
    /** Bounds password-derived work before allocating or invoking a KDF on remote metadata. */
    public static final int PASSWORD_KDF_MAX_PASSWORD_CHARS = 1024;
    public static final int PBKDF2_MIN_ITERATIONS = 100_000;
    public static final int PBKDF2_DEFAULT_ITERATIONS = 600_000;
    public static final int PBKDF2_MAX_ITERATIONS = 10_000_000;
    public static final int ARGON2_MIN_MEMORY_KIB = 16 * 1024;
    public static final int ARGON2_DEFAULT_MEMORY_KIB = 32 * 1024;
    public static final int ARGON2_MAX_MEMORY_KIB = 256 * 1024;
    public static final int ARGON2_MIN_ITERATIONS = 2;
    public static final int ARGON2_DEFAULT_ITERATIONS = 3;
    public static final int ARGON2_MAX_ITERATIONS = 10;
    public static final int ARGON2_MIN_PARALLELISM = 1;
    public static final int ARGON2_DEFAULT_PARALLELISM = 1;
    public static final int ARGON2_MAX_PARALLELISM = 16;

    // --- Delegability / interference (Task 11 defaults) ---
    /** Regions within this ring must be palette-compatible. */
    public static final int DELEGABLE_NEIGHBOR_RING = 1;
    /** Entity presence ⇒ non-delegable until Task 12 narrows it. */
    public static final boolean ENTITY_EXCLUSION = true;
    /** Hysteresis against delegable/non-delegable flapping. */
    public static final int DELEGABILITY_COOLDOWN_TICKS = 200;
    /** Foreign-mutation rate (per minute) that demotes a region. */
    public static final int INTERFERENCE_REVOKE_RATE = 60;
    /** The window {@link #INTERFERENCE_REVOKE_RATE} is measured over: one minute at 20 TPS. */
    public static final int INTERFERENCE_RATE_WINDOW_TICKS = 1200;

    // --- Reliability score (Plan §10) ---
    public static final double RELIABILITY_EMA_ALPHA = 0.02; // score ← 0.98·score + 0.02·outcome
    public static final double RELIABILITY_ASSIGNMENT_FLOOR = 0.95;
    public static final double RELIABILITY_OFFLINE_DECAY_TARGET = 0.5;

    // --- Transport caps (Plan §3.10 / A-4) ---
    /** Safe chunk size under the &lt;32 KiB serverbound cap with frame overhead. */
    public static final int MAX_STREAM_CHUNK = 24 * 1024;
    public static final int NEOFORGE_CLIENTBOUND_CAP = 1 << 20; // 1 MiB
    public static final int NEOFORGE_SERVERBOUND_CAP = 32 * 1024; // < 32 KiB
    /**
     * Absolute upper bound on a reassembled logical stream payload (64 MiB). Used to bound the
     * decompression output buffer in {@code ChunkedStreams.join}: the declared/​frame-embedded
     * original length is rejected before allocation if it exceeds this cap, so a small malformed or
     * maliciously-crafted highly-compressible frame cannot force an unbounded allocation
     * (memory-amplification DoS guard). A zstd ratio bound is NOT usable here — zstd's ratio is
     * effectively unbounded for repetitive input (an 8 MiB constant blob compresses to ~1 KiB).
     */
    public static final int MAX_STREAM_PAYLOAD = 64 * 1024 * 1024;
}
