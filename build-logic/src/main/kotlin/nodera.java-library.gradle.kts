// Nodera plain-Java library conventions (Task 0 §2/§3).
// Applies to every Minecraft-free module: core, protocol, simulation, consensus,
// transport-api, storage-api, testkit.
plugins {
    `java-library`
    `jvm-test-suite`
}

// Compile against the host JDK (env = JDK 25; Task 0 pins 21 — see gradle.properties).
// We use only Java 21-era language features (records, sealed interfaces, virtual threads,
// pattern matching) so the codebase remains Java 21 source-compatible when the pin is restored.
java {
    val v = JavaVersion.toVersion(JavaVersion.current().getMajorVersion())
    sourceCompatibility = v
    targetCompatibility = v
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-options"))
    options.isDeprecation = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("FAILED", "SKIPPED", "STANDARD_ERROR")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "1g"
    // jqwik + junit5 coexist on the junit-platform.
}

// Reproducible archives.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Shared test dependencies for every module (Task 0 §5 testing stack).
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("net.jqwik:jqwik:1.9.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")

    compileOnlyApi("org.jetbrains:annotations:26.0.1")
}
