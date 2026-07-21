package dev.nodera.protocol.rendezvous;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One reachability candidate: how a peer might be reached, and how good it is thought to be
 * (Task 29; rendezvous.md §2.5). A candidate is a peer's own claim, never proof of identity — the
 * transport handshake re-verifies the remote actually owns the expected {@code NodeId}
 * (rendezvous.md §4.4).
 *
 * <p>Encodable (type tag {@value TypeTags#PEER_CANDIDATE}) so it nests byte-identically inside a
 * {@link SignedPeerRecord} that is signed and verified across languages.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param kind     the candidate category.
 * @param address  the dial route ({@code host:port}), as the peer claims it.
 * @param priority preference — higher is better (ICE-style); a hint, never proof.
 */
public record PeerCandidate(CandidateKind kind, String address, int priority) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a reference argument is null or {@code priority} is
     *                                  negative.
     */
    public PeerCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(address, "address");
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be non-negative");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.PEER_CANDIDATE).writeU16(Encodable.ENCODING_VERSION);
        w.writeU8(kind.ordinal());
        w.writeString(address);
        w.writeU32(Integer.toUnsignedLong(priority));
    }

    /**
     * Decode the inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source.
     * @return the candidate.
     * @throws IllegalStateException if the tag/version is wrong.
     * @Thread-context one reader per decode call.
     */
    public static PeerCandidate decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.PEER_CANDIDATE) {
            throw new IllegalStateException("expected PeerCandidate tag, got " + tag);
        }
        int version = r.readU16();
        if (version != Encodable.ENCODING_VERSION) {
            throw new IllegalStateException("unsupported PeerCandidate version " + version);
        }
        CandidateKind kind = CandidateKind.decodeOrdinal(r);
        String address = r.readString();
        int priority = (int) r.readU32();
        return new PeerCandidate(kind, address, priority);
    }

    /** @return whether this candidate reaches the peer without a relay circuit. */
    public boolean isDirect() {
        return kind.isDirect();
    }
}
