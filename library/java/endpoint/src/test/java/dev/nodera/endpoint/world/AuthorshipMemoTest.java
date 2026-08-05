package dev.nodera.endpoint.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memo exists so that "I could not check" stops being answered as "no". These tests pin the
 * three states it has to distinguish.
 */
final class AuthorshipMemoTest {

    @Test
    void aSaveNothingWasEverProvenAboutAnswersNo(@TempDir Path save) {
        assertThat(AuthorshipMemo.lastProven(save)).isFalse();
    }

    @Test
    void itRemembersWhatWasProven(@TempDir Path save) {
        AuthorshipMemo.remember(save, true);
        assertThat(AuthorshipMemo.lastProven(save)).isTrue();
    }

    @Test
    void aLaterCheckOverwritesAnEarlierOneInBothDirections(@TempDir Path save) {
        AuthorshipMemo.remember(save, true);
        AuthorshipMemo.remember(save, false);
        assertThat(AuthorshipMemo.lastProven(save)).isFalse();

        // And back: a save restored onto the machine that authored it must be able to say so again.
        AuthorshipMemo.remember(save, true);
        assertThat(AuthorshipMemo.lastProven(save)).isTrue();
    }

    @Test
    void itIsScopedToOneSave(@TempDir Path mine, @TempDir Path theirs) {
        AuthorshipMemo.remember(mine, true);
        assertThat(AuthorshipMemo.lastProven(theirs)).isFalse();
    }

    @Test
    void anUnwritableLocationIsNotAFailure(@TempDir Path save) throws Exception {
        // The memo is an optimisation over an authoritative check. Losing it costs the NEXT check
        // that cannot reach the worker, and nothing else — so it must never throw at the caller.
        // A regular file where the `nodera/` directory has to go is the cheapest way to make every
        // write fail for real.
        java.nio.file.Files.writeString(save.resolve("nodera"), "not a directory");

        AuthorshipMemo.remember(save, true);

        assertThat(AuthorshipMemo.lastProven(save)).isFalse();
    }
}
