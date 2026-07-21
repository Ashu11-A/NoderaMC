package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.event.RegionEvent;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.List;
import java.util.UUID;

/**
 * Fixed-value deterministic builders shared by every storage-tier test (the union of the former
 * per-module {@code StorageFixtures} and {@code RocksFixtures}).
 */
public final class StoreFixtures {

    public static final HashService HASHES = new HashService();
    public static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    public static final GenesisManifest GENESIS =
            new GenesisManifest(0x4E4F4445_5241L, 1, 99L, root("genesis"));

    private StoreFixtures() {
    }

    public static StateRoot root(String tag) {
        return StateRoot.of(HASHES.sha256(tag.getBytes()));
    }

    public static NodeId voter(long lo) {
        return new NodeId(new UUID(0L, lo));
    }

    /** The deterministic root chain shared by the crash victim and the recovering parent. */
    public static StateRoot chainRoot(long i) {
        return i < 0 ? GENESIS.genesisRoot() : root("chain-" + i);
    }

    public static RegionEvent blockChange() {
        return new BlockChangedEvent(new NBlockPos(5, 70, 5), 0, 1);
    }

    public static QuorumCertificate certificate(RegionId region, SnapshotVersion version,
                                                StateRoot resultingRoot) {
        SignedVote v1 = new SignedVote(voter(1), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        SignedVote v2 = new SignedVote(voter(2), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        StateRoot prev = StateRoot.zero();
        return new QuorumCertificate(region, RegionEpoch.INITIAL, version, prev, resultingRoot,
                List.of(v1, v2));
    }

    public static QuorumCertificate certificate(StateRoot resultingRoot) {
        return certificate(REGION, new SnapshotVersion(1), resultingRoot);
    }

    /** Build a committed event whose certificate reference is the content hash of {@code cert}. */
    public static CommittedEventEnvelope event(RegionId region, long eventId, SnapshotVersion version,
                                               StateRoot prevRoot, StateRoot resultingRoot,
                                               Bytes certHash) {
        return new CommittedEventEnvelope(region, RegionEpoch.INITIAL, version, eventId * 10L,
                eventId, blockChange(), prevRoot, resultingRoot, certHash);
    }

    public static CommittedEventEnvelope event(RegionId region, long eventId,
                                               StateRoot prevRoot, StateRoot resultingRoot) {
        return event(region, eventId, new SnapshotVersion(eventId + 1), prevRoot, resultingRoot,
                Bytes.empty());
    }

    /** A chained event whose roots follow {@link #chainRoot}. */
    public static CommittedEventEnvelope chainedEvent(RegionId region, long eventId) {
        return event(region, eventId, chainRoot(eventId - 1), chainRoot(eventId));
    }
}
