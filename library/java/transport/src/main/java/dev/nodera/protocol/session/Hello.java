package dev.nodera.protocol.session;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The first message on a peer connection: what this build is, and what it can do
 * (Task 14 phase 4, retiring {@code Plan.7} R2).
 *
 * <p>Nodera had four handshake message types and no handshake. Tags 1–4 existed in the codec and in
 * its tests, and no runtime handler ever constructed or consumed one: production authenticated the
 * carrier, sent {@code PeerJoin}, and admitted the peer — with no comparison of protocol version,
 * rules version, registry fingerprint, or feature set. Incompatibility was therefore never
 * <em>reported</em>; it was <em>experienced</em>, as a frame that failed to parse, a liveness timer
 * that expired, or an exception thrown from inside the region engine several minutes later.
 *
 * <p>This message is the question, {@link HelloAck} is the answer, and the answer is binding: the
 * features the two sides agree on become the session's emit profile, so a feature the peer did not
 * accept is never sent to it. That is what makes "tolerant readers, unconditional writers" a
 * structural impossibility rather than a per-message discipline nobody can keep.
 *
 * @param wireEpoch          the frame generation this peer speaks.
 * @param productVersion     the human-readable build identity, for logs and diagnostics only —
 *                           never a compatibility input. What a peer <em>can do</em> is
 *                           {@link #features}; what it <em>calls itself</em> is decoration.
 * @param features           the feature codes this peer supports; see {@link WireFeature}.
 * @param rulesVersion       the simulation rule set. A difference here means the two engines would
 *                           compute different state roots from the same input, which no amount of
 *                           encoding tolerance can bridge.
 * @param registryFingerprint the block/entity registry digest, for the same reason.
 * @param networkId          which network this peer believes it is joining.
 * @param nodeId             the claimed identity; checked against the transport-authenticated peer
 *                           before any answer is issued.
 * @param publicKey          the key {@link #signature} is verified against.
 * @param capabilities       what the peer offers the mesh.
 * @param signature          Ed25519 over the rest of the message.
 * @Thread-context immutable; any thread.
 */
public record Hello(int wireEpoch,
                    String productVersion,
                    Set<Integer> features,
                    int rulesVersion,
                    long registryFingerprint,
                    UUID networkId,
                    NodeId nodeId,
                    Bytes publicKey,
                    NodeCapabilities capabilities,
                    Bytes signature) implements NoderaMessage {

    public Hello {
        if (productVersion == null) {
            throw new IllegalArgumentException("productVersion must not be null; use \"\"");
        }
        if (nodeId == null || publicKey == null || capabilities == null || signature == null
                || networkId == null) {
            throw new IllegalArgumentException("Hello requires identity, key, capabilities, "
                    + "network and signature");
        }
        // Sorted and de-duplicated, and kept in a SORTED immutable view: a feature set with two
        // orders would be two byte strings meaning one value, and this message is signed.
        // (`Set.copyOf` would be wrong here — it returns an unordered set, so the encoder would
        // emit whatever iteration order the hash happened to produce.)
        features = features == null ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(features));
        for (int code : features) {
            // Feature codes share the u16 space every other code here uses. The bound is not
            // decoration: the set is sorted as signed ints and written as unsigned ones, so a code
            // above Integer.MAX_VALUE would encode in an order the decoder refuses to read back.
            if (code <= 0 || code > 0xFFFF) {
                throw new IllegalArgumentException("feature codes are positive u16; got " + code);
            }
        }
    }

    /** @return {@code true} if this peer advertises {@code feature}. */
    public boolean supports(WireFeature feature) {
        return features.contains(feature.code());
    }

    /**
     * The bytes {@link #signature} covers: every field except the signature itself.
     *
     * <p>The record owns this layout, following the same rule as {@code TrackerAnnounce}: the codec
     * and the signer must never be able to disagree about where the signed region ends, and the only
     * way to guarantee that is for one of them to define it.
     *
     * @return the canonical signed portion.
     * @Thread-context any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedPortion(w);
        return w.toBytes();
    }

    /**
     * Write the signed portion into {@code w}.
     *
     * @param w the destination.
     * @Thread-context any thread; does not retain the writer.
     */
    public void writeSignedPortion(CanonicalWriter w) {
        w.writeU16(SIGNED_PORTION_TAG).writeU16(Encodable.ENCODING_VERSION);
        w.writeU16(wireEpoch);
        w.writeString(productVersion);
        w.writeList(features, (ww, code) -> ww.writeU32(Integer.toUnsignedLong(code)));
        w.writeU32(Integer.toUnsignedLong(rulesVersion));
        w.writeU64(registryFingerprint);
        w.writeU64(networkId.getMostSignificantBits());
        w.writeU64(networkId.getLeastSignificantBits());
        nodeId.encode(w);
        w.writeBytes(publicKey);
        capabilities.encode(w);
    }

    /**
     * The message kind that opens the signed portion.
     *
     * <p>It is the frame's own kind, exactly as {@code TrackerAnnounce} does it: the signed portion
     * is a prefix of the canonical frame, so the codec writes the signed bytes and then the
     * signature, and there is no second definition of where the boundary is. Opening with the kind
     * also stops a signature over a {@code Hello} being replayed as a signature over some other
     * message that happens to share a field layout.
     */
    private static final int SIGNED_PORTION_TAG = 74;
}
