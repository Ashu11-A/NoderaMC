package dev.nodera.protocol.service;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A peer's signed report of what it measured (wire tag
 * {@value MessageCodec#TAG_SERVICE_SCORE_REPORT}).
 *
 * <p>Signed like a {@code TrackerAnnounce} — over the frame minus the trailing signature — so the
 * tracker can attribute a report to one identity and cap how much any single identity may move a
 * score. Without attribution, scoring would be the easiest denial-of-service in the system: one
 * host could report every rival rendezvous as dead and take over routing for the whole network.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param reporter          the reporting peer.
 * @param publicKey         the reporter's public key.
 * @param networkId         the network the measurements were taken in.
 * @param observations      one row per measured service.
 * @param reportEpochMillis the reporter's wall-clock at report time — a freshness bound.
 * @param signature         Ed25519 over {@link #signedPortion()}.
 */
public record ServiceScoreReport(
        NodeId reporter,
        Bytes publicKey,
        UUID networkId,
        List<ServiceObservation> observations,
        long reportEpochMillis,
        Bytes signature) implements NoderaMessage {

    /**
     * Compact constructor: validates and defensive-copies the observations.
     *
     * @throws IllegalArgumentException if a reference argument is null or the timestamp is negative.
     */
    public ServiceScoreReport {
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(signature, "signature");
        if (reportEpochMillis < 0) {
            throw new IllegalArgumentException("reportEpochMillis must be non-negative");
        }
        observations = List.copyOf(observations);
    }

    /**
     * The exact bytes the signature covers: the full canonical frame minus the trailing signature.
     *
     * @return the signed portion.
     * @Thread-context any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter(512);
        writeSignedPortion(w);
        return w.toBytes();
    }

    /**
     * Write the signed portion into {@code w} — used by both {@link #signedPortion()} and the codec,
     * so the two can never disagree about where the signature starts.
     *
     * @param w the canonical sink.
     * @Thread-context any thread.
     */
    public void writeSignedPortion(CanonicalWriter w) {
        w.writeU16(MessageCodec.TAG_SERVICE_SCORE_REPORT).writeU16(Encodable.ENCODING_VERSION);
        reporter.encode(w);
        w.writeBytes(publicKey);
        w.writeU64(networkId.getMostSignificantBits());
        w.writeU64(networkId.getLeastSignificantBits());
        w.writeList(observations, (ww, o) -> o.encode(ww));
        w.writeU64(reportEpochMillis);
    }
}
