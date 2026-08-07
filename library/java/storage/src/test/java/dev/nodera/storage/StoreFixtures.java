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
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.testkit.engine.EngineFixtures;

import java.util.List;

/**
 * Fixed-value deterministic builders shared by every storage-tier test (the union of the former
 * per-module {@code StorageFixtures} and {@code RocksFixtures}).
 *
 * <p>What is here is the storage tier's own vocabulary — a genesis manifest, a quorum certificate,
 * a chained committed event. The four values it shares with the rest of the tree (the hasher, the
 * world seed, the region on the grid and a fixed-value node id) are taken from
 * {@link EngineFixtures} rather than re-typed: a storage test and an engine test that disagreed
 * about which region {@code 0,0} is, or about what seed the world runs on, would be writing and
 * reading rows that do not describe the same world while both looking correct.
 */
public final class StoreFixtures {

    public static final HashService HASHES = EngineFixtures.hashes();
    public static final RegionId REGION = EngineFixtures.region(0, 0);
    public static final GenesisManifest GENESIS =
            new GenesisManifest(EngineFixtures.WORLD_SEED, 1, 99L, root("genesis"));

    private StoreFixtures() {
    }

    public static StateRoot root(String tag) {
        return StateRoot.of(HASHES.sha256(tag.getBytes()));
    }

    public static NodeId voter(long lo) {
        return EngineFixtures.node(lo);
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
