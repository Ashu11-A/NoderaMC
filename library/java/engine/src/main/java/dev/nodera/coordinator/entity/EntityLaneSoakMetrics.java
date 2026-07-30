package dev.nodera.coordinator.entity;

/** Deterministic counters for Task-12 mixed item/ghost soak acceptance. */
public final class EntityLaneSoakMetrics {

    public static final long MAX_GHOST_BYTES_PER_MOB_MINUTE = 65_536L;
    public static final long MAX_RESYNC_RATE_BPS = 100L;

    private long ghostBytes;
    private long ghostUpdates;
    private long ghostMobTicks;
    private long commits;
    private long resyncs;

    public void recordGhostUpdate(long encodedBytes) {
        if (encodedBytes < 0) {
            throw new IllegalArgumentException("encodedBytes must be non-negative");
        }
        ghostBytes += encodedBytes;
        ghostUpdates++;
    }

    public void recordGhostMobTicks(long activeGhosts) {
        if (activeGhosts < 0) {
            throw new IllegalArgumentException("activeGhosts must be non-negative");
        }
        ghostMobTicks += activeGhosts;
    }

    public void recordCommit() {
        commits++;
    }

    public void recordResync() {
        resyncs++;
    }

    /** Normalized at 20 ticks/s: bytes * 1200 ticks/minute / observed mob-ticks. */
    public long ghostBytesPerMobMinute() {
        return ghostMobTicks == 0 ? 0 : Math.round(ghostBytes * 1_200.0 / ghostMobTicks);
    }

    public long resyncRateBps() {
        long outcomes = commits + resyncs;
        return outcomes == 0 ? 0 : Math.round(resyncs * 10_000.0 / outcomes);
    }

    public boolean meetsTask12ExitCriterion() {
        return ghostMobTicks > 0 && commits > 0
                && ghostBytesPerMobMinute() <= MAX_GHOST_BYTES_PER_MOB_MINUTE
                && resyncRateBps() <= MAX_RESYNC_RATE_BPS;
    }

    public Snapshot snapshot() {
        return new Snapshot(ghostBytes, ghostUpdates, ghostMobTicks, commits, resyncs,
                ghostBytesPerMobMinute(), resyncRateBps(), meetsTask12ExitCriterion());
    }

    public record Snapshot(
            long ghostBytes,
            long ghostUpdates,
            long ghostMobTicks,
            long commits,
            long resyncs,
            long ghostBytesPerMobMinute,
            long resyncRateBps,
            boolean passes) {
    }
}
