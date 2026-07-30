plugins {
    id("nodera.java-library")
    // `nodera-test` — the acceptance/benchmark/structure tool. The launcher name is contract:
    // scripts/nodera-test.sh stages `java/testing/build/install/nodera-test/bin/nodera-test`.
    application
}

// testing: the Swiss-army knife of this repository's testing ecosystem (issue #30 merged the old
// `testkit` into it; the package `dev.nodera.testkit` is unchanged).
//
// # What lives here
//
//  - dev.nodera.testkit           — the original in-JVM helpers: LoopbackTransport, FakeRegion,
//                                   FixtureWriter/Reader. Consumed by every module's unit tests.
//  - dev.nodera.testkit.harness   — the LIVE harness: the port plan and topology, process
//                                   supervision, log waiting, the worker control client, the
//                                   suite lock, artefact collection.
//  - dev.nodera.testkit.mc        — Minecraft-side clients: RCON, and a Minecraft-protocol bot with
//                                   no Minecraft in it.
//  - dev.nodera.testkit.suite     — the Scenario SPI, its context, results and runner.
//  - dev.nodera.testkit.scenario  — the acceptance scenarios themselves (one per former
//                                   `scripts/e2e-*.sh`).
//  - dev.nodera.testkit.report    — one report over scenarios + benchmarks + structure.
//  - dev.nodera.testkit.cli       — `nodera-test`, the single entry point.
//
// # Why the module may depend on `:peer`
//
// The live harness speaks the worker's own control protocol, so that no assertion can pass on a
// state the product cannot see: there is no test-only accessor and no second serialisation of the
// truth to drift from the first. That is a dependency on the peer LIBRARY, not on the worker
// application — nothing here compiles the worker into anything, and the worker still depends only
// on `:peer`. `:peer`'s own tests depend back on this module at TEST scope, which keeps the
// main-configuration graph acyclic.
dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":engine"))
    implementation(project(":peer"))
    implementation(libs.caffeine)

    // testkit is itself a library consumed by every module's tests; its own tests use junit/assertj
    // via the convention plugin already.
}

application {
    mainClass.set("dev.nodera.testkit.cli.NoderaTestMain")
    applicationName = "nodera-test"
}

// `LayoutManifestTest` compares `layout.properties` against the projects Gradle really configured.
// Only Gradle knows the second half, so `settings.gradle.kts` records it as an extra property and
// this hands it across. Without it the comparison would have nothing to compare and would pass
// vacuously — which is why the test asserts the property is present before using it.
tasks.named<Test>("test") {
    systemProperty(
        "nodera.layout.modules",
        project.extra["noderaConfiguredModules"] as String,
    )
}
