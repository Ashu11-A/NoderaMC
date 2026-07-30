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
 *   <li><b>Rule 1 — ≥25%-seed floor.</b> Each peer seeds at least {@code min(25%, R/N)} of a world,
 *       dynamically: at small N the floor is the spec's flat 25%; as players join it decays to
 *       {@code R/N} (the average that sustains R copies).</li>
 *   <li><b>Rule 3 — &lt;5%-per-peer cap.</b> No peer holds more than {@code max(5%, 2·R/N)}, so no
 *       one peer concentrates data. The cap only binds at large N; before then a flat 5% is
 *       arithmetically impossible (it would fund fewer than R copies).</li>
 * </ul>
 *
 * <p>The floor is always below the cap, so the two can never conflict.
 *
 * <h2>Deterministic placement = convergent repair</h2>
 *
 * <p>{@link dev.nodera.peer.archival.RendezvousArchivePolicy} ranks eligible peers by
 * {@code StableHash(manifestRoot, nodeId)} and takes the top-R as the <i>expected</i> holders.
 * Every peer computes the same set, so when one detects under-replication it assigns the same
 * next-ranked peer everyone else would — repair converges without a central allocator.
 *
 * <h2>Repair is bounded and trustless</h2>
 *
 * <p>{@link dev.nodera.peer.archival.ArchiveRepairService} re-replicates under a
 * {@code maxConcurrent}/{@code bandwidthBudget} cap (the MultiPaper lesson: a mass-disconnect must
 * not trigger a repair storm). It pulls pieces <i>by hash</i> and verifies before ack — and the
 * coordinator re-audits rather than trusting the ack, because a peer can claim anything.
 *
 * <p>Thread-context: the services are thread-safe; see per-class Javadoc.
 */
package dev.nodera.peer.archival;
