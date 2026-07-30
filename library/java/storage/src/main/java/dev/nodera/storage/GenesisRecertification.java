package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The founding peer set's multi-party genesis endorsement (Task 16 / L-20): upgrades a world's
 * trust root from the author's single signature ({@link CertifiedWorldGenesis}, unchanged) to a
 * quorum of the founding peers. Each founder signs the same portion — the genesis root plus the
 * complete declared founding set — so no subset can re-declare a different set, and
 * {@link #verify} accepts only when a strict majority of DISTINCT founders' signatures check out
 * against their declared keys (the {@code CommitteeChangeCertificate} approval pattern).
 *
 * <p>Wire form (tag {@value TypeTags#GENESIS_RECERTIFICATION}, version 1):
 * {@code [u16 tag][u16 version][StateRoot genesisRoot][u32 founderCount]
 * [(NodeId, Bytes publicKey)...][u32 approvalCount][(NodeId, Bytes signature)...]}.
 *
 * @param genesisRoot the certified genesis root being endorsed.
 * @param founders    the declared founding set (id + public key), fixed order.
 * @param approvals   founder signatures over {@link #signedPortion()}.
 * @Thread-context immutable, any thread.
 */
public record GenesisRecertification(
        StateRoot genesisRoot,
        List<Founder> founders,
        List<Approval> approvals) implements Encodable {

    public static final int ENCODING_VERSION = 1;

    /** One declared founding peer. */
    public record Founder(NodeId nodeId, Bytes publicKey) {
        public Founder {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(publicKey, "publicKey");
        }

        void encode(CanonicalWriter w) {
            nodeId.encode(w);
            w.writeBytes(publicKey);
        }

        static Founder decode(CanonicalReader r) {
            return new Founder(NodeId.decode(r), r.readBytesValue());
        }
    }

    /** One founder's signature over the signed portion. */
    public record Approval(NodeId nodeId, Bytes signature) {
        public Approval {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(signature, "signature");
        }

        void encode(CanonicalWriter w) {
            nodeId.encode(w);
            w.writeBytes(signature);
        }

        static Approval decode(CanonicalReader r) {
            return new Approval(NodeId.decode(r), r.readBytesValue());
        }
    }

    public GenesisRecertification {
        Objects.requireNonNull(genesisRoot, "genesisRoot");
        Objects.requireNonNull(founders, "founders");
        Objects.requireNonNull(approvals, "approvals");
        if (founders.isEmpty()) {
            throw new IllegalArgumentException("the founding set must not be empty");
        }
        if (founders.stream().map(Founder::nodeId).distinct().count() != founders.size()) {
            throw new IllegalArgumentException("founders must be distinct");
        }
        founders = List.copyOf(founders);
        approvals = List.copyOf(approvals);
    }

    /** The quorum a valid re-certification needs: a strict majority of the founding set. */
    public int quorumThreshold() {
        return founders.size() / 2 + 1;
    }

    /** The canonical bytes every founder signs: root + the COMPLETE declared founding set. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.GENESIS_RECERTIFICATION).writeU16(ENCODING_VERSION);
        genesisRoot.encode(w);
        w.writeList(founders, (ww, f) -> f.encode(ww));
        return w.toBytes();
    }

    /** One founder's approval of {@code root} under {@code founders}. */
    public static Approval approve(
            StateRoot root, List<Founder> founders, NodeIdentity founder) {
        GenesisRecertification unsigned = new GenesisRecertification(root, founders, List.of());
        return new Approval(founder.nodeId(), founder.sign(unsigned.signedPortion()));
    }

    /**
     * @return true iff a strict majority of DISTINCT declared founders produced verifying
     *         signatures over the signed portion. Approvals from non-founders, duplicate
     *         founders, or with bad signatures never count.
     */
    public boolean verify(SignatureService signatures) {
        Bytes portion = signedPortion();
        Set<NodeId> counted = new HashSet<>();
        int valid = 0;
        for (Approval approval : approvals) {
            Founder founder = founders.stream()
                    .filter(f -> f.nodeId().equals(approval.nodeId()))
                    .findFirst().orElse(null);
            if (founder == null || !counted.add(approval.nodeId())) {
                continue;
            }
            if (signatures.verify(founder.publicKey(), portion, approval.signature())) {
                valid++;
            }
        }
        return valid >= quorumThreshold();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.GENESIS_RECERTIFICATION).writeU16(ENCODING_VERSION);
        genesisRoot.encode(w);
        w.writeList(founders, (ww, f) -> f.encode(ww));
        w.writeList(approvals, (ww, a) -> a.encode(ww));
    }

    /** Decode one full frame (tag + version validated). */
    public static GenesisRecertification decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.GENESIS_RECERTIFICATION) {
            throw new IllegalStateException("expected GENESIS_RECERTIFICATION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        StateRoot root = StateRoot.decode(r);
        List<Founder> founders = r.readList(Founder::decode);
        List<Approval> approvals = new ArrayList<>(r.readList(Approval::decode));
        return new GenesisRecertification(root, founders, approvals);
    }
}
