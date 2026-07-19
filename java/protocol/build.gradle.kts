plugins {
    id("nodera.java-library")
}

dependencies {
    implementation(project(":core"))
    // ChunkedStreams: compress snapshot/delta streams before splitting under payload caps (Task 4).
    implementation(libs.zstd.jni)
}

// Golden wire fixtures (Task 27): the regeneration escape hatch documented on WireFixtureTest only
// works if the flag reaches the test JVM. Forwarded explicitly — Gradle does not pass -D through.
tasks.withType<Test>().configureEach {
    System.getProperty("nodera.fixtures.regenerate")?.let {
        systemProperty("nodera.fixtures.regenerate", it)
    }
}
