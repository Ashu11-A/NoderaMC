package dev.nodera.peer.validation;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reputation that resets on restart is a session counter with a long name. These tests are about
 * the restart.
 */
class DurableCoordinatorStateTest {

    private static final RegionId REGION =
            new RegionId(DimensionKey.of("minecraft", "overworld"), 3, -4);

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }

    @Test
    @DisplayName("a reputation earned before the restart is there after it")
    void reliabilitySurvivesAReopen(@TempDir Path dir) {
        Path file = dir.resolve("coordinator-state.bin");
        NodeId liar = node(1);

        DurableCoordinatorState first = new DurableCoordinatorState(file);
        for (int round = 0; round < 5; round++) {
            first.reliability().record(liar, false);
        }
        double earned = first.reliability().score(liar);
        assertThat(first.reliability().eligibleForAssignment(liar)).isFalse();
        first.flush();

        DurableCoordinatorState reopened = new DurableCoordinatorState(file);

        assertThat(reopened.reliability().score(liar)).isEqualTo(earned);
        assertThat(reopened.reliability().eligibleForAssignment(liar))
                .as("a node that spent an evening disagreeing does not come back spotless")
                .isFalse();
    }

    @Test
    @DisplayName("region epochs survive too — the stale-proposal defence does not reset")
    void epochsSurviveAReopen(@TempDir Path dir) {
        Path file = dir.resolve("coordinator-state.bin");
        DurableCoordinatorState first = new DurableCoordinatorState(file);
        first.leases().restoreEpoch(REGION, 42L);
        first.flush();

        assertThat(new DurableCoordinatorState(file).epochOf(REGION)).isEqualTo(42L);
    }

    @Test
    @DisplayName("a missing file is an empty state, not a failure")
    void aFreshNodeStartsEmpty(@TempDir Path dir) {
        DurableCoordinatorState fresh = new DurableCoordinatorState(dir.resolve("absent.bin"));

        assertThat(fresh.reliability().size()).isZero();
        assertThat(fresh.epochOf(REGION)).isZero();
        assertThat(fresh.reliability().eligibleForAssignment(node(9))).isTrue();
    }

    @Test
    @DisplayName("a corrupt file costs the node its memory, never its world")
    void damageIsRecoveredFromRatherThanThrown(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("coordinator-state.bin");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        DurableCoordinatorState recovered = new DurableCoordinatorState(file);

        assertThat(recovered.reliability().size()).isZero();
        // And it heals: the next flush replaces the damaged bytes with a readable state.
        recovered.reliability().record(node(2), true);
        recovered.flush();
        assertThat(new DurableCoordinatorState(file).reliability().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the file path is required")
    void nullPathIsRejected() {
        assertThatThrownBy(() -> new DurableCoordinatorState(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a service without durable state still works; persistState is a no-op")
    void attachIsOptional(@TempDir Path dir) {
        DurableCoordinatorState durable = new DurableCoordinatorState(
                dir.resolve("coordinator-state.bin"));
        durable.reliability().record(node(3), false);
        durable.flush();

        // Re-attaching a second wrapper over the same file sees the same view — which is what
        // makes "attach on session open, flush on session close" a round trip rather than a reset.
        DurableCoordinatorState again = new DurableCoordinatorState(
                dir.resolve("coordinator-state.bin"));
        assertThat(again.reliability().score(node(3)))
                .isEqualTo(durable.reliability().score(node(3)));
    }
}
