package dev.nodera.core.action;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActionBatch} ordering and round-trip checks (Task 2). The actions list carries server
 * sequence and MUST NOT be re-sorted, even if positions are out of {@code (y,z,x)} order.
 */
final class ActionBatchTest {

    private static final UUID ACTOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void actionsPreserveInputServerSequenceOrder() {
        NBlockPos high = new NBlockPos(0, 64, 0);
        NBlockPos low = new NBlockPos(0, 0, 0);
        ActionEnvelope eHigh = envWith(new BreakBlockAction(high));
        ActionEnvelope eLow = envWith(new BreakBlockAction(low));
        ActionBatch batch = new ActionBatch(
                region(), new RegionEpoch(0), new SnapshotVersion(0), 0L, 1L,
                List.of(eHigh, eLow));

        ActionBatch decoded = ActionBatch.decode(new CanonicalReader(encode(batch)));

        assertThat(decoded.actions()).extracting(a -> ((BreakBlockAction) a.action()).pos())
                .containsExactly(high, low);
    }

    @Test
    void encodeDecodeRoundTrip() {
        ActionEnvelope e1 = envWith(new BreakBlockAction(new NBlockPos(1, 2, 3)));
        ActionEnvelope e2 = envWith(new PlaceBlockAction(new NBlockPos(4, 5, 6), 12, 2));
        ActionBatch batch = new ActionBatch(
                region(), new RegionEpoch(2), new SnapshotVersion(7), 100L, 102L,
                List.of(e1, e2));

        ActionBatch decoded = ActionBatch.decode(new CanonicalReader(encode(batch)));

        assertThat(decoded).isEqualTo(batch);
    }

    private static RegionId region() {
        return new RegionId(DimensionKey.overworld(), 0, 0);
    }

    private static ActionEnvelope envWith(GameAction action) {
        return new ActionEnvelope(
                new NodeId(ACTOR_UUID), 1L, 1L, 1L, region(), action, Bytes.fromHex("ff"));
    }

    private static byte[] encode(ActionBatch batch) {
        CanonicalWriter w = new CanonicalWriter();
        batch.encode(w);
        return w.toByteArray();
    }
}
