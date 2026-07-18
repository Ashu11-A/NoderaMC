package dev.nodera.peer.archival;

/**
 * What kind of canonical content a manifest describes (Task 21). The replication factor depends on
 * it: a current snapshot is replicated ×5; a compacted history log only ×3; a checkpoint or the
 * genesis manifest is replicated to <i>everyone</i>.
 *
 * <p>The class is supplied by the caller (the archive manager / audit) that produced or fetched the
 * content — it is deliberately <b>not</b> a field on {@code PieceManifest} (Task 19), so Task 19's
 * frozen manifest encoding stays untouched and the per-class factor table lives entirely here, in
 * the archival layer that owns the policy.
 *
 * <p>Ordinal order is a frozen contract: the {@link ReplicationFactors} table indexes by it.
 *
 * <p>Thread-context: immutable enum, any thread.
 */
public enum ArchiveObjectClass {
    /** A current region snapshot — the freshest, most-replicated object. */
    SNAPSHOT,
    /** A recent event-log segment — high replication for fast recovery. */
    RECENT_LOG,
    /** A compacted historical log segment — cheaper replication for old state. */
    COMPACTED_LOG,
    /** A certified checkpoint — replicated to every peer that holds any of the world. */
    CHECKPOINT,
    /** The genesis manifest — the world's identity; everyone holds it. */
    GENESIS
}
