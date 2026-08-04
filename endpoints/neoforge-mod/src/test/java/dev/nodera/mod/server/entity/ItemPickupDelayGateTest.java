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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vanilla owns an item's motion and its pickup timing; the lane owns its identity and who ends up
 * holding it.
 *
 * <h2>What this pins, and the two ways it was got wrong</h2>
 *
 * <p>{@code EntityCaptureBridge} used to suppress a captured item's vanilla tick, handing its
 * physics to the deterministic engine. The engine's item model is a flat-world placeholder —
 * {@code ItemEntityRules.GROUND_Y} is world <b>Y = 1.0</b> and {@code move()} reads no blocks — so
 * an item dropped at Y≈78 fell through the real floor in canonical state, every commit teleported
 * the live entity after it, and vanilla's next tick shoved it back out of the stone. Seen live as an
 * item bobbing up and down forever.
 *
 * <p>The same suppression froze {@code pickupDelay}, which vanilla decrements <b>inside</b>
 * {@code ItemEntity.tick()}. Zeroing the delay to compensate made the thrower vacuum the item
 * straight back up ("players simply cannot drop items"); gating that zeroing on being the region's
 * primary — as the pickup admission already was — left every other node holding an item frozen at
 * vanilla's 40-tick drop delay <b>forever</b>, which is the "nobody can pick it up" report. Three
 * gates that had to agree, fixed one at a time, each fix moving the symptom.
 *
 * <p>Mobs were never affected, and that was the tell: a ghost is vanilla-authoritative and mirrored
 * into canonical after its tick, with nothing written back. Items are that shape now too.
 *
 * <p>The two structural assertions read the source because the capture path needs a live
 * {@code ItemEntity} and a running server, and is not reachable from a Minecraft-free test.
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
    @DisplayName("the capture path never touches an item's pickup delay")
    void theCapturePathLeavesVanillaTimingAlone() throws Exception {
        String text = Files.readString(bridgeSource());

        assertThat(text)
                .as("vanilla stamps 40 ticks on a drop and decrements it inside ItemEntity.tick. "
                        + "Overriding that from here has been wrong in both directions: zeroed, the "
                        + "thrower vacuums the item straight back; left at 40 on a tick-suppressed "
                        + "item, it freezes and nobody can ever pick it up")
                .doesNotContain("setPickUpDelay");
    }

    @Test
    @DisplayName("an item's tick is never cancelled")
    void itemTicksAreNotSuppressed() throws Exception {
        String text = Files.readString(bridgeSource());

        int preTick = text.indexOf("private void onTickPre(");
        assertThat(preTick).as("the pre-tick handler still exists").isPositive();
        String handler = text.substring(preTick, text.indexOf("private void onTickPost("));

        assertThat(handler)
                .as("suppressing an item's tick hands its physics to the engine, whose item model "
                        + "rests on a flat-world plane at Y = 1.0 and reads no blocks — so the "
                        + "canonical item falls through the floor while every commit teleports the "
                        + "real one after it and vanilla shoves it back out. Both halves of the "
                        + "endless bobbing, and the frozen pickup delay, come from this one line")
                .doesNotContain("setCanceled(true)");
    }

    private static java.nio.file.Path bridgeSource() {
        return LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod/server/entity/EntityCaptureBridge.java");
    }
}
