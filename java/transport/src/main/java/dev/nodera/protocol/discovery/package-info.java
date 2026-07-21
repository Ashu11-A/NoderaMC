/**
 * Discovery and tracker wire messages — the torrent feature's <b>control</b> plane (Task 20), as
 * distinct from {@code protocol.content}'s data plane.
 *
 * <p>A tracker is a <b>role</b>, not a server: any {@code FULL_ARCHIVE}/{@code BOOTSTRAP}-capable
 * peer answers a {@link dev.nodera.protocol.discovery.TrackerQuery}. That is safe because tracker
 * answers are advisory, not authoritative — a malicious tracker can lie about <i>who is online</i>,
 * but it cannot lie about <i>state</i>, since every manifest and checkpoint verifies by hash
 * (Tasks 19 and 9). The worst a lying tracker achieves is pointing a peer at seeders that turn out
 * to be useless, which the downloader already handles by re-selecting.
 *
 * <ul>
 *   <li>{@link dev.nodera.protocol.discovery.TrackerQuery} — "who is in the world with this
 *       genesis hash, and who seeds it?"</li>
 *   <li>{@link dev.nodera.protocol.discovery.TrackerResponse} — peers, per-manifest seeders, and
 *       the aggregate counters the multiplayer UI (Task 26) renders. Reliability travels as
 *       quantised basis points, never a float: the canonical encoding has no floating-point by
 *       design.</li>
 *   <li>{@link dev.nodera.protocol.discovery.InventoryAdvertisement} — a peer's periodic "here is
 *       what I hold, in this world", which feeds the tracker's seeder index.</li>
 * </ul>
 *
 * <p>Thread-context: all types are immutable records; safe for any thread.
 */
package dev.nodera.protocol.discovery;
