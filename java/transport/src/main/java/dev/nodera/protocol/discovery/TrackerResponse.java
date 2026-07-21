package dev.nodera.protocol.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.List;
import java.util.Objects;

/**
 * The tracker's answer: who is in a world, who seeds it, and how it is doing (Task 20). This is the
 * record the multiplayer UI (Task 26) renders one row per.
 *
 * <h2>Why reliability is basis points</h2>
 *
 * <p>{@code reliabilityBps} is a quantised integer in {@code [0, 10000]} (10000 = 1.0), not a
 * {@code double}. The canonical encoder has no float/double primitive on purpose — floating-point
 * is the project's single biggest determinism hazard — so any real-valued quantity that crosses the
 * wire is quantised first. Basis points give four significant digits, which is far more precision
 * than a "how trustworthy is this world" bar needs.
 *
 * <h2>What is advisory here</h2>
 *
 * <p>Everything. A tracker can misreport counts or omit peers; nothing downstream trusts it with
 * state. The counters drive presentation and peer selection hints only — actual content is
 * verified by hash on arrival (Task 19), so a lying tracker costs a wasted round trip, not
 * correctness.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param genesisHash                 the world's genesis hash (echoes the query).
 * @param worldName                   the host-registered display name; may be empty if unregistered.
 * @param peers                       peers currently online in this world.
 * @param seeders                     per-manifest seeder sets known to the tracker.
 * @param worldPlayerCount            online peers in-world.
 * @param storedChunks                distinct pieces with at least one holder.
 * @param reliabilityBps              quantised mean holder reliability, basis points 0..10000.
 * @param health                      the world's health class (drives the UI's colour).
 * @param retentionDeadlineEpochMillis the Task 22 drop deadline, or {@code 0} when no countdown is
 *                                    running.
 */
public record TrackerResponse(
        Bytes genesisHash,
        String worldName,
        List<PeerEntry> peers,
        List<ManifestSeeders> seeders,
        long worldPlayerCount,
        long storedChunks,
        int reliabilityBps,
        WorldHealth health,
        long retentionDeadlineEpochMillis
) implements NoderaMessage {

    /** Full scale for {@code reliabilityBps} — 10000 basis points is 1.0. */
    public static final int RELIABILITY_BPS_SCALE = 10_000;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference argument is null, a count is negative, or
     *                                  {@code reliabilityBps} is outside {@code [0, 10000]}.
     */
    public TrackerResponse {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(peers, "peers");
        Objects.requireNonNull(seeders, "seeders");
        Objects.requireNonNull(health, "health");
        if (worldPlayerCount < 0) {
            throw new IllegalArgumentException("worldPlayerCount must be non-negative");
        }
        if (storedChunks < 0) {
            throw new IllegalArgumentException("storedChunks must be non-negative");
        }
        if (reliabilityBps < 0 || reliabilityBps > RELIABILITY_BPS_SCALE) {
            throw new IllegalArgumentException(
                    "reliabilityBps must be in [0," + RELIABILITY_BPS_SCALE + "]: " + reliabilityBps);
        }
        if (retentionDeadlineEpochMillis < 0) {
            throw new IllegalArgumentException("retentionDeadlineEpochMillis must be non-negative");
        }
        peers = List.copyOf(peers);
        seeders = List.copyOf(seeders);
    }

    /**
     * Quantise a {@code [0,1]} reliability into basis points, clamping out-of-range inputs rather
     * than throwing — a caller's EMA drifting a hair past 1.0 must not take down a tracker answer.
     *
     * @param reliability a reliability in {@code [0,1]}.
     * @return the value in basis points.
     * @Thread-context any thread.
     */
    public static int toBasisPoints(double reliability) {
        if (Double.isNaN(reliability)) {
            return 0;
        }
        long bps = Math.round(reliability * RELIABILITY_BPS_SCALE);
        return (int) Math.max(0, Math.min(RELIABILITY_BPS_SCALE, bps));
    }

    /** @return {@code true} if a Task 22 retention countdown is running for this world. */
    public boolean hasRetentionCountdown() {
        return retentionDeadlineEpochMillis > 0;
    }

    @Override
    public String toString() {
        return "TrackerResponse[" + genesisHash.toShortHex(6) + " '" + worldName + "' peers="
                + peers.size() + " manifests=" + seeders.size() + " " + health + "]";
    }
}
