package dev.nodera.endpoint.lane;

import dev.nodera.endpoint.lane.BlockCaptureRules;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.VanillaPalette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block-capture decision, pinned without a running game. Each refusal is a rule the live lane
 * would otherwise only reveal as a divergence hours into a soak.
 */
class BlockCaptureRulesTest {

    @Test
    @DisplayName("a player placing a palette block in a delegated region is captured")
    void ordinaryPlacementIsCaptured() {
        BlockCaptureRules.Decision decision =
                BlockCaptureRules.place(true, FlatWorldRules.STONE, 64, false);
        assertTrue(decision.capture());
        assertEquals(BlockCaptureRules.Reason.CAPTURED, decision.reason());
    }

    @Test
    @DisplayName("nothing is captured outside this node's own regions")
    void aRegionThisNodeDoesNotHoldIsNeverCaptured() {
        BlockCaptureRules.Decision decision =
                BlockCaptureRules.place(false, FlatWorldRules.STONE, 64, false);
        assertFalse(decision.capture());
        assertEquals(BlockCaptureRules.Reason.REGION_NOT_DELEGATED, decision.reason());
    }

    @Test
    @DisplayName("an unsupported block is dropped as unsupported, not silently mapped")
    void unsupportedBlocksAreRefusedWithTheirOwnReason() {
        BlockCaptureRules.Decision place =
                BlockCaptureRules.place(true, VanillaPalette.UNSUPPORTED, 64, false);
        assertFalse(place.capture());
        assertEquals(BlockCaptureRules.Reason.BLOCK_UNSUPPORTED, place.reason());
        BlockCaptureRules.Decision broken =
                BlockCaptureRules.breakBlock(true, VanillaPalette.UNSUPPORTED, 64, false);
        assertFalse(broken.capture());
        assertEquals(BlockCaptureRules.Reason.BLOCK_UNSUPPORTED, broken.reason());
    }

    @Test
    @DisplayName("placing a network-computed state is refused, but breaking one is an input")
    void engineOutputsCanBeBrokenAndNeverPlaced() {
        // Placing powered wire would mint 15 power out of a client packet.
        BlockCaptureRules.Decision placed =
                BlockCaptureRules.place(true, FlatWorldRules.WIRE_0 + 15, 64, false);
        assertFalse(placed.capture());
        assertEquals(BlockCaptureRules.Reason.STATE_NOT_PLACEABLE, placed.reason());
        // Breaking it is an ordinary player input on ordinary consensus state.
        assertTrue(BlockCaptureRules.breakBlock(true, FlatWorldRules.WIRE_0 + 15, 64, false)
                .capture());
        assertTrue(BlockCaptureRules.breakBlock(true, FlatWorldRules.PISTON_EXTENDED_BASE, 64, false)
                .capture());
    }

    @Test
    @DisplayName("a break against air has nothing to remove from consensus state")
    void breakingAirIsRefused() {
        BlockCaptureRules.Decision decision =
                BlockCaptureRules.breakBlock(true, FlatWorldRules.AIR, 64, false);
        assertFalse(decision.capture());
        assertEquals(BlockCaptureRules.Reason.NOTHING_TO_BREAK, decision.reason());
        // Placing air is not a break in disguise either — it is simply not placeable.
        assertFalse(BlockCaptureRules.place(true, FlatWorldRules.AIR, 64, false).capture());
    }

    @Test
    @DisplayName("the height envelope is the palette's, and both ends are inclusive")
    void editsOutsideTheHeightEnvelopeAreRefused() {
        assertTrue(BlockCaptureRules.place(true, FlatWorldRules.STONE, FlatWorldRules.MIN_Y, false)
                .capture());
        assertTrue(BlockCaptureRules.place(true, FlatWorldRules.STONE, FlatWorldRules.MAX_Y, false)
                .capture());
        assertEquals(BlockCaptureRules.Reason.OUT_OF_HEIGHT,
                BlockCaptureRules.place(true, FlatWorldRules.STONE, FlatWorldRules.MIN_Y - 1, false)
                        .reason());
        assertEquals(BlockCaptureRules.Reason.OUT_OF_HEIGHT,
                BlockCaptureRules.breakBlock(true, FlatWorldRules.STONE, FlatWorldRules.MAX_Y + 1,
                        false).reason());
    }

    @Test
    @DisplayName("a machine's edit is a foreign write, never a signed player action")
    void fakePlayerEditsAreNeverAttributedToAPlayer() {
        assertEquals(BlockCaptureRules.Reason.FAKE_PLAYER,
                BlockCaptureRules.place(true, FlatWorldRules.STONE, 64, true).reason());
        assertEquals(BlockCaptureRules.Reason.FAKE_PLAYER,
                BlockCaptureRules.breakBlock(true, FlatWorldRules.STONE, 64, true).reason());
    }

    @Test
    @DisplayName("the ownership check comes first: a foreign region refuses before anything else")
    void refusalOrderPutsOwnershipFirst() {
        // A fake player editing an unsupported block outside the envelope in a region we do not
        // hold must still report REGION_NOT_DELEGATED — the counters are read as "how much of the
        // world is ours", so the cheapest, broadest reason has to win.
        assertEquals(BlockCaptureRules.Reason.REGION_NOT_DELEGATED,
                BlockCaptureRules.place(false, VanillaPalette.UNSUPPORTED, 5_000, true).reason());
    }
}
