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
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
