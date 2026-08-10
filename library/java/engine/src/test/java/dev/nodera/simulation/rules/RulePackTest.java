package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.CommandAction;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule-pack seam: what a data pack may declare, what the SDK accepts, and what the command
 * subset allows it to do.
 *
 * <p>Three sibling classes over one subject — the boundary between the engine's own rules and the
 * ones a pack supplies. This is the seam a third party extends, so the three halves of its contract
 * belong in one place.
 */
final class RulePackTest {

    /**
     * L-21's remaining exit half: <b>pack rule execution hooks</b> — a mod's own validate / apply / tick
     * code running inside the validated lane, not just palette ids and an identity.
     *
     * <p>The three properties that make this safe are the ones asserted here: dispatch is by declared
     * palette ownership (so a pack can never reach a vanilla block), pack ticks run in canonical
     * namespace order (so installation order cannot change the root), and a pack's own rejections are
     * ordinary validation results — its blocks are consensus state like any other.
     */
    @Nested
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

    /**
     * Task 16 / L-21 (SDK core): rule packs are IDENTITY folded into the registry fingerprint.
     * Installation order never matters, any content difference always does, collisions die at
     * registration, and a committee member whose pack set differs is REFUSED by the engine's
     * existing fingerprint gate instead of silently diverging.
     */
    @Nested
    final class RulePackSdkTest {

        private record TestPack(String namespace, List<RulePack.PackPaletteEntry> paletteEntries,
                                long semanticFingerprint) implements RulePack {
        }

        private static TestPack pack(String ns, int id, String name, long semantics) {
            return new TestPack(ns, List.of(new RulePack.PackPaletteEntry(id, name)), semantics);
        }

        @Test
        void contentDecidesTheFingerprintInstallationOrderNever() {
            RulePackRegistry a = new RulePackRegistry();
            a.register(pack("alpha", 1000, "alpha_block", 1L));
            a.register(pack("beta", 1100, "beta_block", 2L));

            RulePackRegistry b = new RulePackRegistry();
            b.register(pack("beta", 1100, "beta_block", 2L));
            b.register(pack("alpha", 1000, "alpha_block", 1L));

            long base = FlatWorldRules.registryFingerprint();
            assertThat(b.combinedFingerprint(base))
                    .as("order-independent: both members compute the identical number")
                    .isEqualTo(a.combinedFingerprint(base));
            assertThat(a.combinedFingerprint(base))
                    .as("packs change the fingerprint — a packless member differs")
                    .isNotEqualTo(base);

            RulePackRegistry c = new RulePackRegistry();
            c.register(pack("alpha", 1000, "alpha_block", 1L));
            c.register(pack("beta", 1100, "beta_block", 99L)); // same ids, DIFFERENT semantics
            assertThat(c.combinedFingerprint(base))
                    .as("a semantic bump alone forces refusal")
                    .isNotEqualTo(a.combinedFingerprint(base));
        }

        @Test
        void collisionsAndBasePaletteIntrusionsDieAtRegistration() {
            RulePackRegistry registry = new RulePackRegistry();
            registry.register(pack("alpha", 1000, "alpha_block", 1L));
            assertThatThrownBy(() -> registry.register(pack("alpha", 1200, "other", 2L)))
                    .as("duplicate namespace")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry.register(pack("gamma", 1000, "clash", 3L)))
                    .as("palette id collision across packs")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pack("delta", 5, "base_intrusion", 4L))
                    .as("ids below the pack floor are the frozen base palette")
                    .isInstanceOf(IllegalArgumentException.class);
            registry.freeze();
            assertThatThrownBy(() -> registry.register(pack("late", 1300, "late_block", 5L)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aPackDivergentMemberIsRefusedByTheEngineFingerprintGate() {
            RulePackRegistry withPack = new RulePackRegistry();
            withPack.register(pack("alpha", 1000, "alpha_block", 1L));
            long divergent = withPack.combinedFingerprint(FlatWorldRules.registryFingerprint());

            FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(),
                    new HashService());
            RegionId region = TestFixtures.region(0, 0);
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, 1L,
                    FlatWorldRules.RULES_VERSION, divergent);
            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, List.of());
            assertThatThrownBy(() -> engine.execute(new RegionExecutionRequest(ctx, base, batch)))
                    .as("mixed pack sets refuse at the existing gate — no silent divergence")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * L-14's remaining exit half: the <b>deterministic command subset</b>.
     *
     * <p>Commands were outside the validated path entirely, which meant an operator's {@code /setblock}
     * mutated a delegated region behind the committee's back — the operator's world and every
     * validator's replica silently diverged, which is precisely the failure class the validated lane
     * exists to make impossible. A command is now an ordinary signed action.
     *
     * <p>Two design decisions are load-bearing and are asserted here rather than merely documented:
     * <b>authority is checked in the engine</b> against the committee-agreed operator set (a
     * capture-point check is advice a modified client can skip), and <b>a command may not mint a block
     * a player could not place</b>, or {@code /setblock} would become a way to conjure network-computed
     * states like a powered wire or an extended piston head.
     */
    @Nested
    final class CommandSubsetTest {

        private static final NodeId OPERATOR = TestFixtures.ACTOR;
        private static final NodeId RANDOM_PLAYER =
                new NodeId(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff"));

        private final HashService hashes = new HashService();
        private final RegionId region = TestFixtures.region(0, 0);

        private StateRoot run(List<ActionEnvelope> actions, Set<NodeId> operators) {
            FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);
            RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
            RegionExecutionContext ctx = new RegionExecutionContext(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, 1L,
                    FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), 0L, operators);
            ActionBatch batch = new ActionBatch(
                    region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
            return engine.execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot();
        }

        private ActionEnvelope command(NodeId actor, CommandAction action) {
            return new ActionEnvelope(actor, 1, 1, 1, region, action, dev.nodera.core.Bytes.empty());
        }

        private static CommandAction setblock(int x, int y, int z, int state) {
            return CommandAction.at(CommandAction.Kind.SETBLOCK, new NBlockPos(x, y, z), state);
        }

        @Test
        void anOperatorsSetblockIsCommittedLikeAnyOtherValidatedAction() {
            StateRoot afterCommand = run(
                    List.of(command(OPERATOR, setblock(5, 70, 5, FlatWorldRules.STONE))),
                    Set.of(OPERATOR));
            StateRoot untouched = run(List.of(), Set.of(OPERATOR));

            assertThat(afterCommand)
                    .as("the command reached consensus state instead of mutating behind the committee")
                    .isNotEqualTo(untouched);
        }

        @Test
        void aNonOperatorsCommandIsRejectedOnEveryMember() {
            StateRoot fromPlayer = run(
                    List.of(command(RANDOM_PLAYER, setblock(5, 70, 5, FlatWorldRules.STONE))),
                    Set.of(OPERATOR));
            assertThat(fromPlayer)
                    .as("authority is engine-side; a signed command from a non-operator changes nothing")
                    .isEqualTo(run(List.of(), Set.of(OPERATOR)));
        }

        @Test
        void anEmptyOperatorSetMeansNobodyRatherThanEveryone() {
            // The dangerous default. An unset operator set must never read as "unrestricted".
            StateRoot withNoOperators = run(
                    List.of(command(OPERATOR, setblock(5, 70, 5, FlatWorldRules.STONE))), Set.of());
            assertThat(withNoOperators).isEqualTo(run(List.of(), Set.of()));
        }

        @Test
        void aFillWritesTheWholeBoxAndIsReplicaIdentical() {
            List<ActionEnvelope> fill = List.of(command(OPERATOR, new CommandAction(
                    CommandAction.Kind.FILL,
                    new NBlockPos(2, 68, 2), new NBlockPos(4, 70, 4), FlatWorldRules.STONE)));

            StateRoot a = run(fill, Set.of(OPERATOR));
            StateRoot b = run(fill, Set.of(OPERATOR));
            assertThat(b).as("canonical (y, z, x) iteration ⇒ identical bytes, not merely equivalent")
                    .isEqualTo(a);
            assertThat(a).isNotEqualTo(run(List.of(), Set.of(OPERATOR)));
        }

        @Test
        void aCommandCannotMintABlockAPlayerCouldNotPlace() {
            // WIRE_15 is a network-COMPUTED state. If /setblock could mint it, a command would be a
            // way to conjure powered redstone out of nothing — the same hole the placement mint
            // protection closes for players.
            StateRoot minted = run(
                    List.of(command(OPERATOR, setblock(5, 70, 5, FlatWorldRules.WIRE_15))),
                    Set.of(OPERATOR));
            assertThat(minted).isEqualTo(run(List.of(), Set.of(OPERATOR)));
        }

        @Test
        void anOversizedFillIsRefusedRatherThanStallingEveryValidator() {
            // Execution time is part of what keeps the tick budget honest: one authorised action must
            // not be able to freeze every validator and trip the lag handoff.
            CommandAction huge = new CommandAction(CommandAction.Kind.FILL,
                    new NBlockPos(0, 0, 0), new NBlockPos(63, 63, 63), FlatWorldRules.STONE);
            assertThat(huge.volume()).isGreaterThan(CommandRules.MAX_FILL_VOLUME);
            assertThat(run(List.of(command(OPERATOR, huge)), Set.of(OPERATOR)))
                    .isEqualTo(run(List.of(), Set.of(OPERATOR)));
        }

        @Test
        void timeSetIsRefusedBecauseItIsAContextInputNotRegionState() {
            // Committed world time is agreed by the committee on the context (L-6). Mutating it from
            // inside a batch would leave one replica's context disagreeing with the others' for the
            // rest of that batch, so it is refused here rather than admitted and hoped for.
            StateRoot timeSet = run(List.of(command(OPERATOR, CommandAction.at(
                    CommandAction.Kind.TIME_SET, new NBlockPos(0, 0, 0), 6000))), Set.of(OPERATOR));
            assertThat(timeSet).isEqualTo(run(List.of(), Set.of(OPERATOR)));
        }

        @Test
        void theWireFormRoundTripsAndRejectsAnUnknownKind() {
            CommandAction original = new CommandAction(CommandAction.Kind.FILL,
                    new NBlockPos(-3, 4, 5), new NBlockPos(7, 8, 9), 42);
            CanonicalWriter w = new CanonicalWriter();
            original.encode(w);
            assertThat(CommandAction.decode(new CanonicalReader(w.toBytes()))).isEqualTo(original);

            assertThat(org.assertj.core.api.Assertions
                    .catchThrowable(() -> CommandAction.Kind.fromOrdinal(99)))
                    .as("ordinals are the wire form — an unknown one is refused, never defaulted")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
