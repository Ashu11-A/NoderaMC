package dev.nodera.peer.archival;

import java.util.Objects;

/**
 * Per-object-class replication factor (Task 21). Floors, not ceilings — the audit repairs up to
 * the factor, never caps below it.
 *
 * <p>Values per the spec (Plan §3.12 / Task.21): current snapshot ×5, recent log ×4, compacted
 * history ×3, checkpoint/genesis = everyone. The "everyone" classes are expressed as a factor equal
 * to the network size at audit time, so the same "top-R" rendezvous selection covers them with no
 * special case: when R = N, the top-R of N eligible peers is all of them.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param snapshot   current-snapshot factor (≥1).
 * @param recentLog  recent-log factor (≥1).
 * @param compacted  compacted-history factor (≥1).
 */
public record ReplicationFactors(int snapshot, int recentLog, int compacted) {

    /** The spec defaults: snapshot ×5, recent log ×4, compacted ×3. */
    public static final ReplicationFactors SPEC = new ReplicationFactors(5, 4, 3);

    /**
     * Factors sized for a <b>world archive</b> rather than for the simulation lanes.
     *
     * <p>The constants above are a spec statement about replicated simulation state, and they are
     * the wrong shape for a player's save: five copies of a world whose holders are home machines
     * online about a third of the time is a 12% chance that nobody has it at a given moment.
     * {@link ReplicationTarget} derives the count from that availability instead of asserting it,
     * and {@link #factor} then caps it at the network size — which is what makes a small network
     * keep <b>a full copy on every peer</b> rather than holding back copies it could have made.
     *
     * <p>Only the snapshot factor moves. The other two describe objects this reasoning does not
     * apply to, and changing them here would quietly re-tune the repair lane.
     *
     * @param target the durability model; {@link ReplicationTarget#standard()} for the defaults.
     * @return factors whose SNAPSHOT class follows {@code target}.
     */
    public static ReplicationFactors forWorldArchives(ReplicationTarget target) {
        Objects.requireNonNull(target, "target");
        return new ReplicationFactors(target.idealReplicas(), SPEC.recentLog(), SPEC.compacted());
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any factor is not positive.
     */
    public ReplicationFactors {
        if (snapshot <= 0 || recentLog <= 0 || compacted <= 0) {
            throw new IllegalArgumentException(
                    "factors must be positive: snapshot=" + snapshot + " recentLog=" + recentLog
                            + " compacted=" + compacted);
        }
    }

    /** @return the spec defaults. */
    public static ReplicationFactors spec() {
        return SPEC;
    }

    /**
     * The replication factor for one object class, given the current network size. Checkpoint and
     * genesis return {@code networkSize} (everyone); the countable classes return their fixed
     * factor.
     *
     * @param objectClass the class.
     * @param networkSize the number of eligible peers; must be positive for the "everyone"
     *                    classes (a checkpoint of a world with zero peers is vacuous).
     * @return the factor R.
     * @throws IllegalArgumentException if {@code objectClass} is null or {@code networkSize} is not
     *                                  positive.
     * @Thread-context any thread.
     */
    public int factor(ArchiveObjectClass objectClass, int networkSize) {
        Objects.requireNonNull(objectClass, "objectClass");
        if (networkSize <= 0) {
            throw new IllegalArgumentException("networkSize must be positive: " + networkSize);
        }
        return switch (objectClass) {
            case SNAPSHOT -> Math.min(snapshot, networkSize);
            case RECENT_LOG -> Math.min(recentLog, networkSize);
            case COMPACTED_LOG -> Math.min(compacted, networkSize);
            // Everyone: bounded by the network so a "top-R" selection with R = N is total.
            case CHECKPOINT, GENESIS -> networkSize;
        };
    }
}
