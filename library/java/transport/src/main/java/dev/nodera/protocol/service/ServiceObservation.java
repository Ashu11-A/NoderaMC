package dev.nodera.protocol.service;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;

/**
 * One peer's measurement of one service over a window (type tag
 * {@value TypeTags#SERVICE_OBSERVATION}).
 *
 * <p>Counters and percentiles only — deliberately <b>not a verdict</b>. A peer that reported "this
 * service is bad" would be asking the tracker to trust its judgement; a peer that reports "I probed
 * it 20 times and 3 answered" lets the tracker aggregate evidence and lets other peers' numbers
 * outvote a liar.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param service                 the service this observation is about.
 * @param kind                    which kind it is, so a report is usable with no directory lookup.
 * @param probes                  probes attempted in the window.
 * @param successes               probes that got a well-formed answer.
 * @param rttP50Millis            median round-trip time over the successful probes.
 * @param rttP95Millis            95th-percentile round-trip time over the successful probes.
 * @param observedAtEpochMillis   when the window closed — a freshness bound.
 */
public record ServiceObservation(
        NodeId service,
        ServiceKind kind,
        int probes,
        int successes,
        int rttP50Millis,
        int rttP95Millis,
        long observedAtEpochMillis) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference argument is null, a counter is negative, or
     *                                  {@code successes} exceeds {@code probes}.
     */
    public ServiceObservation {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(kind, "kind");
        if (probes < 0 || successes < 0 || rttP50Millis < 0 || rttP95Millis < 0
                || observedAtEpochMillis < 0) {
            throw new IllegalArgumentException("observation fields must be non-negative");
        }
        if (successes > probes) {
            throw new IllegalArgumentException("successes " + successes + " exceeds probes " + probes);
        }
    }

    /**
     * Availability in permille over this window.
     *
     * <p>A window with no probes is not evidence, so it reads as zero and contributes nothing rather
     * than counting as a perfect or a failed service.
     *
     * @return 0..1000.
     * @Thread-context any thread.
     */
    public int availabilityPermille() {
        if (probes == 0) {
            return 0;
        }
        return (int) (((long) successes * ServiceScore.PERMILLE) / probes);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.SERVICE_OBSERVATION, Encodable.ENCODING_VERSION);
        service.encode(w);
        w.writeU8(kind.ordinal());
        w.writeU32(probes);
        w.writeU32(successes);
        w.writeU32(rttP50Millis);
        w.writeU32(rttP95Millis);
        w.writeU64(observedAtEpochMillis);
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the observation.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static ServiceObservation decode(CanonicalReader r) {
        r.expectFrame(TypeTags.SERVICE_OBSERVATION, "ServiceObservation");
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported ServiceObservation version " + version);
        }
        NodeId service = NodeId.decode(r);
        ServiceKind kind = ServiceKind.decodeOrdinal(r);
        int probes = r.readU32AsInt();
        int successes = r.readU32AsInt();
        int p50 = r.readU32AsInt();
        int p95 = r.readU32AsInt();
        long observedAt = r.readU64();
        return new ServiceObservation(service, kind, probes, successes, p50, p95, observedAt);
    }
}
