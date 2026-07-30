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
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seam that makes L-16 visible: the view had prediction all along and nothing ever called it.
 * These tests are about the four ordinary answers the feed has to give without anybody treating
 * them as errors.
 */
final class PredictionFeedTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId ELSEWHERE = new RegionId(DimensionKey.overworld(), 9, 9);
    private static final NodeId ACTOR = new NodeId(new UUID(0, 7));
    private static final int MIN_Y = -64;
    private static final int SECTION_COUNT = 24;

    private static LocalReplicaView view() {
        return new LocalReplicaView(
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), HASHES),
                HASHES, 1L, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
    }

    private static RegionSnapshot base(RegionId region) {
        List<ChunkColumnState> chunks = new ArrayList<>();
        int originX = region.originChunkX();
        int originZ = region.originChunkZ();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.add(new ChunkColumnState(
                        originX + dx, originZ + dz, new int[SECTION_COUNT], MIN_Y, SECTION_COUNT));
            }
        }
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0, chunks);
    }

    private static ActionEnvelope place(RegionId region, long seq, NBlockPos pos) {
        return new ActionEnvelope(ACTOR, seq, seq, seq, region,
                new PlaceBlockAction(pos, FlatWorldRules.STONE, 1), Bytes.empty());
    }

    @Test
    @DisplayName("a locally-captured action reaches the overlay, which is the whole of L-16")
    void aCapturedActionIsPredicted() {
        LocalReplicaView view = view();
        view.activate(base(REGION), RegionEpoch.INITIAL);
        PredictionFeed feed = new PredictionFeed(() -> view);

        assertThat(feed.onLocalAction(place(REGION, 1, new NBlockPos(4, 70, 4)))).isTrue();
        assertThat(view.pendingPredictions(REGION))
                .as("the render is ahead of the commit — that is the latency L-16 is about")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a node with no renderer predicts nothing, and that is not a failure")
    void aDedicatedServerHasNothingToPredictOnto() {
        assertThat(PredictionFeed.none().onLocalAction(place(REGION, 1, new NBlockPos(4, 70, 4))))
                .isFalse();
        // The capture path must not have to know whether anyone is looking.
        AtomicReference<LocalReplicaView> absent = new AtomicReference<>();
        assertThat(new PredictionFeed(absent::get)
                .onLocalAction(place(REGION, 1, new NBlockPos(4, 70, 4)))).isFalse();
    }

    @Test
    @DisplayName("an action for a region the view does not track is refused, not rendered")
    void anUntrackedRegionIsRefused() {
        LocalReplicaView view = view();
        view.activate(base(REGION), RegionEpoch.INITIAL);
        PredictionFeed feed = new PredictionFeed(() -> view);

        assertThat(feed.onLocalAction(place(ELSEWHERE, 1, new NBlockPos(150, 70, 150)))).isFalse();
        assertThat(view.render(ELSEWHERE)).isEmpty();
        assertThat(view.pendingPredictions(REGION)).isZero();
    }

    @Test
    @DisplayName("a lane that starts later is picked up: the supplier is read per call")
    void theViewIsResolvedFreshEachTime() {
        AtomicReference<LocalReplicaView> slot = new AtomicReference<>();
        PredictionFeed feed = new PredictionFeed(slot::get);
        ActionEnvelope action = place(REGION, 1, new NBlockPos(4, 70, 4));

        assertThat(feed.onLocalAction(action)).isFalse();

        LocalReplicaView view = view();
        view.activate(base(REGION), RegionEpoch.INITIAL);
        slot.set(view);

        assertThat(feed.onLocalAction(action))
                .as("a lane can start under a long-lived capture path; capturing the view once "
                        + "would leave the feed permanently blind")
                .isTrue();
    }

    @Test
    @DisplayName("a view that throws costs a late-looking block, never the submit path")
    void aFaultyViewIsContained() {
        LocalReplicaView exploding = new LocalReplicaView(
                request -> {
                    throw new IllegalStateException("engine on fire");
                },
                HASHES, 1L, FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        exploding.activate(base(REGION), RegionEpoch.INITIAL);

        assertThat(new PredictionFeed(() -> exploding)
                .onLocalAction(place(REGION, 1, new NBlockPos(4, 70, 4))))
                .isFalse();
    }

    @Test
    @DisplayName("a null action is ignored and a null supplier is refused at construction")
    void argumentsAreChecked() {
        assertThat(PredictionFeed.none().onLocalAction(null)).isFalse();
        assertThatThrownBy(() -> new PredictionFeed(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
