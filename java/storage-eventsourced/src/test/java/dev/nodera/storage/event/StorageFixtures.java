package dev.nodera.storage.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.event.RegionEvent;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.List;
import java.util.UUID;

/** Fixed-value deterministic builders for the storage tests. */
final class StorageFixtures {

    static final HashService HASHES = new HashService();
    static final RegionId REGION = new RegionId(dev.nodera.core.region.DimensionKey.overworld(), 0, 0);

    private StorageFixtures() {
    }

    static StateRoot root(String tag) {
        return StateRoot.of(HASHES.sha256(tag.getBytes()));
    }

    static NodeId voter(long lo) {
        return new NodeId(new UUID(0L, lo));
    }

    static QuorumCertificate certificate(RegionId region, SnapshotVersion version, StateRoot resultingRoot) {
        SignedVote v1 = new SignedVote(voter(1), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        SignedVote v2 = new SignedVote(voter(2), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        StateRoot prev = StateRoot.zero();
        return new QuorumCertificate(region, RegionEpoch.INITIAL, version, prev, resultingRoot, List.of(v1, v2));
    }

    static RegionEvent blockChange() {
        return new BlockChangedEvent(new NBlockPos(5, 70, 5), 0, 1);
    }

    /** Build a committed event whose certificate reference is the content hash of {@code cert}. */
    static CommittedEventEnvelope event(RegionId region, long eventId, SnapshotVersion version,
                                        StateRoot prevRoot, StateRoot resultingRoot, Bytes certHash) {
        return new CommittedEventEnvelope(region, RegionEpoch.INITIAL, version, eventId * 10L, eventId,
                blockChange(), prevRoot, resultingRoot, certHash);
    }
}
