plugins {
    id("nodera.java-library")
}

// testing: the shared test library (issue #30; formerly `testkit` — package dev.nodera.testkit
// unchanged). LoopbackTransport, FakeRegion, FixtureWriter/Reader; the future home of the
// multi-peer scenario suite (the commented `integration-tests` declaration).
dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":engine"))
    implementation(libs.caffeine)

    // testkit is itself a library consumed by integration-tests; its own tests use junit/assertj
    // via the convention plugin already.
}
