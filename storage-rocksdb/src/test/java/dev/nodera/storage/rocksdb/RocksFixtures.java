package dev.nodera.storage.rocksdb;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.GenesisManifest;

import java.util.List;
import java.util.UUID;

/** Fixed-value deterministic builders for the RocksDB-tier tests (mirrors StorageFixtures). */
final class RocksFixtures {

    static final HashService HASHES = new HashService();
    static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    static final GenesisManifest GENESIS =
            new GenesisManifest(0x4E4F4445_5241L, 1, 99L, root("genesis"));

    private RocksFixtures() {
    }

    static StateRoot root(String tag) {
        return StateRoot.of(HASHES.sha256(tag.getBytes()));
    }

    /** The deterministic root chain shared by the crash victim and the recovering parent. */
    static StateRoot chainRoot(long i) {
        return i < 0 ? RocksFixtures.GENESIS.genesisRoot() : root("chain-" + i);
    }

    static CommittedEventEnvelope event(RegionId region, long eventId,
                                        StateRoot prevRoot, StateRoot resultingRoot) {
        return new CommittedEventEnvelope(region, RegionEpoch.INITIAL,
                new SnapshotVersion(eventId + 1), eventId * 10L, eventId,
                new BlockChangedEvent(new NBlockPos(5, 70, 5), 0, 1),
                prevRoot, resultingRoot, Bytes.empty());
    }

    /** A chained event whose roots follow {@link #chainRoot}. */
    static CommittedEventEnvelope chainedEvent(RegionId region, long eventId) {
        return event(region, eventId, chainRoot(eventId - 1), chainRoot(eventId));
    }

    static QuorumCertificate certificate(StateRoot resultingRoot) {
        SignedVote v1 = new SignedVote(voter(1), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        SignedVote v2 = new SignedVote(voter(2), resultingRoot, VoteDecision.ACCEPT, Bytes.empty());
        return new QuorumCertificate(REGION, RegionEpoch.INITIAL, new SnapshotVersion(1),
                StateRoot.zero(), resultingRoot, List.of(v1, v2));
    }

    static NodeId voter(long lo) {
        return new NodeId(new UUID(0L, lo));
    }
}
