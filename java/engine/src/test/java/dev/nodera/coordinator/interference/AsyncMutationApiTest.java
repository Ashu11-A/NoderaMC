package dev.nodera.coordinator.interference;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 16 / L-25: the legal async mutation API. Any thread submits signed actions through
 * {@link AsyncActionGate}; the server thread drains them in one deterministic order into the
 * validated lane. An off-thread DIRECT write into a delegated region gets the documented
 * {@link MutationGuard.AsyncWriteException} naming the gate — never a silent block.
 */
final class AsyncMutationApiTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private static ActionEnvelope envelope(long seq) {
        return new ActionEnvelope(
                new NodeId(new UUID(7, 7)), seq, seq, 1, REGION,
                new PlaceBlockAction(new NBlockPos((int) seq, 64, 0), 1, 1), Bytes.empty());
    }

    @Test
    void submissionsFromManyThreadsDrainInFifoOrderOnTheServerThread() throws Exception {
        AsyncActionGate gate = new AsyncActionGate();
        CountDownLatch done = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            final int base = t * 100;
            Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 50; i++) {
                    assertThat(gate.submit(envelope(base + i))).isTrue();
                }
                done.countDown();
            });
        }
        done.await();

        List<ActionEnvelope> lane = new ArrayList<>();
        int drained = gate.drainInto(lane::add);
        assertThat(drained).isEqualTo(200);
        assertThat(lane).hasSize(200);
        assertThat(gate.backlog()).isZero();
        // Per-thread FIFO: each submitter's own sequence stays in order after the drain.
        for (int t = 0; t < 4; t++) {
            final int base = t * 100;
            List<Long> mine = lane.stream()
                    .map(ActionEnvelope::serverSeq)
                    .filter(s -> s >= base && s < base + 50)
                    .toList();
            assertThat(mine).isSorted();
        }
    }

    @Test
    void offThreadDirectWritesGetTheDocumentedErrorNamingTheGate() throws Exception {
        InterferenceBuffer buffer = new InterferenceBuffer();
        InterferenceStats stats = new InterferenceStats();
        MutationGuard guard = new MutationGuard(
                r -> true, MutationGuard.Mode.CONVERT, buffer, stats);

        Throwable[] fromExecutor = new Throwable[1];
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                guard.verdictChecked(REGION, new NBlockPos(1, 64, 1), 0, 1);
            } catch (Throwable t) {
                fromExecutor[0] = t;
            }
        });
        worker.join();

        assertThat(fromExecutor[0])
                .as("the off-thread write is rejected with the DOCUMENTED error")
                .isInstanceOf(MutationGuard.AsyncWriteException.class)
                .hasMessageContaining("AsyncActionGate.submit")
                .hasMessageContaining("docs/SDK.md");

        // The applier scope stays a legal writer through the checked entry point.
        guard.applierScope(() -> assertThat(
                guard.verdictChecked(REGION, new NBlockPos(1, 64, 1), 0, 1))
                .isEqualTo(MutationGuard.Verdict.PASS));

        // A write outside every region stays PASS from any thread (vanilla lane untouched).
        MutationGuard open = new MutationGuard(
                r -> false, MutationGuard.Mode.CONVERT, buffer, stats);
        assertThatCode(() -> open.verdictChecked(REGION, new NBlockPos(1, 64, 1), 0, 1))
                .doesNotThrowAnyException();
    }
}
