package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.event.EventSourcedWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WorldStoreVotePersistenceTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    @TempDir
    Path dir;

    @Test
    void preparedSnapshotSurvivesJournalRestartAndBindsCertificateIdempotently() {
        EventSourcedWorldStore store = store();
        Path file = dir.resolve("votes.bin");
        RegionExecutionRequest request = request();
        RegionExecutionResult result = engine().execute(request);
        new WorldStoreVotePersistence(store, HASHES, file).prepare(request, result);

        WorldStoreVotePersistence restarted = new WorldStoreVotePersistence(store, HASHES, file);
        QuorumCertificate certificate = certificate(request, result);
        restarted.commit(certificate);
        restarted.commit(certificate);

        assertThat(store.content().size()).isEqualTo(1);
        assertThat(store.certificates().getByHash(
                store.certificates().put(certificate).hash())).contains(certificate);
    }

    @Test
    void certificateForAnotherRootCannotConsumePreparedCandidate() {
        EventSourcedWorldStore store = store();
        WorldStoreVotePersistence persistence = new WorldStoreVotePersistence(
                store, HASHES, dir.resolve("votes.bin"));
        RegionExecutionRequest request = request();
        RegionExecutionResult result = engine().execute(request);
        persistence.prepare(request, result);
        QuorumCertificate wrong = new QuorumCertificate(
                REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                StateRoot.of(HASHES.hash(request.snapshot())), StateRoot.zero(),
                certificate(request, result).votes());

        assertThatThrownBy(() -> persistence.commit(wrong))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    private static EventSourcedWorldStore store() {
        return new EventSourcedWorldStore(
                new GenesisManifest(1, FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), StateRoot.zero()), HASHES);
    }

    private static FlatWorldRegionEngine engine() {
        return new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), HASHES);
    }

    private static RegionExecutionRequest request() {
        RegionSnapshot snapshot = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of());
        ActionBatch batch = new ActionBatch(
                REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 1, 1, List.of());
        return new RegionExecutionRequest(new RegionExecutionContext(
                REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 1, 1,
                1, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint()),
                snapshot, batch);
    }

    private static QuorumCertificate certificate(
            RegionExecutionRequest request, RegionExecutionResult result) {
        StateRoot batchRoot = StateRoot.of(HASHES.hash(request.batch()));
        StateRoot transitionRoot = StateRoot.of(HASHES.hash(result.delta()));
        SignedVote first = new SignedVote(
                new NodeId(new UUID(0, 1)), REGION, RegionEpoch.INITIAL,
                SnapshotVersion.INITIAL, batchRoot, result.resultingRoot(), transitionRoot,
                VoteDecision.ACCEPT, Bytes.empty());
        SignedVote second = new SignedVote(
                new NodeId(new UUID(0, 2)), REGION, RegionEpoch.INITIAL,
                SnapshotVersion.INITIAL, batchRoot, result.resultingRoot(), transitionRoot,
                VoteDecision.ACCEPT, Bytes.empty());
        return new QuorumCertificate(
                REGION, RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                StateRoot.of(HASHES.hash(request.snapshot())), result.resultingRoot(),
                List.of(first, second));
    }
}
