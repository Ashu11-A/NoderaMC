package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.coordinator.InMemoryWorldView;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless clean-slate pickup repro for issue #33 / L-50: a fresh store (INITIAL base snapshot,
 * no prior commits, empty journals) whose only entity is one validated ITEM; a solo-committee
 * primary proposes the signed pickup as the very first batch. The exactly-once contract requires
 * the committed delta to remove the item AND land its inventory credit in the world view —
 * "vanish" (item removed, no credit) must be impossible at this layer.
 */
final class CleanSlatePickupTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final int ITEM_STACK_ID = 7;
    private static final int COUNT = 3;

    @Test
    void firstEverPickupOnFreshStoreCreditsExactlyOnce() {
        NodeIdentity primary = NodeIdentity.generate();
        NodeIdentity playerKey = NodeIdentity.generate();
        InMemoryWorldView world = new InMemoryWorldView();
        LoopbackTransport tx = LoopbackTransport.LoopbackNetwork.newNetwork()
                .register(primary.nodeId());
        WorkerValidationService service = new WorkerValidationService(
                primary, tx,
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), HASHES),
                HASHES, new InMemoryCertificateStore(HASHES), 1L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 100L,
                dev.nodera.committee.VotePersistence.none(),
                // Live admission (LiveEntityLaneRuntime.admission) authorizes pickups after its
                // proximity/liveness checks; HEADLESS categorically blocks them, so mirror the
                // authorized outcome here.
                (action, base) -> true, world,
                dev.nodera.coordinator.entity.EntityTransferCoordinator.TransferJournal.NOOP);

        NetworkEntityId itemId = new NetworkEntityId(42L);
        PersistedEntityState item = new PersistedEntityState(
                itemId, EntityKind.ITEM, ITEM_STACK_ID,
                new FixedVec3(FixedVec3.ONE, ItemEntityRules.GROUND_Y, FixedVec3.ONE),
                FixedVec3.ZERO, 0, ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(ITEM_STACK_ID, COUNT));
        RegionSnapshot base = new RegionSnapshot(
                REGION, SnapshotVersion.INITIAL, 0, List.of(), List.of(item));
        RegionLease lease = new RegionLease(
                REGION, RegionEpoch.INITIAL, primary.nodeId(), List.of(), 0, 200);
        service.activateRegion(base, lease);
        service.registerActor(playerKey.nodeId(), playerKey.publicKeyBytes());

        ActionEnvelope unsigned = new ActionEnvelope(
                playerKey.nodeId(), 1L, 1L, 1L, REGION, new PickupItemAction(itemId),
                Bytes.empty());
        ActionEnvelope signed = new ActionEnvelope(
                playerKey.nodeId(), 1L, 1L, 1L, REGION, new PickupItemAction(itemId),
                playerKey.sign(unsigned.signedPortion()));

        assertThat(service.proposeBatch(REGION, 1, 1, List.of(signed)))
                .as("clean-slate pickup batch must commit on the solo committee")
                .isPresent();

        InventoryCredit expected = new InventoryCredit(
                playerKey.nodeId(), itemId, ITEM_STACK_ID, COUNT);
        assertThat(world.getInventoryCredit(expected))
                .as("committed pickup must land the inventory credit exactly once — "
                        + "a missing credit here is the issue-#33 vanish")
                .isEqualTo(expected);
        assertThat(service.currentSnapshot(REGION).orElseThrow().entities())
                .as("picked-up item must leave the canonical snapshot")
                .isEmpty();
    }
}
