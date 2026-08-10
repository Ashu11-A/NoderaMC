package dev.nodera.mod.server.shadow;

import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Terrain that arrives in pieces, written into the level as it lands (network L-33).
 *
 * <p>Two sibling classes over one subject, because the subject has two halves that fail differently.
 * The first is the only decision in the arriving path — <b>which</b> of a cumulative report's columns
 * still need writing — and it is executable here. The second is whether anything in the product ever
 * makes that call: {@link RegionApplyQueue} takes a {@code ServerLevel}, which cannot be constructed
 * outside a running Minecraft server, so the wiring is asserted the way this repository asserts every
 * other absence, by reading the production sources. That is not a stylistic choice — an
 * implemented, unit-tested capability with no production call site is this codebase's dominant defect
 * shape, and it is precisely what kept L-33 open through three audits.
 *
 * <p>What happens <i>after</i> the queue accepts the columns — that a region really is handed over
 * before its last piece verifies — is proven end to end over the real content plane in {@code :peer}
 * ({@code ArchiveLaneTest.RegionRendersOnArrivalTest}), where the decision is actually made.
 *
 * <p>Thread-context: single test thread.
 */
final class RegionApplyQueueTest {

    private static ChunkColumnState column(int x, int z) {
        int[] palette = new int[64];
        java.util.Arrays.fill(palette, 1);
        return new ChunkColumnState(x, z, palette, -64, 64, List.of());
    }

    /** The filter that makes a cumulative arrival cheap instead of quadratic. */
    @Nested
    final class ArrivingColumnsTest {

        @Test
        @DisplayName("a report only queues the columns that are not on the ground yet")
        void columnsAppearBeforeTheLastPieceArrives() {
            List<ChunkColumnState> firstReport = List.of(column(0, 0), column(0, 1));
            // Nothing written yet: the first arrival writes everything it carries, which is what
            // makes terrain visible while the rest of the region is still in flight.
            assertThat(RegionApplyQueue.stillToWrite(Set.of(), firstReport))
                    .isEqualTo(firstReport);

            Set<Long> written = new HashSet<>();
            for (ChunkColumnState landed : firstReport) {
                written.add(RegionApplyQueue.coordinateOf(landed));
            }

            // The next report is cumulative — it repeats what already arrived and adds more.
            List<ChunkColumnState> secondReport = new ArrayList<>(firstReport);
            secondReport.add(column(0, 2));
            secondReport.add(column(1, 0));

            assertThat(RegionApplyQueue.stillToWrite(written, secondReport))
                    .as("only the new columns are queued; re-writing the landed ones would make a "
                            + "region delivered in N reports cost N-squared column writes")
                    .containsExactly(column(0, 2), column(1, 0));
        }

        @Test
        @DisplayName("a report that adds nothing queues nothing")
        void aRepeatedReportIsNotWork() {
            List<ChunkColumnState> report = List.of(column(3, 3));
            Set<Long> written = Set.of(RegionApplyQueue.coordinateOf(column(3, 3)));
            assertThat(RegionApplyQueue.stillToWrite(written, report)).isEmpty();
        }
    }

    /**
     * The call sites, because the fault this row records was an absence and not a wrong answer.
     *
     * <p>Every stage of the region content plane existed and was tested before any of it was joined
     * up — {@code docs/network/PROGRESS.md} records six such capabilities found in one sweep. These
     * four assertions are the cheapest available defence against this one going the same way.
     */
    @Nested
    final class ArrivingPathIsWiredTest {

        private String read(String module, String relative) throws IOException {
            Path source = LayoutManifest.load().module(module).resolve(relative);
            assertThat(Files.isRegularFile(source)).as("cannot locate %s", source).isTrue();
            return Files.readString(source, StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("the worker stages arriving columns during a region fetch")
        void theWorkerStagesWhatHasArrived() throws IOException {
            assertThat(read("peer", "src/main/java/dev/nodera/headless/WorkerControlHandler.java"))
                    .as("without this the control plane still answers once, at the end")
                    .contains("stagePartialRegion(partialFile");
            assertThat(read("peer", "src/main/java/dev/nodera/peer/control/ControlServer.java"))
                    .as("NODERA-FETCH-REGION has to write before it answers, like NODERA-ARCHIVE")
                    .contains("fetchRegion(c, request)");
        }

        @Test
        @DisplayName("the game asks for the staged columns and writes them into the level")
        void theGameDrawsWhatHasArrived() throws IOException {
            assertThat(read("neoforge-mod",
                    "src/main/java/dev/nodera/mod/server/entity/LiveEntityLaneRuntime.java"))
                    .as("the fetch spool's arriving callback must reach the apply queue")
                    .contains("this::applyArrivingColumns");
            assertThat(read("neoforge-mod",
                    "src/main/java/dev/nodera/mod/common/RegionFetchSpool.java"))
                    .as("and the spool must ask the worker to stage in the first place")
                    .contains("onPartial.accept(staged)");
        }
    }
}
