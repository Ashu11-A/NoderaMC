package dev.nodera.testkit;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FixtureWriter} / {@link FixtureReader} round-trip and byte-stability checks (Task 5
 * divergence-fixture acceptance).
 *
 * <p>Thread-context: single test thread.
 */
final class FixtureIOTest {

    @Test
    void writeThenReadRoundTripsAllFields() {
        RegionId region = FakeRegion.overworldRegion(2, -3);
        SnapshotVersion version = new SnapshotVersion(11L);
        RegionSnapshot snapshot = FakeRegion.emptyFlatSnapshot(region, version, 200L);

        NodeId actor = NodeId.random();
        ActionEnvelope place = FakeRegion.place(
                region, actor, 1L, 200L, new NBlockPos(32, 64, 32), FlatWorldRules.GLASS);
        ActionBatch batch = FakeRegion.batch(
                region, RegionEpoch.INITIAL, version, 200L, 201L, place);

        StateRoot expectedRoot = sha256Root(new byte[] {1, 2, 3});
        StateRoot gotRoot = sha256Root(new byte[] {9, 9, 9});
        NodeId client = NodeId.random();

        byte[] bytes = FixtureWriter.write(snapshot, batch, expectedRoot, gotRoot, client);

        FixtureReader.Fixture fixture = FixtureReader.read(bytes);
        assertThat(fixture.snapshot()).isEqualTo(snapshot);
        assertThat(fixture.batch()).isEqualTo(batch);
        assertThat(fixture.expectedRoot()).isEqualTo(expectedRoot);
        assertThat(fixture.gotRoot()).isEqualTo(gotRoot);
        assertThat(fixture.client()).isEqualTo(client);
    }

    @Test
    void writeIsByteStableAcrossCalls() {
        FixtureInput input = canonicalInput();

        byte[] first = FixtureWriter.write(
                input.snapshot, input.batch, input.expectedRoot, input.gotRoot, input.client);
        byte[] second = FixtureWriter.write(
                input.snapshot, input.batch, input.expectedRoot, input.gotRoot, input.client);

        assertThat(second).isEqualTo(first);
        assertThat(FixtureReader.read(second)).isEqualTo(FixtureReader.read(first));
    }

    @Test
    void readRejectsWrongMagic() {
        FixtureInput input = canonicalInput();
        byte[] bytes = FixtureWriter.write(
                input.snapshot, input.batch, input.expectedRoot, input.gotRoot, input.client);

        bytes[3] ^= 0x01;

        assertThatThrownBy(() -> FixtureReader.read(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("magic");
    }

    private static FixtureInput canonicalInput() {
        RegionId region = FakeRegion.overworldRegion(0, 0);
        SnapshotVersion version = SnapshotVersion.INITIAL;
        RegionSnapshot snapshot = FakeRegion.stoneLayerSnapshot(region, version, 0L, 0);
        NodeId actor = new NodeId(new java.util.UUID(0x1234L, 0x5678L));
        ActionEnvelope place = FakeRegion.place(
                region, actor, 1L, 0L, new NBlockPos(0, 0, 0), FlatWorldRules.STONE);
        ActionBatch batch = FakeRegion.batch(region, RegionEpoch.INITIAL, version, 0L, 1L, place);
        StateRoot expectedRoot = sha256Root(new byte[] {-1, 0, 1});
        StateRoot gotRoot = sha256Root(new byte[] {7, 7, 7});
        NodeId client = new NodeId(new java.util.UUID(0xABCDL, 0xEF01L));
        return new FixtureInput(snapshot, batch, expectedRoot, gotRoot, client);
    }

    private static StateRoot sha256Root(byte[] seed) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hash = md.digest(seed);
        return new StateRoot(Bytes.unsafeWrap(hash));
    }

    private record FixtureInput(
            RegionSnapshot snapshot,
            ActionBatch batch,
            StateRoot expectedRoot,
            StateRoot gotRoot,
            NodeId client) {}
}
