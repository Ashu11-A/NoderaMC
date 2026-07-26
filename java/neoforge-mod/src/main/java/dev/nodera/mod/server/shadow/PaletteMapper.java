package dev.nodera.mod.server.shadow;

import dev.nodera.simulation.rules.VanillaPalette;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Minecraft-typed half of the palette binding (minecraft Task 2 deliverable 2): a live
 * {@link BlockState} in, a consensus palette id out — and back again for the applier.
 *
 * <p>This class deliberately holds no table of its own. Every judgement about which vanilla state
 * means which consensus id lives in {@link VanillaPalette}, where it is unit-tested without a game;
 * here we only read the registry key and the property values and hand them over as strings. The
 * split is what makes "did the palette grow past the capture lane?" a question the ordinary test
 * gate can answer.
 *
 * @Thread-context stateless; call from the server thread (block state reads are not synchronised).
 */
public final class PaletteMapper {

    private PaletteMapper() {
    }

    /**
     * @return the consensus palette id for {@code state}, or {@link VanillaPalette#UNSUPPORTED}
     *         when the block or one of its properties is outside the palette.
     */
    public static int idOf(BlockState state) {
        if (state == null) {
            return VanillaPalette.UNSUPPORTED;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return VanillaPalette.idFor(key.toString(), properties(state));
    }

    /**
     * The reverse direction, for the applier: the vanilla state a committed palette id projects to.
     *
     * <p>Empty when the id has no binding at all. A bound id whose vanilla block or property value
     * the running game does not know (a resource pack or version skew) also answers empty rather
     * than guessing — the applier then leaves the block alone and the region reports interference,
     * which is a visible failure instead of a silent one.
     */
    public static Optional<BlockState> stateOf(int paletteId) {
        VanillaPalette.VanillaBlock vanilla = VanillaPalette.vanillaOf(paletteId);
        if (vanilla == null) {
            return Optional.empty();
        }
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath(
                VanillaPalette.NAMESPACE, vanilla.key());
        Block block = BuiltInRegistries.BLOCK.get(key);
        if (block == null) {
            return Optional.empty();
        }
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> wanted : vanilla.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(wanted.getKey());
            if (property == null) {
                return Optional.empty();
            }
            Optional<BlockState> applied = withProperty(state, property, wanted.getValue());
            if (applied.isEmpty()) {
                return Optional.empty();
            }
            state = applied.get();
        }
        return Optional.of(state);
    }

    /** The state's properties as vanilla spells them, which is what the binding table speaks. */
    private static Map<String, String> properties(BlockState state) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            out.put(property.getName(), value(state, property));
        }
        return out;
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> Optional<BlockState> withProperty(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v));
    }
}
