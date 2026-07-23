// Nodera plain-Java library conventions (Task 0 §2/§3).
// Applies to every Minecraft-free module: core, protocol, simulation, consensus,
// transport, storage, testkit.
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

// Shared test dependencies for every module (Task 0 §5 testing stack). Versions come from the
// build-logic-local `libs` catalog (same file as the root catalog; see build-logic settings).
val libs = the<VersionCatalogsExtension>().named("libs")
dependencies {
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    testImplementation(libs.findLibrary("jqwik").get())
    testImplementation(libs.findLibrary("archunit-junit5").get())

    compileOnlyApi(libs.findLibrary("jetbrains-annotations").get())
}
