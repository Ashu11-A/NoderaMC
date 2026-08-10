package dev.nodera.peer.validation;

import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.simulationmsg.EntityTransferPrepare;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The proposer's own check on a transfer plan is the one every remote member runs (issue #233).
 *
 * <p>Two predicates used to validate the same plan. {@code validateTransferSide} ran on every remote
 * committee member; {@code validateTransferPlan} ran on the proposer, from
 * {@code NetworkTransferApprovals.approve} — and it was the weaker of the two. A proposer could
 * therefore build a plan, spend a broadcast and a vote timeout on it, and learn only from the silence
 * that its own committee had refused it unanimously for a reason it could have checked locally.
 *
 * <p><b>What the four omitted clauses turned out to be worth, measured rather than assumed.</b> None
 * of them was reachable as a divergence at the production call site, and this test is where that was
 * established. {@link SnapshotDeltaApplier#apply} re-checks the delta's {@code region} and
 * {@code baseVersion} and <em>throws</em> when its declared {@code resultingRoot} is not what
 * applying it produces — and the old body already ran it inside a {@code catch (RuntimeException)}
 * that returned false. The fourth clause, the replica's own region, cannot disagree because the
 * proposer fetches each replica <i>by</i> the region the descriptor names. So the defect was a
 * duplicated predicate rather than a hole: a second, hand-written copy of a check that had to stay
 * in step with the real one and had no test saying it did.
 *
 * <p>That is what these tests assert now — not that the proposer refuses (it did), but that on every
 * malformed plan the proposer and the committee member it is about to broadcast to return the
 * <b>same answer</b>. Two predicates cannot drift apart when one of them is the other.
 *
 * <p><b>Why reflection.</b> The proposer's check has exactly one production entry — a private inner
 * class handed to the transfer coordinator — and every public route to it (a live border crossing,
 * or durable transfer recovery) runs an earlier consistency gate that rejects these plans first, so
 * driving them through one would assert somebody else's guard instead of this one.
 */
final class TransferPlanMatchesTransferSideTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);
    private static final NetworkEntityId ENTITY = new NetworkEntityId(77);
    private static final long TICK = 11L;
    private static final SnapshotVersion NEXT = SnapshotVersion.INITIAL.next();

    private static final Method VALIDATE_PLAN;
    private static final Method VALIDATE_SIDE;
    private static final Field REPLICAS;

    static {
        try {
            Class<?> replica =
                    Class.forName("dev.nodera.peer.validation.WorkerValidationService$Replica");
            VALIDATE_PLAN = WorkerValidationService.class.getDeclaredMethod(
                    "validateTransferPlan", EntityTransferPrepare.class, replica, replica);
            VALIDATE_SIDE = WorkerValidationService.class.getDeclaredMethod(
                    "validateTransferSide", EntityTransferPrepare.class, replica, boolean.class);
            REPLICAS = WorkerValidationService.class.getDeclaredField("replicas");
            VALIDATE_PLAN.setAccessible(true);
            VALIDATE_SIDE.setAccessible(true);
            REPLICAS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private WorkerValidationService service;
    private RegionSnapshot sourceBase;
    private RegionSnapshot targetBase;
    private RegionDelta sourceDelta;
    private RegionDelta targetDelta;
    private EntityTransferDescriptor descriptor;

    @BeforeEach
    void seatBothCommittees() {
        NodeIdentity identity = NodeIdentity.generate();
        service = new WorkerValidationService(identity,
                LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId()),
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), HASHES),
                HASHES, new InMemoryCertificateStore(HASHES), 1L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 100L);

        PersistedEntityState before = new PersistedEntityState(
                ENTITY, EntityKind.ITEM, 42, FixedVec3.ofBlock(127, 5, 1), FixedVec3.ZERO,
                10, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));
        PersistedEntityState after = new PersistedEntityState(
                ENTITY, EntityKind.ITEM, 42, FixedVec3.ofBlock(128, 5, 1), FixedVec3.ZERO,
                11, ItemEntityRules.DESPAWN_AGE_TICKS, ItemEntityRules.payload(42, 3));

        sourceBase = new RegionSnapshot(SOURCE, SnapshotVersion.INITIAL, 10, List.of(),
                List.of(before));
        // The target replica must already be at the transfer tick — the source delta is what moves
        // the tick, which is why only the target side asserts it.
        targetBase = new RegionSnapshot(TARGET, SnapshotVersion.INITIAL, TICK, List.of(), List.of());

        sourceDelta = delta(SOURCE, new RegionSnapshot(SOURCE, NEXT, TICK, List.of(), List.of()),
                List.of(new EntityMutation(ENTITY, before, null)),
                List.of(new EntityTransferIntent(TARGET, after)));
        targetDelta = delta(TARGET, new RegionSnapshot(TARGET, NEXT, TICK, List.of(), List.of(after)),
                List.of(new EntityMutation(ENTITY, null, after)), List.of());
        descriptor = descriptorFor(sourceDelta, targetDelta);

        service.activateRegion(sourceBase, lease(SOURCE, identity));
        service.activateRegion(targetBase, lease(TARGET, identity));
    }

    @Test
    @DisplayName("a well-formed plan is accepted by the proposer and by a remote member alike")
    void a_well_formed_plan_is_accepted_by_both() {
        EntityTransferPrepare prepare =
                new EntityTransferPrepare(descriptor, sourceDelta, targetDelta);
        assertThat(proposerAccepts(prepare)).as("the proposer").isTrue();
        assertThat(memberAccepts(prepare, true)).as("a source-committee member").isTrue();
        assertThat(memberAccepts(prepare, false)).as("a target-committee member").isTrue();
    }

    @Test
    @DisplayName("a delta whose declared resulting root disagrees with the descriptor is refused")
    void a_delta_whose_declared_root_disagrees_is_refused() {
        // Everything still applies cleanly to the same state and produces the descriptor's root —
        // only the delta's own claim about where it lands is wrong. This is what the proposer used
        // to wave through and every one of its committee members refused.
        RegionDelta lying = withResultingRoot(sourceDelta,
                StateRoot.of(HASHES.sha256("not the root this delta produces".getBytes())));
        assertRefusedByEverybody(new EntityTransferPrepare(
                descriptorFor(lying, targetDelta), lying, targetDelta), true);
    }

    @Test
    @DisplayName("a delta carrying somebody else's region never reaches either check")
    void a_delta_for_another_region_never_reaches_validation() {
        RegionDelta misrouted = new RegionDelta(TARGET, SnapshotVersion.INITIAL, NEXT, List.of(),
                sourceDelta.resultingRoot(), sourceDelta.entityMutations(), List.of(),
                sourceDelta.transferIntents());
        // The third layer, recorded here because it is why the local check's missing clause was
        // never a hole: the message type itself will not hold a delta for a region the descriptor
        // does not name, so neither validator ever sees one.
        assertThatThrownBy(() -> new EntityTransferPrepare(descriptor, misrouted, targetDelta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match descriptor regions");
    }

    @Test
    @DisplayName("a delta based on a version the replica is not at is refused")
    void a_delta_on_the_wrong_base_version_is_refused() {
        RegionDelta ahead = new RegionDelta(SOURCE, NEXT, NEXT.next(), List.of(),
                sourceDelta.resultingRoot(), sourceDelta.entityMutations(), List.of(),
                sourceDelta.transferIntents());
        assertRefusedByEverybody(new EntityTransferPrepare(
                descriptorFor(ahead, targetDelta), ahead, targetDelta), true);
    }

    @Test
    @DisplayName("a replica that is not the region the descriptor names is refused")
    void a_replica_for_another_region_is_refused() {
        EntityTransferPrepare prepare =
                new EntityTransferPrepare(descriptor, sourceDelta, targetDelta);
        // The two replicas swapped: the descriptor's source region is validated against the target's
        // replica. The production call site fetches each replica *by* the region it names, so this
        // clause is defence in depth — but the proposer must hold it as firmly as a member does.
        assertThat(invoke(VALIDATE_PLAN, prepare, replica(TARGET), replica(SOURCE))).isFalse();
        assertThat(invoke(VALIDATE_SIDE, prepare, replica(TARGET), true)).isFalse();
    }

    // ------------------------------------------------------------------------------------------

    /** Both the proposer and the side whose delta was tampered with must say no. */
    private void assertRefusedByEverybody(EntityTransferPrepare prepare, boolean sourceSide) {
        assertThat(proposerAccepts(prepare)).as("the proposer, before it broadcasts").isFalse();
        assertThat(memberAccepts(prepare, sourceSide)).as("the remote member").isFalse();
    }

    private boolean proposerAccepts(EntityTransferPrepare prepare) {
        return invoke(VALIDATE_PLAN, prepare, replica(SOURCE), replica(TARGET));
    }

    private boolean memberAccepts(EntityTransferPrepare prepare, boolean sourceSide) {
        return invoke(VALIDATE_SIDE, prepare,
                replica(sourceSide ? SOURCE : TARGET), sourceSide);
    }

    private boolean invoke(Method method, Object... args) {
        try {
            return (boolean) method.invoke(service, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method.getName() + " could not be invoked", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object replica(RegionId region) {
        try {
            return ((Map<RegionId, ?>) REPLICAS.get(service)).get(region);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("replicas is not readable", e);
        }
    }

    private static RegionLease lease(RegionId region, NodeIdentity primary) {
        return new RegionLease(region, RegionEpoch.INITIAL, primary.nodeId(), List.of(), 0, 200);
    }

    /** A delta from {@code INITIAL} whose declared resulting root is the hash of {@code after}. */
    private static RegionDelta delta(RegionId region, RegionSnapshot after,
            List<EntityMutation> mutations, List<EntityTransferIntent> intents) {
        return new RegionDelta(region, SnapshotVersion.INITIAL, NEXT, List.of(),
                StateRoot.of(HASHES.hash(after)), mutations, List.of(), intents);
    }

    private static RegionDelta withResultingRoot(RegionDelta delta, StateRoot root) {
        return new RegionDelta(delta.region(), delta.baseVersion(), delta.resultingVersion(),
                delta.blockMutations(), root, delta.entityMutations(), delta.inventoryCredits(),
                delta.transferIntents());
    }

    /** The descriptor these two deltas describe — always self-consistent with what it is given. */
    private EntityTransferDescriptor descriptorFor(RegionDelta source, RegionDelta target) {
        return new EntityTransferDescriptor(12L, SOURCE, TARGET,
                RegionEpoch.INITIAL, RegionEpoch.INITIAL, ENTITY,
                SnapshotVersion.INITIAL, NEXT,
                StateRoot.of(HASHES.hash(sourceBase)), sourceDelta.resultingRoot(),
                StateRoot.of(HASHES.hash(source)),
                SnapshotVersion.INITIAL, NEXT,
                StateRoot.of(HASHES.hash(targetBase)), targetDelta.resultingRoot(),
                StateRoot.of(HASHES.hash(target)), TICK);
    }
}
