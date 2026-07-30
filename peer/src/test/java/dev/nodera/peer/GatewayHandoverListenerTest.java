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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bind between the runtime's gateway lifecycle and the freeze (network task 2 deliverable 7).
 *
 * <p>The runtime already detected gateway loss and re-elected; what it could not say was "hold the
 * player's actions while that happens". These tests pin the two cases that are genuinely different
 * — winning the election yourself needs no reconnect, and somebody else winning does.
 */
final class GatewayHandoverListenerTest {

    private static final NodeId SELF = new NodeId(new UUID(0, 1));
    private static final NodeId OTHER = new NodeId(new UUID(0, 2));
    private static final NodeId THIRD = new NodeId(new UUID(0, 3));
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private static ActionEnvelope action(long seq) {
        return new ActionEnvelope(SELF, seq, seq, seq, REGION,
                new PlaceBlockAction(new NBlockPos(1, 70, 1), 1, 1), Bytes.empty());
    }

    private final List<ActionEnvelope> replayed = new ArrayList<>();
    private final GatewayHandover handover = new GatewayHandover();
    private final GatewayHandoverListener listener =
            new GatewayHandoverListener(handover, SELF, replayed::addAll);

    @Test
    @DisplayName("the first gateway is not a handover — a session that has not started cannot freeze")
    void bootstrapIsNotAMigration() {
        listener.onGatewayChanged(null, SELF, 0L);
        assertThat(handover.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("the same gateway re-asserted at a new epoch is not a migration either")
    void aReassertionIsNotAMigration() {
        listener.onGatewayChanged(OTHER, OTHER, 7L);
        assertThat(handover.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("a migration to another node freezes and waits for a link")
    void aMigrationToAnotherNodeFreezes() {
        handover.submit(action(1));
        handover.submit(action(2));

        listener.onGatewayChanged(OTHER, THIRD, 3L);

        // Whether a link to the new gateway exists is a transport question this class must not
        // guess at, so it stays frozen until the owner says otherwise.
        assertThat(handover.isFrozen()).isTrue();
        assertThat(replayed).isEmpty();
        assertThat(handover.submit(action(3))).isFalse();
    }

    @Test
    @DisplayName("winning the election resumes in the same breath — there is no link to wait for")
    void winningTheElectionResumesImmediately() {
        handover.submit(action(1));
        handover.submit(action(2));

        listener.onGatewayChanged(OTHER, SELF, 4L);

        assertThat(handover.isFrozen()).isFalse();
        assertThat(replayed).extracting(ActionEnvelope::playerSeq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("the owner's resume sends the replay exactly once, in order")
    void resumeSendsTheReplay() {
        handover.submit(action(1));
        listener.onGatewayChanged(OTHER, THIRD, 3L);
        handover.submit(action(2));

        assertThat(listener.resume()).isEqualTo(2);
        assertThat(replayed).extracting(ActionEnvelope::playerSeq).containsExactly(1L, 2L);
        assertThat(handover.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("an acknowledged action is never replayed by the bind either")
    void acknowledgedActionsAreNotReplayed() {
        handover.submit(action(1));
        handover.submit(action(2));
        handover.acknowledge(SELF, 1);

        listener.onGatewayChanged(OTHER, SELF, 5L);

        assertThat(replayed).extracting(ActionEnvelope::playerSeq).containsExactly(2L);
    }

    @Test
    @DisplayName("a second resume re-sends what is still unacknowledged, and that is deliberate")
    void resumingTwiceReSendsOnlyTheUnacknowledged() {
        handover.submit(action(1));
        listener.onGatewayChanged(OTHER, THIRD, 3L);
        assertThat(listener.resume()).isEqualTo(1);

        // A replay does not clear the buffer; an ACKNOWLEDGEMENT does. An action is only safe to
        // forget once somebody has confirmed it, so a second handover before any ack replays it
        // again — which the receiver deduplicates by (actor, playerSeq).
        replayed.clear();
        assertThat(listener.resume()).isEqualTo(1);
        assertThat(replayed).hasSize(1);

        handover.acknowledge(SELF, 1);
        replayed.clear();
        assertThat(listener.resume()).isZero();
        assertThat(replayed).isEmpty();
    }

    @Test
    @DisplayName("resuming with nothing in flight sends nothing at all")
    void anEmptyResumeSendsNothing() {
        assertThat(listener.resume()).isZero();
        assertThat(replayed).isEmpty();
    }

    @Test
    @DisplayName("two migrations in a row freeze once each and never lose an action")
    void successiveMigrationsAreSurvivable() {
        handover.submit(action(1));
        listener.onGatewayChanged(OTHER, THIRD, 3L);
        listener.resume();
        handover.acknowledge(SELF, 1);

        handover.submit(action(2));
        listener.onGatewayChanged(THIRD, SELF, 4L);

        assertThat(replayed).extracting(ActionEnvelope::playerSeq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("nulls are refused at construction")
    void argumentsAreChecked() {
        assertThatThrownBy(() -> new GatewayHandoverListener(null, SELF, replayed::addAll))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayHandoverListener(handover, null, replayed::addAll))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayHandoverListener(handover, SELF, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
