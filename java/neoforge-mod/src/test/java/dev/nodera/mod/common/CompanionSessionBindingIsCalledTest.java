package dev.nodera.mod.common;

import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Network L-30's actual cause was a verb with no caller on one of the two paths that needed it.
 *
 * <p>{@code NODERA-MESH} — the verb that promotes a private always-on worker into a MEMBER of a
 * world's session — was invoked on the <b>host</b> startup path only. The joiner path called
 * {@code mesh("", null)}, which detaches. So a joining player's worker was never part of the world
 * its player was standing in, the session's resident population was capped at one however many
 * workers were running, and with one seatable resident there was exactly one inspectable holder per
 * region — no second independently-computed root to compare, which is the whole of L-30.
 *
 * <p>Nothing about that was visible in a unit test, because the fault was an absence. This scanner
 * asserts the presence of each call site instead: the two bind paths and the two detach paths, in the
 * production sources. It is deliberately a source scan — the alternative is a live run, which is the
 * exit test, and the point of this test is to catch the regression before one is scheduled.
 *
 * <p>The repository's dominant defect shape is code that is implemented, tested, and never called
 * ({@code docs/network/PROGRESS.md} records six in a single sweep). A test that a call site exists is
 * the cheapest available defence against it.
 */
final class CompanionSessionBindingIsCalledTest {

    private static Path source(String relative) {
        Path source = LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java")
                .resolve(relative);
        if (!Files.isRegularFile(source)) {
            throw new AssertionError("cannot locate " + source);
        }
        return source;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(source(relative), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the joiner's plan handler binds this player's worker into the session it joined")
    void theJoinerBindsItsWorker() throws IOException {
        assertThat(read("dev/nodera/mod/common/ModNetworking.java"))
                .as("without this the joiner's worker never joins the world its player is in (L-30)")
                .contains("bindCompanionToSession(payload.worldSeed())");
    }

    @Test
    @DisplayName("the bind is issued from the plan broadcast, the first message carrying the seed")
    void theBindCarriesTheWorldSeed() throws IOException {
        String peerService = read("dev/nodera/mod/common/NoderaPeerService.java");
        assertThat(peerService)
                .as("a worker bound with no seed re-executes to roots nobody else computes")
                .contains("companion.mesh(route, worldSeed)");
        assertThat(peerService)
                .as("the joiner's bootstrap route is what the worker dials")
                .contains("clientBootstrapRoute");
    }

    @Test
    @DisplayName("the host still binds its own worker — the path that already worked")
    void theHostStillBindsItsWorker() throws IOException {
        assertThat(read("dev/nodera/mod/common/NoderaHost.java"))
                .as("the host's bind carries the world seed from the level it just planned")
                .contains("companion.mesh(hostRoute, worldSeed)");
    }

    @Test
    @DisplayName("both sides detach on teardown, so a worker never heartbeats at a dead session")
    void bothSidesDetach() throws IOException {
        assertThat(read("dev/nodera/mod/common/NoderaPeerService.java"))
                .as("an empty route is the detach; stopHosting and stopClient must both issue it")
                .containsSubsequence("stopHosting", "companion.mesh(\"\", null)")
                .containsSubsequence("stopClient", "companion.mesh(\"\", null)");
    }

    @Test
    @DisplayName("the resident pool reaches the joiner: the plan payload carries it")
    void thePlanCarriesTheResidentPool() throws IOException {
        assertThat(read("dev/nodera/mod/common/NoderaLanePlanPayload.java"))
                .as("residents are a plan input; a member planning without them plans differently")
                .contains("List<Resident> residents");
        assertThat(read("dev/nodera/mod/common/NoderaHost.java"))
                .as("the host must actually fill the field it now has")
                .contains("residentInputs");
        assertThat(read("dev/nodera/mod/client/entity/ClientValidationLane.java"))
                .as("and the client must plan with it — the five-argument overload")
                .contains("plan.committeeSize(), residents");
    }
}
