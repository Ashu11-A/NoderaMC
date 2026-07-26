package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.peer.view.LocalReplicaView;
import dev.nodera.peer.view.PredictionFeed;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine L-17's exit clause, driven end to end: <b>play continues with zero reconnect during
 * migration</b>.
 *
 * <p>The row describes a gateway migration as "a brief reconnect plus an action freeze". Two things
 * have to be true for that to stop being a limitation, and they pull in opposite directions:
 *
 * <ul>
 *   <li><b>The player keeps seeing a world.</b> The render must never go blank or stall while the
 *       gateway is gone, which it cannot do if it is fed by the gateway. It is not: the local
 *       replica view renders from committed state plus this player's own predictions, so a
 *       migration is invisible to it.</li>
 *   <li><b>No action is lost, and none happens twice.</b> The freeze holds what was in flight, and
 *       the replay is keyed by identity so an acknowledged action never returns.</li>
 * </ul>
 *
 * <p>This exercises both together over a real engine, because either one alone is easy and the pair
 * is the actual claim: a session that keeps rendering by dropping actions is not continuity, and one
 * that preserves actions by freezing the screen is not either.
 */
final class GatewayMigrationContinuityIT {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final NodeId PLAYER = new NodeId(new UUID(0, 7));
    private static final NodeId OLD_GATEWAY = new NodeId(new UUID(0, 1));
    private static final NodeId NEW_GATEWAY = new NodeId(new UUID(0, 2));
    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;

    private static RegionSnapshot base() {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.add(new ChunkColumnState(dx, dz, new int[SECTIONS], MIN_Y, SECTIONS));
            }
        }
        return new RegionSnapshot(REGION, SnapshotVersion.INITIAL, 0, chunks);
    }

    private static LocalReplicaView view() {
        LocalReplicaView view = new LocalReplicaView(
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), HASHES),
                HASHES, 1L, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        view.activate(base(), RegionEpoch.INITIAL);
        return view;
    }

    private static ActionEnvelope place(long seq, int x, int z) {
        return new ActionEnvelope(PLAYER, seq, seq, seq, REGION,
                new PlaceBlockAction(new NBlockPos(x, 70, z), FlatWorldRules.STONE, 1),
                Bytes.empty());
    }

    @Test
    @DisplayName("the render never goes blank across a migration, and nothing is lost or doubled")
    void playContinuesWithZeroReconnect() {
        LocalReplicaView view = view();
        PredictionFeed feed = new PredictionFeed(() -> view);
        GatewayHandover handover = new GatewayHandover();
        List<ActionEnvelope> sentToGateway = new ArrayList<>();
        GatewayHandoverListener listener =
                new GatewayHandoverListener(handover, PLAYER, sentToGateway::addAll);

        // --- before the migration: ordinary play, everything sends and renders ---
        for (long seq = 1; seq <= 3; seq++) {
            ActionEnvelope a = place(seq, (int) seq, 1);
            assertThat(handover.submit(a)).as("an open session sends immediately").isTrue();
            sentToGateway.add(a);
            assertThat(feed.onLocalAction(a)).isTrue();
        }
        // The old gateway commits the first two; the third is still in flight.
        handover.acknowledge(PLAYER, 1);
        handover.acknowledge(PLAYER, 2);
        assertThat(view.render(REGION)).isPresent();

        // --- the gateway dies mid-swing ---
        listener.onGatewayChanged(OLD_GATEWAY, NEW_GATEWAY, 1L);
        assertThat(handover.isFrozen()).isTrue();

        // The player keeps playing. Submissions queue; the RENDER does not care that the gateway is
        // gone, because it was never fed by the gateway.
        for (long seq = 4; seq <= 8; seq++) {
            ActionEnvelope a = place(seq, (int) seq, 1);
            assertThat(handover.submit(a)).as("frozen means later, never lost").isFalse();
            assertThat(feed.onLocalAction(a)).isTrue();
            assertThat(view.render(REGION))
                    .as("the world is still on screen while the session has no gateway at all")
                    .isPresent();
        }
        assertThat(view.pendingPredictions(REGION))
                .as("every action the player took is showing — the overlay holds all eight, "
                        + "because a gateway acknowledgement is not a commit and the view "
                        + "reconciles only against committed state")
                .isEqualTo(8);

        // --- a link to the new gateway comes up ---
        int replayed = listener.resume();

        assertThat(handover.isFrozen()).isFalse();
        assertThat(replayed).as("the unacknowledged third action plus the five taken while frozen")
                .isEqualTo(6);

        // Nothing lost: every sequence the player submitted reached a gateway.
        Set<Long> delivered = new HashSet<>();
        for (ActionEnvelope a : sentToGateway) {
            delivered.add(a.playerSeq());
        }
        assertThat(delivered).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);

        // Nothing doubled: the two the old gateway acknowledged were not replayed to the new one.
        List<Long> afterMigration = new ArrayList<>();
        for (int i = 3; i < sentToGateway.size(); i++) {
            afterMigration.add(sentToGateway.get(i).playerSeq());
        }
        assertThat(afterMigration)
                .as("an acknowledged action must never be replayed — that is how a block is placed "
                        + "twice")
                .containsExactly(3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    @DisplayName("the view reconciles to committed truth after the migration, not to the guess")
    void predictionsAreReconciledAfterTheHandover() {
        LocalReplicaView view = view();
        PredictionFeed feed = new PredictionFeed(() -> view);
        GatewayHandover handover = new GatewayHandover();
        GatewayHandoverListener listener =
                new GatewayHandoverListener(handover, PLAYER, sent -> { });

        ActionEnvelope a = place(1, 4, 4);
        handover.submit(a);
        feed.onLocalAction(a);
        listener.onGatewayChanged(OLD_GATEWAY, NEW_GATEWAY, 1L);
        listener.resume();

        assertThat(view.pendingPredictions(REGION)).isEqualTo(1);

        // The new gateway commits it. The overlay must fold into consensus truth rather than keep
        // rendering its own guess — a prediction that outlives its commit is how a client drifts.
        RegionSnapshot committed = view.render(REGION).orElseThrow();
        view.committed(committed, dev.nodera.core.state.StateRoot.of(HASHES.hash(committed)));

        assertThat(view.pendingPredictions(REGION)).isZero();
        assertThat(view.render(REGION)).isPresent();
    }

    @Test
    @DisplayName("two migrations back to back still lose nothing and double nothing")
    void successiveMigrationsPreserveExactlyOnce() {
        GatewayHandover handover = new GatewayHandover();
        List<Long> sent = new ArrayList<>();
        GatewayHandoverListener listener = new GatewayHandoverListener(handover, PLAYER,
                actions -> actions.forEach(a -> sent.add(a.playerSeq())));

        handover.submit(place(1, 1, 1));
        listener.onGatewayChanged(OLD_GATEWAY, NEW_GATEWAY, 1L);
        listener.resume();
        handover.acknowledge(PLAYER, 1);

        handover.submit(place(2, 2, 2));
        listener.onGatewayChanged(NEW_GATEWAY, OLD_GATEWAY, 2L);
        listener.resume();

        assertThat(sent).containsExactly(1L, 2L);
    }
}
