package dev.nodera.simulation.rules;

import dev.nodera.core.crypto.StableHash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The binding between the vanilla block registry and the consensus palette of
 * {@link FlatWorldRules} — the half of {@code PaletteMapper} that carries no Minecraft types
 * (minecraft Task 2 deliverable 2, the live capture lane of engine Task 3).
 *
 * <p><b>Why the table lives here.</b> The mapping is the place where a real game state becomes a
 * consensus state id, so it is exactly as load-bearing as the palette itself: a wrong row does not
 * crash, it diverges. Keeping it in the engine — as {@code (block key, property map)} strings —
 * means the whole table is unit-testable on the ordinary gate, and the mod side shrinks to
 * "read the registry key, read the properties, ask this class". The reverse direction exists for
 * the same reason: a committed delta has to become a real {@code BlockState} again, and a
 * round-trip test is the only cheap way to prove the two directions agree.
 *
 * <p><b>Unsupported is a first-class answer.</b> {@link #UNSUPPORTED} is returned for every block
 * outside the palette — modded blocks (any namespace other than {@code minecraft}), vanilla blocks
 * the rule set does not model, and vanilla states the palette cannot express (a piston facing up,
 * {@code moving_piston}). An unsupported block is not a bug: it is the exclusion list the
 * validated lane is built around. Capture drops it, and the region keeps it as a foreign write.
 *
 * <p><b>Lossy on purpose.</b> Several vanilla properties carry no consensus meaning — log axis,
 * lever face, comparator mode, rail shape — and are dropped on the way in. The reverse direction
 * therefore emits a canonical default for them, which is why the round-trip property is stated
 * over palette ids ({@code idFor(vanillaOf(id)) == id}) and never over vanilla states.
 *
 * @Thread-context immutable static tables; safe from any thread.
 */
public final class VanillaPalette {

    /** Returned for any block or block state the consensus palette cannot express. */
    public static final int UNSUPPORTED = -1;

    /** The only namespace whose blocks can ever be validated. */
    public static final String NAMESPACE = "minecraft";

    /** Facing order used by every directional palette family: {@code 0=N, 1=S, 2=W, 3=E}. */
    private static final String[] FACINGS = {"north", "south", "west", "east"};

    /** One vanilla block state: its registry path plus the properties that carry meaning. */
    public record VanillaBlock(String key, Map<String, String> properties) {

        public VanillaBlock {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("key must not be blank");
            }
            properties = Map.copyOf(properties == null ? Map.of() : properties);
        }

        /** Convenience for the property-free majority of the palette. */
        public static VanillaBlock of(String key) {
            return new VanillaBlock(key, Map.of());
        }

        /** Convenience for a single-property state. */
        public static VanillaBlock of(String key, String property, String value) {
            return new VanillaBlock(key, Map.of(property, value));
        }
    }

    /** id → the canonical vanilla state it projects to. Ordered; the fingerprint reads it. */
    private static final Map<Integer, VanillaBlock> REVERSE = buildReverse();

    private VanillaPalette() {
    }

    /**
     * Map a live vanilla block state to its consensus palette id.
     *
     * @param blockKey the registry key, with or without the {@code minecraft:} namespace.
     * @param properties the state's properties as {@code name → value} (vanilla's own lowercase
     *                   spellings: {@code powered=true}, {@code facing=north}, {@code level=3}).
     *                   Extra properties are ignored; missing ones fall back to vanilla defaults.
     * @return the palette id, or {@link #UNSUPPORTED}.
     */
    public static int idFor(String blockKey, Map<String, String> properties) {
        if (blockKey == null || blockKey.isBlank()) {
            return UNSUPPORTED;
        }
        String key = blockKey;
        int colon = key.indexOf(':');
        if (colon >= 0) {
            if (!NAMESPACE.equals(key.substring(0, colon))) {
                return UNSUPPORTED; // a modded block is never consensus state
            }
            key = key.substring(colon + 1);
        }
        Map<String, String> props = properties == null ? Map.of() : properties;
        return switch (key) {
            case "air", "cave_air", "void_air" -> FlatWorldRules.AIR;
            case "stone" -> FlatWorldRules.STONE;
            case "dirt" -> FlatWorldRules.DIRT;
            case "grass_block" -> FlatWorldRules.GRASS_BLOCK;
            case "cobblestone" -> FlatWorldRules.COBBLESTONE;
            case "oak_planks" -> FlatWorldRules.OAK_PLANKS;
            case "oak_log" -> FlatWorldRules.OAK_LOG;
            case "glass" -> FlatWorldRules.GLASS;
            case "sand" -> FlatWorldRules.SAND;
            case "gravel" -> FlatWorldRules.GRAVEL;
            case "fire" -> FlatWorldRules.FIRE;
            case "rail" -> FlatWorldRules.RAIL;
            case "powered_rail" -> FlatWorldRules.POWERED_RAIL;
            case "daylight_detector" -> FlatWorldRules.DAYLIGHT_SENSOR;
            case "chest" -> FlatWorldRules.CHEST;
            case "hopper" -> FlatWorldRules.HOPPER;
            case "note_block" -> FlatWorldRules.NOTE_BLOCK;
            case "redstone_block" -> FlatWorldRules.REDSTONE_BLOCK;
            case "nether_portal" -> FlatWorldRules.NETHER_PORTAL;
            case "obsidian" -> FlatWorldRules.OBSIDIAN;
            case "farmland" -> FlatWorldRules.FARMLAND;
            case "wheat" -> wheat(props);
            case "lever" -> flag(props, "powered")
                    ? FlatWorldRules.LEVER_ON : FlatWorldRules.LEVER_OFF;
            case "redstone_torch", "redstone_wall_torch" -> flag(props, "lit", true)
                    ? FlatWorldRules.TORCH_ON : FlatWorldRules.TORCH_OFF;
            case "stone_button" -> flag(props, "powered")
                    ? FlatWorldRules.BUTTON_ON : FlatWorldRules.BUTTON_OFF;
            case "stone_pressure_plate" -> flag(props, "powered")
                    ? FlatWorldRules.PRESSURE_PLATE_ON : FlatWorldRules.PRESSURE_PLATE_OFF;
            case "repeater" -> directional(props,
                    FlatWorldRules.REPEATER_NORTH_OFF + (flag(props, "powered") ? 1 : 0), 2);
            case "comparator" -> directional(props, FlatWorldRules.COMPARATOR_NORTH, 1);
            case "observer" -> directional(props,
                    FlatWorldRules.OBSERVER_NORTH_OFF + (flag(props, "powered") ? 1 : 0), 2);
            case "piston" -> directional(props, flag(props, "extended")
                    ? FlatWorldRules.PISTON_EXTENDED_BASE : FlatWorldRules.PISTON_RETRACTED_BASE, 1);
            case "sticky_piston" -> directional(props, flag(props, "extended")
                            ? FlatWorldRules.STICKY_PISTON_EXTENDED_BASE
                            : FlatWorldRules.STICKY_PISTON_RETRACTED_BASE, 1);
            case "piston_head" -> directional(props, "sticky".equals(props.get("type"))
                    ? FlatWorldRules.STICKY_PISTON_HEAD_BASE : FlatWorldRules.PISTON_HEAD_BASE, 1);
            case "redstone_wire" -> wire(props);
            case "water" -> fluid(props, true);
            case "lava" -> fluid(props, false);
            // `moving_piston` is a transient vanilla block entity, not a state the palette can
            // express — the engine models the extend/retract transition itself.
            default -> UNSUPPORTED;
        };
    }

    /**
     * The reverse direction: the canonical vanilla state a committed palette id projects to.
     *
     * @return the vanilla block, or {@code null} when the id is not a palette entry.
     */
    public static VanillaBlock vanillaOf(int paletteId) {
        return REVERSE.get(paletteId);
    }

    /**
     * @return a {@link StableHash} over the ordered {@code (id, key, properties)} rows. Two builds
     *         whose bindings differ produce different fingerprints, so a capture lane can refuse a
     *         peer that would read the same world into different consensus ids.
     */
    public static long bindingFingerprint() {
        long[] parts = new long[1 + REVERSE.size() * 2];
        int i = 0;
        parts[i++] = StableHash.of("nodera.simulation.VanillaPalette.binding.v1");
        for (Map.Entry<Integer, VanillaBlock> row : REVERSE.entrySet()) {
            parts[i++] = row.getKey();
            parts[i++] = StableHash.of(describe(row.getValue()));
        }
        return StableHash.of(parts);
    }

    private static String describe(VanillaBlock block) {
        StringBuilder out = new StringBuilder(block.key());
        block.properties().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append('[').append(e.getKey()).append('=').append(e.getValue())
                        .append(']'));
        return out.toString();
    }

    private static boolean flag(Map<String, String> properties, String name) {
        return flag(properties, name, false);
    }

    private static boolean flag(Map<String, String> properties, String name, boolean fallback) {
        String value = properties.get(name);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /**
     * Resolve a horizontal family: {@code base + facingIndex * stride}. The palette has no vertical
     * variants, so a piston or observer pointing up or down is genuinely inexpressible.
     */
    private static int directional(Map<String, String> properties, int base, int stride) {
        String facing = properties.getOrDefault("facing", "north");
        for (int index = 0; index < FACINGS.length; index++) {
            if (FACINGS[index].equals(facing)) {
                return base + index * stride;
            }
        }
        return UNSUPPORTED;
    }

    /** Wheat carries its growth stage in {@code age} (0..7); anything else is not consensus state. */
    private static int wheat(Map<String, String> properties) {
        int age = number(properties, "age", 0);
        if (age < 0 || age > 7) {
            return UNSUPPORTED;
        }
        return FlatWorldRules.WHEAT_0 + age;
    }

    private static int wire(Map<String, String> properties) {
        int power = number(properties, "power", 0);
        if (power < 0 || power > 15) {
            return UNSUPPORTED;
        }
        return FlatWorldRules.WIRE_0 + power;
    }

    /**
     * Vanilla encodes a fluid's strength in {@code level}: {@code 0} is a source, {@code 1..7} are
     * weakening flows, and {@code >= 8} is a falling column. The engine models a falling flow as a
     * fresh level-1 flow (see {@link FluidRules}), so the whole {@code >= 8} range folds onto 1.
     * Lava outside the nether steps by two ({@code 2, 4, 6}) over three engine levels.
     */
    private static int fluid(Map<String, String> properties, boolean water) {
        int level = number(properties, "level", 0);
        if (level < 0) {
            return UNSUPPORTED;
        }
        if (level == 0) {
            return water ? FlatWorldRules.WATER_SOURCE : FlatWorldRules.LAVA_SOURCE;
        }
        if (level >= 8) {
            return water ? FlatWorldRules.WATER_FLOW_BASE : FlatWorldRules.LAVA_FLOW_BASE;
        }
        if (water) {
            return FlatWorldRules.WATER_FLOW_BASE + level - 1;
        }
        int lavaLevel = Math.min(FluidRules.LAVA_MAX_FLOW, (level + 1) / 2);
        return FlatWorldRules.LAVA_FLOW_BASE + Math.max(1, lavaLevel) - 1;
    }

    private static int number(Map<String, String> properties, String name, int fallback) {
        String value = properties.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static Map<Integer, VanillaBlock> buildReverse() {
        Map<Integer, VanillaBlock> map = new LinkedHashMap<>();
        map.put(FlatWorldRules.AIR, VanillaBlock.of("air"));
        map.put(FlatWorldRules.STONE, VanillaBlock.of("stone"));
        map.put(FlatWorldRules.DIRT, VanillaBlock.of("dirt"));
        map.put(FlatWorldRules.GRASS_BLOCK, VanillaBlock.of("grass_block"));
        map.put(FlatWorldRules.COBBLESTONE, VanillaBlock.of("cobblestone"));
        map.put(FlatWorldRules.OAK_PLANKS, VanillaBlock.of("oak_planks"));
        map.put(FlatWorldRules.OAK_LOG, VanillaBlock.of("oak_log"));
        map.put(FlatWorldRules.GLASS, VanillaBlock.of("glass"));
        map.put(FlatWorldRules.SAND, VanillaBlock.of("sand"));
        map.put(FlatWorldRules.REDSTONE_BLOCK, VanillaBlock.of("redstone_block"));
        map.put(FlatWorldRules.LEVER_OFF, VanillaBlock.of("lever", "powered", "false"));
        map.put(FlatWorldRules.LEVER_ON, VanillaBlock.of("lever", "powered", "true"));
        for (int power = 0; power <= 15; power++) {
            map.put(FlatWorldRules.WIRE_0 + power,
                    VanillaBlock.of("redstone_wire", "power", Integer.toString(power)));
        }
        map.put(FlatWorldRules.TORCH_ON, VanillaBlock.of("redstone_torch", "lit", "true"));
        map.put(FlatWorldRules.TORCH_OFF, VanillaBlock.of("redstone_torch", "lit", "false"));
        for (int facing = 0; facing < FACINGS.length; facing++) {
            map.put(FlatWorldRules.REPEATER_NORTH_OFF + facing * 2,
                    repeater(FACINGS[facing], false));
            map.put(FlatWorldRules.REPEATER_NORTH_ON + facing * 2,
                    repeater(FACINGS[facing], true));
            map.put(FlatWorldRules.OBSERVER_NORTH_OFF + facing * 2,
                    observer(FACINGS[facing], false));
            map.put(FlatWorldRules.OBSERVER_NORTH_ON + facing * 2,
                    observer(FACINGS[facing], true));
            map.put(FlatWorldRules.COMPARATOR_NORTH + facing,
                    VanillaBlock.of("comparator", "facing", FACINGS[facing]));
            map.put(FlatWorldRules.PISTON_RETRACTED_BASE + facing,
                    piston("piston", FACINGS[facing], false));
            map.put(FlatWorldRules.PISTON_EXTENDED_BASE + facing,
                    piston("piston", FACINGS[facing], true));
            map.put(FlatWorldRules.STICKY_PISTON_RETRACTED_BASE + facing,
                    piston("sticky_piston", FACINGS[facing], false));
            map.put(FlatWorldRules.STICKY_PISTON_EXTENDED_BASE + facing,
                    piston("sticky_piston", FACINGS[facing], true));
            map.put(FlatWorldRules.PISTON_HEAD_BASE + facing,
                    head(FACINGS[facing], "normal"));
            map.put(FlatWorldRules.STICKY_PISTON_HEAD_BASE + facing,
                    head(FACINGS[facing], "sticky"));
        }
        map.put(FlatWorldRules.BUTTON_OFF, VanillaBlock.of("stone_button", "powered", "false"));
        map.put(FlatWorldRules.BUTTON_ON, VanillaBlock.of("stone_button", "powered", "true"));
        map.put(FlatWorldRules.PRESSURE_PLATE_OFF,
                VanillaBlock.of("stone_pressure_plate", "powered", "false"));
        map.put(FlatWorldRules.PRESSURE_PLATE_ON,
                VanillaBlock.of("stone_pressure_plate", "powered", "true"));
        map.put(FlatWorldRules.WATER_SOURCE, VanillaBlock.of("water", "level", "0"));
        for (int level = 1; level <= FluidRules.WATER_MAX_FLOW; level++) {
            map.put(FlatWorldRules.WATER_FLOW_BASE + level - 1,
                    VanillaBlock.of("water", "level", Integer.toString(level)));
        }
        map.put(FlatWorldRules.LAVA_SOURCE, VanillaBlock.of("lava", "level", "0"));
        for (int level = 1; level <= FluidRules.LAVA_MAX_FLOW; level++) {
            // The inverse of the two-step lava ladder: engine level 1,2,3 → vanilla 2,4,6.
            map.put(FlatWorldRules.LAVA_FLOW_BASE + level - 1,
                    VanillaBlock.of("lava", "level", Integer.toString(level * 2)));
        }
        map.put(FlatWorldRules.GRAVEL, VanillaBlock.of("gravel"));
        map.put(FlatWorldRules.FIRE, VanillaBlock.of("fire"));
        map.put(FlatWorldRules.RAIL, VanillaBlock.of("rail"));
        map.put(FlatWorldRules.POWERED_RAIL, VanillaBlock.of("powered_rail"));
        map.put(FlatWorldRules.DAYLIGHT_SENSOR, VanillaBlock.of("daylight_detector"));
        map.put(FlatWorldRules.CHEST, VanillaBlock.of("chest"));
        map.put(FlatWorldRules.HOPPER, VanillaBlock.of("hopper"));
        map.put(FlatWorldRules.NOTE_BLOCK, VanillaBlock.of("note_block"));
        map.put(FlatWorldRules.NETHER_PORTAL, VanillaBlock.of("nether_portal"));
        map.put(FlatWorldRules.OBSIDIAN, VanillaBlock.of("obsidian"));
        map.put(FlatWorldRules.FARMLAND, VanillaBlock.of("farmland"));
        for (int age = 0; age <= 7; age++) {
            map.put(FlatWorldRules.WHEAT_0 + age,
                    VanillaBlock.of("wheat", "age", Integer.toString(age)));
        }
        return Map.copyOf(map);
    }

    private static VanillaBlock repeater(String facing, boolean powered) {
        return new VanillaBlock("repeater",
                Map.of("facing", facing, "powered", Boolean.toString(powered)));
    }

    private static VanillaBlock observer(String facing, boolean powered) {
        return new VanillaBlock("observer",
                Map.of("facing", facing, "powered", Boolean.toString(powered)));
    }

    private static VanillaBlock piston(String key, String facing, boolean extended) {
        return new VanillaBlock(key,
                Map.of("facing", facing, "extended", Boolean.toString(extended)));
    }

    private static VanillaBlock head(String facing, String type) {
        return new VanillaBlock("piston_head", Map.of("facing", facing, "type", type));
    }
}
