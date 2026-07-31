/**
 * The durable full-archive storage tier (Task 9, Phase 5): {@code RocksWorldStore} implements the
 * {@code dev.nodera.storage} seam over RocksDB column families with WAL-backed atomic appends,
 * keeping its blobs in a {@link dev.nodera.storage.fs.FsContentStore}. Heads are recovered from
 * the log tail on open, so a forcibly killed writer can never leave a torn chain — proven by
 * {@code RocksCrashRecoveryIT}.
 *
 * <p>Everything in this package needs {@code org.rocksdb} on the runtime classpath, and the build
 * no longer supplies it transitively: {@code :storage} declares rocksdbjni {@code compileOnly}, so
 * a consumer that classloads this tier must declare it too (today only the NeoForge mod does).
 */
package dev.nodera.storage.rocksdb;
