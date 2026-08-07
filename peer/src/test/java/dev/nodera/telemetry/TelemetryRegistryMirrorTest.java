package dev.nodera.telemetry;

import dev.nodera.testkit.harness.SpawnedService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * <p>Skipped — not failed — when the binary has not been built, which is now only true of a local
 * checkout with no cargo toolchain. The {@code java} job builds {@code nodera-telemetry} before
 * {@code ./gradlew check} and fails on any skip at all, so this comparison runs on every push;
 * {@code scripts/e2e-telemetry.sh} and the {@code telemetry} CI job build it too. Add
 * {@code -Dnodera.test.requireServiceBinaries=true} locally and the skip becomes a failure that
 * names the binary — see {@link SpawnedService}.
 */
final class TelemetryRegistryMirrorTest {

    @Test
    void everyEventThisPeerCanEmitIsDeclaredByTheIngestService() {
        String schema = printSchema();
        Assumptions.assumeTrue(schema != null,
                SpawnedService.buildHint("nodera-telemetry"));

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
    void theServiceDeclaresTheServiceOnlyEventsThisPeerDoesNotEmit() {
        String schema = printSchema();
        Assumptions.assumeTrue(schema != null, "nodera-telemetry has not been built");

        Set<String> declaredByIngest = eventNames(schema);
        assertThat(declaredByIngest)
                .contains("tracker.window", "rendezvous.punch", "endpoint.window")
                .doesNotContainAnyElementsOf(List.of("world.name", "player.name", "chat.message"));
    }

    /** Run {@code nodera-telemetry --print-schema}, or {@code null} when no binary exists. */
    private static String printSchema() {
        return SpawnedService.binary("nodera-telemetry")
                .flatMap(binary -> SpawnedService.runOnce(binary, "--print-schema"))
                .orElse(null);
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
}
