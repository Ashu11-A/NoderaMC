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
// 21.1.238 matches the real test client at ~/.minecraft (Task 0 §6 pin reconciliation; was 21.1.77).
the<NeoForgeExtension>().version = "21.1.238"

// The L-45 exit's first half: `runClient`/`runServer` launch from Gradle. The mod under test is
// this module's main source set; the harness (Xvfb log/screenshot assertions) drives these tasks.
the<NeoForgeExtension>().apply {
    mods.register("nodera") {
        sourceSet(project.the<SourceSetContainer>()["main"])
    }
    runs.register("client") {
        client()
    }
    runs.register("server") {
        server()
    }
    // Scripted join smoke (the L-45 acceptance's join half): boots a client straight into the
    // dev server via quick play, no GUI interaction needed. Own game dir so it does not fight
    // the interactive client run over locks/options.
    runs.register("clientJoin") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join"))
        // Distinct offline username: with the continuity series running two dev clients at once, a
        // duplicate name/UUID login would kick the host player the moment the joiner connects.
        programArguments.addAll("--quickPlayMultiplayer", "127.0.0.1:25599",
                "--username", "JoinerDev")
    }
    // Scripted HOST half of the continuity series (scripts/e2e-continuity.sh): boots a client
    // straight into the pre-baked shared world "NoderaE2E" — the Task 33 auto-re-share puts it on
    // the network with no GUI interaction. Own game dir for the same lock/options isolation.
    runs.register("clientHost") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-host"))
        programArguments.addAll("--quickPlaySingleplayer", "NoderaE2E",
                "--username", "HostDev")
    }
    // Player 1's NETWORK re-join (live Test 1, step 3): same game dir/identity as the host run,
    // but connecting as a client to whoever now hosts the world on the conventional dev port.
    runs.register("clientRejoin") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-host"))
        programArguments.addAll("--quickPlayMultiplayer", "127.0.0.1:25599",
                "--username", "HostDev")
    }
    // Second interactive client for manual two-player testing (scripts/play-two.sh): own game
    // dir + username, no quick play — the player drives the Nodera multiplayer UI by hand.
    runs.register("clientTwo") {
        client()
        gameDirectory.set(project.layout.projectDirectory.dir("run-join"))
        programArguments.addAll("--username", "JoinerDev")
    }
}

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
