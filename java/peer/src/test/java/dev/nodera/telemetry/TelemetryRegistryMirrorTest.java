package dev.nodera.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-language mirror: everything this peer can emit must be declared by the ingest service
 * that would receive it.
 *
 * <p>Same mechanism, and the same reason, as the wire-tag mirror in {@code rust/nodera-codec}: two
 * implementations of one contract drift unless something mechanical fails when they do. Without
 * this, an event added on the Java side and forgotten in {@code schema.rs} would be silently
 * dropped in production, which is the worst possible failure mode — the fleet believes it is
 * reporting and nobody notices for a release.
 *
 * <p>The registry is read from the <b>running binary</b> ({@code --print-schema}), not from a
 * checked-in copy of it, so the thing being compared is the thing that would actually enforce the
 * policy.
 *
 * <p>Skipped — not failed — when the binary has not been built. This suite runs on the pure-Java
 * gate, which does not build Rust; {@code scripts/e2e-telemetry.sh} and the
 * {@code telemetry} CI job both build it first and therefore always run the comparison.
 */
final class TelemetryRegistryMirrorTest {

    @Test
    void everyEventThisPeerCanEmitIsDeclaredByTheIngestService() throws Exception {
        String schema = printSchema();
        Assumptions.assumeTrue(schema != null,
                "nodera-telemetry has not been built — run scripts/e2e-telemetry.sh or the "
                        + "telemetry CI job, which build it first");

        Set<String> declaredByIngest = eventNames(schema);
        assertThat(declaredByIngest).isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (String name : TelemetryRegistry.eventNames()) {
            if (!declaredByIngest.contains(name)) {
                missing.add(name);
            }
        }
        assertThat(missing)
                .as("events the peer can emit that the ingest service would reject — add them to "
                        + "rust/nodera-telemetry/src/schema.rs and docs/plans/Plan.6.md §4")
                .isEmpty();
    }

    /**
     * The service may declare events this peer does not emit — the tracker's, the rendezvous
     * service's, an endpoint's. It may not declare an event whose <i>name</i> this peer uses with a
     * different meaning, so the names are checked for overlap rather than for equality.
     */
    @Test
    void theServiceDeclaresTheServiceOnlyEventsThisPeerDoesNotEmit() throws Exception {
        String schema = printSchema();
        Assumptions.assumeTrue(schema != null, "nodera-telemetry has not been built");

        Set<String> declaredByIngest = eventNames(schema);
        assertThat(declaredByIngest)
                .contains("tracker.window", "rendezvous.punch", "endpoint.window")
                .doesNotContainAnyElementsOf(List.of("world.name", "player.name", "chat.message"));
    }

    /** Run {@code nodera-telemetry --print-schema}, or {@code null} when no binary exists. */
    private static String printSchema() throws IOException, InterruptedException {
        Path root = repositoryRoot();
        for (String profile : new String[] {"release", "debug"}) {
            Path binary = root.resolve("rust/target").resolve(profile).resolve("nodera-telemetry");
            if (!Files.isExecutable(binary)) {
                continue;
            }
            Process process = new ProcessBuilder(binary.toString(), "--print-schema")
                    .redirectErrorStream(false)
                    .start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? out : null;
        }
        return null;
    }

    /**
     * Pull the event names out of the schema JSON without a JSON dependency.
     *
     * <p>{@code peer} has none, and adding one so a test can read a list of strings would be a
     * dependency for nothing.
     */
    private static Set<String> eventNames(String schema) {
        Set<String> names = new LinkedHashSet<>();
        String key = "\"name\":\"";
        int at = schema.indexOf(key);
        while (at >= 0) {
            int start = at + key.length();
            int end = schema.indexOf('"', start);
            if (end < 0) {
                break;
            }
            names.add(schema.substring(start, end));
            at = schema.indexOf(key, end);
        }
        return names;
    }

    /** Walk up from the working directory to the repository root (the one carrying VERSION). */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("VERSION"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        return Path.of("").toAbsolutePath();
    }
}
