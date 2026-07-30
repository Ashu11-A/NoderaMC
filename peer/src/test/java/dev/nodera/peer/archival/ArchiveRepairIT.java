package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.distribution.Piece;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.RegionSnapshotSplitter;
import dev.nodera.peer.discovery.ArchiveInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 21 acceptance #2: kill holders of a ×5 manifest → the audit detects the loss → repair
 * re-replicates back to the factor within the budget, and <b>no committed data is lost</b> — every
 * repaired piece still verifies against the manifest.
 *
 * <p>Headless: uses real Task-19 pieces/manifests. The fetcher serves a piece iff the source holds
 * it (per the {@link ArchiveInventory}) and returns the manifest's own verified bytes for that
 * index — exactly what a real seeder does (it serves content it holds, content-addressed by hash).
 * The verifier is the manifest itself, so the integrity claim is genuine.
 *
 * <p>Thread-context: single test thread.
 */
final class ArchiveRepairIT {

    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;

    @Test
    void repairReReplicatesAKilledManifestBackToTheFactorWithNoDataLoss() {
        Fixture f = new Fixture("aa21");
        int pieces = f.manifest.pieceCount();
        assertThat(pieces).isGreaterThanOrEqualTo(4);

        // 8 partial peers + 1 full-archive host.
        List<NodeId> partials = partials(0xA21L, 8);
        NodeId host = new NodeId(new UUID(0xA21L, 999L));
        List<NodeId> eligible = new ArrayList<>(partials);
        eligible.add(host);
        Set<NodeId> fullArchive = Set.of(host);

        // Initial healthy state: every expected holder holds every piece.
        List<NodeId> expected = f.policy.expectedHolders(f.manifest.manifestRoot(),
                ArchiveObjectClass.SNAPSHOT, eligible, fullArchive);
        assertThat(expected).hasSize(6).contains(host);
        for (NodeId h : expected) {
            f.seed(h, allPieces(pieces));
        }
        assertThat(f.audit.audit(f.manifest.manifestRoot(), pieces, ArchiveObjectClass.SNAPSHOT,
                eligible, fullArchive, f.inventory).isHealthy()).isTrue();

        // KILL two of the partial holders.
        NodeId deadA = firstPartial(expected, host);
        NodeId deadB = firstPartial(expected, host, deadA);
        List<NodeId> survivors = new ArrayList<>(eligible);
        survivors.remove(deadA);
        survivors.remove(deadB);
        f.inventory.forgetHolder(deadA);
        f.inventory.forgetHolder(deadB);
        f.physical.remove(deadA);
        f.physical.remove(deadB);

        ArchiveAuditTask.AuditResult result = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, survivors, fullArchive, f.inventory);
        assertThat(result.isHealthy()).isFalse();
        assertThat(result.repairTargets()).hasSize(2);
        assertThat(result.missingPieces()).isEqualTo(2 * pieces);

        ArchiveRepairService repair = f.repair(Integer.MAX_VALUE, Long.MAX_VALUE);
        ArchiveRepairService.RepairOutcome outcome = repair.repair(
                f.world, f.manifest.manifestRoot(), result, f.inventory, f.source());

        assertThat(outcome.piecesRepaired()).isEqualTo(2 * pieces);
        assertThat(outcome.piecesSkipped()).isZero();
        assertThat(outcome.planComplete()).isTrue();

        ArchiveAuditTask.AuditResult reAudited = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, survivors, fullArchive, f.inventory);
        assertThat(reAudited.isHealthy()).isTrue();
        for (NodeId h : reAudited.expectedHolders()) {
            assertThat(f.inventory.holderSet(f.manifest.manifestRoot()).get(h))
                    .containsExactlyInAnyOrderElementsOf(allPieces(pieces));
            // Every piece the holder now claims still verifies — no committed data was lost.
            for (Integer idx : f.inventory.holderSet(f.manifest.manifestRoot()).get(h)) {
                Bytes stored = f.physical.getOrDefault(h, Map.of()).get(idx);
                assertThat(stored).isNotNull();
                assertThat(f.manifest.verifyPiece(idx, stored)).isTrue();
            }
        }
    }

    @Test
    void repairIsBoundedAndProgressiveWhenTheBudgetIsSmall() {
        Fixture f = new Fixture("bb21");
        int pieces = f.manifest.pieceCount();

        List<NodeId> partials = partials(0xB21L, 8);
        NodeId host = new NodeId(new UUID(0xB21L, 999L));
        List<NodeId> eligible = new ArrayList<>(partials);
        eligible.add(host);
        Set<NodeId> fullArchive = Set.of(host);

        // Only the host is seeded; all 5 partial expected holders need repair.
        f.seed(host, allPieces(pieces));

        ArchiveAuditTask.AuditResult result = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, eligible, fullArchive, f.inventory);
        assertThat(result.repairTargets()).hasSize(5);

        // A budget of ONE piece per tick: repair makes progress without storming.
        ArchiveRepairService repair = f.repair(1, Long.MAX_VALUE);
        ArchiveRepairService.RepairOutcome first = repair.repair(
                f.world, f.manifest.manifestRoot(), result, f.inventory, f.source());
        assertThat(first.piecesRepaired()).isEqualTo(1);
        assertThat(first.budgetExhausted()).isTrue();
        assertThat(first.planComplete()).isFalse();

        // Re-audit + re-repair converges to healthy; each tick is bounded to one piece.
        for (int tick = 0; tick < 5 * pieces + 20; tick++) {
            ArchiveAuditTask.AuditResult r = f.audit.audit(f.manifest.manifestRoot(), pieces,
                    ArchiveObjectClass.SNAPSHOT, eligible, fullArchive, f.inventory);
            if (r.isHealthy()) {
                break;
            }
            repair.repair(f.world, f.manifest.manifestRoot(), r, f.inventory, f.source());
        }
        assertThat(f.audit.audit(f.manifest.manifestRoot(), pieces, ArchiveObjectClass.SNAPSHOT,
                eligible, fullArchive, f.inventory).isHealthy()).isTrue();
    }

    @Test
    void aCorruptHolderIsSkippedAndNothingIsRecorded() {
        Fixture f = new Fixture("cc21");
        int pieces = f.manifest.pieceCount();

        List<NodeId> eligible = partials(0xC21L, 8);
        NodeId honest = eligible.get(0);
        f.seed(honest, allPieces(pieces));

        ArchiveAuditTask.AuditResult result = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of(), f.inventory);

        // A verifier that rejects everything cannot corrupt the result: every fetch is skipped,
        // nothing is recorded, and the manifest stays under-replicated rather than falsely repaired.
        ArchiveRepairService repair = new ArchiveRepairService(
                f.fetcher(),
                (root, idx, payload) -> false,
                f.storer(),
                Integer.MAX_VALUE, Long.MAX_VALUE);
        ArchiveRepairService.RepairOutcome outcome = repair.repair(
                f.world, f.manifest.manifestRoot(), result, f.inventory, f.source());

        assertThat(outcome.piecesRepaired()).isZero();
        assertThat(outcome.piecesSkipped()).isEqualTo(result.missingPieces());
        for (ArchiveAuditTask.RepairTarget t : result.repairTargets()) {
            assertThat(f.inventory.holderSet(f.manifest.manifestRoot()).getOrDefault(t.assignee(), Set.of()))
                    .isEmpty();
        }
    }

    @Test
    void aFailedPhysicalStoreIsSkippedAndNeverAdvertised() {
        Fixture f = new Fixture("dd21");
        int pieces = f.manifest.pieceCount();
        List<NodeId> eligible = partials(0xD21L, 8);
        NodeId honest = eligible.get(0);
        f.seed(honest, allPieces(pieces));

        ArchiveAuditTask.AuditResult result = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of(), f.inventory);
        ArchiveRepairService repair = new ArchiveRepairService(
                f.fetcher(), f.verifier(), (assignee, root, idx, payload) -> false,
                Integer.MAX_VALUE, Long.MAX_VALUE);

        ArchiveRepairService.RepairOutcome outcome = repair.repair(
                f.world, f.manifest.manifestRoot(), result, f.inventory, f.source());

        assertThat(outcome.piecesRepaired()).isZero();
        assertThat(outcome.piecesSkipped()).isEqualTo(result.missingPieces());
        for (ArchiveAuditTask.RepairTarget target : result.repairTargets()) {
            assertThat(f.inventory.holderSet(f.manifest.manifestRoot())
                    .getOrDefault(target.assignee(), Set.of())).isEmpty();
            assertThat(f.physical.getOrDefault(target.assignee(), Map.of())).isEmpty();
        }
    }

    @Test
    void aPieceThatDoesNotFitTheRemainingBandwidthIsNeitherCountedNorStored() {
        Fixture f = new Fixture("ee21");
        int pieces = f.manifest.pieceCount();
        List<NodeId> eligible = partials(0xE21L, 8);
        NodeId honest = eligible.get(0);
        f.seed(honest, allPieces(pieces));

        ArchiveAuditTask.AuditResult result = f.audit.audit(f.manifest.manifestRoot(), pieces,
                ArchiveObjectClass.SNAPSHOT, eligible, Set.of(), f.inventory);
        int[] storeCalls = {0};
        long budget = f.manifest.piece(0).length() - 1;
        assertThat(budget).isPositive();
        ArchiveRepairService repair = new ArchiveRepairService(
                f.fetcher(), f.verifier(), (assignee, root, idx, payload) -> {
                    storeCalls[0]++;
                    return true;
                }, Integer.MAX_VALUE, budget);

        ArchiveRepairService.RepairOutcome outcome = repair.repair(
                f.world, f.manifest.manifestRoot(), result, f.inventory, f.source());

        assertThat(outcome.piecesRepaired()).isZero();
        assertThat(outcome.bytesTransferred()).isZero();
        assertThat(outcome.budgetExhausted()).isTrue();
        assertThat(storeCalls[0]).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static Set<Integer> allPieces(int pieces) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 0; i < pieces; i++) {
            out.add(i);
        }
        return out;
    }

    private static List<NodeId> partials(long msb, int n) {
        List<NodeId> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new NodeId(new UUID(msb, i)));
        }
        return out;
    }

    private static NodeId firstPartial(List<NodeId> expected, NodeId host, NodeId... skip) {
        Set<NodeId> skipSet = Set.of(skip);
        return expected.stream()
                .filter(n -> !n.equals(host) && !skipSet.contains(n))
                .findFirst().orElseThrow();
    }

    /** One real region split into pieces, with its inventory/policy/audit and a verifying fetcher. */
    private final class Fixture {
        final Bytes world;
        final PieceManifest manifest;
        final Map<Integer, Bytes> pieceBytes;
        final Map<NodeId, Map<Integer, Bytes>> physical = new HashMap<>();
        final ArchiveInventory inventory = new ArchiveInventory();
        final RendezvousArchivePolicy policy = new RendezvousArchivePolicy();
        final ArchiveAuditTask audit = new ArchiveAuditTask(policy);

        Fixture(String worldHex) {
            RegionSnapshot snapshot = variedSnapshot();
            RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(snapshot, 512);
            this.world = Bytes.fromHex(worldHex);
            this.manifest = layout.manifest();
            this.pieceBytes = new HashMap<>();
            for (int i = 0; i < manifest.pieceCount(); i++) {
                Piece p = manifest.piece(i);
                pieceBytes.put(i, new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length()));
            }
        }

        /** Serves bytes from the source's physical store; inventory is not treated as storage. */
        ArchiveRepairService.PieceFetcher fetcher() {
            return (from, root, idx) -> Optional.ofNullable(
                    physical.getOrDefault(from, Map.of()).get(idx));
        }

        ArchiveRepairService.PieceVerifier verifier() {
            return (root, idx, payload) -> manifest.verifyPiece(idx, payload);
        }

        ArchiveRepairService.PieceStorer storer() {
            return (assignee, root, idx, payload) -> {
                physical.computeIfAbsent(assignee, ignored -> new HashMap<>()).put(idx, payload);
                return true;
            };
        }

        ArchiveRepairService repair(int maxConcurrent, long bandwidth) {
            return new ArchiveRepairService(
                    fetcher(), verifier(), storer(), maxConcurrent, bandwidth);
        }

        void seed(NodeId holder, Set<Integer> indexes) {
            Map<Integer, Bytes> stored = physical.computeIfAbsent(holder, ignored -> new HashMap<>());
            for (Integer index : indexes) {
                stored.put(index, pieceBytes.get(index));
            }
            inventory.record(world, manifest.manifestRoot(), holder, bitmap(indexes));
        }

        java.util.function.IntFunction<Optional<NodeId>> source() {
            return ArchiveRepairService.holderSource(manifest.manifestRoot(), inventory, null);
        }

        dev.nodera.core.Bytes bitmap(Set<Integer> indexes) {
            return dev.nodera.protocol.content.PieceBitmap.of(new LinkedHashSet<>(indexes));
        }
    }

    private Fixture newFixture(String worldHex) {
        return new Fixture(worldHex);
    }

    private static RegionSnapshot variedSnapshot() {
        RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
        List<ChunkColumnState> cols = new ArrayList<>(64);
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int[] palette = new int[SECTION_COUNT];
                for (int s = 0; s < SECTION_COUNT; s++) {
                    palette[s] = (dx * 8 + dz) * 31 + s;
                }
                cols.add(new ChunkColumnState(dx, dz, palette, MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L, cols);
    }
}
