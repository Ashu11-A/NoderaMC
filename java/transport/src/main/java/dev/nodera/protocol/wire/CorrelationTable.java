package dev.nodera.protocol.wire;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which requests are still outstanding, so an answer nobody asked for never reaches a handler
 * (Task 14 phase 5, {@code Plan.7} §4.1).
 *
 * <p>The old wire had no request identity at all. A response was recognised by its <em>type</em>, so
 * whatever arrived next that looked like an answer was treated as the answer — which produced the
 * same defect three times over in three different lanes:
 *
 * <ul>
 *   <li>a tracker response for a different world merged into a pending fetch, because both were
 *       {@code TrackerResponse};</li>
 *   <li>an unsolicited {@code WorldManifestAnswer} was filed against a request that had never gone
 *       out;</li>
 *   <li>an event-sync answer from a peer nobody had asked was accepted as the answer.</li>
 * </ul>
 *
 * <p>Each was reported and fixed separately, in the handler. A correlation id fixes the shape: an
 * answer is matched to the question it answers, and one that matches nothing is dropped
 * <em>before</em> a handler can act on it. There is nothing left for a handler to get wrong.
 *
 * <p>Ids are drawn from a counter seeded at a random point, so two peers that restart at the same
 * moment do not both start at 1 and mistake each other's answers.
 *
 * <p>Thread-context: safe for concurrent use; one table per connection or per node.
 *
 * @param <T> what the caller wants to remember about a pending request.
 */
public final class CorrelationTable<T> {

    private final Map<Long, Pending<T>> pending = new ConcurrentHashMap<>();
    private final AtomicLong next;

    /** A pending request: what was asked, when, and what the caller wants back. */
    private record Pending<T>(int kind, long issuedAtMillis, T context) {}

    /** A table seeded from a random starting point. */
    public CorrelationTable() {
        this(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
    }

    /** A table seeded from a fixed starting point, for deterministic tests. */
    public CorrelationTable(long seed) {
        // 0 is reserved: it means "this is an event, do not look for a request".
        this.next = new AtomicLong(Math.max(1, seed));
    }

    /**
     * Register an outgoing request.
     *
     * @param kind    the kind being sent.
     * @param context whatever the caller needs when the answer arrives.
     * @return the correlation id to put in the frame.
     * @Thread-context any thread.
     */
    public long issue(int kind, T context) {
        long id = next.incrementAndGet();
        if (id == 0) {
            id = next.incrementAndGet();
        }
        pending.put(id, new Pending<>(kind, System.currentTimeMillis(), context));
        return id;
    }

    /**
     * Claim the request an answer belongs to.
     *
     * @param correlationId the id from the response frame.
     * @param kind          the kind that arrived.
     * @return the context that was registered, or empty when nothing matches — which is the whole
     *         point: an answer that matches no question is not an answer, and must not be treated
     *         as one. A mismatched <em>kind</em> is also empty: a request for one thing cannot be
     *         satisfied by an unrelated reply carrying a borrowed id.
     * @Thread-context any thread; a given id is claimed at most once.
     */
    public Optional<T> claim(long correlationId, int kind) {
        if (correlationId == 0) {
            return Optional.empty();
        }
        Pending<T> found = pending.get(correlationId);
        if (found == null) {
            return Optional.empty();
        }
        MessageType requested = MessageTypes.of(found.kind());
        if (!requested.expectsResponse()) {
            return Optional.empty();
        }
        pending.remove(correlationId);
        return Optional.of(found.context());
    }

    /**
     * Drop requests older than {@code maxAgeMillis} and report how many.
     *
     * <p>A pending entry whose answer never comes is a leak with a slow fuse; expiring it is also
     * how a caller learns that a peer is not going to reply, which used to be indistinguishable
     * from a peer that was merely slow.
     *
     * @param maxAgeMillis how long an unanswered request may sit.
     * @param nowMillis    the current time, injected so the sweep is testable.
     * @return the contexts of the requests that expired.
     * @Thread-context any thread.
     */
    public java.util.List<T> expire(long maxAgeMillis, long nowMillis) {
        java.util.List<T> dropped = new java.util.ArrayList<>();
        pending.entrySet().removeIf(e -> {
            if (nowMillis - e.getValue().issuedAtMillis() >= maxAgeMillis) {
                dropped.add(e.getValue().context());
                return true;
            }
            return false;
        });
        return dropped;
    }

    /** How many requests are outstanding. */
    public int outstanding() {
        return pending.size();
    }
}
