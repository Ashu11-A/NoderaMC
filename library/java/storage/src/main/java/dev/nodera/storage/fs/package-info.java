/**
 * The filesystem blob tier: {@code FsContentStore} keeps content-addressed blobs on disk with
 * atomic writes and hash-verified reads, over {@code java.nio.file} and nothing else.
 *
 * <p>It lived in {@code dev.nodera.storage.rocksdb} until 2026-07-31, purely because
 * {@code RocksWorldStore} was its first caller. That was a costly misfiling: the headless peer
 * uses this class and no other part of the durable tier, so "the peer imports
 * {@code storage.rocksdb}" read as "the peer needs RocksDB", and the peer shipped rocksdbjni's
 * fourteen platform natives — 67 MB compressed — in every artefact for a library it never loads.
 * The package boundary now says what the dependency actually is.
 */
package dev.nodera.storage.fs;
