package dev.nodera.endpoint.paper.compat;

import dev.nodera.coordinator.interference.InterferenceBuffer;
import dev.nodera.coordinator.interference.InterferenceStats;
import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.coordinator.interference.MutationSource;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.rules.VanillaPalette;
import org.bukkit.NamespacedKey;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PC-3 on the plugin path (server task 8 deliverables 2–4, L-65).
 *
 * <p>Two questions, and the second is the one the row exists for:
 *
 * <ol>
 *   <li>does a foreign write into a delegated region become a certified external delta rather than
 *       being suppressed;</li>
 *   <li>when a write <b>cannot</b> be kept, is it refused <b>visibly</b> — does every plugin on the
 *       server receive {@link NoderaRegionDeniedEvent} naming the region, the block, the reason and
 *       the state the world keeps? A silently vanishing WorldEdit operation is the single worst
 *       outcome available here, and deliverable 3 exists to prevent it.</li>
 * </ol>
 *
 * <p>No server is started. Bukkit's {@code Location} takes a nullable world and its
 * {@code HandlerList} is a plain object, so the event's payload — the thing another plugin's author
 * actually reads — is assertable with the platform interfaces stubbed by a {@link Proxy}. What is
 * NOT assertable here is that Paper delivers the event to a real subscriber; that is the corpus
 * stage's job.
 */
class ForeignWriteBridgeTest {

    private static final DimensionKey OVERWORLD = DimensionKey.overworld();
    private static final RegionId REGION = new RegionId(OVERWORLD, 0, 0);
    private static final NBlockPos POS = new NBlockPos(7, -60, 9);

    private static final VanillaPalette.VanillaBlock AIR =
            VanillaPalette.VanillaBlock.of("minecraft:air");
    private static final VanillaPalette.VanillaBlock STONE =
            VanillaPalette.VanillaBlock.of("minecraft:stone");
    private static final VanillaPalette.VanillaBlock DIRT =
            VanillaPalette.VanillaBlock.of("minecraft:dirt");

    private final List<ForeignWriteBridge.Denial> denied = new ArrayList<>();
    private final List<RegionId> flushed = new ArrayList<>();

    // -------------------------------------------------------------------------------------
    // 1 — a foreign write is CERTIFIED, not suppressed
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a plugin's write into a delegated region is recorded for certification")
    void aForeignWriteIntoADelegatedRegionIsCertified() {
        ForeignWriteBridge bridge = delegated(MutationGuard.Mode.CONVERT, headOf(AIR));

        assertThat(bridge.allows(REGION, POS, AIR, STONE))
                .as("the plugin keeps authority over the world; Nodera certifies what it did")
                .isTrue();
        assertThat(bridge.record(REGION, POS, AIR, STONE, MutationSource.UNKNOWN))
                .isEqualTo(ForeignWriteBridge.Outcome.CERTIFIED);
        assertThat(denied).isEmpty();

        assertThat(bridge.flushAll())
                .as("the recorded write folds into the version chain as one external delta")
                .hasSize(1);
        assertThat(flushed).containsExactly(REGION);
    }

    @Test
    @DisplayName("a region this endpoint does not validate is never gated and never certified")
    void anUndelegatedRegionPassesThrough() {
        ForeignWriteBridge bridge = new ForeignWriteBridge(
                guard(region -> false, MutationGuard.Mode.CONVERT), certification(),
                (region, pos) -> ForeignWriteBridge.CertifiedHead.UNKNOWN, denied::add, 4096);

        assertThat(bridge.allows(REGION, POS, AIR, STONE)).isTrue();
        assertThat(bridge.record(REGION, POS, AIR, STONE, MutationSource.UNKNOWN))
                .isEqualTo(ForeignWriteBridge.Outcome.PASS);
        assertThat(bridge.flushAll()).isEmpty();
    }

    @Test
    @DisplayName("a block outside the consensus palette is never certified — and never refused")
    void aBlockOutsideThePaletteIsNotConsensusState() {
        ForeignWriteBridge bridge = delegated(MutationGuard.Mode.CONVERT, headOf(AIR));
        VanillaPalette.VanillaBlock modded = VanillaPalette.VanillaBlock.of("someothermod:machine");

        assertThat(bridge.allows(REGION, POS, AIR, modded))
                .as("Nodera never modelled it, so it has no certified state to lose to")
                .isTrue();
        assertThat(bridge.record(REGION, POS, AIR, modded, MutationSource.UNKNOWN))
                .isEqualTo(ForeignWriteBridge.Outcome.UNSUPPORTED);
    }

    @Test
    @DisplayName("a bulk operation folds every flushThreshold writes, not once at the end")
    void aLargeWriteIsAStreamOfBoundedDeltas() {
        ForeignWriteBridge bridge = new ForeignWriteBridge(
                guard(region -> true, MutationGuard.Mode.CONVERT), certification(),
                headOf(AIR), denied::add, 4);

        for (int i = 0; i < 8; i++) {
            bridge.record(REGION, new NBlockPos(i, -60, 0), AIR, STONE, MutationSource.UNKNOWN);
        }

        assertThat(flushed)
                .as("PC-3 consequence 1: a million-block //set is a stream of bounded deltas")
                .containsExactly(REGION, REGION);
        assertThat(bridge.flushAll()).isEmpty();
    }

    @Test
    @DisplayName("every landed foreign write is observed, delegated or not — the bulk-path counter")
    void observedCountsEveryWriteTheBridgeIsHanded() {
        ForeignWriteBridge bridge = new ForeignWriteBridge(
                guard(region -> false, MutationGuard.Mode.CONVERT), certification(),
                (region, pos) -> ForeignWriteBridge.CertifiedHead.UNKNOWN, denied::add, 4096);

        for (int i = 0; i < 5; i++) {
            bridge.record(REGION, new NBlockPos(i, -60, 0), AIR, STONE, MutationSource.UNKNOWN);
        }

        // This is the number the live corpus stage reads, and the reason it is a real assertion: a
        // bridge listening only to Bukkit events answers ZERO here for a WorldEdit //set, because
        // //set fires no Bukkit block event at all.
        assertThat(bridge.summary()).contains("5 observed", "5 passed", "0 certified");
    }

    // -------------------------------------------------------------------------------------
    // 2 — a write that cannot be kept is refused VISIBLY
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a write that raced a committed delta is refused, and the committed state wins")
    void aRacedWriteIsRefusedAndSaysWhy() {
        // The world the plugin read said DIRT; the committee has already committed STONE there.
        ForeignWriteBridge bridge = delegated(MutationGuard.Mode.CONVERT, headOf(STONE));

        assertThat(bridge.allows(REGION, POS, DIRT, AIR)).isFalse();

        assertThat(denied).hasSize(1);
        ForeignWriteBridge.Denial denial = denied.getFirst();
        assertThat(denial.reason())
                .isEqualTo(ForeignWriteBridge.DenialReason.RACED_CERTIFIED_DELTA);
        assertThat(denial.region()).isEqualTo(REGION);
        assertThat(denial.pos()).isEqualTo(POS);
        assertThat(denial.certified().key())
                .as("the palette's own reverse mapping is namespace-free; the EVENT adds it back")
                .isEqualTo("stone");
    }

    @Test
    @DisplayName("STRICT refuses a foreign write without first recording the write it cancels")
    void strictModeRefusesAtTheGate() {
        ForeignWriteBridge bridge = delegated(MutationGuard.Mode.STRICT, headOf(AIR));

        assertThat(bridge.allows(REGION, POS, AIR, STONE)).isFalse();
        assertThat(denied).hasSize(1);
        assertThat(denied.getFirst().reason())
                .isEqualTo(ForeignWriteBridge.DenialReason.INTERFERENCE_STRICT_MODE);
        assertThat(bridge.flushAll())
                .as("a cancelled write must not also be certified")
                .isEmpty();
    }

    @Test
    @DisplayName("the refusal reaches every other plugin as NoderaRegionDeniedEvent — never silently")
    void aRefusalIsPublishedToTheWholeServer() {
        List<Event> published = new ArrayList<>();
        Plugin plugin = stubPlugin(published);

        BukkitForeignWrites.denials(plugin).deny(new ForeignWriteBridge.Denial(
                REGION, POS, ForeignWriteBridge.DenialReason.RACED_CERTIFIED_DELTA, STONE));

        assertThat(published).hasSize(1);
        NoderaRegionDeniedEvent event = (NoderaRegionDeniedEvent) published.getFirst();
        assertThat(event.getRegion()).isEqualTo(REGION.toString());
        assertThat(event.getReason())
                .isEqualTo(ForeignWriteBridge.DenialReason.RACED_CERTIFIED_DELTA);
        assertThat(event.getCertifiedState()).isEqualTo("minecraft:stone");
        assertThat(event.getLocation().getBlockX()).isEqualTo(POS.x());
        assertThat(event.getLocation().getBlockY()).isEqualTo(POS.y());
        assertThat(event.getLocation().getBlockZ()).isEqualTo(POS.z());
        assertThat(event.isAsynchronous())
                .as("a denial is fired on the thread that owned the write")
                .isFalse();
        assertThat(NoderaRegionDeniedEvent.getHandlerList())
                .as("Bukkit's registration scan reads this reflectively; without it no plugin can "
                        + "subscribe at all")
                .isSameAs(event.getHandlers());
    }

    // -------------------------------------------------------------------------------------
    // 3 — the block-state spelling both feeds share
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("Bukkit's and WorldEdit's block-state strings parse to the same palette state")
    void theEncodedStateIsTheSameOnBothFeeds() {
        // Bukkit's BlockData#getAsString and WorldEdit's BlockState#getAsString print the same
        // registry the same way, which is why one parser serves both adapters. If that ever stops
        // being true, the bulk path silently starts certifying the wrong block.
        assertThat(BukkitForeignWrites.stateOf("minecraft:stone"))
                .isEqualTo(VanillaPalette.VanillaBlock.of("minecraft:stone"));
        assertThat(BukkitForeignWrites.stateOf("minecraft:redstone_torch[lit=true]"))
                .isEqualTo(new VanillaPalette.VanillaBlock(
                        "minecraft:redstone_torch", Map.of("lit", "true")));
        assertThat(BukkitForeignWrites.stateOf("minecraft:repeater[delay=2,facing=north,"
                + "locked=false,powered=true]").properties())
                .containsEntry("facing", "north")
                .containsEntry("powered", "true");
    }

    // -------------------------------------------------------------------------------------

    private ForeignWriteBridge delegated(MutationGuard.Mode mode,
                                         ForeignWriteBridge.CertifiedHead head) {
        return new ForeignWriteBridge(guard(region -> true, mode), certification(), head,
                denied::add, 4096);
    }

    private static MutationGuard guard(java.util.function.Predicate<RegionId> delegated,
                                       MutationGuard.Mode mode) {
        return new MutationGuard(delegated, mode, new InterferenceBuffer(), new InterferenceStats());
    }

    /** The committee has {@code state} at every position it is asked about. */
    private static ForeignWriteBridge.CertifiedHead headOf(VanillaPalette.VanillaBlock state) {
        int id = VanillaPalette.idFor(state.key(), state.properties());
        return (region, pos) -> id;
    }

    /** A certification that always succeeds, so the test is about the bridge and not the chain. */
    private ForeignWriteBridge.Certification certification() {
        return region -> {
            flushed.add(region);
            SnapshotVersion base = SnapshotVersion.INITIAL;
            return Optional.of(new RegionDelta(region, base, base.next(), List.of(),
                    StateRoot.zero(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), 2));
        };
    }

    /**
     * Bukkit's {@code Plugin} → {@code Server} → {@code PluginManager} chain, stubbed.
     *
     * <p>A {@link Proxy} rather than a mocking framework because only four methods are ever called
     * and every one of them is on the path a denial actually takes. Anything else returning
     * {@code null} is the point: if this class ever starts calling more of Bukkit than that, this
     * test fails loudly rather than quietly covering it.
     */
    private static Plugin stubPlugin(List<Event> published) {
        Object world = Proxy.newProxyInstance(ForeignWriteBridgeTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.World.class}, stub(Map.of()));
        Object pluginManager = Proxy.newProxyInstance(ForeignWriteBridgeTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.plugin.PluginManager.class},
                stub(Map.of("callEvent", args -> {
                    published.add((Event) args[0]);
                    return null;
                })));
        Object server = Proxy.newProxyInstance(ForeignWriteBridgeTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.Server.class},
                stub(Map.of(
                        "getPluginManager", args -> pluginManager,
                        // The dimension key is what identifies the level — NOT World.Environment,
                        // which would collapse two overworlds into one region grid.
                        "getWorld", args -> args[0] instanceof NamespacedKey ? world : null)));
        return (Plugin) Proxy.newProxyInstance(ForeignWriteBridgeTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                stub(Map.of(
                        "getServer", args -> server,
                        "getLogger", args -> Logger.getLogger("ForeignWriteBridgeTest"))));
    }

    private static InvocationHandler stub(Map<String, java.util.function.Function<Object[], Object>>
                                                  answers) {
        return (proxy, method, args) -> {
            java.util.function.Function<Object[], Object> answer = answers.get(method.getName());
            if (answer != null) {
                return answer.apply(args);
            }
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "stub:" + method.getDeclaringClass().getSimpleName();
                default -> defaultOf(method);
            };
        };
    }

    private static Object defaultOf(Method method) {
        Class<?> returned = method.getReturnType();
        if (!returned.isPrimitive()) {
            return null;
        }
        return returned == boolean.class ? Boolean.FALSE
                : returned == void.class ? null : (Object) 0;
    }
}
