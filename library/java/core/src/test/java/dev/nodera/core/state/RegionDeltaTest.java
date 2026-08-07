package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RegionDelta} canonicalisation and round-trip checks (Task 2). Block mutations are sorted
 * by {@code (y, z, x)} (via {@link NBlockPos#compareTo}) so equivalent deltas encode identically.
 */
final class RegionDeltaTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot ROOT = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));

    @Test
    void mutationsSortedByYZX() {
        BlockMutation m1 = mut(new NBlockPos(0, 64, 0));
        BlockMutation m2 = mut(new NBlockPos(0, 0, 5));
        BlockMutation m3 = mut(new NBlockPos(9, 0, 0));

        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1),
                List.of(m1, m2, m3), ROOT);

        assertThat(delta.blockMutations()).extracting(BlockMutation::pos)
                .containsExactly(new NBlockPos(9, 0, 0), new NBlockPos(0, 0, 5), new NBlockPos(0, 64, 0));
    }

    @Test
    void isEmptyTrueWhenNoMutations() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(), ROOT);
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void isEmptyFalseWhenMutationsPresent() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(0), new SnapshotVersion(1),
                List.of(mut(new NBlockPos(1, 2, 3))), ROOT);
        assertThat(delta.isEmpty()).isFalse();
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionDelta delta = new RegionDelta(
                REGION, new SnapshotVersion(2), new SnapshotVersion(3),
                List.of(mut(new NBlockPos(1, 2, 3)), mut(new NBlockPos(4, 5, 6))), ROOT);

        RegionDelta decoded = RegionDelta.decode(new CanonicalReader(encode(delta)));
        assertThat(decoded).isEqualTo(delta);
    }

    @Test
    void byteStableAcrossInputOrder() {
        BlockMutation a = mut(new NBlockPos(0, 64, 0));
        BlockMutation b = mut(new NBlockPos(0, 0, 5));
        RegionDelta d1 = new RegionDelta(REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(a, b), ROOT);
        RegionDelta d2 = new RegionDelta(REGION, new SnapshotVersion(0), new SnapshotVersion(1), List.of(b, a), ROOT);

        assertThat(encode(d1)).isEqualTo(encode(d2));
    }

    @Test
    void entityMutationsAndCreditsRoundTrip() {
        PersistedEntityState entity = EntityMutationTest.entity(4, 2);
        InventoryCredit credit = new InventoryCredit(
                new dev.nodera.core.identity.NodeId(new UUID(1, 2)), entity.id(), 42, 2);
        RegionDelta delta = new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(new EntityMutation(entity.id(), null, entity)),
                List.of(credit));
        assertThat(RegionDelta.decode(new CanonicalReader(encode(delta)))).isEqualTo(delta);
    }

    @Test
    void transferIntentsRoundTripAndMakeDeltaNonEmpty() {
        PersistedEntityState entity = EntityMutationTest.entity(4, 2);
        EntityTransferIntent transfer = new EntityTransferIntent(
                new RegionId(DimensionKey.overworld(), 1, 0), entity);
        RegionDelta delta = new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(), List.of(transfer));
        assertThat(delta.isEmpty()).isFalse();
        assertThat(RegionDelta.decode(new CanonicalReader(encode(delta)))).isEqualTo(delta);
    }

    @Test
    void entityMutationsSortById() {
        EntityMutation high = new EntityMutation(
                new NetworkEntityId(8), null, EntityMutationTest.entity(8, 1));
        EntityMutation low = new EntityMutation(
                new NetworkEntityId(-1), null, EntityMutationTest.entity(-1, 1));
        RegionDelta delta = new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(high, low), List.of());
        assertThat(delta.entityMutations()).extracting(m -> m.id().value()).containsExactly(-1L, 8L);
    }

    @Test
    void inventoryCreditMakesDeltaNonEmpty() {
        InventoryCredit credit = new InventoryCredit(
                new dev.nodera.core.identity.NodeId(new UUID(1, 2)),
                new NetworkEntityId(4), 42, 2);
        RegionDelta delta = new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(credit));
        assertThat(delta.isEmpty()).isFalse();
    }

    /**
     * A body encoded by an older build: the fixed head, then {@code emptyTails} empty lists.
     *
     * @param bodyVersion the version to stamp in the header.
     * @param emptyTails how many trailing empty lists that version's body carries after the root.
     * @return the encoded frame.
     */
    private static byte[] olderBody(int bodyVersion, int emptyTails) {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(dev.nodera.core.crypto.TypeTags.REGION_DELTA).writeU16(bodyVersion);
        REGION.encode(w);
        SnapshotVersion.INITIAL.encode(w);
        SnapshotVersion.INITIAL.next().encode(w);
        w.writeList(List.of(), CanonicalWriter::writeEncodable);
        ROOT.encode(w);
        for (int i = 0; i < emptyTails; i++) {
            w.writeList(List.of(), CanonicalWriter::writeEncodable);
        }
        return w.toByteArray();
    }

    /**
     * Every body version this build still reads, and how many trailing lists it carries.
     *
     * <p>These were three methods differing by a version number and a count of
     * {@code writeList(List.of(), …)} calls, each asserting the subset of tails its own version
     * introduced. As one parameterised claim every version asserts <i>all</i> the tails are empty,
     * which is strictly stronger, and version five is a row rather than a fourth copy.
     *
     * @return version, number of trailing empty lists.
     */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> olderBodyVersions() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(1, 0),
                org.junit.jupiter.params.provider.Arguments.of(2, 2),
                org.junit.jupiter.params.provider.Arguments.of(3, 3));
    }

    @ParameterizedTest(name = "a v{0} body decodes with empty tails and re-encodes to its own bytes")
    @MethodSource("olderBodyVersions")
    void anOlderBodyVersionDecodesEmptyAndReEncodesExactly(int bodyVersion, int emptyTails) {
        byte[] encoded = olderBody(bodyVersion, emptyTails);
        RegionDelta decoded = RegionDelta.decode(new CanonicalReader(encoded));

        assertThat(decoded.bodyVersion()).isEqualTo(bodyVersion);
        assertThat(decoded.entityMutations()).isEmpty();
        assertThat(decoded.inventoryCredits()).isEmpty();
        assertThat(decoded.transferIntents()).isEmpty();
        assertThat(decoded.scheduledTicks()).isEmpty();
        assertThat(decoded.blockEvents()).isEmpty();
        // Re-encoding to the SAME bytes is the whole point: a build that upgraded the body on
        // decode would hash a different root from the peer that sent it.
        assertThat(encode(decoded)).isEqualTo(encoded);
    }

    @Test
    void versionFourRoundTripsScheduledStateInCanonicalOrder() {
        ScheduledTickEntry late = new ScheduledTickEntry(new NBlockPos(1, 64, 1), 5, 9L, 0, 2L);
        ScheduledTickEntry early = new ScheduledTickEntry(new NBlockPos(2, 64, 2), 5, 3L, 0, 1L);
        BlockEventEntry event = new BlockEventEntry(new NBlockPos(3, 64, 3), 0, 1, 0);
        RegionDelta delta = new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(), List.of(),
                List.of(late, early), List.of(event));
        assertThat(delta.bodyVersion()).isEqualTo(RegionDelta.SCHEDULED_ENCODING_VERSION);
        assertThat(delta.scheduledTicks())
                .as("scheduled ticks canonicalise to execution order")
                .containsExactly(early, late);
        assertThat(delta.isEmpty()).isFalse();
        RegionDelta decoded = RegionDelta.decode(new CanonicalReader(encode(delta)));
        assertThat(decoded).isEqualTo(delta);
        assertThat(encode(decoded)).isEqualTo(encode(delta));
    }

    @Test
    void legacyDeltaCannotCarryScheduledState() {
        ScheduledTickEntry entry = new ScheduledTickEntry(new NBlockPos(1, 64, 1), 5, 3L, 0, 1L);
        assertThatThrownBy(() -> new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(), List.of(),
                List.of(entry), List.of(), List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy delta cannot carry scheduled state");
    }

    @Test
    void rejectsBodyVersionAboveContainer() {
        assertThatThrownBy(() -> new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported region-delta body version");
    }

    @Test
    void rejectsDuplicateEntityMutations() {
        PersistedEntityState entity = EntityMutationTest.entity(1, 1);
        EntityMutation mutation = new EntityMutation(entity.id(), null, entity);
        assertThatThrownBy(() -> new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(mutation, mutation), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate entity mutation");
    }

    @Test
    void rejectsDuplicateInventoryCreditReplayKeys() {
        InventoryCredit credit = new InventoryCredit(
                new dev.nodera.core.identity.NodeId(new UUID(1, 2)),
                new NetworkEntityId(4), 42, 2);
        assertThatThrownBy(() -> new RegionDelta(
                REGION, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(),
                List.of(), ROOT, List.of(), List.of(credit, credit)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate inventory credit");
    }

    @Test
    void rejectsUnsupportedBodyVersion() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(dev.nodera.core.crypto.TypeTags.REGION_DELTA).writeU16(6);
        assertThatThrownBy(() -> RegionDelta.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REGION_DELTA encoding version");
    }

    private static BlockMutation mut(NBlockPos pos) {
        return new BlockMutation(pos, 1, 2, 0);
    }

    private static byte[] encode(RegionDelta delta) {
        CanonicalWriter w = new CanonicalWriter();
        delta.encode(w);
        return w.toByteArray();
    }
}
