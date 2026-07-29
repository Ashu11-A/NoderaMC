package dev.nodera.mod.server.entity;

import dev.nodera.core.action.MovePlayerAction;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.simulation.entity.MovementRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-12, the half that was missing: the engine has validated {@code MovePlayerAction}s since Task
 * 16, and nothing in the mod ever produced one — the action type existed with no production call
 * site, so player movement never entered the lane. These tests drive real movement traces through
 * the producer and assert the actions it makes are ones the committee can accept.
 */
final class PlayerMovementCaptureTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static FixedVec3 at(double x, double y, double z) {
        return FixedVec3.fromExternal(x, y, z);
    }

    @Test
    void theFirstSampleAnchorsAndProposesNothing() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        PlayerMovementCapture.Plan plan = capture.observe(PLAYER, true, at(64.5, 64.0, 64.5));

        assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.ANCHORED);
        assertThat(plan.steps()).isEmpty();
        assertThat(capture.anchor(PLAYER)).contains(at(64.5, 64.0, 64.5));
    }

    @Test
    void walkingProducesOneStepPerTickEndingExactlyWhereThePlayerIs() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        capture.observe(PLAYER, true, at(64.5, 64.0, 64.5));

        // A vanilla walk is ~0.21 blocks per tick.
        FixedVec3 previous = at(64.5, 64.0, 64.5);
        for (int tick = 1; tick <= 5; tick++) {
            FixedVec3 now = at(64.5 + 0.21 * tick, 64.0, 64.5);
            PlayerMovementCapture.Plan plan = capture.observe(PLAYER, true, now);

            assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.CAPTURED);
            assertThat(plan.steps()).hasSize(1);
            assertThat(plan.steps().get(0).to())
                    .as("the committee is told where the player IS, not a rounded stand-in")
                    .isEqualTo(now);
            assertWithinEnvelope(previous, plan.steps());
            previous = now;
        }
    }

    @Test
    void standingStillIsNotWorthAnActionAndTheAnchorIsNotMoved() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        FixedVec3 stood = at(64.5, 64.0, 64.5);
        capture.observe(PLAYER, true, stood);

        // Sub-dead-zone jitter, twenty ticks of it.
        for (int tick = 0; tick < 20; tick++) {
            PlayerMovementCapture.Plan plan = capture.observe(
                    PLAYER, true, at(64.5 + 0.001 * (tick % 3), 64.0, 64.5));
            assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.IDLE);
            assertThat(plan.steps()).isEmpty();
        }
        assertThat(capture.anchor(PLAYER))
                .as("drift is accumulated against the original anchor, never rounded away")
                .contains(stood);
    }

    @Test
    void aLagSpikeIsSplitIntoStepsThatEachFitTheEngineSpeedEnvelope() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        FixedVec3 from = at(64.5, 64.0, 64.5);
        capture.observe(PLAYER, true, from);

        // Four blocks east and a little up in one sample: a stalled tick, not a cheat.
        FixedVec3 to = at(68.5, 65.0, 64.5);
        PlayerMovementCapture.Plan plan = capture.observe(PLAYER, true, to);

        assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.CAPTURED);
        assertThat(plan.steps()).hasSize(4);
        assertWithinEnvelope(from, plan.steps());
        assertThat(plan.steps().get(plan.steps().size() - 1).to())
                .as("the chain ends exactly on the sampled position")
                .isEqualTo(to);
    }

    @Test
    void aTeleportIsNotWalkingAndIsNeverSmuggledThroughAsMovement() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        capture.observe(PLAYER, true, at(64.5, 64.0, 64.5));

        // A pearl, a portal, a /tp: 200 blocks.
        PlayerMovementCapture.Plan plan = capture.observe(PLAYER, true, at(264.5, 64.0, 64.5));

        assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.TELEPORT_RESYNC);
        assertThat(plan.steps()).isEmpty();
        assertThat(capture.anchor(PLAYER))
                .as("re-anchored, so the next ordinary step is measured from where the player is")
                .contains(at(264.5, 64.0, 64.5));
        assertThat(capture.observe(PLAYER, true, at(265.0, 64.0, 64.5)).outcome())
                .isEqualTo(PlayerMovementCapture.Outcome.CAPTURED);
    }

    @Test
    void aRegionThisNodeDoesNotHoldProducesNothingAndDropsTheAnchor() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        capture.observe(PLAYER, true, at(64.5, 64.0, 64.5));

        PlayerMovementCapture.Plan plan = capture.observe(PLAYER, false, at(64.9, 64.0, 64.5));

        assertThat(plan.outcome()).isEqualTo(PlayerMovementCapture.Outcome.OFF_LANE);
        assertThat(plan.steps()).isEmpty();
        assertThat(capture.anchor(PLAYER)).isEmpty();
        assertThat(capture.observe(PLAYER, true, at(64.9, 64.0, 64.5)).outcome())
                .as("coming back on lane re-anchors rather than inventing a step")
                .isEqualTo(PlayerMovementCapture.Outcome.ANCHORED);
    }

    @Test
    void aForgottenPlayerStartsOverRatherThanManufacturingATeleport() {
        PlayerMovementCapture capture = new PlayerMovementCapture();
        capture.observe(PLAYER, true, at(64.5, 64.0, 64.5));
        capture.forget(PLAYER);

        assertThat(capture.observe(PLAYER, true, at(9000.5, 64.0, 9000.5)).outcome())
                .isEqualTo(PlayerMovementCapture.Outcome.ANCHORED);
    }

    /** Exactly the engine's per-axis test in {@link MovementRules#validate}, applied step by step. */
    private static void assertWithinEnvelope(FixedVec3 from, List<MovePlayerAction> steps) {
        FixedVec3 previous = from;
        for (MovePlayerAction step : steps) {
            assertThat(Math.abs(step.to().x() - previous.x()))
                    .as("per-axis X envelope").isLessThanOrEqualTo(MovementRules.MAX_STEP);
            assertThat(Math.abs(step.to().y() - previous.y()))
                    .as("per-axis Y envelope").isLessThanOrEqualTo(MovementRules.MAX_STEP);
            assertThat(Math.abs(step.to().z() - previous.z()))
                    .as("per-axis Z envelope").isLessThanOrEqualTo(MovementRules.MAX_STEP);
            previous = step.to();
        }
    }
}
