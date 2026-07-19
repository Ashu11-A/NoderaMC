/**
 * The durable full-archive storage tier (Task 9, Phase 5): {@code RocksWorldStore} implements the
 * {@code storage-api} seam over RocksDB column families with WAL-backed atomic appends;
 * {@code FsContentStore} keeps content-addressed blobs on the filesystem with atomic writes and
 * hash-verified reads. Heads are recovered from the log tail on open, so a forcibly killed writer
 * can never leave a torn chain — proven by {@code RocksCrashRecoveryIT}.
 */
package dev.nodera.storage.rocksdb;
