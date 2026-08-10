package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LayoutManifest;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A call-site guard, because the fault it pins was an <b>absence</b>.
 *
 * <p>{@code BlockCaptureBridge} listens to {@code BlockEvent.EntityPlaceEvent} and
 * {@code BlockEvent.BreakEvent} and submits only when the placer is a {@code ServerPlayer}, because
 * the player's key is what signs the action. That is correct — and it makes the validated block
 * lane <b>undrivable from a script</b>: an RCON {@code /setblock} or {@code /fill} is a direct
 * world write and fires neither event, so nothing it does ever reaches a committee.
 *
 * <p>Two suites have now been caught measuring an idle lane for exactly that reason. The
 * determinism soak drove 197 rounds of {@code setblock} and recorded an empty capture ledger, so
 * its "zero divergences" proved nothing (fixed 2026-07-31, {@code DeterminismScenario} D2). The
 * mesh soak drove {@code fill} for its whole life, which is why every L-30 live run recorded
 * workers holding dozens of committee seats and {@code votes_cast=0}: there was never an action for
 * a primary to propose, and the region versions that did advance came from the ghost lane's
 * {@code ExternalDelta} stream — certified state arriving, not a committee round.
 *
 * <p>The failure mode is invisible from inside a run: a soak that captures nothing and a soak that
 * captures everything look identical from the stage list. So it is pinned here instead. The two
 * scenarios below exist to exercise the validated lane under sustained load; if either stops
 * driving through {@code /nodera debug drive} — which calls the same {@code submitBlockAction} the
 * bridge calls, as the player — this test says so before a live run spends twenty minutes not
 * measuring anything.
 *
 * <p>Deliberately not a blanket rule over every scenario: {@code CommandsScenario} uses a real
 * {@code setblock} to place a block it then reads back, which is a world write on purpose and has
 * nothing to do with the validated lane.
 */
final class ValidatedLoadIsCapturedTest {

    /** The scenarios whose entire purpose is to load the validated lane and read its counters. */
    private static final List<String> VALIDATED_SOAKS = List.of("mesh-soak", "determinism");

    /** The only RCON verb that reaches the validated block lane. */
    private static final String CAPTURED_DRIVE = "nodera debug drive";

    private static final LayoutManifest LAYOUT = LayoutManifest.load();

    @Test
    @DisplayName("every validated-lane soak drives its block load through the captured entry point")
    void everyValidatedSoakDrivesCapturedEdits() throws IOException {
        ScenarioRegistry registry = ScenarioRegistry.discover();
        for (String id : VALIDATED_SOAKS) {
            Scenario scenario = registry.require(id);
            String source = sourceOf(scenario);
            assertThat(source)
                    .as("%s (%s) must drive its block load with `%s` — a direct world write fires "
                                    + "no capture event and reaches the committee through nothing "
                                    + "at all", id, scenario.getClass().getSimpleName(),
                            CAPTURED_DRIVE)
                    .contains(CAPTURED_DRIVE);
        }
    }

    @Test
    @DisplayName("no validated-lane soak drives block load with fill or setblock")
    void noValidatedSoakDrivesDirectWorldWrites() throws IOException {
        ScenarioRegistry registry = ScenarioRegistry.discover();
        for (String id : VALIDATED_SOAKS) {
            Scenario scenario = registry.require(id);
            List<String> offending = Files.readAllLines(sourcePath(scenario), StandardCharsets.UTF_8)
                    .stream()
                    .map(String::strip)
                    // Comments explain WHY these verbs are wrong here; only real sends count.
                    .filter(line -> !line.startsWith("//") && !line.startsWith("*"))
                    .filter(line -> line.contains("run fill ") || line.contains("run setblock "))
                    .toList();
            assertThat(offending)
                    .as("%s (%s) sends a direct world write as its load: it fires neither "
                                    + "EntityPlaceEvent nor BreakEvent, so BlockCaptureBridge never "
                                    + "sees it and the lane this scenario measures stays idle",
                            id, scenario.getClass().getSimpleName())
                    .isEmpty();
        }
    }

    private static String sourceOf(Scenario scenario) throws IOException {
        return Files.readString(sourcePath(scenario), StandardCharsets.UTF_8);
    }

    /** The scenario's own {@code .java}, resolved through the layout manifest. */
    private static Path sourcePath(Scenario scenario) {
        Path file = LAYOUT.module("testing")
                .resolve("src/main/java")
                .resolve(scenario.getClass().getName().replace('.', '/') + ".java");
        assertThat(file)
                .as("source of %s — if this moved, the layout manifest is the table to fix",
                        scenario.getClass().getName())
                .isRegularFile();
        return file;
    }
}
