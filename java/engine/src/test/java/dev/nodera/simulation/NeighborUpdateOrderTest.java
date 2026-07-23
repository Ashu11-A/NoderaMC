package dev.nodera.simulation;

import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 13 / L-26: fixed D-U-N-S-W-E order, iterative worklist, loud bound. */
final class NeighborUpdateOrderTest {

    @Test
    void neighborsFollowTheDocumentedFixedOrder() {
        assertThat(NeighborUpdateOrder.neighborsOf(new NBlockPos(0, 64, 0))).containsExactly(
                new NBlockPos(0, 63, 0),   // down
                new NBlockPos(0, 65, 0),   // up
                new NBlockPos(0, 64, -1),  // north
                new NBlockPos(0, 64, 1),   // south
                new NBlockPos(-1, 64, 0),  // west
                new NBlockPos(1, 64, 0));  // east
    }

    @Test
    void propagationOrderIsDeterministicAndVisitorGated() {
        // Expand only along a straight east line: the visit order must interleave the six
        // rejected neighbors of each expanded position identically on every run.
        List<NBlockPos> first = run();
        assertThat(run()).isEqualTo(first);
        // The origin's east neighbor expands; its own east neighbor is in the sequence.
        assertThat(first).contains(new NBlockPos(1, 64, 0), new NBlockPos(2, 64, 0));
        // Each position is visited at most once.
        assertThat(first).doesNotHaveDuplicates();
    }

    private static List<NBlockPos> run() {
        List<NBlockPos> order = new ArrayList<>();
        NeighborUpdateOrder.propagate(new NBlockPos(0, 64, 0), pos -> {
            order.add(pos);
            return pos.y() == 64 && pos.z() == 0 && pos.x() >= 0 && pos.x() <= 3;
        });
        return order;
    }

    @Test
    void depthIsIrrelevantNoRecursion() {
        // A 1000-long line propagates fine iteratively — recursion would be stack-bound.
        int[] count = {0};
        NeighborUpdateOrder.propagate(new NBlockPos(0, 64, 0), pos -> {
            boolean expand = pos.y() == 64 && pos.z() == 0
                    && pos.x() > 0 && pos.x() <= 500;
            if (expand) {
                count[0]++;
            }
            return expand;
        });
        assertThat(count[0]).isEqualTo(500);
    }

    @Test
    void runawayChainFailsLoudlyAtTheBound() {
        assertThatThrownBy(() -> NeighborUpdateOrder.propagate(
                new NBlockPos(0, 64, 0), pos -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded");
    }
}
