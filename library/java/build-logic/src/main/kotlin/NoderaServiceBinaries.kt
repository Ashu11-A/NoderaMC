import org.gradle.api.Project

/**
 * The one place the "this run built the Rust service binaries" promise crosses from the build into
 * the forked test JVMs.
 *
 * `dev.nodera.testkit.harness.SpawnedService` turns a missing binary from a skip into a failure
 * when this flag is set, which is what stops a CI job that promises to build them from silently
 * stopping. That mechanism was unreachable: `docs/testing/TESTING.md` documented
 * `./gradlew check -Dnodera.test.requireServiceBinaries=true`, and a launcher `-D` lands in the
 * **Gradle daemon's** JVM, never in a `Test` worker. Neither convention plugin forwarded it, so
 * `binariesRequired()` was permanently false — a built-but-never-called living inside the
 * deliverable whose whole purpose is to end that pattern.
 *
 * The environment variable was not a working fallback either. A `Test` task inherits the *daemon's*
 * environment, and a daemon is reused across invocations, so `NODERA_REQUIRE_SERVICE_BINARIES=true
 * ./gradlew check` against a daemon started without it reaches nothing. Both sources are therefore
 * read here, at configuration time, and re-published as an explicit `systemProperty` on every
 * `Test` task.
 *
 * Read through `providers` rather than `System.getProperty`/`System.getenv` so the configuration
 * cache records the flag as a build input: flipping it invalidates the cache and, because `Test`
 * tracks its system properties as task inputs, re-runs the suites instead of restoring a green
 * result produced without the promise.
 */
const val REQUIRE_SERVICE_BINARIES_PROPERTY = "nodera.test.requireServiceBinaries"

/** Environment variable read when the system property is absent. Mirrors `SpawnedService`. */
const val REQUIRE_SERVICE_BINARIES_ENV = "NODERA_REQUIRE_SERVICE_BINARIES"

/**
 * The flag's value for this build, or null when this run made no promise.
 *
 * Call once per project at configuration time and hand the result to every `Test` task; calling it
 * inside `configureEach` would work but re-reads the provider for each task.
 */
fun Project.serviceBinaryRequirement(): String? =
    providers.systemProperty(REQUIRE_SERVICE_BINARIES_PROPERTY)
        .orElse(providers.environmentVariable(REQUIRE_SERVICE_BINARIES_ENV))
        .orNull
