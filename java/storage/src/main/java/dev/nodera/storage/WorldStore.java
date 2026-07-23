package dev.nodera.storage;

/**
 * The aggregate event-sourced world store (Plan §4 / Task 9): the genesis manifest plus the four
 * sub-stores. The canonical world state is {@code genesis + per-region event logs + checkpoints +
 * certificates + content blobs}; this interface is the seam an archival peer (in-memory now, RocksDB
 * later) implements, and the seam the new-peer sync flow reads.
 *
 * @Thread-context implementations document their own thread-safety.
 */
public interface WorldStore {

    /** @return the world's genesis manifest. */
    GenesisManifest genesis();

    /** @return the content-addressed blob store. */
    ContentStore content();

    /** @return the append-only per-region event logs. */
    RegionEventStore events();

    /** @return the per-region checkpoint index. */
    CheckpointStore checkpoints();

    /** @return the quorum-certificate store. */
    CertificateStore certificates();

    /** @return durable cross-region transfer stages for crash recovery. */
    TransferStore transfers();
}
