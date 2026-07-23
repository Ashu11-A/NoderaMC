package dev.nodera.peer.validation;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.PlayerView;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.testkit.FakeRegion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityLaneBootstrapTest {

    private static final HashService HASHES = new HashService();

    private static final NodeId NODE_A =
            new NodeId(new UUID(0x0000000000000001L, 0x0000000000000001L));
    private static final NodeId NODE_B =
            new NodeId(new UUID(0x0000000000000002L, 0x0000000000000002L));

    @Test
    void genesisIsDeterministicAndPinnedToFlatWorldRules() {
        GenesisManifest first = EntityLaneBootstrap.genesis(42L, HASHES);
        GenesisManifest second = EntityLaneBootstrap.genesis(42L, HASHES);

        assertThat(first).isEqualTo(second);
        assertThat(first.worldSeed()).isEqualTo(42L);
        assertThat(first.rulesVersion()).isEqualTo(FlatWorldRules.RULES_VERSION);
        assertThat(first.registryFingerprint()).isEqualTo(FlatWorldRules.registryFingerprint());

        CanonicalWriter w1 = new CanonicalWriter();
        first.encode(w1);
        CanonicalWriter w2 = new CanonicalWriter();
        second.encode(w2);
        assertThat(w1.toBytes()).isEqualTo(w2.toBytes());
    }

    @Test
    void genesisRootChangesWithTheSeed() {
        assertThat(EntityLaneBootstrap.genesis(1L, HASHES).genesisRoot())
                .isNotEqualTo(EntityLaneBootstrap.genesis(2L, HASHES).genesisRoot());
    }

    @Test
    void genesisRejectsNullHashService() {
        assertThatThrownBy(() -> EntityLaneBootstrap.genesis(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void initialSnapshotMatchesTheCanonicalFlatFixture() {
        RegionId region = FakeRegion.overworldRegion(3, -2);

        assertThat(EntityLaneBootstrap.initialSnapshot(region))
                .isEqualTo(FakeRegion.emptyFlatSnapshot(region, SnapshotVersion.INITIAL, 0L));
    }

    @Test
    void initialSnapshotRejectsNullRegion() {
        assertThatThrownBy(() -> EntityLaneBootstrap.initialSnapshot(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planMarksLocallyPrimaryRegionsAndBuildsFirstEpochLeases() {
        PlayerView view = PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8);
        List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                Map.of(NODE_A, view), NODE_A, 100L, NoderaConstants.QUORUM_MVP_SIZE);

        assertThat(plan).isNotEmpty();
        for (EntityLaneBootstrap.PlannedRegion planned : plan) {
            assertThat(planned.locallyPrimary()).isTrue();
            assertThat(planned.lease().region()).isEqualTo(planned.region());
            assertThat(planned.lease().epoch()).isEqualTo(new RegionEpoch(1));
            assertThat(planned.lease().primary()).isEqualTo(NODE_A);
            assertThat(planned.lease().validators()).isEmpty();
            assertThat(planned.lease().validFromTick()).isEqualTo(100L);
            assertThat(planned.lease().expiresAtTick())
                    .isEqualTo(100L + NoderaConstants.LEASE_LENGTH_TICKS);
        }
    }

    @Test
    void planOverlappingViewsFormCommitteesWithTheCloserNodePrimary() {
        // NODE_A stands at the origin; NODE_B one region east (chunk 8) — B's disc still covers the
        // origin region, but A is closer to its centre (chunk 3.5, 3.5), so A is primary there.
        PlayerView near = PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8);
        PlayerView far = PlayerView.fromBlock(DimensionKey.overworld(), 128, 0, 8);
        List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                Map.of(NODE_A, near, NODE_B, far), NODE_B, 0L, NoderaConstants.QUORUM_MVP_SIZE);

        RegionId origin = near.centerRegion();
        EntityLaneBootstrap.PlannedRegion originPlan = plan.stream()
                .filter(p -> p.region().equals(origin))
                .findFirst().orElseThrow();
        assertThat(originPlan.lease().primary()).isEqualTo(NODE_A);
        assertThat(originPlan.lease().validators()).containsExactly(NODE_B);
        assertThat(originPlan.locallyPrimary()).isFalse();

        // The plan is deterministic regardless of map iteration order.
        assertThat(EntityLaneBootstrap.plan(
                Map.of(NODE_B, far, NODE_A, near), NODE_B, 0L, NoderaConstants.QUORUM_MVP_SIZE))
                .isEqualTo(plan);
    }

    @Test
    void planRejectsNullArguments() {
        assertThatThrownBy(() -> EntityLaneBootstrap.plan(null, NODE_A, 0L, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityLaneBootstrap.plan(Map.of(), null, 0L, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannedRegionRejectsMismatchedLease() {
        List<EntityLaneBootstrap.PlannedRegion> plan = EntityLaneBootstrap.plan(
                Map.of(NODE_A, PlayerView.fromBlock(DimensionKey.overworld(), 0, 0, 8)),
                NODE_A, 0L, 3);
        RegionId other = FakeRegion.overworldRegion(1_000, 1_000);
        assertThatThrownBy(() -> new EntityLaneBootstrap.PlannedRegion(
                other, plan.get(0).lease(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
