package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-node reliability EMA (Plan §10, Task 6): {@code score ← (1-α)·score + α·outcome} with
 * {@code α = }{@link NoderaConstants#RELIABILITY_EMA_ALPHA} (0.02, i.e. {@code 0.98·score +
 * 0.02·outcome}). A matched proposal is outcome 1, a mismatch outcome 0; equivocation slashes the
 * score to 0 outright. Assignment requires {@code score ≥ }{@link
 * NoderaConstants#RELIABILITY_ASSIGNMENT_FLOOR}.
 *
 * <p>Persisted verbatim in {@code NoderaSavedData}: this ledger is {@link Encodable} (a sorted
 * {@code NodeId → scoreBits} list) so epochs and reputations survive a server restart — the
 * round-trip is covered by a unit test. Scores are stored/encoded as raw IEEE-754 bits; no
 * floating-point arithmetic happens during encoding, so the bytes are deterministic across JVMs.
 *
 * @Thread-context confined to the coordinator thread; not thread-safe.
 */
public final class ReliabilityLedger implements Encodable {

    private final double alpha;
    private final double assignmentFloor;
    private final double initialScore;
    private final Map<NodeId, Double> scores = new TreeMap<>(Comparator.comparing(NodeId::value));

    /** Ledger with the {@link NoderaConstants} defaults (α=0.02, floor=0.95, initial=0.95). */
    public ReliabilityLedger() {
        this(NoderaConstants.RELIABILITY_EMA_ALPHA,
                NoderaConstants.RELIABILITY_ASSIGNMENT_FLOOR,
                NoderaConstants.RELIABILITY_ASSIGNMENT_FLOOR);
    }

    /**
     * @param alpha           EMA weight for the new outcome (0..1).
     * @param assignmentFloor minimum score to be eligible for assignment.
     * @param initialScore    the score a newly-seen node starts at.
     */
    public ReliabilityLedger(double alpha, double assignmentFloor, double initialScore) {
        if (alpha < 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("alpha must be in [0,1], got " + alpha);
        }
        this.alpha = alpha;
        this.assignmentFloor = assignmentFloor;
        this.initialScore = initialScore;
    }

    /** @return the current score for {@code node} (the initial score if unseen). */
    public double score(NodeId node) {
        return scores.getOrDefault(node, initialScore);
    }

    /**
     * Fold one proposal outcome into the node's EMA.
     *
     * @param node    the proposer.
     * @param success {@code true} for a matched proposal (outcome 1), {@code false} for a mismatch.
     * @return the updated score.
     */
    public double record(NodeId node, boolean success) {
        double outcome = success ? 1.0 : 0.0;
        double updated = (1.0 - alpha) * score(node) + alpha * outcome;
        scores.put(node, updated);
        return updated;
    }

    /** Slash a node's score to 0 (equivocation — Plan §10: "slash to 0 on equivocation"). */
    public void slash(NodeId node) {
        scores.put(node, 0.0);
    }

    /** @return {@code true} if {@code node}'s score is at or above the assignment floor. */
    public boolean eligibleForAssignment(NodeId node) {
        return score(node) >= assignmentFloor;
    }

    /** @return the number of nodes with a recorded (non-default) score. */
    public int size() {
        return scores.size();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.RELIABILITY_LEDGER).writeU16(ENCODING_VERSION);
        List<Map.Entry<NodeId, Double>> entries = new ArrayList<>(scores.entrySet());
        // scores is a TreeMap by NodeId.value, so iteration is already canonical.
        w.writeList(entries, (ww, e) -> {
            e.getKey().encode(ww);
            ww.writeU64(Double.doubleToLongBits(e.getValue()));
        });
    }

    /** Restore a ledger (with the given tuning) from its canonical bytes. */
    public static ReliabilityLedger decode(CanonicalReader r, double alpha, double floor, double initial) {
        int tag = r.readU16();
        if (tag != TypeTags.RELIABILITY_LEDGER) {
            throw new IllegalStateException("expected RELIABILITY_LEDGER tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        ReliabilityLedger ledger = new ReliabilityLedger(alpha, floor, initial);
        List<Map.Entry<NodeId, Double>> entries = r.readList(rr -> {
            NodeId id = NodeId.decode(rr);
            double score = Double.longBitsToDouble(rr.readU64());
            return Map.entry(id, score);
        });
        for (Map.Entry<NodeId, Double> e : entries) {
            ledger.scores.put(e.getKey(), e.getValue());
        }
        return ledger;
    }

    /** Restore a ledger with the default tuning. */
    public static ReliabilityLedger decode(CanonicalReader r) {
        return decode(r, NoderaConstants.RELIABILITY_EMA_ALPHA,
                NoderaConstants.RELIABILITY_ASSIGNMENT_FLOOR,
                NoderaConstants.RELIABILITY_ASSIGNMENT_FLOOR);
    }
}
