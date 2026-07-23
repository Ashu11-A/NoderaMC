package dev.nodera.coordinator.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One simulated minute of a mixed validated-item and vanilla-authoritative ghost region. */
final class EntityLaneSoakIT {

    @Test
    void mixedItemGhostMinuteStaysUnderBandwidthAndResyncThresholds() {
        RegionId region = TestFixtures.region(0, 0);
        PersistedEntityState item = new PersistedEntityState(
                new NetworkEntityId(1), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(2, 1, 2), FixedVec3.ZERO,
                4_800, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
        PersistedEntityState ghost = new PersistedEntityState(
                new NetworkEntityId(2), EntityKind.GHOST, 54,
                FixedVec3.ofBlock(4, 1, 4), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.fromHex("00010000000000"));
        RegionSnapshot current = new RegionSnapshot(
                region, SnapshotVersion.INITIAL, 0, List.of(), List.of(item, ghost));
        HashService hashes = new HashService();
        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);
        EntityLaneSoakMetrics metrics = new EntityLaneSoakMetrics();

        for (long tick = 1; tick <= 1_200; tick++) {
            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, current.version(), tick, tick, List.of());
            RegionExecutionContext context = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, current.version(), tick, tick,
                    12, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
            RegionExecutionResult result = engine.execute(
                    new RegionExecutionRequest(context, current, batch));
            current = SnapshotDeltaApplier.apply(current, result.delta(), tick);
            metrics.recordCommit();
            metrics.recordGhostMobTicks(1);
            if (tick % GhostUpdatePolicy.UPDATE_INTERVAL_TICKS == 0) {
                CanonicalWriter encoded = new CanonicalWriter();
                ghost.encode(encoded);
                metrics.recordGhostUpdate(encoded.size());
            }
        }

        assertThat(current.entities()).singleElement().isEqualTo(ghost);
        EntityLaneSoakMetrics.Snapshot observed = metrics.snapshot();
        assertThat(observed.ghostUpdates()).isEqualTo(240);
        assertThat(observed.ghostBytesPerMobMinute()).isEqualTo(23_040);
        assertThat(observed.ghostBytesPerMobMinute())
                .isLessThanOrEqualTo(EntityLaneSoakMetrics.MAX_GHOST_BYTES_PER_MOB_MINUTE);
        assertThat(observed.resyncRateBps()).isZero();
        assertThat(observed.passes()).isTrue();
    }
}
