package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * A single validator's signed vote on a proposed commit (Task 2 consensuscert/). The
 * {@link Bytes signature} covers exactly the bytes returned by {@link #signedPortion()} (typeTag +
 * version + voter + resultingRoot + transitionRoot + decision + proposal anchor); the signature
 * itself is never signed. {@code transitionRoot} binds non-state effects such as inventory credits
 * to the vote, while the proposal anchor prevents replay across regions, epochs, or versions.
 *
 * <p>Wire form: {@code signedPortion()} || {@code [u32 len][signature bytes]}.
 *
 * @Thread-context immutable, any thread.
 */
public record SignedVote(
        NodeId voter,
        StateRoot resultingRoot,
        StateRoot transitionRoot,
        VoteDecision decision,
        Bytes signature,
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion baseVersion,
        StateRoot batchRoot,
        int bodyVersion
) implements Encodable {

    /** Version 1 was root-only; version 2 added transitionRoot; version 3 adds proposal anchor. */
    public static final int VOTE_ENCODING_VERSION = 3;

    /** Source-compatible constructor for legacy root-only votes. */
    public SignedVote(NodeId voter, StateRoot resultingRoot, VoteDecision decision, Bytes signature) {
        this(voter, resultingRoot, resultingRoot, decision, signature,
                null, null, null, null, 1);
    }

    /** Source-compatible constructor for version-2 transition-bound votes. */
    public SignedVote(
            NodeId voter, StateRoot resultingRoot, StateRoot transitionRoot,
            VoteDecision decision, Bytes signature) {
        this(voter, resultingRoot, transitionRoot, decision, signature,
                null, null, null, null, 2);
    }

    /** Current vote constructor, bound to the proposal key as well as the full transition. */
    public SignedVote(
            NodeId voter, RegionId region, RegionEpoch epoch, SnapshotVersion baseVersion,
            StateRoot batchRoot,
            StateRoot resultingRoot, StateRoot transitionRoot,
            VoteDecision decision, Bytes signature) {
        this(voter, resultingRoot, transitionRoot, decision, signature,
                region, epoch, baseVersion, batchRoot, VOTE_ENCODING_VERSION);
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public SignedVote {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (transitionRoot == null) {
            throw new IllegalArgumentException("transitionRoot must not be null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (signature == null) {
            throw new IllegalArgumentException("signature must not be null");
        }
        if (bodyVersion < 1 || bodyVersion > VOTE_ENCODING_VERSION) {
            throw new IllegalArgumentException("unsupported signed-vote body version " + bodyVersion);
        }
        if (bodyVersion == 1 && !transitionRoot.equals(resultingRoot)) {
            throw new IllegalArgumentException("version 1 vote cannot carry a transition root");
        }
        if (bodyVersion < 3
                && (region != null || epoch != null || baseVersion != null || batchRoot != null)) {
            throw new IllegalArgumentException("legacy vote cannot carry a proposal anchor");
        }
        if (bodyVersion >= 3
                && (region == null || epoch == null || baseVersion == null || batchRoot == null)) {
            throw new IllegalArgumentException("version 3 vote requires a proposal anchor");
        }
    }

    /**
     * The canonical bytes that the signature covers: {@code SIGNED_VOTE} typeTag + version + voter
     * + resultingRoot + transitionRoot + decision. A strict prefix of
     * {@link #encode(CanonicalWriter)}; excludes the signature by construction.
     *
     * @Thread-context deterministic; safe from any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.SIGNED_VOTE).writeU16(bodyVersion);
        voter.encode(w);
        resultingRoot.encode(w);
        if (bodyVersion >= 2) {
            transitionRoot.encode(w);
        }
        decision.encode(w);
        if (bodyVersion >= 3) {
            region.encode(w);
            epoch.encode(w);
            baseVersion.encode(w);
            batchRoot.encode(w);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode (signed fields + signature).
     *
     * @throws IllegalStateException if the next tag is not {@code SIGNED_VOTE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static SignedVote decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SIGNED_VOTE) {
            throw new IllegalStateException("expected SIGNED_VOTE tag, got " + tag);
        }
        int bodyVersion = r.readU16();
        if (bodyVersion < 1 || bodyVersion > VOTE_ENCODING_VERSION) {
            throw new IllegalStateException("unsupported SIGNED_VOTE encoding version " + bodyVersion);
        }
        NodeId voter = NodeId.decode(r);
        StateRoot resultingRoot = StateRoot.decode(r);
        StateRoot transitionRoot = bodyVersion >= 2 ? StateRoot.decode(r) : resultingRoot;
        VoteDecision decision = VoteDecision.decode(r);
        RegionId region = bodyVersion >= 3 ? RegionId.decode(r) : null;
        RegionEpoch epoch = bodyVersion >= 3 ? RegionEpoch.decode(r) : null;
        SnapshotVersion baseVersion = bodyVersion >= 3 ? SnapshotVersion.decode(r) : null;
        StateRoot batchRoot = bodyVersion >= 3 ? StateRoot.decode(r) : null;
        Bytes signature = r.readBytesValue();
        return new SignedVote(
                voter, resultingRoot, transitionRoot, decision, signature,
                region, epoch, baseVersion, batchRoot, bodyVersion);
    }
}
