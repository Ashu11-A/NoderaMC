package dev.nodera.diagnostics.metric;

import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-peer event-relay accounting for the validation lane (the "who is processing whose events,
 * and how long does it take" view): every player node counts the actions it CAPTURES locally and
 * transmits to the network, the forwarded actions it PROPOSES for other players (it is the
 * region primary), and the proposals/votes/commits it processes from each remote peer — with
 * nanosecond totals for the two heavy paths (forwarded-action proposal + proposal re-execution).
 *
 * <p>Motivation (live play-two, 2026-07-24): players report low TPS with the lane active; this
 * is the instrument that says whether the time goes into validating each other's events, and how
 * asymmetric the load is. Rendered by {@code /nodera debug relay} and streamed to the player's
 * chat console by {@code /nodera debug verbose on}.
 *
 * <p>Thread-context: all methods safe from any thread (atomics over concurrent maps); metrics
 * never touch simulation state or certificates.
 */
public final class RelayMetrics {

    /** One remote peer's lane totals. */
    public static final class PeerLane {
        final AtomicLong forwardedTo = new AtomicLong();
        final AtomicLong forwardedFrom = new AtomicLong();
        final AtomicLong forwardProcessNanos = new AtomicLong();
        final AtomicLong proposalsFrom = new AtomicLong();
        final AtomicLong proposalProcessNanos = new AtomicLong();
        final AtomicLong votesFrom = new AtomicLong();
        final AtomicLong commitsFrom = new AtomicLong();
    }

    /** Immutable per-peer snapshot row. */
    public record PeerRow(
            NodeId peer,
            long forwardedTo,
            long forwardedFrom,
            long forwardProcessNanos,
            long proposalsFrom,
            long proposalProcessNanos,
            long votesFrom,
            long commitsFrom) {
    }

    /** Immutable whole-lane snapshot. */
    public record Snapshot(long localSubmitted, long localProposed, List<PeerRow> peers) {
    }

    private final Map<NodeId, PeerLane> lanes = new ConcurrentHashMap<>();
    private final AtomicLong localSubmitted = new AtomicLong();
    private final AtomicLong localProposed = new AtomicLong();

    private PeerLane lane(NodeId peer) {
        return lanes.computeIfAbsent(peer, p -> new PeerLane());
    }

    /** A locally-captured action entered the lane (own game → network). */
    public void recordLocalSubmitted() {
        localSubmitted.incrementAndGet();
    }

    /** A locally-captured action was proposed by THIS node (it is the primary). */
    public void recordLocalProposed() {
        localProposed.incrementAndGet();
    }

    /** A locally-captured action was forwarded to {@code primary} (own game → that player). */
    public void recordForwardedTo(NodeId primary) {
        lane(primary).forwardedTo.incrementAndGet();
    }

    /**
     * A forwarded action FROM {@code actor} was processed (proposed) here, taking {@code nanos}
     * (this node is the primary doing another player's work).
     */
    public void recordForwardProcessed(NodeId actor, long nanos) {
        PeerLane l = lane(actor);
        l.forwardedFrom.incrementAndGet();
        l.forwardProcessNanos.addAndGet(Math.max(0, nanos));
    }

    /** A proposal from {@code proposer} was re-executed here, taking {@code nanos}. */
    public void recordProposalProcessed(NodeId proposer, long nanos) {
        PeerLane l = lane(proposer);
        l.proposalsFrom.incrementAndGet();
        l.proposalProcessNanos.addAndGet(Math.max(0, nanos));
    }

    /** A vote from {@code voter} was processed here. */
    public void recordVote(NodeId voter) {
        lane(voter).votesFrom.incrementAndGet();
    }

    /** A commit announce from {@code committer} was applied here. */
    public void recordCommit(NodeId committer) {
        lane(committer).commitsFrom.incrementAndGet();
    }

    /** @return an immutable snapshot of every peer lane + the local counters. */
    public Snapshot snapshot() {
        List<PeerRow> rows = new ArrayList<>(lanes.size());
        for (Map.Entry<NodeId, PeerLane> e : lanes.entrySet()) {
            PeerLane l = e.getValue();
            rows.add(new PeerRow(e.getKey(),
                    l.forwardedTo.get(), l.forwardedFrom.get(), l.forwardProcessNanos.get(),
                    l.proposalsFrom.get(), l.proposalProcessNanos.get(),
                    l.votesFrom.get(), l.commitsFrom.get()));
        }
        rows.sort((a, b) -> a.peer().value().compareTo(b.peer().value()));
        return new Snapshot(localSubmitted.get(), localProposed.get(), List.copyOf(rows));
    }

    /**
     * Human-readable multi-line summary (chat console / logs): local capture counters + one line
     * per peer with counts and average processing milliseconds.
     */
    public String describe() {
        Snapshot s = snapshot();
        StringBuilder out = new StringBuilder();
        out.append("events: own-captured ").append(s.localSubmitted())
                .append(" (self-proposed ").append(s.localProposed()).append(')');
        for (PeerRow r : s.peers()) {
            out.append('\n').append(r.peer().toString(), 0, 8)
                    .append(": sent→").append(r.forwardedTo())
                    .append(" | did-their-work ").append(r.forwardedFrom())
                    .append(" (avg ").append(avgMs(r.forwardProcessNanos(), r.forwardedFrom()))
                    .append(" ms) | proposals ").append(r.proposalsFrom())
                    .append(" (avg ").append(avgMs(r.proposalProcessNanos(), r.proposalsFrom()))
                    .append(" ms) | votes ").append(r.votesFrom())
                    .append(" | commits ").append(r.commitsFrom());
        }
        return out.toString();
    }

    private static String avgMs(long nanos, long count) {
        if (count <= 0) {
            return "0.00";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0 / count);
    }
}
