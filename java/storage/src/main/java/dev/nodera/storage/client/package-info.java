/**
 * The bounded, quota'd client-side content store (Task 22; retires L-37).
 *
 * <p>A client peer holds a <i>partial</i> archive — the slice Task 21's placement assigned it plus
 * whatever it has fetched. Without a bound that slice grows without limit (Plan §3.13 forbids
 * unbounded maps keyed by remote input), so this module wraps a {@link dev.nodera.storage.ContentStore}
 * with a byte budget and an eviction policy.
 *
 * <h2>The non-negotiable eviction rule</h2>
 *
 * <p>{@link dev.nodera.storage.client.ArchiveEvictionPolicy} evicts the oldest <b>cold</b> shard
 * first — content not pinned to an assigned region, least-recently-used. It <b>never</b> evicts an
 * assigned region's current snapshot or recent log: dropping those would lose the peer its committee
 * duties and break the replication factor Task 21 enforces. When eviction removes a replica, it
 * signals Task 21's repair so the factor is re-met elsewhere before the data is gone everywhere.
 *
 * <p>Thread-context: {@link dev.nodera.storage.client.BoundedClientWorldStore} is thread-safe.
 */
package dev.nodera.storage.client;
