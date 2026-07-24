package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16 opener (L-13): validated PvE combat. Mob vitals live in the root as the MOB payload;
 * arrows and blast proximity wound engine-owned mobs through the single
 * {@link MobCombatRules#damage} mutation point; death removes the entity from committed state —
 * and every replica agrees on who bled and who died.
 */
final class MobCombatTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 55555L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static PersistedEntityState mob(RegionId r, int seq, double x, double y, double z,
                                            int health) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.MOB, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN,
                MobCombatRules.vitalsPayload(health, MobCombatRules.ZOMBIE_MAX_HEALTH));
    }

    private static PersistedEntityState fastArrow(RegionId r, int seq, double x, double y,
                                                  double z) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), new FixedVec3(2 * FixedVec3.ONE, 0L, 0L),
                0, ProjectileRules.LIFETIME_TICKS, Bytes.empty());
    }

    private static PersistedEntityState tnt(RegionId r, int seq, double x, double y, double z,
                                            int detonateTick) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.TNT, TntRules.TNT_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, detonateTick, Bytes.empty());
    }

    private static PersistedEntityState soleMob(RegionSnapshot snapshot) {
        List<PersistedEntityState> mobs = snapshot.entities().stream()
                .filter(e -> e.kind() == EntityKind.MOB).toList();
        assertThat(mobs).hasSize(1);
        return mobs.get(0);
    }

    @Test
    void arrowWoundsAnEngineOwnedMobDeterministically() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(
                        fastArrow(region, 1, 60.5, 64.5, 64.5),
                        mob(region, 2, 62.5, 64.5, 64.5, MobCombatRules.ZOMBIE_MAX_HEALTH)));

        RegionExecutionResult first = executeTicks(base, List.of(), 5);
        RegionExecutionResult second = executeTicks(base, List.of(), 5);
        assertThat(second.resultingRoot())
                .as("the wound is replica-identical committed state")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 5L);
        assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()))
                .as("the arrow took exactly ARROW_DAMAGE halves off the root's health")
                .isEqualTo(new MobCombatRules.Vitals(
                        MobCombatRules.ZOMBIE_MAX_HEALTH - MobCombatRules.ARROW_DAMAGE,
                        MobCombatRules.ZOMBIE_MAX_HEALTH));
    }

    @Test
    void arrowFinishesAWoundedMobAndDeathIsCommittedState() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(
                        fastArrow(region, 1, 60.5, 64.5, 64.5),
                        mob(region, 2, 62.5, 64.5, 64.5, MobCombatRules.ARROW_DAMAGE - 1)));

        RegionExecutionResult first = executeTicks(base, List.of(), 5);
        RegionExecutionResult second = executeTicks(base, List.of(), 5);
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 5L);
        assertThat(settled.entities().stream().filter(e -> e.kind() == EntityKind.MOB))
                .as("health reached zero: the mob is GONE from the root, on every replica")
                .isEmpty();
    }

    @Test
    void blastDamageFallsOffWithDistance() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState near = mob(region, 2, 65.5, 64.5, 64.5,
                MobCombatRules.ZOMBIE_MAX_HEALTH);
        PersistedEntityState far = mob(region, 3, 67.5, 64.5, 64.5,
                MobCombatRules.ZOMBIE_MAX_HEALTH);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), near, far));

        RegionExecutionResult first = executeTicks(base, List.of(), 5);
        RegionExecutionResult second = executeTicks(base, List.of(), 5);
        assertThat(second.resultingRoot())
                .as("blast damage is replica-identical")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 5L);
        int nearHealth = healthOf(settled, near.id());
        int farHealth = healthOf(settled, far.id());
        assertThat(nearHealth)
                .as("both were wounded, the closer mob worse")
                .isLessThan(farHealth);
        assertThat(farHealth).isLessThan(MobCombatRules.ZOMBIE_MAX_HEALTH);
    }

    @Test
    void blastKillsAFrailMobAndTheDeadTakeNoKnockback() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState frail = mob(region, 2, 65.5, 64.5, 64.5, 2);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), frail));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), 5).delta(), 5L);
        assertThat(settled.entities())
                .as("the frail mob died in the blast (and the spent TNT is gone)")
                .isEmpty();
    }

    @Test
    void ghostsAreShovedButNeverWounded() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState ghost = new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(65.5, 64.5, 64.5), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(tnt(region, 1, 64.5, 64.5, 64.5, 2), ghost));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, List.of(), 3).delta(), 3L);
        PersistedEntityState shoved = settled.entities().stream()
                .filter(e -> e.kind() == EntityKind.GHOST).findFirst().orElseThrow();
        assertThat(shoved.payload())
                .as("a GHOST's vitals are server-authoritative: the blast shoves, never wounds")
                .isEqualTo(Bytes.empty());
        assertThat(shoved.pos().x())
                .as("the ghost still took the outward shove")
                .isGreaterThan(ghost.pos().x());
    }

    @Test
    void meleeAttackActionWoundsTheMobThroughTheValidatedLane() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState target = mob(region, 1, 64.5, 64.5, 64.5,
                MobCombatRules.ZOMBIE_MAX_HEALTH);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(target));
        List<ActionEnvelope> attack = List.of(TestFixtures.envelope(region, 0L, 1L,
                new dev.nodera.core.action.AttackEntityAction(
                        target.id(), FixedVec3.fromExternal(63.5, 64.5, 64.5))));

        RegionExecutionResult first = executeTicks(base, attack, 1);
        RegionExecutionResult second = executeTicks(base, attack, 1);
        assertThat(second.resultingRoot())
                .as("a signed melee strike is replica-identical committed state")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 1L);
        assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()).health())
                .as("the strike took exactly MELEE_DAMAGE halves — the constant is rule-set-owned")
                .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH - MobCombatRules.MELEE_DAMAGE);
    }

    @Test
    void outOfReachMeleeAttackIsRejectedAndWoundsNothing() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState target = mob(region, 1, 64.5, 64.5, 64.5,
                MobCombatRules.ZOMBIE_MAX_HEALTH);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(target));
        List<ActionEnvelope> tooFar = List.of(TestFixtures.envelope(region, 0L, 1L,
                new dev.nodera.core.action.AttackEntityAction(
                        target.id(), FixedVec3.fromExternal(50.5, 64.5, 64.5))));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, tooFar, 1).delta(), 1L);
        assertThat(MobCombatRules.decodeVitals(soleMob(settled).payload()).health())
                .as("a 14-block 'melee' strike is rejected by reach validation — no cheat damage")
                .isEqualTo(MobCombatRules.ZOMBIE_MAX_HEALTH);
    }

    @Test
    void vitalsPayloadRejectsMalformedBytes() {
        assertThatThrownBy(() -> MobCombatRules.vitalsPayload(0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MobCombatRules.vitalsPayload(21, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MobCombatRules.decodeVitals(Bytes.empty()))
                .isInstanceOf(RuntimeException.class);
        assertThat(MobCombatRules.decodeVitals(MobCombatRules.vitalsPayload(7, 20)))
                .isEqualTo(new MobCombatRules.Vitals(7, 20));
    }

    private static int healthOf(RegionSnapshot snapshot, NetworkEntityId id) {
        PersistedEntityState mob = snapshot.entities().stream()
                .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        return MobCombatRules.decodeVitals(mob.payload()).health();
    }
}
