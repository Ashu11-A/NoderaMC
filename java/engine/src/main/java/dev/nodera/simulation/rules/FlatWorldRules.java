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
    };

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
        };
    }

    private static Optional<ActionRejection> validatePlace(
            RegionWorldView view, ActionEnvelope env, PlaceBlockAction p) {
        if (!WHITELIST.get(p.blockStateId())) {
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
            case PlaceBlockAction p -> state.setBlock(p.pos(), p.blockStateId(), env, rng);
            case BreakBlockAction b -> state.setBlock(b.pos(), AIR, env, rng);
            // Drop/Pickup are validated as UNSUPPORTED_ACTION above, so apply never sees them;
            // the entity lane (Task 12a EntityRuleSet) owns their application. Exhaustive by kind.
            case DropItemAction d -> throw new IllegalStateException(
                    "FlatWorldRules.apply received a DropItemAction (should be rejected in validate)");
            case PickupItemAction p -> throw new IllegalStateException(
                    "FlatWorldRules.apply received a PickupItemAction (should be rejected in validate)");
        }
    }

    /** Ordered id→name row of the MVP palette. */
    private record PaletteEntry(int id, String name) {}
}
