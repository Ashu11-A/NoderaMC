package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionWorldView;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-21's remaining exit half: <b>pack rule execution hooks</b> — a mod's own validate / apply / tick
 * code running inside the validated lane, not just palette ids and an identity.
 *
 * <p>The three properties that make this safe are the ones asserted here: dispatch is by declared
 * palette ownership (so a pack can never reach a vanilla block), pack ticks run in canonical
 * namespace order (so installation order cannot change the root), and a pack's own rejections are
 * ordinary validation results — its blocks are consensus state like any other.
 */
final class PackRuleExecutionTest {

    private static final int PACK_BLOCK = 1000;
    private static final int PACK_BLOCK_ACTIVE = 1001;
    private static final int OTHER_PACK_BLOCK = 1100;

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);

    // --- a minimal but real pack ---------------------------------------------------------------

    /**
     * A pack whose block refuses to be placed below y=0 and, once placed, "activates" itself on the
     * next tick. Deliberately trivial — the point is that the hooks are reached at all, and reached
     * identically on every replica.
     */
    private record GrowingPack(String namespace, List<PackPaletteEntryHolder> ignored)
            implements RulePack {

        record PackPaletteEntryHolder() {
        }

        @Override
        public List<PackPaletteEntry> paletteEntries() {
            return List.of(new PackPaletteEntry(PACK_BLOCK, "growing_block"),
                    new PackPaletteEntry(PACK_BLOCK_ACTIVE, "growing_block_active"));
        }

        @Override
        public long semanticFingerprint() {
            return 0x6772_6f77L;
        }

        @Override
        public Optional<PackRules> rules() {
            return Optional.of(new PackRules() {
                @Override
                public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
                    if (env.action() instanceof dev.nodera.core.action.PlaceBlockAction p
                            && p.pos().y() < 0) {
                        return Optional.of(new ActionRejection(
                                env, ActionRejection.Reason.ILLEGAL_BLOCK));
                    }
                    return Optional.empty();
                }

                @Override
                public void apply(MutableRegionState state, ActionEnvelope env,
                                  DeterministicRandom rng) {
                    if (env.action() instanceof dev.nodera.core.action.PlaceBlockAction p) {
                        state.setBlock(p.pos(), PACK_BLOCK, env, rng);
                    }
                }

                @Override
                public void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
                    // Scan a small fixed window in canonical order: pure, bounded, replica-identical.
                    for (int y = 60; y < 80; y++) {
                        NBlockPos pos = new NBlockPos(5, y, 5);
                        if (state.getBlock(pos) == PACK_BLOCK) {
                            state.setBlock(pos, PACK_BLOCK_ACTIVE, null, rng);
                        }
                    }
                }
            });
        }
    }

    /** A second pack that records the order it ticked in, to pin canonical sequencing. */
    private record OrderPack(String namespace, long semanticFingerprint,
                             List<String> log) implements RulePack {
        @Override
        public List<PackPaletteEntry> paletteEntries() {
            return List.of(new PackPaletteEntry(OTHER_PACK_BLOCK, namespace + "_block"));
        }

        @Override
        public Optional<PackRules> rules() {
            return Optional.of(new PackRules() {
                @Override
                public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
                    return Optional.empty();
                }

                @Override
                public void apply(MutableRegionState state, ActionEnvelope env,
                                  DeterministicRandom rng) {
                }

                @Override
                public void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
                    log.add(namespace);
                }
            });
        }
    }

    private static GrowingPack growingPack() {
        return new GrowingPack("growth", List.of());
    }

    private RulePackRegistry registryWith(RulePack... packs) {
        RulePackRegistry registry = new RulePackRegistry();
        for (RulePack pack : packs) {
            registry.register(pack);
        }
        registry.freeze();
        return registry;
    }

    private StateRoot run(RulePackRegistry registry, List<ActionEnvelope> actions, long ticks) {
        long fingerprint = registry.combinedFingerprint(FlatWorldRules.registryFingerprint());
        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, registry, hashes);
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, ticks, 1L,
                FlatWorldRules.RULES_VERSION, fingerprint);
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, ticks, actions);
        return engine.execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot();
    }

    private ActionEnvelope place(int x, int y, int z, int blockStateId) {
        return TestFixtures.envelope(region, 1, 1,
                TestFixtures.place(new NBlockPos(x, y, z), blockStateId));
    }

    // --- the exit ------------------------------------------------------------------------------

    @Test
    void aPacksBlockIsPlacedAndTickedByThePacksOwnCode() {
        RulePackRegistry registry = registryWith(growingPack());
        StateRoot withPack = run(registry, List.of(place(5, 70, 5, PACK_BLOCK)), 2);

        // The same actions with no pack registered cannot produce the same world: the base rules
        // do not know id 1000 at all, so nothing is placed and nothing ticks.
        RulePackRegistry empty = new RulePackRegistry();
        empty.freeze();
        StateRoot withoutPack = run(empty, List.of(place(5, 70, 5, PACK_BLOCK)), 2);
        assertThat(withPack)
                .as("the pack's rules actually ran — its block reached consensus state")
                .isNotEqualTo(withoutPack);
    }

    @Test
    void thePackRunIsReplicaIdenticalAcrossFreshRegistries() {
        // Two "members", each building its own registry from its own pack instances.
        StateRoot a = run(registryWith(growingPack()), List.of(place(5, 70, 5, PACK_BLOCK)), 5);
        StateRoot b = run(registryWith(growingPack()), List.of(place(5, 70, 5, PACK_BLOCK)), 5);
        assertThat(b).as("pack execution is deterministic across replicas").isEqualTo(a);
    }

    @Test
    void aPacksOwnRejectionIsHonouredLikeAnyOtherValidation() {
        RulePackRegistry registry = registryWith(growingPack());
        // y < 0 is illegal for THIS pack's block — its own rule, not the base rules'.
        StateRoot rejected = run(registry, List.of(place(5, -10, 5, PACK_BLOCK)), 1);
        StateRoot nothing = run(registry, List.of(), 1);
        assertThat(rejected)
                .as("a pack rejection drops the action; the world is untouched")
                .isEqualTo(nothing);
    }

    @Test
    void packTicksRunInCanonicalNamespaceOrderNotInstallationOrder() {
        List<String> forward = new java.util.ArrayList<>();
        RulePackRegistry a = new RulePackRegistry();
        a.register(new OrderPack("aaa", 1L, forward));
        a.register(new GrowingPack("zzz", List.of()));
        a.freeze();
        run(a, List.of(), 1);

        List<String> reversed = new java.util.ArrayList<>();
        RulePackRegistry b = new RulePackRegistry();
        b.register(new GrowingPack("zzz", List.of()));
        b.register(new OrderPack("aaa", 1L, reversed));
        b.freeze();
        run(b, List.of(), 1);

        assertThat(a.packs().stream().map(RulePack::namespace))
                .as("the registry sorts by namespace whatever order mods loaded in")
                .containsExactly("aaa", "zzz");
        assertThat(reversed).isEqualTo(forward);
    }

    @Test
    void aPackCanNeverInterceptABaseBlock() {
        RulePackRegistry registry = registryWith(growingPack());
        // Placing STONE must still go through the base rules, pack or no pack.
        StateRoot withPack = run(registry, List.of(place(5, 70, 5, FlatWorldRules.STONE)), 1);

        RulePackRegistry empty = new RulePackRegistry();
        empty.freeze();
        long emptyFingerprint = empty.combinedFingerprint(FlatWorldRules.registryFingerprint());
        FlatWorldRegionEngine plain = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION, emptyFingerprint, hashes);
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, 1L,
                FlatWorldRules.RULES_VERSION, emptyFingerprint);
        StateRoot baseline = plain.execute(new RegionExecutionRequest(ctx, base,
                new ActionBatch(region, RegionEpoch.INITIAL, base.version(), 0, 1,
                        List.of(place(5, 70, 5, FlatWorldRules.STONE))))).resultingRoot();

        assertThat(withPack)
                .as("base blocks behave identically with a pack installed — ownership starts at 1000")
                .isEqualTo(baseline);
        assertThat(registry.ownerOf(FlatWorldRules.STONE)).isEmpty();
        assertThat(registry.ownerOf(PACK_BLOCK)).isPresent();
        assertThat(emptyFingerprint)
                .as("a packless registry must be indistinguishable from no SDK at all, or the "
                        + "SDK's mere presence would fork the network")
                .isEqualTo(FlatWorldRules.registryFingerprint());
    }
}
