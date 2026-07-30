/**
 * Nodera storage API (Plan §4, Task 9): the {@link dev.nodera.storage.WorldStore} seam and its
 * content/event/checkpoint/certificate sub-stores, Minecraft-free.
 *
 * <p>The canonical world state is event-sourced: a {@link dev.nodera.storage.GenesisManifest} plus
 * per-region append-only {@link dev.nodera.storage.RegionEventStore event logs}, bounded by
 * {@link dev.nodera.storage.CheckpointStore checkpoints}, each event referencing the
 * {@link dev.nodera.storage.CertificateStore quorum certificate} that finalised it, with snapshots
 * and log segments held content-addressed in a {@link dev.nodera.storage.ContentStore}. No process
 * may declare its local state canonical without the certified log (Invariant 3); a new or returning
 * peer synchronises <b>forward</b> from the network (Invariant 8).
 *
 * <p>Since the Java API unification (issue #30) every tier lives in this one module: the
 * in-memory event-sourced store ({@link dev.nodera.storage.event}), the durable RocksDB +
 * content-addressed-blob archive ({@link dev.nodera.storage.rocksdb}), and the bounded client
 * store ({@link dev.nodera.storage.client}). Shared support lives beside the seam:
 * {@link dev.nodera.storage.EventChainGuard}, {@link dev.nodera.storage.RegionOrder}, and
 * {@link dev.nodera.storage.io.AtomicFileWriter}.
 */
package dev.nodera.storage;
