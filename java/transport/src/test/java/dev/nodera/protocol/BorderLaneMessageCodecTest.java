package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.GroupMigration;
import dev.nodera.protocol.simulationmsg.HaloUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13 increment 9 (L-26): the border-lane protocol messages — tags 56/57 appended to the
 * frozen wire contract. Halo slices travel as opaque encoded column frames (the transport
 * plane never interprets region state); a migration order carries the shared primary and one
 * epoch bump per group region.
 */
final class BorderLaneMessageCodecTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 3, -2);

    @Test
    void haloUpdateRoundTripsByteExactly() {
        HaloUpdate update = new HaloUpdate(
                REGION, new SnapshotVersion(41),
                List.of(Bytes.unsafeWrap(new byte[]{1, 2, 3}), Bytes.unsafeWrap(new byte[]{4, 5})));
        byte[] encoded = MessageCodec.encode(update);
        NoderaMessage decoded = MessageCodec.decode(encoded);
        assertThat(decoded).isEqualTo(update);
        assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
        assertThat(MessageCodec.typeTagOf(update)).isEqualTo(MessageCodec.TAG_HALO_UPDATE);
    }

    @Test
    void groupMigrationRoundTripsByteExactly() {
        GroupMigration migration = new GroupMigration(
                new NodeId(new UUID(7, 9)),
                List.of(
                        new GroupMigration.RegionEpochBump(REGION, new RegionEpoch(5)),
                        new GroupMigration.RegionEpochBump(
                                new RegionId(DimensionKey.overworld(), 4, -2),
                                new RegionEpoch(3))));
        byte[] encoded = MessageCodec.encode(migration);
        NoderaMessage decoded = MessageCodec.decode(encoded);
        assertThat(decoded).isEqualTo(migration);
        assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
        assertThat(MessageCodec.typeTagOf(migration))
                .isEqualTo(MessageCodec.TAG_GROUP_MIGRATION);
    }

    @Test
    void emptyMigrationIsRejected() {
        assertThatThrownBy(() -> new GroupMigration(new NodeId(new UUID(1, 1)), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one region");
    }
}
