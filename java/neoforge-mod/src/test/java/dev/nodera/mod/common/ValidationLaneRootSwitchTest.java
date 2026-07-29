package dev.nodera.mod.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic validation lane is off for the initial release, and it is off <b>at both
 * roots</b>.
 *
 * <p>A scope decision that lives only in a comment is a decision that comes back on by accident.
 * The lane has two activation points — the session server's ownership bootstrap and every other
 * player's client lane — and gating one of them is the shape of mistake this test exists to catch:
 * the client lane is normally started by a plan the server broadcasts, so a build that gated only
 * the server would look correct in every homogeneous test and start a lane the moment an older or
 * hostile peer sent a plan.
 */
final class ValidationLaneRootSwitchTest {

    @Test
    @DisplayName("deterministic validation is off for this release")
    void theLaneIsOff() {
        assertThat(ValidationLane.deterministicValidationEnabled())
                .as("flipping this back on is a deliberate act; see ValidationLane")
                .isFalse();
    }

    @Test
    @DisplayName("both activation roots consult the switch")
    void bothRootsAreGated() throws Exception {
        Path source = Path.of("src/main/java/dev/nodera/mod");
        assertThat(Files.readString(source.resolve("common/NoderaHost.java")))
                .as("the session server's ownership bootstrap")
                .contains("ValidationLane.deterministicValidationEnabled()");
        assertThat(Files.readString(source.resolve("client/entity/ClientValidationLane.java")))
                .as("every other player's client lane")
                .contains("ValidationLane.deterministicValidationEnabled()");
    }

    /**
     * Nothing was deleted to switch it off. The engine, committee and capture code stay in the
     * build with their tests, so the lane returns by flipping one constant rather than by being
     * rebuilt from a description of how it used to work.
     */
    @Test
    @DisplayName("the lane is dormant, not removed")
    void theLaneIsStillThere() {
        List<String> stillPresent = List.of(
                "dev.nodera.peer.validation.WorkerValidationService",
                "dev.nodera.peer.validation.EntityLaneBootstrap",
                "dev.nodera.mod.server.entity.LiveEntityLaneRuntime",
                "dev.nodera.mod.client.entity.ClientValidationLane");
        for (String name : stillPresent) {
            assertThat(classExists(name)).as(name).isTrue();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ValidationLaneRootSwitchTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
