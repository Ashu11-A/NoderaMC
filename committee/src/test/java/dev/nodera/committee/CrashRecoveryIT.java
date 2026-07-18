package dev.nodera.committee;

import dev.nodera.consensus.ProposalKey;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.coordinator.ReliabilityLedger;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.RegionSnapshotSplitter;
import dev.nodera.peer.archival.ArchiveAuditTask;
import dev.nodera.peer.archival.ArchiveObjectClass;
import dev.nodera.peer.archival.ArchiveRepairService;
import dev.nodera.peer.archival.RendezvousArchivePolicy;
import dev.nodera.peer.discovery.ArchiveInventory;
import dev.nodera.protocol.content.PieceBitmap;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.storage.ContentId;
import dev.nodera.storage.event.EventReplayer;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.storage.event.InMemoryRegionEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task-24 hard-crash proof: quorum writes before voting, abrupt primary death skips shutdown hooks,
 * real destination stores regain ×5 snapshot placement, and a survivor replays its certificate log.
 */
final class CrashRecoveryIT {

    private static final HashService HASHES = new HashService();

    @TempDir
    Path tempDir;

    @Test
    void forcedPrimaryDeathLosesNoCommittedStateAndRepairRestoresArchivalFactor() throws Exception {
        RegionId region = CommFixtures.region(0, 0);
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        ActionBatch batch = CommFixtures.batch(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(CommFixtures.place(region, 1, 0, 5, 70, 5, 1)));
        RegionExecutionRequest request = CommFixtures.request(base, batch);

        List<DurableReplica> replicas = List.of(
                new DurableReplica(), new DurableReplica(), new DurableReplica());
        List<CommitteeMember> members = replicas.stream()
                .map(replica -> new CommitteeMember(
                        CommFixtures.identity(), CommFixtures.engine(), replica))
                .toList();
        InMemoryWorldView canonicalWorld = new InMemoryWorldView();
        canonicalWorld.load(base);
        CommitteeSession session = CommitteeSession.mvp(
                new WorldMutationApplier(canonicalWorld), new ReliabilityLedger());

        CommitResult commit = session.runBatch(
                new ProposalKey(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL),
                CommFixtures.rootOf(base), members, request);

        assertThat(commit.isCommitted()).isTrue();
        assertThat(commit.certificate().voteCount()).isGreaterThanOrEqualTo(2);
        assertThat(replicas).allSatisfy(replica -> {
            assertThat(replica.preparedSnapshot).isNotNull();
            assertThat(replica.preparedRoot()).isEqualTo(commit.committedRoot());
        });
        assertThat(replicas.stream().filter(DurableReplica::isCertified).count())
                .isEqualTo(commit.certificate().voteCount());
        DurableReplica retryReplica = replicas.stream()
                .filter(DurableReplica::isCertified).findFirst().orElseThrow();
        retryReplica.commit(commit.certificate());
        assertThat(retryReplica.events.lastEventId(region)).isZero();

        // Real forcibly-destroyed JVM: its shutdown hook never runs. Dropping replica 0 below models
        // the primary's process-local store disappearing at the same instant, before stream/flush.
        assertHardKillSkipsShutdownHook();
        DurableReplica crashedPrimary = replicas.get(0);
        crashedPrimary.crash();
        List<DurableReplica> survivors = replicas.subList(1, replicas.size());
        assertThat(crashedPrimary.preparedSnapshot).isNull();
        assertThat(survivors).allSatisfy(replica ->
                assertThat(replica.preparedRoot()).isEqualTo(commit.committedRoot()));
        assertThat(survivors.stream().filter(DurableReplica::isCertified).count())
                .isGreaterThanOrEqualTo(1);

        DurableReplica sourceReplica = survivors.stream()
                .filter(DurableReplica::isCertified)
                .findFirst().orElseThrow();
        RegionSnapshotSplitter.Layout layout = sourceReplica.layout;
        Bytes world = Bytes.fromHex("ca24");
        ArchiveInventory inventory = new ArchiveInventory();
        Map<NodeId, Map<Integer, Bytes>> physical = new HashMap<>();
        List<NodeId> eligible = new ArrayList<>();
        for (CommitteeMember member : members.subList(1, members.size())) {
            eligible.add(member.nodeId());
            seed(member.nodeId(), layout, physical, inventory, world);
        }
        for (long i = 100; i < 106; i++) {
            eligible.add(new NodeId(new UUID(0xCA24L, i)));
        }

        RendezvousArchivePolicy placement = new RendezvousArchivePolicy();
        ArchiveAuditTask audit = new ArchiveAuditTask(placement);
        ArchiveAuditTask.AuditResult missing = audit.audit(
                layout.manifest().manifestRoot(), layout.manifest().pieceCount(),
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of(), inventory);
        assertThat(missing.isHealthy()).isFalse();

        ArchiveRepairService repair = new ArchiveRepairService(
                (from, root, index) -> Optional.ofNullable(
                        physical.getOrDefault(from, Map.of()).get(index)),
                (root, index, payload) -> layout.manifest().verifyPiece(index, payload),
                (assignee, root, index, payload) -> {
                    if (!layout.manifest().verifyPiece(index, payload)) {
                        return false;
                    }
                    physical.computeIfAbsent(assignee, ignored -> new HashMap<>())
                            .put(index, payload);
                    return payload.equals(physical.get(assignee).get(index));
                },
                Integer.MAX_VALUE,
                Long.MAX_VALUE);
        ArchiveRepairService.RepairOutcome repaired = repair.repair(
                world, layout.manifest().manifestRoot(), missing, inventory,
                ArchiveRepairService.holderSource(
                        layout.manifest().manifestRoot(), inventory, null));

        assertThat(repaired.planComplete()).isTrue();
        ArchiveAuditTask.AuditResult healthy = audit.audit(
                layout.manifest().manifestRoot(), layout.manifest().pieceCount(),
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of(), inventory);
        assertThat(healthy.isHealthy()).isTrue();
        assertThat(healthy.expectedHolders()).hasSize(5);
        for (NodeId holder : healthy.expectedHolders()) {
            assertThat(physical.get(holder)).hasSize(layout.manifest().pieceCount());
        }

        EventReplayer.ReplayResult replay = EventReplayer.replay(
                sourceReplica.events, sourceReplica.certificates, region,
                CommFixtures.rootOf(base), 0);
        assertThat(replay.finalRoot()).isEqualTo(commit.committedRoot());
        assertThat(replay.eventsApplied()).isEqualTo(1);
        assertThat(replay.stoppedAtUncertified()).isFalse();

        RegionSnapshot restarted = RegionSnapshot.decode(new CanonicalReader(layout.blob()));
        assertThat(StateRoot.of(HASHES.hash(restarted))).isEqualTo(commit.committedRoot());
        assertThat(restarted).isEqualTo(sourceReplica.preparedSnapshot);
    }

    private void assertHardKillSkipsShutdownHook() throws Exception {
        Path hookMarker = tempDir.resolve("shutdown-hook-ran");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process victim = new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                CrashVictimMain.class.getName(),
                hookMarker.toString())
                .redirectErrorStream(true)
                .start();
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(victim.getInputStream()))) {
            assertThat(output.readLine()).isEqualTo("READY");
        }
        victim.destroyForcibly();
        assertThat(victim.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(victim.exitValue()).isNotZero();
        assertThat(Files.exists(hookMarker)).isFalse();
    }

    private static void seed(
            NodeId holder,
            RegionSnapshotSplitter.Layout layout,
            Map<NodeId, Map<Integer, Bytes>> physical,
            ArchiveInventory inventory,
            Bytes world) {
        Map<Integer, Bytes> stored = physical.computeIfAbsent(holder, ignored -> new HashMap<>());
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int i = 0; i < layout.manifest().pieceCount(); i++) {
            Piece piece = layout.manifest().piece(i);
            stored.put(i, new Bytes(
                    layout.blob().toArray(),
                    Math.toIntExact(piece.offset()),
                    Math.toIntExact(piece.length())));
            indexes.add(i);
        }
        inventory.record(
                world, layout.manifest().manifestRoot(), holder, PieceBitmap.of(indexes));
    }

    /** Test process representing a primary that is killed without any graceful callback. */
    public static final class CrashVictimMain {
        private CrashVictimMain() {}

        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[0]);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(marker, "ran");
                } catch (Exception ignored) {
                    // Marker is only test evidence.
                }
            }));
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(Duration.ofDays(1));
        }
    }

    private static final class DurableReplica implements VotePersistence {
        private RegionExecutionRequest preparedRequest;
        private RegionExecutionResult preparedResult;
        private RegionSnapshot preparedSnapshot;
        private RegionSnapshotSplitter.Layout layout;
        private InMemoryCertificateStore certificates = new InMemoryCertificateStore(HASHES);
        private InMemoryRegionEventStore events = new InMemoryRegionEventStore();

        @Override
        public void prepare(RegionExecutionRequest request, RegionExecutionResult result) {
            InMemoryWorldView world = new InMemoryWorldView();
            world.load(request.snapshot());
            WorldMutationApplier.ApplyResult applied =
                    new WorldMutationApplier(world).apply(result.delta());
            if (!applied.committed()) {
                throw new IllegalStateException("candidate delta did not apply to its base snapshot");
            }
            RegionSnapshot snapshot = world.reExtract(
                    result.delta().region(),
                    result.delta().resultingVersion(),
                    request.context().tickTo());
            StateRoot root = StateRoot.of(HASHES.hash(snapshot));
            if (!root.equals(result.resultingRoot())) {
                throw new IllegalStateException("prepared snapshot root does not match vote root");
            }
            preparedRequest = request;
            preparedResult = result;
            preparedSnapshot = snapshot;
            layout = RegionSnapshotSplitter.split(snapshot, 512);
        }

        @Override
        public void commit(QuorumCertificate certificate) {
            if (preparedResult == null
                    || !preparedResult.resultingRoot().equals(certificate.resultingRoot())) {
                throw new IllegalStateException("certificate does not match prepared candidate");
            }
            ContentId id = certificates.contentId(certificate);
            if (events.lastEventId(preparedRequest.context().region()) == 0) {
                if (certificates.has(id)) {
                    return; // idempotent retry of the same canonical certificate.
                }
                throw new IllegalStateException("different certificate already committed");
            }
            certificates.put(certificate);
            BlockMutation mutation = preparedResult.delta().blockMutations().get(0);
            events.append(new CommittedEventEnvelope(
                    preparedRequest.context().region(),
                    preparedRequest.context().epoch(),
                    certificate.version(),
                    preparedRequest.context().tickTo(),
                    0,
                    new BlockChangedEvent(
                            mutation.pos(), mutation.expectedPreviousStateId(), mutation.newStateId()),
                    certificate.prevRoot(),
                    certificate.resultingRoot(),
                    id.hash()));
        }

        StateRoot preparedRoot() {
            return preparedSnapshot == null ? null : StateRoot.of(HASHES.hash(preparedSnapshot));
        }

        boolean isCertified() {
            return events.lastEventId(preparedRequest.context().region()) == 0;
        }

        void crash() {
            preparedRequest = null;
            preparedResult = null;
            preparedSnapshot = null;
            layout = null;
            certificates = new InMemoryCertificateStore(HASHES);
            events = new InMemoryRegionEventStore();
        }
    }
}
