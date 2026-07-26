package dev.nodera.mod.server.shadow;

import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.VanillaPalette;

/**
 * The rule that decides whether a real block edit becomes a consensus action (minecraft Task 2
 * deliverable 1 — the capture half of engine Task 3's shadow lane).
 *
 * <p>Kept Minecraft-free for the same reason as {@link dev.nodera.mod.server.entity.VanillaCancelGate}:
 * the decision is where a live world meets the validated lane, and a rule only observable in a
 * running game is a rule nobody can regression-test. The bridge does the event plumbing; every
 * *judgement* lives here.
 *
 * <p><b>What is refused, and why refusal is normal.</b>
 * <ul>
 *   <li><b>Not delegated</b> — the region is not on this node's lane; the edit is somebody else's
 *       consensus state or nobody's.</li>
 *   <li><b>Unsupported block</b> — a modded or unmodelled block. It stays a vanilla-only block and
 *       the interference lane, not the action lane, accounts for it.</li>
 *   <li><b>Not placeable</b> — the state is a network-computed output (powered wire, an extended
 *       piston, a pressed plate). Accepting a *placement* of one would let a client mint power.
 *       Breaking one is still capturable: the block is consensus state, and removing it is a
 *       legitimate player input.</li>
 *   <li><b>Outside the height envelope</b> — the palette's world is {@code [MIN_Y, MAX_Y]}.</li>
 *   <li><b>Fake player</b> — an automated edit from a machine (a Create deployer, a quarry) is not
 *       a player action. It is a foreign write, and converting it into a signed player action would
 *       attribute it to whichever id the machine borrowed.</li>
 * </ul>
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class BlockCaptureRules {

    /** Why an edit was or was not turned into an action — carried so the HUD can say it. */
    public enum Reason {
        /** The edit becomes a signed action on the validated lane. */
        CAPTURED,
        /** This node's lane does not hold the region. */
        REGION_NOT_DELEGATED,
        /** The block has no consensus palette id. */
        BLOCK_UNSUPPORTED,
        /** The state exists in the palette but is an engine output, never a player input. */
        STATE_NOT_PLACEABLE,
        /** Outside the palette's buildable height envelope. */
        OUT_OF_HEIGHT,
        /** The editor is a machine wearing a player's name. */
        FAKE_PLAYER,
        /** A break whose target was already air — nothing to remove from consensus state. */
        NOTHING_TO_BREAK
    }

    /** The decision, with the reason attached whether or not it captured. */
    public record Decision(boolean capture, Reason reason) {

        static Decision refuse(Reason reason) {
            return new Decision(false, reason);
        }

        static Decision accept() {
            return new Decision(true, Reason.CAPTURED);
        }
    }

    private BlockCaptureRules() {
    }

    /**
     * @param delegated whether this node's lane holds the edited region.
     * @param paletteId the palette id of the state being placed, or {@link VanillaPalette#UNSUPPORTED}.
     * @param y         the block's world Y.
     * @param fakePlayer whether the placing entity is a NeoForge fake player.
     */
    public static Decision place(boolean delegated, int paletteId, int y, boolean fakePlayer) {
        Decision common = common(delegated, paletteId, y, fakePlayer);
        if (common != null) {
            return common;
        }
        // Air is a legal *engine* place target (the rule set treats it as a removal), but a place
        // EVENT that carries air is never a player building something — capturing it would let a
        // client delete a block through the place path, bypassing the break rules entirely.
        if (paletteId == FlatWorldRules.AIR || !FlatWorldRules.isPlaceable(paletteId)) {
            return Decision.refuse(Reason.STATE_NOT_PLACEABLE);
        }
        return Decision.accept();
    }

    /**
     * @param previousPaletteId the palette id of the state being removed. A break is capturable for
     *                          any known non-air state, including engine outputs: breaking a
     *                          powered wire is an input, placing one is not.
     */
    public static Decision breakBlock(
            boolean delegated, int previousPaletteId, int y, boolean fakePlayer) {
        Decision common = common(delegated, previousPaletteId, y, fakePlayer);
        if (common != null) {
            return common;
        }
        if (previousPaletteId == FlatWorldRules.AIR) {
            return Decision.refuse(Reason.NOTHING_TO_BREAK);
        }
        return Decision.accept();
    }

    private static Decision common(boolean delegated, int paletteId, int y, boolean fakePlayer) {
        if (!delegated) {
            return Decision.refuse(Reason.REGION_NOT_DELEGATED);
        }
        if (fakePlayer) {
            return Decision.refuse(Reason.FAKE_PLAYER);
        }
        if (paletteId == VanillaPalette.UNSUPPORTED || !FlatWorldRules.isKnown(paletteId)) {
            return Decision.refuse(Reason.BLOCK_UNSUPPORTED);
        }
        if (y < FlatWorldRules.MIN_Y || y > FlatWorldRules.MAX_Y) {
            return Decision.refuse(Reason.OUT_OF_HEIGHT);
        }
        return null;
    }
}
