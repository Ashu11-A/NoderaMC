package dev.nodera.simulation;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.entity.MobCombatRules;
import dev.nodera.simulation.entity.PlayerRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-13's {@code @Invariant(10)} clause, extended to the combat lane: <b>vitals are part of the
 * hashed region root.</b>
 *
 * <p>Invariant 10 is the rule that everything determining future state must be visible in the root
 * *immediately*, so the forced-failure class it prevents — "peers agree on the blocks and diverge
 * later" — cannot happen. {@code ScheduledStateRootTest} pins it for the scheduled-tick queue;
 * combat needs the same guarantee for a different reason: two replicas whose worlds look identical
 * but whose mob is on 3 health versus 20 will disagree the moment the next arrow lands, and a
 * committee that could not see that difference would certify the divergence.
 *
 * <p>Health lives in the entity payload rather than in a side table precisely so this holds by
 * construction. These tests are the proof, not the intention.
 */
final class CombatStateRootTest {

    private static final HashService HASHES = new HashService();
    private static final NetworkEntityId MOB_ID = new NetworkEntityId(0x1234L);

    private static PersistedEntityState mob(int health) {
        return new PersistedEntityState(MOB_ID, EntityKind.MOB, 1,
                new FixedVec3(5L << 32, 70L << 32, 5L << 32), FixedVec3.ZERO,
                10, PersistedEntityState.NEVER_DESPAWN,
                MobCombatRules.vitalsPayload(health, MobCombatRules.ZOMBIE_MAX_HEALTH));
    }

    private static RegionSnapshot snapshotWith(PersistedEntityState... entities) {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(TestFixtures.region(0, 0), 0);
        return new RegionSnapshot(base.region(), new SnapshotVersion(1), 1, base.chunks(),
                java.util.List.of(entities));
    }

    private static StateRoot rootOf(RegionSnapshot snapshot) {
        return StateRoot.of(HASHES.hash(snapshot));
    }

    @Test
    void identicalBlocksDifferentMobHealthDifferentRoot() {
        StateRoot wounded = rootOf(snapshotWith(mob(3)));
        StateRoot healthy = rootOf(snapshotWith(mob(MobCombatRules.ZOMBIE_MAX_HEALTH)));

        assertThat(wounded)
                .as("a replica whose mob is on different health MUST diverge in the root "
                        + "immediately — the next arrow would otherwise fork the world")
                .isNotEqualTo(healthy);
    }

    @Test
    void identicalVitalsIdenticalRoots() {
        assertThat(rootOf(snapshotWith(mob(7))))
                .as("same vitals, same root — the payload is canonical, not incidental")
                .isEqualTo(rootOf(snapshotWith(mob(7))));
    }

    @Test
    void aDeadMobIsAbsentFromTheRootRatherThanPresentAtZero() {
        // Death is committed state: the entity is REMOVED, so an observer cannot confuse
        // "killed" with "alive on 0 health" — there is no such encodable value.
        StateRoot afterDeath = rootOf(snapshotWith());
        assertThat(afterDeath).isNotEqualTo(rootOf(snapshotWith(mob(1))));
        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> MobCombatRules.vitalsPayload(0, 20)))
                .as("zero health is not a representable state")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void playerHealthIsInTheRootToo() {
        // The PvP half: a player's committed health is root state for the same reason a mob's is.
        Bytes fullHealth = PlayerRules.emptyInventoryPayload(TestFixtures.ACTOR);
        PlayerRules.PlayerState state = PlayerRules.decode(fullHealth);
        Bytes hurt = PlayerRules.payload(state.withHealth(4));

        PersistedEntityState healthy = new PersistedEntityState(
                new NetworkEntityId(0x99L), EntityKind.PLAYER, 0,
                new FixedVec3(1L << 32, 70L << 32, 1L << 32), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, fullHealth);
        PersistedEntityState wounded = new PersistedEntityState(
                healthy.id(), healthy.kind(), healthy.typeId(), healthy.pos(), healthy.vel(),
                healthy.ageTicks(), healthy.despawnTick(), hurt);

        assertThat(rootOf(snapshotWith(healthy)))
                .as("committed player health is consensus state, not a server-side detail")
                .isNotEqualTo(rootOf(snapshotWith(wounded)));
    }
}
