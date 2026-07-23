package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurableActionJournalTest {

    @TempDir
    Path dir;

    @Test
    void reservationsAndTerminalStagesSurviveRestart() {
        ActionEnvelope first = action(1, 1);
        ActionEnvelope second = action(2, 2);
        Path file = dir.resolve("actions.bin");
        DurableActionJournal journal = new DurableActionJournal(file);
        journal.reserve(List.of(first, second));
        journal.commit(List.of(first));

        DurableActionJournal reopened = new DurableActionJournal(file);
        assertThat(reopened.retained()).containsExactly(first, second);
        assertThat(reopened.pending()).containsExactly(second);
        reopened.abort(List.of(second));
        assertThat(new DurableActionJournal(file).pending()).isEmpty();
    }

    @Test
    void sameSequenceCannotChangePayloadOrTerminalOutcome() {
        ActionEnvelope first = action(1, 1);
        ActionEnvelope changed = action(1, 9);
        DurableActionJournal journal = new DurableActionJournal(dir.resolve("actions.bin"));
        journal.reserve(List.of(first));
        assertThatThrownBy(() -> journal.reserve(List.of(changed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reused");
        journal.commit(List.of(first));
        assertThatThrownBy(() -> journal.abort(List.of(first)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMMITTED");
    }

    @Test
    void abortPendingCompensatesOnlyReservedEntriesAndKeepsSequencesConsumed() {
        ActionEnvelope committed = action(1, 1);
        ActionEnvelope stale = action(2, 2);
        Path file = dir.resolve("actions.bin");
        DurableActionJournal journal = new DurableActionJournal(file);
        journal.reserve(List.of(committed, stale));
        journal.commit(List.of(committed));

        DurableActionJournal reopened = new DurableActionJournal(file);
        assertThat(reopened.abortPending()).containsExactly(stale);
        assertThat(reopened.pending()).isEmpty();
        // The compensated reservation stays in the journal: sequences remain consumed and the
        // same sequence cannot be re-reserved with a different payload.
        assertThat(reopened.retained()).containsExactly(committed, stale);
        assertThat(reopened.nextServerSequence()).isEqualTo(3L);
        assertThatThrownBy(() -> reopened.reserve(List.of(action(2, 9))))
                .isInstanceOf(IllegalArgumentException.class);
        // Idempotent: nothing left to compensate, on this handle or after another reopen.
        assertThat(reopened.abortPending()).isEmpty();
        assertThat(new DurableActionJournal(file).abortPending()).isEmpty();
    }

    private static ActionEnvelope action(long sequence, int stateId) {
        return new ActionEnvelope(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                sequence, sequence, 1,
                new RegionId(DimensionKey.overworld(), 0, 0),
                new PlaceBlockAction(new NBlockPos(1, 70, 1), stateId, 0),
                Bytes.empty());
    }
}
