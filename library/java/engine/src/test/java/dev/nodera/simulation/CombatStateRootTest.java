package dev.nodera.simulation;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.entity.MobCombatRules;
import dev.nodera.simulation.entity.MobState;
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
                MobState.fresh(health, MobCombatRules.ZOMBIE_MAX_HEALTH).encode());
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
                .catchThrowable(() -> MobState.fresh(0, 20)))
                .as("zero health is not a representable state")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The Task 11 root-shape change (L-7), stated as the negative it has to be: <b>AI memory is
     * hashed</b>. Two mobs with identical vitals in an identical world, one heading north and one
     * heading south, are two different worlds — next decision interval they are in different
     * places. If that difference were invisible in the root, two replicas could agree on
     * everything a committee compares and still be about to disagree, which is precisely the
     * class of failure Invariant 10 exists to make impossible.
     */
    @Test
    void identicalVitalsWithDifferentAiMemoryGiveDifferentRoots() {
        StateRoot north = rootOf(snapshotWith(mobHeading(new NBlockPos(5, 70, -1))));
        StateRoot south = rootOf(snapshotWith(mobHeading(new NBlockPos(5, 70, 11))));

        assertThat(north)
                .as("where a mob is GOING determines the next root, so it belongs in this one")
                .isNotEqualTo(south);
        assertThat(rootOf(snapshotWith(mobHeading(new NBlockPos(5, 70, -1)))))
                .as("same intention, same root — the memory is canonical, not incidental")
                .isEqualTo(north);
    }

    /**
     * The same claim from the other side, and the one that would have caught this change being
     * made wrongly: <b>drop the AI memory out of the payload and the root moves.</b> The bytes
     * below are exactly the pre-Task-11 vitals-only payload, so this test fails if anyone ever
     * "simplifies" {@code MobState.encode} back to its first four bytes — the change would
     * otherwise be silent, because every mob would still decode and every existing assertion
     * about health would still pass.
     */
    @Test
    void droppingAiMemoryFromThePayloadChangesTheRoot() {
        MobState full = new MobState(9, MobCombatRules.ZOMBIE_MAX_HEALTH,
                MobState.AiMemory.wanderTo(new NBlockPos(5, 70, -1), 400L));
        CanonicalWriter vitalsOnly = new CanonicalWriter(4);
        vitalsOnly.writeU16(full.health());
        vitalsOnly.writeU16(full.maxHealth());

        assertThat(rootOf(snapshotWith(withPayload(full.encode()))))
                .as("the hashed payload carries strictly more than the vitals")
                .isNotEqualTo(rootOf(snapshotWith(withPayload(vitalsOnly.toBytes()))));
    }

    private static PersistedEntityState withPayload(Bytes payload) {
        return new PersistedEntityState(MOB_ID, EntityKind.MOB, 1,
                new FixedVec3(5L << 32, 70L << 32, 5L << 32), FixedVec3.ZERO,
                10, PersistedEntityState.NEVER_DESPAWN, payload);
    }

    private static PersistedEntityState mobHeading(NBlockPos destination) {
        return withPayload(new MobState(
                MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH,
                MobState.AiMemory.wanderTo(destination, 400L)).encode());
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
