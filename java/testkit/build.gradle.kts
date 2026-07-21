plugins {
    id("nodera.java-library")
}

// testkit: shared test fakes + the ArchUnit forbidden-API policy used across modules.
dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":simulation"))
    implementation(project(":consensus"))
    implementation(libs.caffeine)

    // testkit is itself a library consumed by integration-tests; its own tests use junit/assertj
    // via the convention plugin already.
}
