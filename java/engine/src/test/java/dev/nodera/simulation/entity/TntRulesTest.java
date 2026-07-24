package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 (L-9 warmup): deterministic TNT. A primed TNT entity fuses in the root and detonates as
 * a seeded blast — every replica computes the identical crater, the fuse fires on time, and a
 * detonation ignites chained TNT in deterministic sequence.
 */
final class TntRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private static final int CX = 64;
    private static final int CY = 64;
    private static final int CZ = 64;

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 31337L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    /** A solid-stone world carrying one primed TNT at the centre, fuse = FUSE_TICKS. */
    private RegionSnapshot stoneWorldWithTnt() {
        return stoneWorldWithTnt(CX, CZ, TntRules.FUSE_TICKS);
    }

    private RegionSnapshot stoneWorldWithTnt(int x, int z, int detonateTick) {
        RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
        PersistedEntityState tnt = new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 1),
                EntityKind.TNT, TntRules.TNT_TYPE_ID,
                FixedVec3.fromExternal(x + 0.5, CY + 0.5, z + 0.5), FixedVec3.ZERO,
                0, detonateTick, Bytes.empty());
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                stone.chunks(), List.of(tnt));
    }

    private static PersistedEntityState tnt(RegionId r, int seq, int x, int z, int detonateTick) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.TNT, TntRules.TNT_TYPE_ID,
                FixedVec3.fromExternal(x + 0.5, CY + 0.5, z + 0.5), FixedVec3.ZERO,
                0, detonateTick, Bytes.empty());
    }

    private static int airCellsInSphere(MutableRegionState state) {
        int air = 0;
        for (int dy = TntRules.BLAST_RADIUS; dy >= -TntRules.BLAST_RADIUS; dy--) {
            for (int dx = -TntRules.BLAST_RADIUS; dx <= TntRules.BLAST_RADIUS; dx++) {
                for (int dz = -TntRules.BLAST_RADIUS; dz <= TntRules.BLAST_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > TntRules.BLAST_RADIUS_SQ) {
                        continue;
                    }
                    if (state.getBlock(new NBlockPos(CX + dx, CY + dy, CZ + dz))
                            == FlatWorldRules.AIR) {
                        air++;
                    }
                }
            }
        }
        return air;
    }

    @Test
    void blastCarvesASeededSphereWithIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = stoneWorldWithTnt();

        RegionExecutionResult first = executeTicks(base, List.of(), 100);
        RegionExecutionResult second = executeTicks(base, List.of(), 100);
        assertThat(second.resultingRoot())
                .as("the seeded blast settles to one crater root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 100L);
        MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));

        assertThat(view.getBlock(new NBlockPos(CX, CY, CZ)))
                .as("the detonation centre always clears (distSq == 0 ⇒ certain destruction)")
                .isEqualTo(FlatWorldRules.AIR);
        assertThat(airCellsInSphere(view))
                .as("the blast actually carved a crater")
                .isGreaterThan(0);
        // Border of the sphere is untouched: a cell one beyond the cutoff stays stone.
        assertThat(view.getBlock(new NBlockPos(CX + TntRules.BLAST_RADIUS + 1, CY, CZ)))
                .as("destruction never escapes the Euclidean cutoff")
                .isEqualTo(FlatWorldRules.STONE);
        // The TNT entity is gone — it detonated, it did not merely age out.
        assertThat(view.entities().stream()
                .filter(e -> e.kind() == EntityKind.TNT).toList())
                .isEmpty();
    }

    @Test
    void fuseDoesNotDetonateEarly() {
        RegionSnapshot base = stoneWorldWithTnt();
        NetworkEntityId tntId = base.entities().get(0).id();

        RegionSnapshot before = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), TntRules.FUSE_TICKS - 1).delta(),
                TntRules.FUSE_TICKS - 1L);
        MutableRegionState beforeView = new MutableRegionState(before, RegionBounds.of(region));
        assertThat(beforeView.entity(tntId))
                .as("one tick short of the fuse: the TNT is still fusing")
                .isNotNull();
        assertThat(beforeView.getBlock(new NBlockPos(CX, CY, CZ)))
                .as("no crater before detonation")
                .isEqualTo(FlatWorldRules.STONE);

        RegionSnapshot at = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), TntRules.FUSE_TICKS).delta(),
                TntRules.FUSE_TICKS);
        MutableRegionState atView = new MutableRegionState(at, RegionBounds.of(region));
        assertThat(atView.entity(tntId))
                .as("the fuse fires exactly on time")
                .isNull();
        assertThat(atView.getBlock(new NBlockPos(CX, CY, CZ)))
                .isEqualTo(FlatWorldRules.AIR);
    }

    @Test
    void blastKnocksBackNearbyItemsDeterministically() {
        // Short fuse so the item is still at blast height when it fires (items have no block
        // collision — a long fuse lets them free-fall to the floor, clear of the radius).
        RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
        PersistedEntityState tnt = tnt(region, 1, CX, CZ, 2);
        // A resting item one block east of the detonation centre.
        PersistedEntityState item = new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                dev.nodera.core.state.EntityKind.ITEM, 0x11,
                FixedVec3.fromExternal(CX + 1 + 0.5, CY + 0.5, CZ + 0.5),
                FixedVec3.ZERO, 0, PersistedEntityState.NEVER_DESPAWN,
                dev.nodera.simulation.entity.ItemEntityRules.payload(0x11, 1));
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                stone.chunks(), List.of(tnt, item));

        RegionExecutionResult first = executeTicks(base, List.of(), 4);
        RegionExecutionResult second = executeTicks(base, List.of(), 4);
        assertThat(second.resultingRoot())
                .as("the knockback settles to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 4L);
        PersistedEntityState victim = settled.entities().stream()
                .filter(e -> e.kind() == dev.nodera.core.state.EntityKind.ITEM).findFirst().orElseThrow();
        assertThat(victim.vel().x())
                .as("the blast shoved the east-side item further east (positive impulse)")
                .isGreaterThan(0L);
        assertThat(victim.pos().blockX())
                .as("the item was knocked clear of the crater centre")
                .isGreaterThan(CX);
    }

    @Test
    void chainIgnitionDetonatesAdjacentTntDeterministically() {
        RegionSnapshot stone = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.STONE);
        PersistedEntityState a = tnt(region, 1, CX, CZ, TntRules.FUSE_TICKS);
        PersistedEntityState b = tnt(region, 2, CX + 2, CZ, 1_000); // distSq 4 ⇒ in A's blast radius
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                stone.chunks(), List.of(a, b));

        // One tick past A's detonation: A is gone, B is lit but has not yet fired.
        RegionSnapshot afterA = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), TntRules.FUSE_TICKS).delta(),
                TntRules.FUSE_TICKS);
        MutableRegionState afterAView = new MutableRegionState(afterA, RegionBounds.of(region));
        assertThat(afterAView.entity(a.id())).isNull();
        assertThat(afterAView.entity(b.id()))
                .as("A's blast shortened B's fuse to detonateTick + 1")
                .isNotNull();

        // Two replicas run past B's chained detonation: identical roots, both TNT gone, both craters.
        RegionExecutionResult first = executeTicks(base, List.of(), TntRules.FUSE_TICKS + 2);
        RegionExecutionResult second = executeTicks(base, List.of(), TntRules.FUSE_TICKS + 2);
        assertThat(second.resultingRoot())
                .as("chained detonations settle to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), TntRules.FUSE_TICKS + 2L);
        MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));
        assertThat(view.entity(a.id())).isNull();
        assertThat(view.entity(b.id())).isNull();
        assertThat(view.getBlock(new NBlockPos(CX, CY, CZ)))
                .as("A's crater is present")
                .isEqualTo(FlatWorldRules.AIR);
        assertThat(view.getBlock(new NBlockPos(CX + 2, CY, CZ)))
                .as("B's chained crater is present")
                .isEqualTo(FlatWorldRules.AIR);
    }
}
