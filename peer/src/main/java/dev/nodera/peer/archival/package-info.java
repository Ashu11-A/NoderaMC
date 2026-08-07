/**
 * Archive placement, replication, and repair (Task 21) — the guarantee that world data is
 * <b>redundantly spread</b> across the peer network so that no single peer, host included, is a
 * point of loss.
 *
 * <h2>Three spec rules, one policy layer</h2>
 *
 * <ul>
 *   <li><b>Rule 0 — host = physical backup.</b> The {@code FULL_ARCHIVE} host holds everything and
 *       is in every expected-holder set. It is the physical fallback (Task 24's continuous stream
 *       keeps it fed) but gets <i>no extra consensus vote</i> (Invariant 2).</li>
 *   <li><b>Rule 1 — every placed peer seeds the world it was placed for.</b> Placement is a pure
 *       function of the live peer set ({@code RendezvousArchivePolicy}), and the worker's sweep
 *       adopts every world it is an expected holder of, up to a byte budget.</li>
 *   <li><b>Rule 3 — &lt;5%-per-peer cap.</b> No peer holds more than {@code max(5%, 2·R/N)}, so no
 *       one peer concentrates data. The cap only binds at large N; before then a flat 5% is
 *       arithmetically impossible (it would fund fewer than R copies).</li>
 * </ul>
 *
 * <p>A piece-denominated seed floor was written for Task 21 alongside this and never reached from
 * a shipping entry point — the worker bounds itself in bytes, a different model — so it was
 * deleted on 2026-08-06 (Plan 11 round 2, issue #210).
 *
 * <h2>Deterministic placement = convergent repair</h2>
 *
 * <p>{@link dev.nodera.peer.archival.RendezvousArchivePolicy} ranks eligible peers by
 * {@code StableHash(manifestRoot, nodeId)} and takes the top-R as the <i>expected</i> holders.
 * Every peer computes the same set, so when one detects under-replication it assigns the same
 * next-ranked peer everyone else would — repair converges without a central allocator.
 *
 * <h2>Repair is the sweep, not a second mechanism</h2>
 *
 * <p>A departure shrinks the peer set, placement re-ranks, a peer that was not a holder becomes
 * one, and the next sweep adopts — which is what {@code WorldReplicationService.placedFor} reads
 * and what {@code DepartureIsRepairedByPlacementTest} pins. A separate audit→repair triangle
 * (an audit task, a repair service and a custody digest, plus the push-side assignment/ack wire
 * tags) was written for Task 21 as a closed loop with no entry point into it, and was deleted on
 * 2026-08-06 (Plan 11 round 2, issue #210). Its durability property is the one the sweep already
 * holds, and it held it worse: evacuating on the way out depends on the departing node still being
 * alive, which a crash and a closed laptop both violate.
 *
 * <p>Thread-context: the services are thread-safe; see per-class Javadoc.
 */
package dev.nodera.peer.archival;
