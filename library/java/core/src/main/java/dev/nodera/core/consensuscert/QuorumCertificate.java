package dev.nodera.core.consensuscert;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A quorum certificate: proof that a threshold of validators signed off on a commit (Task 2
 * consensuscert/). Carries the region/epoch/version anchor, the {@code prevRoot → resultingRoot}
 * transition, and the collection of {@link SignedVote}s.
 *
 * <p>The votes list is canonical: it is sorted by each voter's {@link NodeId} UUID and stored
 * unmodifiable, so two certificates that collected the same votes in different orders encode
 * identical bytes. This is consensus-critical — certificate identity is defined by the encoded
 * bytes.
 *
 * <p>Wire form: {@code [u16 QUORUM_CERTIFICATE][u16 ENCODING_VERSION][RegionId][RegionEpoch]
     * [SnapshotVersion][StateRoot prevRoot][StateRoot resultingRoot][list SignedVote]}.
 *
 * @Thread-context immutable, any thread.
 */
public record QuorumCertificate(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion version,
        StateRoot prevRoot,
        StateRoot resultingRoot,
        List<SignedVote> votes
) implements Encodable {

    private static final Comparator<SignedVote> VOTE_ORDER =
            Comparator.comparing(v -> v.voter().value());

    /**
     * Compact constructor. Defensive-copies {@code votes} into an unmodifiable list sorted by
     * voter {@link java.util.UUID} so the encoded form is byte-stable.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public QuorumCertificate {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (prevRoot == null) {
            throw new IllegalArgumentException("prevRoot must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (votes == null) {
            throw new IllegalArgumentException("votes must not be null");
        }
        List<SignedVote> sorted = new ArrayList<>(votes);
        sorted.sort(VOTE_ORDER);
        votes = List.copyOf(sorted);
    }

    /** Number of votes carried by this certificate. */
    public int voteCount() {
        return votes.size();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.QUORUM_CERTIFICATE).writeU16(ENCODING_VERSION);
        region.encode(w);
        epoch.encode(w);
        version.encode(w);
        prevRoot.encode(w);
        resultingRoot.encode(w);
        w.writeList(votes, CanonicalWriter::writeEncodable);
    }

    /**
     * Full-frame decode. The decoded votes list is re-canonicalised by the compact constructor.
     *
     * @throws IllegalStateException if the next tag is not {@code QUORUM_CERTIFICATE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static QuorumCertificate decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.QUORUM_CERTIFICATE) {
            throw new IllegalStateException("expected QUORUM_CERTIFICATE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        RegionId region = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        StateRoot prevRoot = StateRoot.decode(r);
        StateRoot resultingRoot = StateRoot.decode(r);
        List<SignedVote> votes = r.readList(SignedVote::decode);
        return new QuorumCertificate(region, epoch, version, prevRoot, resultingRoot, votes);
    }
}
