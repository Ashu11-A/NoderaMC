package dev.nodera.peer.validation;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurableInventoryCreditJournalTest {

    @TempDir
    Path dir;

    @Test
    void retainedCreditSurvivesRestartAndReplayIsIdempotent() {
        Path file = dir.resolve("credits.bin");
        InventoryCredit credit = credit(42, 3);
        DurableInventoryCreditJournal journal = new DurableInventoryCreditJournal(file);
        journal.retain(credit);
        journal.retain(credit);

        assertThat(new DurableInventoryCreditJournal(file).retained()).containsExactly(credit);
    }

    @Test
    void sameActorEntityCannotChangePayload() {
        DurableInventoryCreditJournal journal =
                new DurableInventoryCreditJournal(dir.resolve("credits.bin"));
        journal.retain(credit(42, 3));

        assertThatThrownBy(() -> journal.retain(credit(42, 4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different payload");
    }

    private static InventoryCredit credit(int item, int count) {
        return new InventoryCredit(
                new NodeId(new UUID(0, 1)), new NetworkEntityId(7), item, count);
    }
}
