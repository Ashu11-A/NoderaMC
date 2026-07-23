package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
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

/**
 * Reopen-resume proof for issue #34 / L-50: the store head — quorum-committed or
 * external-committed — survives a journal restart and wins over the INITIAL genesis snapshot.
 */
final class EntityLaneResumeTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    @TempDir
    Path dir;

    @Test
    void externalHeadSurvivesJournalRestart() {
        EventSourcedWorldStore store = store();
        Path file = dir.resolve("external-heads.bin");
        RegionSnapshot snapshot = snapshotAt(2);
        ServerAuthorityCertificate certificate = externalCertificate(snapshot);
        new WorldStoreExternalHeads(store, HASHES, file)
                .externalCommitted(snapshot, certificate);

        WorldStoreExternalHeads restarted = new WorldStoreExternalHeads(store, HASHES, file);

        assertThat(restarted.head(REGION)).contains(snapshot);
        assertThat(restarted.headVersion(REGION)).contains(new SnapshotVersion(2));
    }

    @Test
    void externalHeadRejectsSnapshotCertificateMismatch() {
        EventSourcedWorldStore store = store();
        WorldStoreExternalHeads heads = new WorldStoreExternalHeads(
                store, HASHES, dir.resolve("external-heads.bin"));
        RegionSnapshot snapshot = snapshotAt(2);
        ServerAuthorityCertificate wrong = externalCertificate(snapshotAt(3));

        assertThatThrownBy(() -> heads.externalCommitted(snapshot, wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void laterExternalCommitSupersedesEarlierHead() {
        EventSourcedWorldStore store = store();
        WorldStoreExternalHeads heads = new WorldStoreExternalHeads(
                store, HASHES, dir.resolve("external-heads.bin"));
        heads.externalCommitted(snapshotAt(2), externalCertificate(snapshotAt(2)));
        heads.externalCommitted(snapshotAt(5), externalCertificate(snapshotAt(5)));

        assertThat(heads.headVersion(REGION)).contains(new SnapshotVersion(5));
    }

    @Test
    void quorumCommittedSnapshotIsResumableAfterRestart() {
        EventSourcedWorldStore store = store();
        Path file = dir.resolve("votes.bin");
        RegionExecutionRequest request = request();
        RegionExecutionResult result = engine().execute(request);
        WorldStoreVotePersistence votes = new WorldStoreVotePersistence(store, HASHES, file);
        votes.prepare(request, result);
        votes.commit(certificate(request, result));

        WorldStoreVotePersistence restarted = new WorldStoreVotePersistence(store, HASHES, file);

        assertThat(restarted.latestCommittedSnapshot(REGION))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.version()).isEqualTo(SnapshotVersion.INITIAL.next());
                    assertThat(StateRoot.of(HASHES.hash(snapshot)))
                            .isEqualTo(result.resultingRoot());
                });
    }

    @Test
    void preparedButUncommittedCandidateIsNotResumable() {
        EventSourcedWorldStore store = store();
        WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                store, HASHES, dir.resolve("votes.bin"));
        RegionExecutionRequest request = request();
        votes.prepare(request, engine().execute(request));

        assertThat(votes.latestCommittedSnapshot(REGION)).isEmpty();
    }

    @Test
    void resumeHeadPicksHighestVersionAcrossBothSources() {
        EventSourcedWorldStore store = store();
        WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                store, HASHES, dir.resolve("votes.bin"));
        WorldStoreExternalHeads externals = new WorldStoreExternalHeads(
                store, HASHES, dir.resolve("external-heads.bin"));
        RegionExecutionRequest request = request();
        RegionExecutionResult result = engine().execute(request);
        votes.prepare(request, result);
        votes.commit(certificate(request, result));

        // Quorum head is v1; an external commit at v4 must win.
        externals.externalCommitted(snapshotAt(4), externalCertificate(snapshotAt(4)));
        assertThat(EntityLaneResume.resumeHead(votes, externals, REGION))
                .hasValueSatisfying(snapshot ->
                        assertThat(snapshot.version()).isEqualTo(new SnapshotVersion(4)));
    }

    @Test
    void resumeHeadEmptyWhenNothingEverCommitted() {
        EventSourcedWorldStore store = store();
        WorldStoreVotePersistence votes = new WorldStoreVotePersistence(
                store, HASHES, dir.resolve("votes.bin"));
        WorldStoreExternalHeads externals = new WorldStoreExternalHeads(
                store, HASHES, dir.resolve("external-heads.bin"));

        assertThat(EntityLaneResume.resumeHead(votes, externals, REGION)).isEmpty();
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

    private static RegionSnapshot snapshotAt(long version) {
        return new RegionSnapshot(REGION, new SnapshotVersion(version), version, List.of());
    }

    private static ServerAuthorityCertificate externalCertificate(RegionSnapshot snapshot) {
        return new ServerAuthorityCertificate(
                snapshot.region(),
                new SnapshotVersion(snapshot.version().value() - 1),
                snapshot.version(),
                StateRoot.of(HASHES.hash(snapshot)),
                StateRoot.zero(),
                ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION,
                Bytes.empty());
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
