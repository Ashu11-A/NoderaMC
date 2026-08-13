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
import java.util.concurrent.ConcurrentHashMap;

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
 * @Thread-context call from the server thread (block state reads are not synchronised). The only
 *                 state it holds is a pure memo of {@link #idOf}, kept in a concurrent map so a
 *                 reader on another thread is still safe.
 */
public final class PaletteMapper {

    /**
     * One entry per distinct {@link BlockState} the game has ever handed us.
     *
     * <p>This is not a micro-optimisation, it is what makes region extraction survivable. A region
     * is 64 chunks; a chunk column is up to 24 sections of 4096 blocks, so one extraction asks this
     * question about six million blocks — and the uncached answer builds a {@link LinkedHashMap},
     * walks the state's property collection and formats every value, per block. On the 2026-08-10
     * live matrix that put {@code PaletteMapper.properties} on the vanilla watchdog's stack and
     * killed the dedicated server mid-run: {@code a single server tick took 60.00 seconds}.
     *
     * <p>Caching is sound because the answer is a pure function of the state: block states are
     * canonical, immutable singletons owned by the block's state definition, and
     * {@link VanillaPalette} is a static table. The map therefore has one entry per state in the
     * registry (tens of thousands at the very worst, and in practice the few hundred a world
     * actually contains), and no invalidation rule — there is no input that can change an answer.
     *
     * <p>{@link BlockState} inherits identity equality, so this is an identity map in effect, which
     * is exactly the comparison we want: two distinct instances of the same state cannot exist.
     */
    private static final Map<BlockState, Integer> ID_CACHE = new ConcurrentHashMap<>();

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
        return ID_CACHE.computeIfAbsent(state, PaletteMapper::computeId);
    }

    private static int computeId(BlockState state) {
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
