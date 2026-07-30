package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.VanillaPalette;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow soak driven from <b>vanilla block states</b> rather than from palette ids
 * (minecraft Task 2 deliverables 1–2 / the live half of engine Task 3).
 *
 * <p>{@link ShadowValidationIT} proves determinism over the id space the engine defines. It cannot
 * see the failure this test exists for: a *binding* that reads a real world into the wrong id.
 * Every action here starts life as a {@code (block key, properties)} pair — the exact shape the mod
 * hands over after reading a {@code BlockState} — goes through {@link VanillaPalette}, and is
 * dropped or captured by the same judgement the live bridge applies. What is asserted is that a
 * vanilla-shaped edit stream over the whole palette still produces zero divergence across three
 * independent workers, and that the exclusion list actually excludes: modded blocks and vertical
 * piston states in the same stream never reach the lane.
 */
class VanillaCaptureSoakIT {

    private static final int WORKERS = 3;
    private static final int BATCHES = 250;


    private record Vanilla(String key, Map<String, String> properties) {}

    /** The placeable pool: keys and property sets a survival player can actually produce. */
    private static final List<Vanilla> PLACEABLE = List.of(
            new Vanilla("minecraft:stone", Map.of()),
            new Vanilla("minecraft:dirt", Map.of()),
            new Vanilla("minecraft:cobblestone", Map.of()),
            new Vanilla("minecraft:oak_planks", Map.of()),
            new Vanilla("minecraft:oak_log", Map.of("axis", "y")),
            new Vanilla("minecraft:glass", Map.of()),
            new Vanilla("minecraft:sand", Map.of()),
            new Vanilla("minecraft:gravel", Map.of()),
            new Vanilla("minecraft:redstone_block", Map.of()),
            new Vanilla("minecraft:lever", Map.of("powered", "false", "face", "floor")),
            new Vanilla("minecraft:redstone_torch", Map.of("lit", "true")),
            new Vanilla("minecraft:redstone_wire", Map.of("power", "0")),
            new Vanilla("minecraft:repeater",
                    Map.of("facing", "west", "powered", "false", "delay", "1")),
            new Vanilla("minecraft:comparator",
                    Map.of("facing", "east", "mode", "compare", "powered", "false")),
            new Vanilla("minecraft:observer", Map.of("facing", "north", "powered", "false")),
            new Vanilla("minecraft:piston", Map.of("facing", "south", "extended", "false")),
            new Vanilla("minecraft:sticky_piston", Map.of("facing", "east", "extended", "false")),
            new Vanilla("minecraft:stone_button", Map.of("powered", "false", "face", "wall")),
            new Vanilla("minecraft:stone_pressure_plate", Map.of("powered", "false")),
            new Vanilla("minecraft:chest", Map.of("facing", "north", "type", "single")),
            new Vanilla("minecraft:hopper", Map.of("facing", "down", "enabled", "true")),
            new Vanilla("minecraft:note_block", Map.of("note", "0", "powered", "false")),
            new Vanilla("minecraft:rail", Map.of("shape", "north_south")),
            new Vanilla("minecraft:daylight_detector", Map.of("power", "0")),
            new Vanilla("minecraft:water", Map.of("level", "0")));

    /** The same stream carries these; not one may ever reach the lane. */
    private static final List<Vanilla> EXCLUDED = List.of(
            new Vanilla("minecraft:diamond_block", Map.of()),
            new Vanilla("minecraft:oak_stairs", Map.of("facing", "north", "half", "bottom")),
            new Vanilla("create:cogwheel", Map.of("axis", "x")),
            new Vanilla("minecraft:piston", Map.of("facing", "up", "extended", "false")),
            new Vanilla("minecraft:observer", Map.of("facing", "down", "powered", "false")),
            // A network-computed state: expressible, but never a player input.
            new Vanilla("minecraft:redstone_wire", Map.of("power", "12")));

    private final List<WorkerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        runtimes.forEach(WorkerRuntime::close);
    }

    private ShadowWorker newWorker(long id, SessionParams params, RegionEngine engine) {
        WorkerRuntime rt = new WorkerRuntime(engine);
        rt.activate();
        runtimes.add(rt);
        return new ShadowWorker(Fixtures.node(id), rt, new ReplicaStore(16), params);
    }

    @Test
    @DisplayName("a vanilla-shaped edit stream over the whole palette diverges nowhere")
    void threeWorkersAgreeOnAStreamCapturedFromVanillaStates() {
        SessionParams params = Fixtures.params();
        RegionId region = Fixtures.region(0, 0);

        ShadowCoordinator coord = new ShadowCoordinator(params, Fixtures.engine(), 16);
        coord.seedRegion(Fixtures.fullUniformSnapshot(region, FlatWorldRules.AIR));

        List<ShadowWorker> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            ShadowWorker w = newWorker(300 + i, params, Fixtures.engine());
            coord.registerWorker(w);
            coord.assign(w.nodeId(), region);
            workers.add(w);
        }

        Random rng = new Random(0x5EEDBEEF);
        Set<Integer> capturedIds = new HashSet<>();
        int refusedByTheBinding = 0;
        SnapshotVersion base = SnapshotVersion.INITIAL;
        for (long tick = 0; tick < BATCHES; tick++) {
            List<ActionEnvelope> actions = new ArrayList<>();
            int edits = 1 + rng.nextInt(4);
            for (int i = 0; i < edits; i++) {
                int x = rng.nextInt(128);
                int z = rng.nextInt(128);
                int y = 1 + rng.nextInt(200);
                long seq = tick * 16 + i;
                if (rng.nextInt(4) == 0) {
                    // A break needs no binding on the way in: the position is the whole action.
                    actions.add(Fixtures.brk(region, seq, tick, x, y, z));
                    continue;
                }
                boolean excluded = rng.nextInt(5) == 0;
                Vanilla state = excluded
                        ? EXCLUDED.get(rng.nextInt(EXCLUDED.size()))
                        : PLACEABLE.get(rng.nextInt(PLACEABLE.size()));
                int id = VanillaPalette.idFor(state.key(), state.properties());
                // The live bridge's judgement, mirrored: unsupported or non-placeable never
                // becomes an action. The engine would reject them anyway — the point is that
                // they are refused BEFORE they cost a signature and a committee round.
                if (id == VanillaPalette.UNSUPPORTED || id == FlatWorldRules.AIR
                        || !FlatWorldRules.isPlaceable(id)) {
                    refusedByTheBinding++;
                    continue;
                }
                capturedIds.add(id);
                actions.add(Fixtures.place(region, seq, tick, x, y, z, id));
            }
            if (actions.isEmpty()) {
                actions.add(Fixtures.brk(region, tick * 16 + 15, tick, 0, 1, 0));
            }
            coord.submitBatch(Fixtures.batch(region, base, tick, tick, actions));
            base = base.next();
        }

        ShadowMetrics.Stats stats = coord.metrics().stats();
        assertThat(stats.mismatches()).isZero();
        assertThat(coord.tracker().divergences()).isEmpty();
        assertThat(stats.batches()).isEqualTo(BATCHES);
        assertThat(stats.matches()).isEqualTo((long) BATCHES * WORKERS);
        assertThat(stats.clean()).isTrue();

        SnapshotVersion finalVersion = new SnapshotVersion(BATCHES);
        for (ShadowWorker w : workers) {
            assertThat(w.replicas().version(region)).isEqualTo(finalVersion);
            assertThat(Fixtures.rootOf(w.replicas().get(region)))
                    .isEqualTo(Fixtures.rootOf(coord.referenceSnapshot(region)));
        }

        // The soak's breadth is part of what it proves: a run that only ever placed stone would
        // pass every assertion above and tell us nothing about the binding.
        assertThat(capturedIds).hasSizeGreaterThan(15);
        assertThat(refusedByTheBinding).isPositive();
    }

    @Test
    @DisplayName("every excluded state is refused by the binding, not by luck")
    void theExclusionListIsExhaustiveOverItsOwnPool() {
        for (Vanilla state : EXCLUDED) {
            int id = VanillaPalette.idFor(state.key(), state.properties());
            boolean refused = id == VanillaPalette.UNSUPPORTED
                    || id == FlatWorldRules.AIR
                    || !FlatWorldRules.isPlaceable(id);
            assertThat(refused)
                    .as("%s%s must never become a captured placement", state.key(),
                            state.properties())
                    .isTrue();
        }
        for (Vanilla state : PLACEABLE) {
            int id = VanillaPalette.idFor(state.key(), state.properties());
            assertThat(id)
                    .as("%s%s is something a player can build and must map", state.key(),
                            state.properties())
                    .isNotEqualTo(VanillaPalette.UNSUPPORTED);
            assertThat(FlatWorldRules.isPlaceable(id))
                    .as("%s maps to id %d, which the rule set refuses to place", state.key(), id)
                    .isTrue();
        }
    }
}
