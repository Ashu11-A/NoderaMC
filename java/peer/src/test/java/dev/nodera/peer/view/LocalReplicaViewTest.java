package dev.nodera.peer.view;

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
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task-16a local-replica view core (issue #35): committed base + prediction overlay + commit
 * reconciliation, all through THE deterministic engine — the headless half of the seamless
 * host-handover exit.
 */
final class LocalReplicaViewTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;
    private static final NodeId ACTOR = new NodeId(new UUID(0, 7));

    private LocalReplicaView view() {
        return new LocalReplicaView(
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), HASHES),
                HASHES, 1L, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }

    private static RegionSnapshot base() {
        List<ChunkColumnState> chunks = new ArrayList<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.add(new ChunkColumnState(dx, dz, new int[SECTION_COUNT], MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(REGION, SnapshotVersion.INITIAL, 0, chunks);
    }

    private static ActionEnvelope place(long seq, long tick, NBlockPos pos) {
        return new ActionEnvelope(
                ACTOR, seq, seq, tick, REGION,
                new PlaceBlockAction(pos, FlatWorldRules.STONE, 1), Bytes.empty());
    }

    @Test
    void predictionRendersBeforeAnyCommit() {
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);

        assertThat(view.predict(place(1, 1, new NBlockPos(3, 64, 3)))).isTrue();

        RegionSnapshot rendered = view.render(REGION).orElseThrow();
        assertThat(rendered).isNotEqualTo(base());
        assertThat(view.committedBase(REGION)).contains(base());
        assertThat(view.pendingPredictions(REGION)).isEqualTo(1);
    }

    @Test
    void matchingCommitAbsorbsThePredictionAndDropsTheOverlay() {
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);
        view.predict(place(1, 1, new NBlockPos(3, 64, 3)));
        RegionSnapshot predicted = view.render(REGION).orElseThrow();

        // The committee committed exactly what we predicted (same engine, same action).
        RegionSnapshot committed = predicted;
        view.committed(committed, StateRoot.of(HASHES.hash(committed)));

        // The overlaid action now conflicts with the committed base (its target block is already
        // placed), so reconciliation drops it — render equals certified truth exactly.
        assertThat(view.render(REGION)).contains(committed);
        assertThat(view.pendingPredictions(REGION)).isZero();
    }

    @Test
    void mismatchedCommitRollsTheRenderBackToCertifiedTruth() {
        // Note: predictions and the commit target DIFFERENT chunk columns — the flat-world state
        // model is uniform-per-section, so two placements in the same section are content-equal
        // and the overlay would (correctly) be absorbed instead of surviving.
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);
        view.predict(place(1, 1, new NBlockPos(40, 64, 40)));

        // The committee committed a DIFFERENT outcome (another player's action won the batch):
        // a stone in chunk (0,0), not ours in chunk (2,2).
        LocalReplicaView oracle = view();
        oracle.activate(base(), RegionEpoch.INITIAL);
        oracle.predict(place(9, 1, new NBlockPos(5, 64, 5)));
        RegionSnapshot committed = oracle.render(REGION).orElseThrow();

        view.committed(committed, StateRoot.of(HASHES.hash(committed)));

        // Our prediction survives reconciliation only if it still executes on the new base —
        // placing at (3,64,3) still works, so the render = committed + surviving overlay.
        RegionSnapshot rendered = view.render(REGION).orElseThrow();
        assertThat(view.committedBase(REGION)).contains(committed);
        assertThat(rendered).isNotEqualTo(committed);
        assertThat(view.pendingPredictions(REGION)).isEqualTo(1);
    }

    @Test
    void staleCommitNeverRegressesTheBase() {
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);
        view.predict(place(1, 1, new NBlockPos(3, 64, 3)));
        RegionSnapshot committed = view.render(REGION).orElseThrow();
        view.committed(committed, StateRoot.of(HASHES.hash(committed)));

        // A duplicate/late announce of the SAME version must be ignored.
        view.committed(committed, StateRoot.of(HASHES.hash(committed)));

        assertThat(view.committedBase(REGION)).contains(committed);
    }

    @Test
    void departedHostChangesNothingOnScreen() {
        // The issue-#35 no-seam invariant: when the committed stream stops (host gone), the view
        // keeps rendering the last certified base + overlay — no transition, no empty render.
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);
        view.predict(place(1, 1, new NBlockPos(3, 64, 3)));
        RegionSnapshot beforeDeparture = view.render(REGION).orElseThrow();

        // No further commits arrive. Render stays identical and live.
        assertThat(view.render(REGION)).contains(beforeDeparture);
        assertThat(view.render(REGION)).contains(beforeDeparture);
    }

    @Test
    void rejectedPredictionNeverCorruptsTheRender() {
        LocalReplicaView view = view();
        view.activate(base(), RegionEpoch.INITIAL);

        // Illegal block id: engine rejects — the overlay must not adopt it.
        ActionEnvelope illegal = new ActionEnvelope(
                ACTOR, 1, 1, 1, REGION,
                new PlaceBlockAction(new NBlockPos(3, 64, 3), 999, 1), Bytes.empty());
        assertThat(view.predict(illegal)).isFalse();
        assertThat(view.render(REGION)).contains(base());
        assertThat(view.pendingPredictions(REGION)).isZero();
    }

    @Test
    void untrackedRegionRendersEmptyAndRejectsPredictions() {
        LocalReplicaView view = view();
        assertThat(view.render(REGION)).isEmpty();
        assertThat(view.predict(place(1, 1, new NBlockPos(3, 64, 3)))).isFalse();
    }
}
