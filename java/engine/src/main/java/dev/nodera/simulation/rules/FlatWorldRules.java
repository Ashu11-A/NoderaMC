package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.DropItemAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;
import dev.nodera.simulation.entity.ItemEntityRules;

import java.util.BitSet;
import java.util.Optional;

/**
 * The MVP flat-world {@link RuleSet} (Task 3). Whitelists a small fixed block palette by integer
 * state id, enforces horizontal ownership ({@link ActionRejection.Reason#OUT_OF_REGION}) and a sane
 * height envelope ({@link ActionRejection.Reason#OUT_OF_REACH}), and applies place/break at
 * section granularity via {@link MutableRegionState#setBlock}.
 *
 * <p><b>Palette id table (frozen for the MVP).</b> The integer id is the consensus state id; there
 * are no property layers in the MVP, so {@code blockStateId == paletteId} everywhere:
 * <table>
 *   <caption>MVP palette</caption>
 *   <tr><th>id</th><th>name</th></tr>
 *   <tr><td>0</td><td>air</td></tr>
 *   <tr><td>1</td><td>stone</td></tr>
 *   <tr><td>2</td><td>dirt</td></tr>
 *   <tr><td>3</td><td>grass_block</td></tr>
 *   <tr><td>4</td><td>cobblestone</td></tr>
 *   <tr><td>5</td><td>oak_planks</td></tr>
 *   <tr><td>6</td><td>oak_log</td></tr>
 *   <tr><td>7</td><td>glass</td></tr>
 *   <tr><td>8</td><td>sand</td></tr>
 * </table>
 *
 * <p><b>Registry fingerprint.</b> {@link #registryFingerprint()} mixes the ordered {@code (id, name)}
 * table through {@link StableHash}; two builds whose palettes differ in any id or name refuse to
 * validate each other. The engine asserts {@link RegionExecutionContext#registryFingerprint()}
 * matches its own before touching state.
 *
 * <p><b>RNG.</b> MVP rules draw nothing from {@code rng}; the parameter is kept so later rule sets
 * (plant growth, combat) can consume deterministic randomness without changing the
 * {@link RuleSet} signature.
 *
 * @Thread-context thread-confined per call; the rule set holds no mutable state.
 */
public final class FlatWorldRules implements RuleSet {

    /** Rules-version: bumped whenever this rule set's semantics change. Mixed-version committees must refuse. */
    public static final int RULES_VERSION = 3;

    /** Palette id for air. */
    public static final int AIR = 0;
    /** Palette id for stone. */
    public static final int STONE = 1;
    /** Palette id for dirt. */
    public static final int DIRT = 2;
    /** Palette id for grass_block. */
    public static final int GRASS_BLOCK = 3;
    /** Palette id for cobblestone. */
    public static final int COBBLESTONE = 4;
    /** Palette id for oak_planks. */
    public static final int OAK_PLANKS = 5;
    /** Palette id for oak_log. */
    public static final int OAK_LOG = 6;
    /** Palette id for glass. */
    public static final int GLASS = 7;
    /** Palette id for sand. */
    public static final int SAND = 8;

    // --- Task 13 redstone palette (v3 first slice) -------------------------------------------
    /** Constant 15-power source. */
    public static final int REDSTONE_BLOCK = 9;
    /** Lever, OFF (placeable; toggled by {@code InteractBlockAction}). */
    public static final int LEVER_OFF = 10;
    /** Lever, ON (network-computed state — never directly placeable). */
    public static final int LEVER_ON = 11;
    /** Redstone wire at power 0 (placeable); powers 1..15 are the ids {@code WIRE_0 + p}. */
    public static final int WIRE_0 = 12;
    /** Redstone wire at power 15. */
    public static final int WIRE_15 = 27;
    /** Standing redstone torch, lit (placeable — a fresh torch burns). */
    public static final int TORCH_ON = 28;
    /** Standing redstone torch, unlit (network-computed: powered support extinguishes it). */
    public static final int TORCH_OFF = 29;
    /** Repeaters: facing = OUTPUT direction; OFF placeable, ON network-computed. Delay 1 tick. */
    public static final int REPEATER_NORTH_OFF = 30;
    public static final int REPEATER_NORTH_ON = 31;
    public static final int REPEATER_SOUTH_OFF = 32;
    public static final int REPEATER_SOUTH_ON = 33;
    public static final int REPEATER_WEST_OFF = 34;
    public static final int REPEATER_WEST_ON = 35;
    public static final int REPEATER_EAST_OFF = 36;
    public static final int REPEATER_EAST_ON = 37;
    /** Stone button: OFF placeable; interact presses it (ON, 15 omni) with a 10-tick auto-off. */
    public static final int BUTTON_OFF = 38;
    public static final int BUTTON_ON = 39;
    /** Pistons (non-sticky MVP): facing index f in 0..3 = N,S,W,E. RETRACTED bases placeable;
     * EXTENDED bases and HEADS are network-computed (motion is the engine's decision). */
    public static final int PISTON_RETRACTED_BASE = 40;
    public static final int PISTON_EXTENDED_BASE = 44;
    public static final int PISTON_HEAD_BASE = 48;
    /** Highest piston id ({@code PISTON_HEAD_BASE + 3}). */
    public static final int PISTON_MAX = 51;
    /** Water source (placeable); flows 1..7 are {@code WATER_FLOW_BASE + (level-1)}, minted. */
    public static final int WATER_SOURCE = 52;
    public static final int WATER_FLOW_BASE = 53;
    /** Lava source (placeable); flows 1..3 are {@code LAVA_FLOW_BASE + (level-1)}, minted. */
    public static final int LAVA_SOURCE = 60;
    public static final int LAVA_FLOW_BASE = 61;
    /** Highest fluid id ({@code LAVA_FLOW_BASE + 2}). */
    public static final int FLUID_MAX = 63;
    /** Gravel (gravity block like SAND — instant-settle, Task 14 L-3). */
    public static final int GRAVEL = 64;
    /** Fire (ages and spreads through random ticks; burns planks/logs). */
    public static final int FIRE = 65;

    /** Inclusive minimum buildable Y (mirrors the vanilla overworld floor for the MVP). */
    public static final int MIN_Y = -64;
    /** Inclusive maximum buildable Y (vanilla overworld ceiling; 24 sections × 16 ⇒ top block 319). */
    public static final int MAX_Y = 319;

    /** Ordered (id, name) table; the source of the registry fingerprint and the whitelist. */
    private static final PaletteEntry[] PALETTE = {
            new PaletteEntry(AIR, "air"),
            new PaletteEntry(STONE, "stone"),
            new PaletteEntry(DIRT, "dirt"),
            new PaletteEntry(GRASS_BLOCK, "grass_block"),
            new PaletteEntry(COBBLESTONE, "cobblestone"),
            new PaletteEntry(OAK_PLANKS, "oak_planks"),
            new PaletteEntry(OAK_LOG, "oak_log"),
            new PaletteEntry(GLASS, "glass"),
            new PaletteEntry(SAND, "sand"),
            new PaletteEntry(REDSTONE_BLOCK, "redstone_block"),
            new PaletteEntry(LEVER_OFF, "lever_off"),
            new PaletteEntry(LEVER_ON, "lever_on"),
            new PaletteEntry(TORCH_ON, "redstone_torch_on"),
            new PaletteEntry(TORCH_OFF, "redstone_torch_off"),
            new PaletteEntry(REPEATER_NORTH_OFF, "repeater_north_off"),
            new PaletteEntry(REPEATER_NORTH_ON, "repeater_north_on"),
            new PaletteEntry(REPEATER_SOUTH_OFF, "repeater_south_off"),
            new PaletteEntry(REPEATER_SOUTH_ON, "repeater_south_on"),
            new PaletteEntry(REPEATER_WEST_OFF, "repeater_west_off"),
            new PaletteEntry(REPEATER_WEST_ON, "repeater_west_on"),
            new PaletteEntry(REPEATER_EAST_OFF, "repeater_east_off"),
            new PaletteEntry(REPEATER_EAST_ON, "repeater_east_on"),
            new PaletteEntry(BUTTON_OFF, "stone_button_off"),
            new PaletteEntry(BUTTON_ON, "stone_button_on"),
            new PaletteEntry(PISTON_RETRACTED_BASE, "piston_north"),
            new PaletteEntry(PISTON_RETRACTED_BASE + 1, "piston_south"),
            new PaletteEntry(PISTON_RETRACTED_BASE + 2, "piston_west"),
            new PaletteEntry(PISTON_RETRACTED_BASE + 3, "piston_east"),
            new PaletteEntry(PISTON_EXTENDED_BASE, "piston_north_extended"),
            new PaletteEntry(PISTON_EXTENDED_BASE + 1, "piston_south_extended"),
            new PaletteEntry(PISTON_EXTENDED_BASE + 2, "piston_west_extended"),
            new PaletteEntry(PISTON_EXTENDED_BASE + 3, "piston_east_extended"),
            new PaletteEntry(PISTON_HEAD_BASE, "piston_head_north"),
            new PaletteEntry(PISTON_HEAD_BASE + 1, "piston_head_south"),
            new PaletteEntry(PISTON_HEAD_BASE + 2, "piston_head_west"),
            new PaletteEntry(PISTON_HEAD_BASE + 3, "piston_head_east"),
            new PaletteEntry(WATER_SOURCE, "water_source"),
            new PaletteEntry(WATER_FLOW_BASE, "water_flow_1"),
            new PaletteEntry(WATER_FLOW_BASE + 1, "water_flow_2"),
            new PaletteEntry(WATER_FLOW_BASE + 2, "water_flow_3"),
            new PaletteEntry(WATER_FLOW_BASE + 3, "water_flow_4"),
            new PaletteEntry(WATER_FLOW_BASE + 4, "water_flow_5"),
            new PaletteEntry(WATER_FLOW_BASE + 5, "water_flow_6"),
            new PaletteEntry(WATER_FLOW_BASE + 6, "water_flow_7"),
            new PaletteEntry(LAVA_SOURCE, "lava_source"),
            new PaletteEntry(LAVA_FLOW_BASE, "lava_flow_1"),
            new PaletteEntry(LAVA_FLOW_BASE + 1, "lava_flow_2"),
            new PaletteEntry(LAVA_FLOW_BASE + 2, "lava_flow_3"),
            new PaletteEntry(GRAVEL, "gravel"),
            new PaletteEntry(FIRE, "fire"),
            new PaletteEntry(WIRE_0 + 0, "redstone_wire_0"),
            new PaletteEntry(WIRE_0 + 1, "redstone_wire_1"),
            new PaletteEntry(WIRE_0 + 2, "redstone_wire_2"),
            new PaletteEntry(WIRE_0 + 3, "redstone_wire_3"),
            new PaletteEntry(WIRE_0 + 4, "redstone_wire_4"),
            new PaletteEntry(WIRE_0 + 5, "redstone_wire_5"),
            new PaletteEntry(WIRE_0 + 6, "redstone_wire_6"),
            new PaletteEntry(WIRE_0 + 7, "redstone_wire_7"),
            new PaletteEntry(WIRE_0 + 8, "redstone_wire_8"),
            new PaletteEntry(WIRE_0 + 9, "redstone_wire_9"),
            new PaletteEntry(WIRE_0 + 10, "redstone_wire_10"),
            new PaletteEntry(WIRE_0 + 11, "redstone_wire_11"),
            new PaletteEntry(WIRE_0 + 12, "redstone_wire_12"),
            new PaletteEntry(WIRE_0 + 13, "redstone_wire_13"),
            new PaletteEntry(WIRE_0 + 14, "redstone_wire_14"),
            new PaletteEntry(WIRE_0 + 15, "redstone_wire_15"),
    };

    /**
     * The ids a player may PLACE: network-computed states (powered wire, lever ON) are engine
     * outputs, never player inputs — placing them directly would let a client mint power.
     */
    private static final BitSet PLACEABLE = buildPlaceable();

    private static BitSet buildPlaceable() {
        BitSet s = buildWhitelist();
        s.clear(LEVER_ON);
        s.clear(TORCH_OFF);
        s.clear(REPEATER_NORTH_ON);
        s.clear(REPEATER_SOUTH_ON);
        s.clear(REPEATER_WEST_ON);
        s.clear(REPEATER_EAST_ON);
        s.clear(BUTTON_ON);
        for (int f = 0; f < 4; f++) {
            s.clear(PISTON_EXTENDED_BASE + f);
            s.clear(PISTON_HEAD_BASE + f);
        }
        for (int level = 0; level < 7; level++) {
            s.clear(WATER_FLOW_BASE + level);
        }
        for (int level = 0; level < 3; level++) {
            s.clear(LAVA_FLOW_BASE + level);
        }
        for (int p = 1; p <= 15; p++) {
            s.clear(WIRE_0 + p);
        }
        return s;
    }

    private static final BitSet WHITELIST = buildWhitelist();

    private static BitSet buildWhitelist() {
        BitSet s = new BitSet();
        for (PaletteEntry e : PALETTE) {
            s.set(e.id());
        }
        return s;
    }

    /**
     * @return the registry fingerprint: {@link StableHash} over a version tag followed by the
     *         ordered {@code (id, StableHash.of(name))} pairs. Stable across replicas and ports.
     * @Thread-context pure function; safe from any thread.
     */
    public static long registryFingerprint() {
        long[] parts = new long[2 + PALETTE.length * 2];
        int i = 0;
        parts[i++] = StableHash.of("nodera.simulation.FlatWorldRules.palette.v3");
        for (PaletteEntry e : PALETTE) {
            parts[i++] = e.id();
            parts[i++] = StableHash.of(e.name());
        }
        parts[i] = ItemEntityRules.semanticFingerprint();
        return StableHash.of(parts);
    }

    @Override
    public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
        return switch (env.action()) {
            case PlaceBlockAction p -> validatePlace(view, env, p);
            case BreakBlockAction b -> validateBreak(view, env, b);
            // The block-only MVP rules do not implement the entity lane (Task 12a ships its own
            // EntityRuleSet); item actions are rejected here rather than silently dropped.
            case DropItemAction d -> Optional.of(new ActionRejection(env, ActionRejection.Reason.UNSUPPORTED_ACTION));
            case PickupItemAction p -> Optional.of(new ActionRejection(env, ActionRejection.Reason.UNSUPPORTED_ACTION));
            case dev.nodera.core.action.InteractBlockAction i -> validateInteract(view, env, i);
        };
    }

    private static Optional<ActionRejection> validateInteract(
            RegionWorldView view, ActionEnvelope env, dev.nodera.core.action.InteractBlockAction i) {
        NBlockPos pos = i.pos();
        if (!view.inOwnedRegion(pos)) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        if (pos.y() < MIN_Y || pos.y() > MAX_Y) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        int id = view.getBlock(pos);
        if (RedstoneRules.toggled(id) == id) {
            // Nothing interactable there — deterministic rejection, never a silent no-op.
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ILLEGAL_BLOCK));
        }
        return Optional.empty();
    }

    private static Optional<ActionRejection> validatePlace(
            RegionWorldView view, ActionEnvelope env, PlaceBlockAction p) {
        if (!PLACEABLE.get(p.blockStateId())) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ILLEGAL_BLOCK));
        }
        NBlockPos pos = p.pos();
        if (!view.inOwnedRegion(pos)) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        if (pos.y() < MIN_Y || pos.y() > MAX_Y) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        return Optional.empty();
    }

    private static Optional<ActionRejection> validateBreak(
            RegionWorldView view, ActionEnvelope env, BreakBlockAction b) {
        NBlockPos pos = b.pos();
        if (!view.inOwnedRegion(pos)) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        if (pos.y() < MIN_Y || pos.y() > MAX_Y) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        return Optional.empty();
    }

    @Override
    public void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng) {
        switch (env.action()) {
            case PlaceBlockAction p -> {
                state.setBlock(p.pos(), p.blockStateId(), env, rng);
                if (RedstoneRules.isRedstoneFamily(p.blockStateId())
                        || touchesRedstone(state, p.pos())) {
                    RedstoneRules.recomputeNetwork(state, p.pos(), env, rng, env.targetTick());
                }
                if (FluidRules.isFluid(p.blockStateId()) || touchesFluid(state, p.pos())) {
                    FluidRules.onChanged(state, p.pos(), env.targetTick());
                }
                GravityRules.onPlaced(state, p.pos(), rng);
            }
            case BreakBlockAction b -> {
                boolean affected = RedstoneRules.isRedstoneFamily(state.getBlock(b.pos()))
                        || touchesRedstone(state, b.pos());
                boolean wetted = FluidRules.isFluid(state.getBlock(b.pos()))
                        || touchesFluid(state, b.pos());
                state.setBlock(b.pos(), AIR, env, rng);
                if (affected) {
                    RedstoneRules.recomputeNetwork(state, b.pos(), env, rng, env.targetTick());
                }
                if (wetted) {
                    FluidRules.onChanged(state, b.pos(), env.targetTick());
                }
                GravityRules.onVacated(state, b.pos(), rng);
            }
            case dev.nodera.core.action.InteractBlockAction i -> {
                RedstoneRules.interact(state, i.pos(), env, rng, env.targetTick());
            }
            // Drop/Pickup are validated as UNSUPPORTED_ACTION above, so apply never sees them;
            // the entity lane (Task 12a EntityRuleSet) owns their application. Exhaustive by kind.
            case DropItemAction d -> throw new IllegalStateException(
                    "FlatWorldRules.apply received a DropItemAction (should be rejected in validate)");
            case PickupItemAction p -> throw new IllegalStateException(
                    "FlatWorldRules.apply received a PickupItemAction (should be rejected in validate)");
        }
    }

    /** Whether any of {@code pos}'s six neighbors is a fluid cell. */
    private static boolean touchesFluid(MutableRegionState state, NBlockPos pos) {
        for (NBlockPos n : dev.nodera.simulation.NeighborUpdateOrder.neighborsOf(pos)) {
            if (FluidRules.isFluid(state.getBlock(n))) {
                return true;
            }
        }
        return false;
    }

    /** Whether any of {@code pos}'s six neighbors participates in the redstone graph. */
    private static boolean touchesRedstone(MutableRegionState state, NBlockPos pos) {
        for (NBlockPos n : dev.nodera.simulation.NeighborUpdateOrder.neighborsOf(pos)) {
            if (RedstoneRules.isRedstoneFamily(state.getBlock(n))) {
                return true;
            }
        }
        return false;
    }

    /** Ordered id→name row of the MVP palette. */
    private record PaletteEntry(int id, String name) {}
}
