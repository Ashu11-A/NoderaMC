package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.endpoint.lane.VanillaCancelGate;
import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dropped item may only lose vanilla's pickup grace where the validated lane can actually replace
 * it.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>{@code EntityCaptureBridge} adopts every item that appears in a delegated region and used to
 * zero its pickup delay unconditionally, reasoning that pickup validity now belongs to the lane's
 * {@code PickupItemAction} admission. It does — but only on the region's <b>primary</b>:
 * {@code submitPickup} refuses everywhere else ({@link VanillaCancelGate}, issues #33/#44), so
 * {@code onPickupPre} does not cancel the vanilla pickup there.
 *
 * <p>On a node that is merely a validator, the delay was therefore removed and nothing took its
 * place. A tossed item lay collectable at the thrower's feet and vanilla vacuumed it straight back
 * in — reported from a live two-player session as "players simply cannot drop items", and, tellingly,
 * only while <b>both</b> players read {@code VALIDATING}. Two players standing close together near a
 * region boundary are each inside a region whose centre the other is nearer to, so neither is
 * primary of the ground under their own feet; walk one of them away and drops start working again.
 *
 * <p>The rule is asserted twice: once as behaviour on the pure gate, and once structurally on the
 * capture path, because that path needs a live {@code ItemEntity} and a running server and is
 * therefore not reachable from a Minecraft-free test.
 */
final class ItemPickupDelayGateTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0);

    private static RegionLease leaseOf(NodeId primary, List<NodeId> validators) {
        return new RegionLease(REGION, new RegionEpoch(1), primary, validators, 0L, Long.MAX_VALUE);
    }

    @Test
    @DisplayName("the primary may take vanilla's outcome; a validator may not")
    void onlyThePrimaryMayCancelVanilla() {
        NodeId primary = NodeId.random();
        NodeId validator = NodeId.random();
        Optional<RegionLease> lease = Optional.of(leaseOf(primary, List.of(validator)));

        assertThat(VanillaCancelGate.mayCancelVanilla(lease, primary))
                .as("the primary commits synchronously, so the validated item lands in the same tick")
                .isTrue();
        assertThat(VanillaCancelGate.mayCancelVanilla(lease, validator))
                .as("a validator's submit is fire-and-forget: 'accepted' is not 'committed', and "
                        + "taking vanilla away against it is how an item silently snaps back")
                .isFalse();
    }

    @Test
    @DisplayName("a region this node holds no lease for never cancels vanilla")
    void noLeaseNeverCancels() {
        assertThat(VanillaCancelGate.mayCancelVanilla(Optional.empty(), NodeId.random())).isFalse();
    }

    @Test
    @DisplayName("the capture path zeroes a pickup delay only behind that gate")
    void theCapturePathIsGated() throws Exception {
        Path source = LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod/server/entity/EntityCaptureBridge.java");
        String text = Files.readString(source);

        int zeroed = text.indexOf("item.setPickUpDelay(0)");
        assertThat(zeroed).as("the capture path still zeroes a pickup delay somewhere").isPositive();

        // The gate call must appear immediately above it. Asserting adjacency rather than mere
        // presence is the point: the previous version of this code had the gate available on the
        // runtime and simply did not consult it here.
        String preceding = text.substring(Math.max(0, zeroed - 200), zeroed);
        assertThat(preceding)
                .as("zeroing the delay outside `mayCancelVanilla` removes vanilla's grace and puts "
                        + "nothing in its place — see this class's Javadoc")
                .contains("mayCancelVanilla(region)");
    }
}
