package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session-gateway migration (network task 2 deliverable 7 / engine L-17): freeze, reconnect,
 * resubmit exactly once.
 *
 * <p>The reconnect is not what makes L-17 a limitation — the in-flight actions are. Dropping them
 * makes a player's last second of play silently never have happened; resending them blindly places
 * a block twice, which is the failure the validated lane exists to make impossible. These tests are
 * about that middle: what is held, what replays, and what must not replay twice.
 */
final class GatewayHandoverTest {

    private static final NodeId ALICE = new NodeId(new UUID(0, 1));
    private static final NodeId BOB = new NodeId(new UUID(0, 2));
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private static ActionEnvelope action(NodeId actor, long playerSeq) {
        return new ActionEnvelope(actor, playerSeq, playerSeq, playerSeq, REGION,
                new PlaceBlockAction(new NBlockPos(1, 70, 1), 1, 1), Bytes.empty());
    }

    @Test
    @DisplayName("while open, actions send immediately and are held until acknowledged")
    void openSessionsSendImmediately() {
        GatewayHandover handover = new GatewayHandover();

        assertThat(handover.submit(action(ALICE, 1))).isTrue();
        assertThat(handover.inFlightCount()).isEqualTo(1);

        assertThat(handover.acknowledge(ALICE, 1)).isTrue();
        assertThat(handover.inFlightCount()).isZero();
    }

    @Test
    @DisplayName("a frozen session queues rather than refuses — false means later, never lost")
    void freezingQueuesRatherThanRefuses() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));

        assertThat(handover.freeze()).isTrue();
        // Refusing would push the failure into the capture path, which would have to decide what a
        // refused placement means to a player mid-swing.
        assertThat(handover.submit(action(ALICE, 2))).isFalse();
        assertThat(handover.submit(action(ALICE, 3))).isFalse();
        assertThat(handover.inFlightCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("resume replays everything unacknowledged, in submission order")
    void resumeReplaysInOrder() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));
        handover.freeze();
        handover.submit(action(ALICE, 2));
        handover.submit(action(BOB, 7));

        List<ActionEnvelope> replay = handover.resume();

        assertThat(handover.isFrozen()).isFalse();
        assertThat(replay).extracting(ActionEnvelope::playerSeq).containsExactly(1L, 2L, 7L);
        assertThat(handover.replayedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("an acknowledged action never replays — this is the whole of exactly-once")
    void acknowledgedActionsAreNotReplayed() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));
        handover.submit(action(ALICE, 2));
        handover.acknowledge(ALICE, 1);

        handover.freeze();
        List<ActionEnvelope> replay = handover.resume();

        // Replaying an action the old gateway already committed is how a migration places a block
        // twice.
        assertThat(replay).extracting(ActionEnvelope::playerSeq).containsExactly(2L);
    }

    @Test
    @DisplayName("re-offering a held action is idempotent — a retry is not a second action")
    void resubmittingTheSameActionDoesNotDoubleIt() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));
        handover.submit(action(ALICE, 1));
        handover.submit(action(ALICE, 1));

        assertThat(handover.inFlightCount()).isEqualTo(1);
        assertThat(handover.resume()).hasSize(1);
    }

    @Test
    @DisplayName("two actors' sequences are independent — identity is (actor, seq), not seq")
    void identityIncludesTheActor() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));
        handover.submit(action(BOB, 1));

        assertThat(handover.inFlightCount()).isEqualTo(2);
        assertThat(handover.acknowledge(ALICE, 1)).isTrue();
        assertThat(handover.resume()).extracting(ActionEnvelope::actor).containsExactly(BOB);
    }

    @Test
    @DisplayName("a replayed envelope is byte-identical — a migration cannot mint authority")
    void replayPreservesTheSignedEnvelope() {
        GatewayHandover handover = new GatewayHandover();
        ActionEnvelope original = action(ALICE, 1);
        handover.submit(original);
        handover.freeze();

        assertThat(handover.resume().get(0))
                .as("the signature that authorised it must still be the one that authorises it")
                .isSameAs(original);
    }

    @Test
    @DisplayName("a batch acknowledgement clears a run at once")
    void acknowledgeIfClearsARun() {
        GatewayHandover handover = new GatewayHandover();
        for (long seq = 1; seq <= 5; seq++) {
            handover.submit(action(ALICE, seq));
        }

        int cleared = handover.acknowledgeIf(a -> a.playerSeq() <= 3);

        assertThat(cleared).isEqualTo(3);
        assertThat(handover.resume()).extracting(ActionEnvelope::playerSeq).containsExactly(4L, 5L);
    }

    @Test
    @DisplayName("a long freeze drops the OLDEST, because the newest is what the player awaits")
    void capacityDropsOldestFirst() {
        GatewayHandover handover = new GatewayHandover(4);
        handover.freeze();
        for (long seq = 1; seq <= 10; seq++) {
            handover.submit(action(ALICE, seq));
        }

        assertThat(handover.inFlightCount()).isEqualTo(4);
        assertThat(handover.droppedByCapacity()).isEqualTo(6);
        assertThat(handover.resume()).extracting(ActionEnvelope::playerSeq)
                .containsExactly(7L, 8L, 9L, 10L);
    }

    @Test
    @DisplayName("freezing twice is not two freezes — a flapping link must not restart it")
    void freezeIsIdempotent() {
        GatewayHandover handover = new GatewayHandover();
        assertThat(handover.freeze()).isTrue();
        assertThat(handover.freeze()).isFalse();
        assertThat(handover.isFrozen()).isTrue();
    }

    @Test
    @DisplayName("two handovers in a row each replay only what is still unacknowledged")
    void successiveHandoversDoNotAccumulate() {
        GatewayHandover handover = new GatewayHandover();
        handover.submit(action(ALICE, 1));
        handover.freeze();
        assertThat(handover.resume()).hasSize(1);

        // The new gateway takes it, then dies too.
        handover.acknowledge(ALICE, 1);
        handover.submit(action(ALICE, 2));
        handover.freeze();

        assertThat(handover.resume()).extracting(ActionEnvelope::playerSeq).containsExactly(2L);
        assertThat(handover.replayedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("nulls are ignored and a non-positive capacity is refused")
    void argumentsAreChecked() {
        GatewayHandover handover = new GatewayHandover();
        assertThat(handover.submit(null)).isFalse();
        assertThat(handover.acknowledge(null, 1)).isFalse();
        assertThat(handover.inFlightCount()).isZero();

        handover.submit(action(ALICE, 1));
        handover.clear();
        assertThat(handover.inFlightCount()).isZero();

        assertThatThrownBy(() -> new GatewayHandover(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
