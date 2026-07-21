// Nodera NeoForge mod conventions (Task 1 §1/§3, Task 0 §3).
// Applies to the NeoForge-bound module: neoforge-mod.
// Uses ModDevGradle (net.neoforged.moddev) to provision the mojang-mapped NeoForge 21.1.x
// compile classpath. NeoForge runs on Java 21; the host JDK is 25 (see AGENTS.md), so we
// compile against the host JDK and target the SAME jvm.version as the pure-Java modules
// (host) so Gradle's org.gradle.jvm.version attribute matches across module boundaries —
// pinning 21 here is what previously broke dependency resolution against :core.
import net.neoforged.moddevgradle.dsl.NeoForgeExtension

plugins {
    id("net.neoforged.moddev")
    `java-library`
    `jvm-test-suite`
}

// NeoForge 21.1.x (MC 1.21.1, LTS). Single source of truth for the mod toolchain.
the<NeoForgeExtension>().version = "21.1.77"

// ModDevGradle forces a Java 21 toolchain onto the module (NeoForge runs on Java 21). Target
// release 21 to match the pure-Java modules (nodera.java-library) and keep the
// org.gradle.jvm.version attribute consistent across project dependencies.
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
}

// Reproducible archives
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Shared test dependencies
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("net.jqwik:jqwik:1.9.0")

    compileOnlyApi("org.jetbrains:annotations:26.0.1")
}

// NeoForge mod resources configuration: expand gradle.properties into the descriptor.
tasks.named<ProcessResources>("processResources") {
    val modId: String by project
    val modVersion: String by project
    val modName: String by project
    val modDescription: String by project

    inputs.property("modId", modId)
    inputs.property("modVersion", modVersion)
    inputs.property("modName", modName)
    inputs.property("modDescription", modDescription)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf(
            "modId" to modId,
            "modVersion" to modVersion,
            "modName" to modName,
            "modDescription" to modDescription
        ))
    }

    filesMatching("*.mixins.json") {
        expand(mapOf("modId" to modId))
    }
}
