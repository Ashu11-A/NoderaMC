// Nodera plain-Java library conventions (Task 0 §2/§3).
// Applies to every Minecraft-free module: core, protocol, simulation, consensus,
// transport-api, storage-api, testkit.
plugins {
    `java-library`
    `jvm-test-suite`
}

// Target Java 21 (Task 0 §3 pin). The host JDK may be newer (env = JDK 25), so we compile
// with --release 21: this emits Java 21 bytecode (v65) AND advertises
// org.gradle.jvm.version = 21. That attribute MUST match across module boundaries — the
// NeoForge modules (nodera.neoforge-mod convention) are forced to a Java 21 toolchain by
// ModDevGradle, so a 25/21 mismatch breaks project dependency resolution.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
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
