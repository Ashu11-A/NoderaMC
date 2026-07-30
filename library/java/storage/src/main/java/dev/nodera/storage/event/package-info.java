/**
 * In-memory event-sourced {@link dev.nodera.storage.WorldStore} (Task 9, Phase 5), Minecraft-free.
 *
 * <p>Implements the {@code dev.nodera.storage} seam: a content-addressed blob store, append-only per-region
 * event logs that validate the {@code prevRoot → resultingRoot} chain and monotonic event ids, a
 * checkpoint index, and a certificate store. {@link dev.nodera.storage.event.EventReplayer} replays a
 * region's certified log and verifies every event is backed by a matching quorum certificate
 * (Invariant 3); {@link dev.nodera.storage.event.PeerSyncFlow} is the new-peer sync — synchronise
 * <b>forward</b> from the network (Invariant 8), treating any locally-newer-but-uncertified suffix as
 * uncommitted.
 *
 * <p>Everything is pure Java over {@code core} + the {@code dev.nodera.storage} seam; the archival RocksDB tier will
 * implement the same seam later.
 */
package dev.nodera.storage.event;
