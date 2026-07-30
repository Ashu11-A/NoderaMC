package dev.nodera.protocol.service;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * A tracker's aggregate opinion of one service (type tag {@value TypeTags#SERVICE_SCORE}).
 *
 * <p>Every field is a permille or a millisecond count, so the Java and Rust implementations cannot
 * disagree through floating point. {@link #compositePermille()} is <b>derived, not trusted</b>:
 * {@link #composite(int, int, int, int)} is mirrored byte-for-byte in
 * {@code nodera_codec::service::ServiceScore::composite}, and a peer recomputes the number from the
 * components with {@link #recomputedComposite()} rather than believing the transmitted one. A
 * tracker that inflates a favourite's composite therefore changes nothing.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param availabilityPermille successful probes over total probes across reporters, in permille.
 * @param rttP50Millis         median reported round-trip time.
 * @param rttP95Millis         95th-percentile reported round-trip time.
 * @param capacityPermille     free capacity from the service's own record, in permille.
 * @param freshnessPermille    how continuously the tracker itself saw heartbeats, in permille.
 * @param reporterCount        how many distinct peers contributed observations.
 * @param compositePermille    the weighted composite — a hint a peer recomputes.
 */
public record ServiceScore(
        int availabilityPermille,
        int rttP50Millis,
        int rttP95Millis,
        int capacityPermille,
        int freshnessPermille,
        int reporterCount,
        int compositePermille) implements Encodable {

    /**
     * The relative weights of the four components: availability, latency, capacity, freshness.
     *
     * <p>They sum to 100, so a composite reads directly as a permille of the ideal service.
     * Availability dominating latency is the deliberate ordering: registration and discovery are
     * latency-tolerant (rendezvous {@code REFERENCE.md} §15), so a slow rendezvous that is always up
     * must outrank a fast one that is usually down.
     */
    public static final int[] WEIGHTS = {40, 30, 20, 10};

    /** The RTT at which the latency term reaches zero. */
    public static final int LATENCY_CEILING_MILLIS = 1_500;

    /** Permille denominator. */
    public static final int PERMILLE = 1_000;

    /** A score with no evidence in it at all — what an unmeasured service starts from. */
    public static final ServiceScore UNKNOWN = new ServiceScore(0, 0, 0, 0, 0, 0, 0);

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any field is negative.
     */
    public ServiceScore {
        if (availabilityPermille < 0 || rttP50Millis < 0 || rttP95Millis < 0
                || capacityPermille < 0 || freshnessPermille < 0 || reporterCount < 0
                || compositePermille < 0) {
            throw new IllegalArgumentException("score fields must be non-negative");
        }
    }

    /**
     * The weighted composite of the four components, in permille (0..1000).
     *
     * <p>Integer-only and identical in Rust. The latency term uses <b>p95, not p50</b>: a rendezvous
     * whose tail is bad is bad, and a median hides exactly the stalls that leave a peer sitting on an
     * unusable path.
     *
     * @param availabilityPermille measured availability, 0..1000 (clamped).
     * @param rttP95Millis         measured tail latency.
     * @param capacityPermille     self-reported free capacity, 0..1000 (clamped).
     * @param freshnessPermille    heartbeat continuity, 0..1000 (clamped).
     * @return the composite in permille.
     * @Thread-context any thread.
     */
    public static int composite(int availabilityPermille, int rttP95Millis, int capacityPermille,
            int freshnessPermille) {
        int[] terms = {
                Math.min(Math.max(availabilityPermille, 0), PERMILLE),
                latencyPermille(rttP95Millis),
                Math.min(Math.max(capacityPermille, 0), PERMILLE),
                Math.min(Math.max(freshnessPermille, 0), PERMILLE),
        };
        long total = 0;
        long divisor = 0;
        for (int i = 0; i < terms.length; i++) {
            total += (long) terms[i] * WEIGHTS[i];
            divisor += WEIGHTS[i];
        }
        return (int) (total / divisor);
    }

    /**
     * The latency term: 0 when unmeasured, then linear from just under 1000 down to 0 at
     * {@link #LATENCY_CEILING_MILLIS}.
     *
     * <p><b>Zero means unmeasured, not instant.</b> A score with no reporters carries
     * {@code rttP95Millis == 0}, and reading that as a perfect round trip would score every unprobed
     * service as the fastest thing in the directory — so a service nobody has measured would outrank
     * one measured as merely good. A real sub-millisecond RTT loses a thousandth of the term to this,
     * which is not a number anybody selects on.
     *
     * @param rttMillis the measured round-trip time; 0 or negative reads as unmeasured.
     * @return 0..1000.
     * @Thread-context any thread.
     */
    public static int latencyPermille(int rttMillis) {
        if (rttMillis <= 0 || rttMillis >= LATENCY_CEILING_MILLIS) {
            return 0;
        }
        return (int) (((long) (LATENCY_CEILING_MILLIS - rttMillis) * PERMILLE)
                / LATENCY_CEILING_MILLIS);
    }

    /**
     * This score's composite, recomputed from its own components.
     *
     * @return the composite a correct tracker would have sent.
     * @Thread-context any thread.
     */
    public int recomputedComposite() {
        return composite(availabilityPermille, rttP95Millis, capacityPermille, freshnessPermille);
    }

    /**
     * A copy of this score with {@link #compositePermille()} filled from the other fields.
     *
     * @return the completed score.
     * @Thread-context any thread.
     */
    public ServiceScore withComposite() {
        return new ServiceScore(availabilityPermille, rttP50Millis, rttP95Millis, capacityPermille,
                freshnessPermille, reporterCount, recomputedComposite());
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SERVICE_SCORE).writeU16(Encodable.ENCODING_VERSION);
        w.writeU32(availabilityPermille);
        w.writeU32(rttP50Millis);
        w.writeU32(rttP95Millis);
        w.writeU32(capacityPermille);
        w.writeU32(freshnessPermille);
        w.writeU32(reporterCount);
        w.writeU32(compositePermille);
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the score.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static ServiceScore decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SERVICE_SCORE) {
            throw new IllegalStateException("expected ServiceScore tag, got " + tag);
        }
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported ServiceScore version " + version);
        }
        return new ServiceScore(r.readU32AsInt(), r.readU32AsInt(), r.readU32AsInt(),
                r.readU32AsInt(), r.readU32AsInt(), r.readU32AsInt(), r.readU32AsInt());
    }
}
