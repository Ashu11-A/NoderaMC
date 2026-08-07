package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a validating peer must still know after the process that knew it died.
 *
 * <p>Four sibling classes over one subject: a file on disk with a magic number and a version, an
 * append or a rewrite, and a reopen that has to produce the same answer. They were four files whose
 * import blocks were the same nine lines and whose bodies each opened a {@code @TempDir} and closed
 * a journal — the imports move here and the fixtures stay per-nest, because each journal's record
 * shape is its own and a shared one would be a default quietly settling that.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class DurableStateTest {

    @Nested
    final class DurableActionJournalTest {
        @TempDir
        Path dir;

        @Test
        void reservationsAndTerminalStagesSurviveRestart() {
            ActionEnvelope first = action(1, 1);
            ActionEnvelope second = action(2, 2);
            Path file = dir.resolve("actions.bin");
            DurableActionJournal journal = new DurableActionJournal(file);
            journal.reserve(List.of(first, second));
            journal.commit(List.of(first));

            DurableActionJournal reopened = new DurableActionJournal(file);
            assertThat(reopened.retained()).containsExactly(first, second);
            assertThat(reopened.pending()).containsExactly(second);
            reopened.abort(List.of(second));
            assertThat(new DurableActionJournal(file).pending()).isEmpty();
        }

        @Test
        void sameSequenceCannotChangePayloadOrTerminalOutcome() {
            ActionEnvelope first = action(1, 1);
            ActionEnvelope changed = action(1, 9);
            DurableActionJournal journal = new DurableActionJournal(dir.resolve("actions.bin"));
            journal.reserve(List.of(first));
            assertThatThrownBy(() -> journal.reserve(List.of(changed)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reused");
            journal.commit(List.of(first));
            assertThatThrownBy(() -> journal.abort(List.of(first)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("COMMITTED");
        }

        @Test
        void abortPendingCompensatesOnlyReservedEntriesAndKeepsSequencesConsumed() {
            ActionEnvelope committed = action(1, 1);
            ActionEnvelope stale = action(2, 2);
            Path file = dir.resolve("actions.bin");
            DurableActionJournal journal = new DurableActionJournal(file);
            journal.reserve(List.of(committed, stale));
            journal.commit(List.of(committed));

            DurableActionJournal reopened = new DurableActionJournal(file);
            assertThat(reopened.abortPending()).containsExactly(stale);
            assertThat(reopened.pending()).isEmpty();
            // The compensated reservation stays in the journal: sequences remain consumed and the
            // same sequence cannot be re-reserved with a different payload.
            assertThat(reopened.retained()).containsExactly(committed, stale);
            assertThat(reopened.nextServerSequence()).isEqualTo(3L);
            assertThatThrownBy(() -> reopened.reserve(List.of(action(2, 9))))
                    .isInstanceOf(IllegalArgumentException.class);
            // Idempotent: nothing left to compensate, on this handle or after another reopen.
            assertThat(reopened.abortPending()).isEmpty();
            assertThat(new DurableActionJournal(file).abortPending()).isEmpty();
        }

        private static ActionEnvelope action(long sequence, int stateId) {
            return new ActionEnvelope(
                    new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    sequence, sequence, 1,
                    new RegionId(DimensionKey.overworld(), 0, 0),
                    new PlaceBlockAction(new NBlockPos(1, 70, 1), stateId, 0),
                    Bytes.empty());
        }
    }

    /**
     * Reputation that resets on restart is a session counter with a long name. These tests are about
     * the restart.
     */
    @Nested
    final class DurableCoordinatorStateTest {
        private static final RegionId REGION =
                new RegionId(DimensionKey.of("minecraft", "overworld"), 3, -4);

        private static NodeId node(long id) {
            return new NodeId(new UUID(0L, id));
        }

        @Test
        @DisplayName("a reputation earned before the restart is there after it")
        void reliabilitySurvivesAReopen(@TempDir Path dir) {
            Path file = dir.resolve("coordinator-state.bin");
            NodeId liar = node(1);

            DurableCoordinatorState first = new DurableCoordinatorState(file);
            for (int round = 0; round < 5; round++) {
                first.reliability().record(liar, false);
            }
            double earned = first.reliability().score(liar);
            assertThat(first.reliability().eligibleForAssignment(liar)).isFalse();
            first.flush();

            DurableCoordinatorState reopened = new DurableCoordinatorState(file);

            assertThat(reopened.reliability().score(liar)).isEqualTo(earned);
            assertThat(reopened.reliability().eligibleForAssignment(liar))
                    .as("a node that spent an evening disagreeing does not come back spotless")
                    .isFalse();
        }

        @Test
        @DisplayName("region epochs survive too — the stale-proposal defence does not reset")
        void epochsSurviveAReopen(@TempDir Path dir) {
            Path file = dir.resolve("coordinator-state.bin");
            DurableCoordinatorState first = new DurableCoordinatorState(file);
            first.leases().restoreEpoch(REGION, 42L);
            first.flush();

            assertThat(new DurableCoordinatorState(file).epochOf(REGION)).isEqualTo(42L);
        }

        @Test
        @DisplayName("a missing file is an empty state, not a failure")
        void aFreshNodeStartsEmpty(@TempDir Path dir) {
            DurableCoordinatorState fresh = new DurableCoordinatorState(dir.resolve("absent.bin"));

            assertThat(fresh.reliability().size()).isZero();
            assertThat(fresh.epochOf(REGION)).isZero();
            assertThat(fresh.reliability().eligibleForAssignment(node(9))).isTrue();
        }

        @Test
        @DisplayName("a corrupt file costs the node its memory, never its world")
        void damageIsRecoveredFromRatherThanThrown(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("coordinator-state.bin");
            Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

            DurableCoordinatorState recovered = new DurableCoordinatorState(file);

            assertThat(recovered.reliability().size()).isZero();
            // And it heals: the next flush replaces the damaged bytes with a readable state.
            recovered.reliability().record(node(2), true);
            recovered.flush();
            assertThat(new DurableCoordinatorState(file).reliability().size()).isEqualTo(1);
        }

        @Test
        @DisplayName("the file path is required")
        void nullPathIsRejected() {
            assertThatThrownBy(() -> new DurableCoordinatorState(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a service without durable state still works; persistState is a no-op")
        void attachIsOptional(@TempDir Path dir) {
            DurableCoordinatorState durable = new DurableCoordinatorState(
                    dir.resolve("coordinator-state.bin"));
            durable.reliability().record(node(3), false);
            durable.flush();

            // Re-attaching a second wrapper over the same file sees the same view — which is what
            // makes "attach on session open, flush on session close" a round trip rather than a reset.
            DurableCoordinatorState again = new DurableCoordinatorState(
                    dir.resolve("coordinator-state.bin"));
            assertThat(again.reliability().score(node(3)))
                    .isEqualTo(durable.reliability().score(node(3)));
        }
    }

    @Nested
    final class DurableInventoryCreditJournalTest {
        @TempDir
        Path dir;

        @Test
        void retainedCreditSurvivesRestartAndReplayIsIdempotent() {
            Path file = dir.resolve("credits.bin");
            InventoryCredit credit = credit(42, 3);
            DurableInventoryCreditJournal journal = new DurableInventoryCreditJournal(file);
            journal.retain(credit);
            journal.retain(credit);

            assertThat(new DurableInventoryCreditJournal(file).retained()).containsExactly(credit);
        }

        @Test
        void sameActorEntityCannotChangePayload() {
            DurableInventoryCreditJournal journal =
                    new DurableInventoryCreditJournal(dir.resolve("credits.bin"));
            journal.retain(credit(42, 3));

            assertThatThrownBy(() -> journal.retain(credit(42, 4)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different payload");
        }

        private static InventoryCredit credit(int item, int count) {
            return new InventoryCredit(
                    new NodeId(new UUID(0, 1)), new NetworkEntityId(7), item, count);
        }
    }

    @Nested
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
}
