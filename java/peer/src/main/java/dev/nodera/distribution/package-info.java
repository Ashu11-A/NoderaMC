/**
 * The torrent distribution data plane (Task 19) — Minecraft-free, headlessly testable.
 *
 * <h2>What this module adds</h2>
 *
 * <p>Before this module, world data moved <b>whole-region</b>: {@code SnapshotAnnounce} plus a
 * sequence of {@code StreamChunk}s, addressed by {@code (streamId, index)}, carrying no per-chunk
 * hash. That is transport-level frame splitting, not a swarm — a frame cannot be requested from an
 * alternate peer, cannot be verified on its own, and cannot be resumed (LIMITATIONS L-32/L-33).
 *
 * <p>This module adds a <b>piece layer beneath the region layer</b>, leaving Tasks 2 and 9
 * completely unchanged:
 *
 * <pre>
 *   RegionSnapshot (frozen, Task 2)
 *         │  canonical encoding = the blob
 *         ▼
 *   RegionSnapshotSplitter ──► PieceManifest ──► Piece[]
 *         │                        │ manifestRoot commits index+length+hash of every piece
 *         │                        ▼
 *         │                   ContentChunk (wire) ──► seeders
 *         ▼
 *   PieceDownloader ──► PieceReassembler ──► verified blob ──► StateRoot == the committee's
 *         │  hash-validate BEFORE accept; retry a bad piece from another holder
 *         └─ ChunkLockMap: an unverified piece leaves its chunk sections locked vs render + edit
 * </pre>
 *
 * <h2>The invariant that makes it safe</h2>
 *
 * <p>The blob the pieces slice is byte-for-byte the frozen {@code RegionSnapshot} encoding, so
 * {@code SHA-256(reassembled blob)} is the region's {@code StateRoot}. A region rebuilt from
 * arbitrary, untrusted, partial seeders is therefore provably the same state the committee
 * committed — the data plane gained a swarm without the consensus layer gaining an assumption.
 *
 * <h2>Determinism hygiene</h2>
 *
 * <p>The {@code simulation} module's forbidden-API ban does not extend here, but the same
 * discipline is kept in the selection path: {@link dev.nodera.distribution.PieceSelector} uses
 * {@link dev.nodera.core.crypto.StableHash} (cross-JVM stable) and no clocks or entropy, so two
 * peers given the same {@code (manifest, holder set)} request pieces in the same order. Serve
 * budgets are advanced by an explicit call rather than a wall clock for the same reason.
 *
 * <p>Thread-context: value types are immutable; {@link dev.nodera.distribution.ChunkLockMap} and
 * {@link dev.nodera.distribution.ContentTransferService} are thread-safe;
 * {@link dev.nodera.distribution.PieceReassembler} is single-threaded behind its owning downloader.
 */
package dev.nodera.distribution;
