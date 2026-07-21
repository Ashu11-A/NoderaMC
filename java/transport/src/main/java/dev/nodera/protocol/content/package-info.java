/**
 * Content-distribution wire messages — the torrent data plane's control words (Task 19).
 *
 * <p>Where {@code simulationmsg.SnapshotAnnounce}/{@code StreamChunk} move a <i>whole</i> region
 * blob as an ordered, transport-level frame split, this package moves <b>addressable pieces</b>:
 * every piece has its own hash (pinned by the {@code PieceManifest} in the {@code distribution}
 * module), so a piece may be requested from <i>any</i> peer that holds it and verified on arrival
 * without trusting the sender.
 *
 * <ul>
 *   <li>{@link dev.nodera.protocol.content.ContentRequest} — "send me these piece indexes of the
 *       blob whose manifest root is X".</li>
 *   <li>{@link dev.nodera.protocol.content.ContentChunk} — one piece's bytes. It deliberately
 *       carries <b>no</b> hash: the receiver verifies against the manifest's hash for that index,
 *       because a hash travelling next to attacker-supplied bytes proves nothing.</li>
 *   <li>{@link dev.nodera.protocol.content.ContentAvailability} — "which pieces I hold, per
 *       manifest", as a {@link dev.nodera.protocol.content.PieceBitmap}. Piece-level (not
 *       manifest-level) holdings are load-bearing: rarest-first selection and partial seeders are
 *       inexpressible at manifest granularity.</li>
 * </ul>
 *
 * <p>Thread-context: all types are immutable records; safe for any thread.
 */
package dev.nodera.protocol.content;
