package dev.nodera.coordinator;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One node's reliability <b>signals</b>, each quantised to basis points (0..10000) before it is
 * blended (Task 22; retires L-36).
 *
 * <h2>Why basis points, not doubles</h2>
 *
 * <p>Reliability drives placement, gateway election, and lag handoff — all of which must be
 * bit-identical across JVMs. Floating-point is the project's biggest determinism hazard, so every
 * signal is quantised to an integer the moment it enters this type, and the
 * {@link ReliabilityScorer} blend is pure integer arithmetic. A {@code double} only reappears as a
 * <i>display</i> convenience ({@link #asFraction()}); the load-bearing value is the integer.
 *
 * <p>The five factors (Plan §10 / Task 22):
 * <ul>
 *   <li><b>correctness</b> — the proposal-outcome EMA from {@link ReliabilityLedger}, quantised.</li>
 *   <li><b>connectivity</b> — reachability + frame-loss (keep-alive seq gaps).</li>
 *   <li><b>uptime</b> — EMA of the online fraction over a window.</li>
 *   <li><b>availability</b> — heartbeat regularity.</li>
 *   <li><b>worldsSeeded</b> — distinct manifests held vs expected (Task 21 seed-share).</li>
 * </ul>
 *
 * <p>Wire form: {@code [u16 RELIABILITY_FACTORS][u16 ENCODING_VERSION][5× u16 basis points]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param correctness   proposal-outcome EMA, basis points.
 * @param connectivity  reachability/frame-loss, basis points.
 * @param uptime        online-fraction EMA, basis points.
 * @param availability  heartbeat regularity, basis points.
 * @param worldsSeeded  seed-share, basis points.
 */
public record ReliabilityFactors(
        int correctness,
        int connectivity,
        int uptime,
        int availability,
        int worldsSeeded
) implements Encodable {

    /** Full scale: 10000 basis points = 1.0. */
    public static final int BPS_SCALE = 10_000;

    /** All-five signal count, for codecs/property tests. */
    public static final int SIGNAL_COUNT = 5;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any factor is outside {@code [0, 10000]}.
     */
    public ReliabilityFactors {
        correctness = checkBps("correctness", correctness);
        connectivity = checkBps("connectivity", connectivity);
        uptime = checkBps("uptime", uptime);
        availability = checkBps("availability", availability);
        worldsSeeded = checkBps("worldsSeeded", worldsSeeded);
    }

    /**
     * Build factors from {@code [0,1]} doubles, quantising each to basis points (clamped).
     *
     * @Thread-context any thread.
     */
    public static ReliabilityFactors ofFractions(
            double correctness, double connectivity, double uptime,
            double availability, double worldsSeeded) {
        return new ReliabilityFactors(
                toBps(correctness), toBps(connectivity), toBps(uptime),
                toBps(availability), toBps(worldsSeeded));
    }

    /** A neutral profile: every signal at 1.0 (a freshly-joined, untested, well-behaved peer). */
    public static ReliabilityFactors perfect() {
        return new ReliabilityFactors(BPS_SCALE, BPS_SCALE, BPS_SCALE, BPS_SCALE, BPS_SCALE);
    }

    /** @return the factor at the canonical signal ordinal (codec order). */
    public int signal(int ordinal) {
        return switch (ordinal) {
            case 0 -> correctness;
            case 1 -> connectivity;
            case 2 -> uptime;
            case 3 -> availability;
            case 4 -> worldsSeeded;
            default -> throw new IllegalArgumentException("unknown signal ordinal " + ordinal);
        };
    }

    /** @return a copy with one signal replaced (by ordinal). */
    public ReliabilityFactors withSignal(int ordinal, int bps) {
        return switch (ordinal) {
            case 0 -> new ReliabilityFactors(bps, connectivity, uptime, availability, worldsSeeded);
            case 1 -> new ReliabilityFactors(correctness, bps, uptime, availability, worldsSeeded);
            case 2 -> new ReliabilityFactors(correctness, connectivity, bps, availability, worldsSeeded);
            case 3 -> new ReliabilityFactors(correctness, connectivity, uptime, bps, worldsSeeded);
            case 4 -> new ReliabilityFactors(correctness, connectivity, uptime, availability, bps);
            default -> throw new IllegalArgumentException("unknown signal ordinal " + ordinal);
        };
    }

    /** @return the correctness factor as a {@code [0,1]} double (display convenience only). */
    public double asFraction() {
        return (double) correctness / BPS_SCALE;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.RELIABILITY_FACTORS).writeU16(ENCODING_VERSION);
        w.writeU16(correctness);
        w.writeU16(connectivity);
        w.writeU16(uptime);
        w.writeU16(availability);
        w.writeU16(worldsSeeded);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @throws IllegalStateException if the next tag is not {@code RELIABILITY_FACTORS}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static ReliabilityFactors decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.RELIABILITY_FACTORS) {
            throw new IllegalStateException("expected RELIABILITY_FACTORS tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return new ReliabilityFactors(r.readU16(), r.readU16(), r.readU16(), r.readU16(), r.readU16());
    }

    private static int checkBps(String name, int bps) {
        if (bps < 0 || bps > BPS_SCALE) {
            throw new IllegalArgumentException(
                    name + " must be in [0," + BPS_SCALE + "]: " + bps);
        }
        return bps;
    }

    private static int toBps(double fraction) {
        if (Double.isNaN(fraction)) {
            return 0;
        }
        return (int) Math.max(0, Math.min(BPS_SCALE, Math.round(fraction * BPS_SCALE)));
    }

    @Override
    public String toString() {
        return "ReliabilityFactors[corr=" + correctness + " conn=" + connectivity
                + " up=" + uptime + " avail=" + availability + " seeded=" + worldsSeeded + "]";
    }
}
