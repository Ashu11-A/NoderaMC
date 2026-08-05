package dev.nodera.mod.common;

import dev.nodera.endpoint.lane.ValidationLane;
import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic validation lane is on, and it is switched <b>at both roots</b>.
 *
 * <p>A scope decision that lives only in a comment is a decision that changes by accident. The lane
 * has two activation points — the session server's ownership bootstrap and every other player's
 * client lane — and gating one of them is the shape of mistake this test exists to catch: the client
 * lane is normally started by a plan the server broadcasts, so a build that gated only the server
 * would look correct in every homogeneous test and start a lane the moment an older or hostile peer
 * sent a plan.
 *
 * <p>The state asserted here is <b>on</b>, because live region seeding hangs off it: no commits
 * means no {@code RegionSeedSpool} pushes, which means the only remaining way to move a change
 * between peers is to move the whole world. Switching it back off is a deliberate act that has to
 * come here first.
 */
final class ValidationLaneRootSwitchTest {

    @Test
    @DisplayName("deterministic validation is on")
    void theLaneIsOn() {
        assertThat(ValidationLane.deterministicValidationEnabled())
                .as("switching this off also switches off live region seeding; see ValidationLane")
                .isTrue();
    }

    @Test
    @DisplayName("both activation roots consult the switch")
    void bothRootsAreGated() throws Exception {
        Path source = LayoutManifest.load()
                .module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod");
        assertThat(Files.readString(source.resolve("common/NoderaHost.java")))
                .as("the session server's ownership bootstrap")
                .contains("ValidationLane.deterministicValidationEnabled()");
        assertThat(Files.readString(source.resolve("client/entity/ClientValidationLane.java")))
                .as("every other player's client lane")
                .contains("ValidationLane.deterministicValidationEnabled()");
    }

    /**
     * The switch moves one constant, never the code. The engine, committee and capture classes stay
     * in the build with their tests in either state, so the lane changes by flipping the constant
     * rather than by being rebuilt from a description of how it used to work.
     */
    @Test
    @DisplayName("the lane is a switch, not a deletion")
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
